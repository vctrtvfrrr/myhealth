package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionBatch
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService

sealed interface SendResult {

    /** Nothing is left to deliver for this record type. */
    data object Drained : SendResult

    data class Halted(val outcome: SyncOutcome) : SendResult
}

/**
 * Delivers what the import staged, in idempotent batches, and clears an item only once the API says
 * it is durable.
 *
 * A rejected item is kept and taken out of every later batch: it is a mapping pendency, and retrying
 * it would only fail again while blocking everything behind it.
 */
class OutboxSender(
    private val store: SyncStore,
    private val endpoints: IngestionEndpointStore,
    private val client: IngestionClient,
    private val maintenance: MaintenanceService,
    private val policy: SyncPolicy,
) {

    suspend fun drain(capability: HealthCapability): SendResult {
        val endpoint = endpoints.read() ?: return SendResult.Halted(SyncOutcome.NOT_CONFIGURED)

        while (true) {
            val items = store.pending(capability.recordType, policy.batchItems)
            if (items.isEmpty()) return SendResult.Drained

            val envelopes = items.map { decode(it) }
            val undecodable = items.filterIndexed { index, _ -> envelopes[index] == null }
            undecodable.forEach { reject(capability, it.id, listOf(RejectionCode.INVALID_PAYLOAD)) }
            val sendable = items.filterIndexed { index, _ -> envelopes[index] != null }
            if (sendable.isEmpty()) continue

            val batch = IngestionBatch(
                recordType = capability.recordType,
                items = envelopes.filterNotNull(),
            )

            when (val outcome = client.send(endpoint, batch)) {
                SendOutcome.Unreachable -> return SendResult.Halted(SyncOutcome.INGESTION_UNAVAILABLE)
                is SendOutcome.Refused -> return SendResult.Halted(outcomeOf(outcome.error))
                is SendOutcome.Delivered -> {
                    val settled = settle(capability, sendable, outcome)
                    // Answering fewer positions than were sent leaves the batch unresolved, and
                    // asking again for the same items would spin. It is the API failing to keep its
                    // side of the contract, so it is reported as an ingestion failure.
                    if (settled == 0) return SendResult.Halted(SyncOutcome.INGESTION_UNAVAILABLE)
                }
            }
        }
    }

    private suspend fun settle(
        capability: HealthCapability,
        items: List<OutboxItem>,
        delivered: SendOutcome.Delivered,
    ): Int {
        val confirmed = mutableListOf<Long>()
        val rejected = mutableListOf<Pair<Long, List<RejectionCode>>>()

        delivered.results.forEach { result ->
            val item = items.getOrNull(result.index) ?: return@forEach
            when (result.status) {
                ItemStatus.ACCEPTED, ItemStatus.ALREADY_PRESENT -> confirmed += item.id
                ItemStatus.REJECTED -> rejected += item.id to result.codes.orEmpty()
            }
        }

        if (confirmed.isNotEmpty()) store.confirm(confirmed)
        rejected.forEach { (id, codes) -> reject(capability, id, codes) }
        return confirmed.size + rejected.size
    }

    /**
     * Keeps the item as a mapping pendency and reports it, which is the same thing said twice on
     * purpose: the outbox is where the observation waits, and the incident is where its owner is told
     * that nothing will move it until the mapper changes.
     */
    private suspend fun reject(capability: HealthCapability, id: Long, codes: List<RejectionCode>) {
        store.reject(id, codes)
        maintenance.reportUnmappableRecord(capability, codes)
    }

    private fun decode(item: OutboxItem): HealthRecordEnvelope? = runCatching {
        IngestionContract.json.decodeFromString(HealthRecordEnvelope.serializer(), item.envelopeJson)
    }.getOrNull()

    private fun outcomeOf(error: BatchErrorCode): SyncOutcome = when (error) {
        BatchErrorCode.INVALID_DEVICE_TOKEN -> SyncOutcome.NOT_CONFIGURED
        BatchErrorCode.CONTRACT_VERSION_TOO_OLD,
        BatchErrorCode.CONTRACT_VERSION_TOO_NEW,
        -> SyncOutcome.CONTRACT_INCOMPATIBLE

        else -> SyncOutcome.INGESTION_UNAVAILABLE
    }
}
