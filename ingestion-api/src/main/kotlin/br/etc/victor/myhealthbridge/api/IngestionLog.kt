package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.ItemStatus
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * The only place this service is allowed to say anything about an ingestion.
 *
 * It writes from an allowlist: the server generated ingestion id, the contract version, sizes,
 * durations, counts and stable codes. Payloads, biometric values, coordinates, tokens, token digests,
 * Samsung UIDs, Source Provenance identifiers and device labels have no path into a log line, and
 * neither do exception messages, which can quote the very content they failed on.
 */
object IngestionLog {

    private val logger = LoggerFactory.getLogger("br.etc.victor.myhealthbridge.api.Ingestion")

    fun ingested(response: IngestionResponse, contractVersion: Int, durationMs: Long) {
        val counts = ItemStatus.entries.associateWith { status -> response.results.count { it.status == status } }
        val codes = response.results.flatMap { it.codes.orEmpty() }.toSortedSet().map { it.wireValue }

        logger.info(
            "ingestion={} contractVersion={} items={} durationMs={} accepted={} alreadyPresent={} rejected={} codes={}",
            response.ingestionId,
            contractVersion,
            response.results.size,
            durationMs,
            counts[ItemStatus.ACCEPTED],
            counts[ItemStatus.ALREADY_PRESENT],
            counts[ItemStatus.REJECTED],
            codes,
        )
    }

    fun refused(error: BatchErrorCode) {
        logger.info("ingestion refused code={} status={}", error.wireValue, error.status)
    }

    /** Names the failure by type and SQL state only, so a diagnosis never needs the health content. */
    fun unavailable(failure: Throwable) {
        logger.warn(
            "ingestion unavailable code={} failure={} sqlState={}",
            BatchErrorCode.INGESTION_TEMPORARILY_UNAVAILABLE.wireValue,
            failure::class.java.name,
            failure.sqlState() ?: "none",
        )
    }

    private fun Throwable.sqlState(): String? =
        generateSequence(this, Throwable::cause).filterIsInstance<SQLException>().firstOrNull()?.sqlState
}
