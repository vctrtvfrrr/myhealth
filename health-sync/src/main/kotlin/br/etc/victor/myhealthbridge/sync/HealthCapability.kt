package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.HealthCategory

/** How a Health Category can be read from Samsung Health. */
enum class ReadOperation {
    /** A paginated read over a range of local time, which is what an import walks. */
    TIME_RANGE,

    /** The changes feed, which reports edits and removals at the source. */
    CHANGES,

    /** Data points that only exist attached to another record, such as an exercise route. */
    ASSOCIATED,
}

/**
 * One entry of the capability catalog: everything the synchronization needs to know about a Health
 * Category, declared in one place.
 *
 * A Health Category without an entry here is cataloged for permissions but not synchronized, which is
 * how coverage grows one vertical at a time instead of by an SDK upgrade.
 */
data class HealthCapability(
    val category: HealthCategory,
    /** The record type on the wire; with the Samsung UID it forms the Health Record Identity. */
    val recordType: String,
    val readOperations: Set<ReadOperation>,
    /** How many records one read asks Samsung Health for. */
    val pageSize: Int,
    val mapper: RecordMapper,
    /** Whether the API projects this record type into a read model view. */
    val projected: Boolean,
) {
    val supportsChanges: Boolean get() = ReadOperation.CHANGES in readOperations

    val hasAssociatedData: Boolean get() = ReadOperation.ASSOCIATED in readOperations
}

/**
 * The capabilities this build synchronizes.
 *
 * The read permission is not repeated here: it belongs to the [HealthCategory], which is the single
 * place a permission is ever derived from.
 */
object HealthCapabilities {

    val entries: List<HealthCapability> = listOf(
        HealthCapability(
            category = HealthCategory.HEART_RATE,
            recordType = "heart_rate",
            readOperations = setOf(ReadOperation.TIME_RANGE, ReadOperation.CHANGES),
            pageSize = 500,
            mapper = HeartRateMapper,
            projected = true,
        ),
    )

    fun of(category: HealthCategory): HealthCapability? = entries.firstOrNull { it.category == category }
}
