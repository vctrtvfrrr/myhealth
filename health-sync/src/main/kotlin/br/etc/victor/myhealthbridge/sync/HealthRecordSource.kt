package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.Instant
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
 * Something the source reports about a Health Record it already served.
 *
 * [changedAt] is when the source says the change happened, which is what dates the observation of it.
 */
sealed interface SourceChange {

    val changedAt: Instant

    data class Upserted(override val changedAt: Instant, val record: SourceRecord) : SourceChange

    /** A Source Removal: the identity is all the source still reports about the record. */
    data class Removed(override val changedAt: Instant, val uid: String) : SourceChange
}

/** The stretch of change time one changes read covers, both ends included. */
data class ChangeWindow(val from: Instant, val to: Instant)

/**
 * One page of changes, in no promised order.
 *
 * The feed exposes no ordering to ask for, so nothing about a page bounds what a later one holds. That
 * is why a changes read is resumed by its window and not by the times inside it.
 *
 * [nextPageToken] is null when the window holds nothing more, which is what ends a changes read.
 */
data class ChangePage(
    val changes: List<SourceChange>,
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

    /**
     * The changes the source reports inside [window].
     *
     * This is the only read that reports a Source Removal, and the only one that reaches a record the
     * history walk already passed: a time range read is filtered by the record's own time, so an edit
     * to an old record never appears in one again.
     */
    suspend fun readChanges(
        capability: HealthCapability,
        window: ChangeWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<ChangePage>
}
