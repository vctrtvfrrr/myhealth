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

    /**
     * A total Samsung Health computes over each window of local time, with no UID and no modification
     * time, which is read into Observed Snapshots rather than Health Records. See ADR 0025.
     */
    AGGREGATE,
}

/**
 * One entry of the capability catalog: everything the synchronization needs to know about a Health
 * Category, declared in one place.
 *
 * A Health Category without an entry here is cataloged for permissions but not synchronized, which is
 * how coverage grows one vertical at a time instead of by an SDK upgrade.
 */
sealed interface HealthCapability {
    val category: HealthCategory

    /** The type this capability's batches name at their root. */
    val wireType: String

    val readOperations: Set<ReadOperation>

    /** Whether the API projects this type into a read model view. */
    val projected: Boolean
}

/** A capability whose data Samsung Health identifies record by record, read into Health Records. */
data class RecordCapability(
    override val category: HealthCategory,
    /** The record type on the wire; with the Samsung UID it forms the Health Record Identity. */
    val recordType: String,
    override val readOperations: Set<ReadOperation>,
    /** How many records one read asks Samsung Health for. */
    val pageSize: Int,
    val mapper: RecordMapper,
    override val projected: Boolean,
) : HealthCapability {
    override val wireType: String get() = recordType

    val supportsChanges: Boolean get() = ReadOperation.CHANGES in readOperations

    val hasAssociatedData: Boolean get() = ReadOperation.ASSOCIATED in readOperations
}

/**
 * A capability Samsung Health only exposes as an aggregate, read window by window into Observed
 * Snapshots. Its import shares the Outbox and the Sync Cursor of its Health Category with every other
 * capability, and has neither a changes feed nor a Record Inventory.
 */
data class SnapshotCapability(
    override val category: HealthCategory,
    /** The snapshot type on the wire; with the Snapshot Window it identifies an Observed Snapshot. */
    val snapshotType: String,
    val window: SnapshotWindowUnit,
    /** How many windows one aggregate read covers, which is how many observations it stages at most. */
    val windowsPerRead: Int,
    val mapper: SnapshotMapper,
    override val projected: Boolean,
) : HealthCapability {
    override val wireType: String get() = snapshotType

    override val readOperations: Set<ReadOperation> get() = setOf(ReadOperation.AGGREGATE)
}

/**
 * The capabilities this build synchronizes.
 *
 * The read permission is not repeated here: it belongs to the [HealthCategory], which is the single
 * place a permission is ever derived from.
 */
object HealthCapabilities {

    val entries: List<HealthCapability> = listOf(
        RecordCapability(
            category = HealthCategory.HEART_RATE,
            recordType = "heart_rate",
            readOperations = setOf(ReadOperation.TIME_RANGE, ReadOperation.CHANGES),
            pageSize = 500,
            mapper = HeartRateMapper,
            projected = true,
        ),
        RecordCapability(
            category = HealthCategory.EXERCISE,
            recordType = "exercise",
            // The route is read as part of the exercise: Samsung Health exposes no read of its own for
            // it, only the separate permission that decides whether the parent record discloses it.
            readOperations = setOf(ReadOperation.TIME_RANGE, ReadOperation.CHANGES, ReadOperation.ASSOCIATED),
            // An exercise carries its whole route, so a page of them is far heavier than a page of
            // measurements and the outbox is what a page has to fit in.
            pageSize = 50,
            mapper = ExerciseMapper,
            projected = true,
        ),
        RecordCapability(
            category = HealthCategory.SLEEP,
            recordType = "sleep",
            // The stages are fields of the sleep, read with it. The associated read Samsung Health offers
            // for a night is not used: what it returns are blood oxygen and skin temperature records that
            // carry their own UIDs, and their own categories read them. See ADR 0023.
            readOperations = setOf(ReadOperation.TIME_RANGE, ReadOperation.CHANGES),
            // A night carries every stage it went through, so a page is heavier than one of measurements,
            // though far lighter than a page of routes.
            pageSize = 100,
            mapper = SleepMapper,
            projected = true,
        ),
        SnapshotCapability(
            category = HealthCategory.STEPS,
            snapshotType = "steps",
            window = SnapshotWindowUnit.LOCAL_HOUR,
            // A month of hours: a read is cheap, and the outbox, not the read, is what bounds the run.
            windowsPerRead = 31 * 24,
            mapper = StepsMapper,
            projected = true,
        ),
    )

    fun of(category: HealthCategory): HealthCapability? = entries.firstOrNull { it.category == category }
}
