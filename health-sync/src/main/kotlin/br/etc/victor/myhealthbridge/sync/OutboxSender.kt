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

        // Lowered, never raised: the API's own limit is not readable from here, so a refusal for size
        // is the only thing that can tell this side its budget was too generous.
        var budget = policy.maxBatchBytes

        while (true) {
            val items = withinByteBudget(store.pending(capability.recordType, policy.batchItems), budget)
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
                is SendOutcome.Refused -> when {
                    outcome.error != BatchErrorCode.BATCH_TOO_LARGE ->
                        return SendResult.Halted(outcomeOf(outcome.error))

                    // Nothing but a change to the mapper can shrink a single envelope the API refuses,
                    // so it becomes a mapping pendency instead of staying at the head of the outbox
                    // blocking every observation staged behind it.
                    sendable.size == 1 -> reject(capability, sendable.first().id, TOO_LARGE)

                    else -> budget /= 2
                }
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

    /**
     * The longest prefix of the outbox that fits one request, never shorter than a single item.
     *
     * A prefix is what keeps delivery in the order the observations were staged. One item alone over
     * the budget is still sent: only the API can say whether its own limit refuses it, and answering
     * that here from a number this side guessed would strand a record the API would have taken.
     */
    private fun withinByteBudget(items: List<OutboxItem>, budget: Int): List<OutboxItem> {
        var bytes = 0
        return items.takeIndexedWhile { index, item ->
            bytes += item.envelopeJson.length
            index == 0 || bytes <= budget
        }
    }

    private fun <T> List<T>.takeIndexedWhile(keep: (Int, T) -> Boolean): List<T> {
        val taken = mutableListOf<T>()
        forEachIndexed { index, element ->
            if (!keep(index, element)) return taken
            taken += element
        }
        return taken
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
     *
     * Reported first, because a rejection happens once: marking the item takes it out of every later
     * batch, so a process death between the two writes would leave a pendency nothing ever reports.
     * The other order costs a re-report of an item the API refuses again, which is one occurrence on
     * an incident that already exists.
     */
    private suspend fun reject(capability: HealthCapability, id: Long, codes: List<RejectionCode>) {
        maintenance.reportUnmappableRecord(capability, codes)
        store.reject(id, codes)
    }

    private fun decode(item: OutboxItem): HealthRecordEnvelope? = runCatching {
        IngestionContract.json.decodeFromString(HealthRecordEnvelope.serializer(), item.envelopeJson)
    }.getOrNull()

    private companion object {
        /**
         * What an envelope the API refuses for its size is kept as.
         *
         * The contract has no code for it, and inventing one would mean a wire change for a condition
         * whose fix is the same as every other pendency's: the mapper has to render the record
         * differently. The size is not the item's content, so nothing about it is echoed anywhere.
         */
        val TOO_LARGE = listOf(RejectionCode.INVALID_PAYLOAD)
    }

    private fun outcomeOf(error: BatchErrorCode): SyncOutcome = when (error) {
        BatchErrorCode.INVALID_DEVICE_TOKEN -> SyncOutcome.NOT_CONFIGURED
        BatchErrorCode.CONTRACT_VERSION_TOO_OLD,
        BatchErrorCode.CONTRACT_VERSION_TOO_NEW,
        -> SyncOutcome.CONTRACT_INCOMPATIBLE

        else -> SyncOutcome.INGESTION_UNAVAILABLE
    }
}
