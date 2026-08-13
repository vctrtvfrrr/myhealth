package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.LocalDateTime

/** The stretch of local time one read covers, both ends included. */
data class ReadWindow(val from: LocalDateTime, val to: LocalDateTime)

/**
 * One page of records, in ascending local start time.
 *
 * [nextPageToken] is null when the window holds nothing more, which is what ends an import run.
 */
data class RecordPage(
    val records: List<SourceRecord>,
    val nextPageToken: String?,
)

/**
 * The replaceable boundary in front of reading Samsung Health, mirroring the permissions gateway:
 * SDK types stay behind it, and a failure answers as an availability rather than as an exception.
 */
interface HealthRecordSource {

    suspend fun readPage(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<RecordPage>
}
