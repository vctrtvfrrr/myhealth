package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A homogeneous batch of Observed Snapshots, which exists from version 3 on.
 *
 * It is told apart from an [IngestionBatch] by naming a `snapshotType` at its root instead of a
 * `recordType`: a snapshot has no Samsung UID and no source instant, so it is identified by its type and
 * its Snapshot Window, and the two dating rules never share a document. See ADR 0025.
 */
@Serializable
data class SnapshotBatch(
    val snapshotType: String,
    val items: List<SnapshotObservation>,
    val contractVersion: Int = IngestionContract.CURRENT_VERSION,
)

/**
 * One reading of an Observed Snapshot.
 *
 * [observedAt] is when the application read it, and it is left out of what makes two observations the
 * same: the API keeps one only when its content differs from the observation before it.
 *
 * [window] is null for a snapshot that summarizes no stretch of time. [content] is null for a window
 * with nothing in it, which is observed as empty rather than left unobserved, so that a total deleted
 * at the source stops being current.
 */
@Serializable
data class SnapshotObservation(
    val window: LocalInterval?,
    val observedAt: ZonedInstant,
    val sourceScope: SourceScope,
    val mapperVersion: String,
    val content: JsonObject?,
)

/**
 * Which sources Samsung Health aggregated a snapshot over.
 *
 * An aggregate names no application or device, so its provenance is the scope it was asked for rather
 * than a Source Provenance it would have to pretend to know.
 */
@Serializable
enum class SourceScope {
    @SerialName("platform")
    PLATFORM,

    @SerialName("all_sources")
    ALL_SOURCES,
    ;

    val wireValue: String get() = name.lowercase()
}
