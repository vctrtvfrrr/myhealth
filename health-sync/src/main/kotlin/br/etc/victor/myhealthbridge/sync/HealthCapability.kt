package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.HealthCategory

/** How a Health Category can be read from Samsung Health. */
enum class ReadOperation {
    /** A paginated read over a range of local time, which is what an import walks. */
    TIME_RANGE,

    /** A paginated read over a range of local dates, the only one Samsung Health offers for some records. */
    DATE_RANGE,

    /** The changes feed, which reports edits and removals at the source. */
    CHANGES,

    /** Data points that only exist attached to another record, such as an exercise route. */
    ASSOCIATED,

    /**
     * A total Samsung Health computes over each window of local time, with no UID and no modification
     * time, which is read into Observed Snapshots rather than Health Records. See ADR 0025.
     */
    AGGREGATE,

    /** One read of the whole profile, with no filter, no page and no time. */
    PROFILE,
}

/** What a Health Category is to the synchronization, which decides how its data is identified and kept. */
enum class CoverageKind {
    /** Identified record by record by Samsung Health, with a changes feed. */
    HEALTH_RECORD,

    /** Only exposed as an aggregate or a profile, with no UID, observed when read. See ADR 0025. */
    OBSERVED_SNAPSHOT,

    /** A permission that discloses data of another category, with nothing to read of its own. */
    PERMISSION_ONLY,
}

/** How much one read of a capability asks Samsung Health for. */
sealed interface Pagination {

    /** Records paged by token, [pageSize] at a time. Samsung Health publishes no default nor maximum. */
    data class Records(val pageSize: Int) : Pagination

    /** One aggregate read covering [windowsPerRead] Snapshot Windows, so staging that many at most. */
    data class Windows(val windowsPerRead: Int) : Pagination

    /** Read whole every time, because there is nothing to page. */
    data object Unpaged : Pagination
}

/** What makes two observations of a capability observations of the same thing. */
sealed interface CapabilityIdentity {

    val kind: CoverageKind

    /** The type the batches of this capability name at their root, or null when it sends none. */
    val wireType: String?

    /** The Samsung UID together with the record type: the Health Record Identity. */
    data class SamsungUid(val recordType: String) : CapabilityIdentity {
        override val kind: CoverageKind get() = CoverageKind.HEALTH_RECORD
        override val wireType: String get() = recordType
    }

    /** The snapshot type together with the Snapshot Window, or the type alone when [window] is null. */
    data class Snapshot(val snapshotType: String, val window: SnapshotWindowUnit?) : CapabilityIdentity {
        override val kind: CoverageKind get() = CoverageKind.OBSERVED_SNAPSHOT
        override val wireType: String get() = snapshotType
    }

    /** No identity of its own: what the permission discloses is identified inside [carrier]. */
    data class CarriedBy(val carrier: HealthCategory) : CapabilityIdentity {
        override val kind: CoverageKind get() = CoverageKind.PERMISSION_ONLY
        override val wireType: String? get() = null
    }
}

/** What Samsung Health hands over together with a record. */
sealed interface AssociatedData {

    data object None : AssociatedData

    /**
     * Structures with no UID of their own, preserved inside the record that carries them (ADR 0019),
     * named by their path in the record.
     */
    data class Nested(val paths: List<String>) : AssociatedData {
        constructor(vararg paths: String) : this(paths.toList())
    }
}

/**
 * One entry of the capability catalog: everything known about reading a Health Category of the adopted
 * SDK, declared in one place whether or not this build reads it.
 */
sealed interface CatalogEntry {

    val category: HealthCategory

    val identity: CapabilityIdentity

    val kind: CoverageKind get() = identity.kind

    /** The category whose read permission has to be granted before this entry is read. */
    val permission: HealthCategory get() = category

    val readOperations: Set<ReadOperation>

    val pagination: Pagination

    val supportsChanges: Boolean get() = ReadOperation.CHANGES in readOperations

    val associatedData: AssociatedData

    /** The version of the mapper this build reads the category with, or null while there is none. */
    val mapperVersion: String?

    /** The read model view the API projects the category into, or null while there is none. */
    val projection: String?

    val implemented: Boolean
}

/**
 * A category of the adopted SDK this build does not read yet: cataloged so that the gap is visible and
 * its permission can already be granted, but never synchronized.
 *
 * Covering it is replacing this entry with a [RecordCapability] or a [SnapshotCapability] that keeps
 * what is declared here and adds a mapper and a projection.
 */
data class NotImplemented(
    override val category: HealthCategory,
    override val identity: CapabilityIdentity,
    override val readOperations: Set<ReadOperation>,
    override val pagination: Pagination,
    override val associatedData: AssociatedData = AssociatedData.None,
) : CatalogEntry {
    override val mapperVersion: String? get() = null
    override val projection: String? get() = null
    override val implemented: Boolean get() = false
}

/**
 * A permission that only decides whether another category discloses part of what it reads, such as
 * the exercise route. It is covered once its carrier maps and projects what it discloses.
 */
data class PermissionOnly(
    override val category: HealthCategory,
    val carrier: HealthCategory,
    override val projection: String,
) : CatalogEntry {
    override val identity: CapabilityIdentity get() = CapabilityIdentity.CarriedBy(carrier)
    override val readOperations: Set<ReadOperation> get() = emptySet()
    override val pagination: Pagination get() = Pagination.Unpaged
    override val associatedData: AssociatedData get() = AssociatedData.None
    override val mapperVersion: String? get() = null
    override val implemented: Boolean get() = true
}

/** A cataloged category this build synchronizes. */
sealed interface HealthCapability : CatalogEntry {

    /** The type this capability's batches name at their root. */
    val wireType: String

    override val projection: String

    override val implemented: Boolean get() = true
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
    override val projection: String,
    override val associatedData: AssociatedData = AssociatedData.None,
) : HealthCapability {
    override val wireType: String get() = recordType

    override val identity: CapabilityIdentity get() = CapabilityIdentity.SamsungUid(recordType)

    override val pagination: Pagination get() = Pagination.Records(pageSize)

    override val mapperVersion: String get() = mapper.version

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
    override val projection: String,
) : HealthCapability {
    override val wireType: String get() = snapshotType

    override val identity: CapabilityIdentity get() = CapabilityIdentity.Snapshot(snapshotType, window)

    override val readOperations: Set<ReadOperation> get() = setOf(ReadOperation.AGGREGATE)

    override val pagination: Pagination get() = Pagination.Windows(windowsPerRead)

    override val associatedData: AssociatedData get() = AssociatedData.None

    override val mapperVersion: String get() = mapper.version
}

/**
 * The profile, the one capability read into an Observed Snapshot without a Snapshot Window: Samsung
 * Health holds one per Data Owner, with no UID and no time, and reads it whole with no filter and no
 * page. It shares the Outbox and the Sync Cursor of its Health Category like every other capability.
 */
data class ProfileCapability(
    override val category: HealthCategory,
    /** The snapshot type on the wire, which alone identifies the profile's Observed Snapshot. */
    val snapshotType: String,
    val mapper: ProfileMapper,
    override val projection: String,
) : HealthCapability {
    override val wireType: String get() = snapshotType

    override val identity: CapabilityIdentity get() = CapabilityIdentity.Snapshot(snapshotType, window = null)

    override val readOperations: Set<ReadOperation> get() = setOf(ReadOperation.PROFILE)

    override val pagination: Pagination get() = Pagination.Unpaged

    override val associatedData: AssociatedData get() = AssociatedData.None

    override val mapperVersion: String get() = ProfileMapper.VERSION
}

/** The categories the adopted SDK exposes without an entry in [HealthCapabilities], by the SDK's names. */
fun interface UncatalogedCategories {
    fun read(): Set<String>
}

/**
 * The capability catalog: the source of truth of what this build covers of the adopted SDK.
 *
 * It holds exactly one entry per [HealthCategory], in display order, implemented or not. A category the
 * SDK exposes without an entry here fails the test suite and, should a build ever ship one, becomes a
 * Maintenance Incident: it is never authorized nor synchronized on its own. See ADR 0010.
 *
 * Page and window sizes are choices of this repository, not limits of the SDK; those of the entries not
 * implemented yet are where their slice starts from.
 */
object HealthCapabilities {

    /** A month of hours: a read is cheap, and the outbox, not the read, is what bounds the run. */
    private const val HOURS_PER_READ = 31 * 24

    /** A year of days, for the goals Samsung Health keeps one of per local day. */
    private const val DAYS_PER_READ = 366

    /** What a page of plain measurements is asked for in, a page being what the outbox has to fit. */
    private const val MEASUREMENTS_PER_PAGE = 500

    private val RECORD_READS = setOf(ReadOperation.TIME_RANGE, ReadOperation.CHANGES)

    val entries: List<CatalogEntry> = listOf(
        SnapshotCapability(
            category = HealthCategory.ACTIVITY_SUMMARY,
            snapshotType = "activity_summary",
            window = SnapshotWindowUnit.LOCAL_HOUR,
            windowsPerRead = HOURS_PER_READ,
            mapper = ActivitySummaryMapper,
            projection = "read_model.current_hourly_activity_summary",
        ),
        RecordCapability(
            category = HealthCategory.EXERCISE,
            recordType = "exercise",
            // The route is read as part of the exercise: Samsung Health exposes no read of its own for
            // it, only the separate permission that decides whether the parent record discloses it.
            readOperations = RECORD_READS + ReadOperation.ASSOCIATED,
            // An exercise carries its whole route, so a page of them is far heavier than a page of
            // measurements and the outbox is what a page has to fit in.
            pageSize = 50,
            mapper = ExerciseMapper,
            projection = "read_model.current_exercise",
            associatedData = AssociatedData.Nested("sessions", "sessions.route"),
        ),
        PermissionOnly(
            category = HealthCategory.EXERCISE_LOCATION,
            carrier = HealthCategory.EXERCISE,
            projection = "read_model.current_exercise_location",
        ),
        plainMeasurement(HealthCategory.FLOORS_CLIMBED, "floors_climbed", FloorsClimbedMapper),
        SnapshotCapability(
            category = HealthCategory.STEPS,
            snapshotType = "steps",
            window = SnapshotWindowUnit.LOCAL_HOUR,
            windowsPerRead = HOURS_PER_READ,
            mapper = StepsMapper,
            projection = "read_model.current_hourly_steps",
        ),

        plainMeasurement(HealthCategory.NUTRITION, "nutrition", NutritionMapper),
        dailyGoal(HealthCategory.NUTRITION_GOAL, "nutrition_goal", GoalMappers.NUTRITION),
        plainMeasurement(HealthCategory.WATER_INTAKE, "water_intake", WaterIntakeMapper),
        dailyGoal(HealthCategory.WATER_INTAKE_GOAL, "water_intake_goal", GoalMappers.WATER_INTAKE),

        RecordCapability(
            category = HealthCategory.SLEEP,
            recordType = "sleep",
            // The stages are fields of the sleep, read with it. The associated read Samsung Health offers
            // for a night is not used: what it returns are blood oxygen and skin temperature records that
            // carry their own UIDs, and their own categories read them. See ADR 0023.
            readOperations = RECORD_READS,
            // A night carries every stage it went through, so a page is heavier than one of measurements,
            // though far lighter than a page of routes.
            pageSize = 100,
            mapper = SleepMapper,
            projection = "read_model.current_sleep",
            associatedData = AssociatedData.Nested("sessions", "sessions.stages"),
        ),
        monitor(HealthCategory.SLEEP_APNEA, "sleep_apnea", SleepApneaMapper),
        dailyGoal(HealthCategory.SLEEP_GOAL, "sleep_goal", GoalMappers.SLEEP),
        binnedMeasurement(HealthCategory.SKIN_TEMPERATURE, "skin_temperature", SkinTemperatureMapper),

        binnedMeasurement(HealthCategory.BLOOD_GLUCOSE, "blood_glucose", BloodGlucoseMapper),
        binnedMeasurement(HealthCategory.BLOOD_OXYGEN, "blood_oxygen", BloodOxygenMapper),
        plainMeasurement(HealthCategory.BLOOD_PRESSURE, "blood_pressure", BloodPressureMapper),
        plainMeasurement(HealthCategory.BODY_COMPOSITION, "body_composition", BodyCompositionMapper),
        plainMeasurement(HealthCategory.BODY_TEMPERATURE, "body_temperature", BodyTemperatureMapper),
        RecordCapability(
            category = HealthCategory.HEART_RATE,
            recordType = "heart_rate",
            readOperations = RECORD_READS,
            pageSize = MEASUREMENTS_PER_PAGE,
            mapper = HeartRateMapper,
            projection = "read_model.current_heart_rate",
            associatedData = AssociatedData.Nested("binning_data"),
        ),
        monitor(HealthCategory.IRREGULAR_HEART_RHYTHM, "irregular_heart_rhythm", IrregularHeartRhythmMapper),

        dailyGoal(HealthCategory.ACTIVE_CALORIES_BURNED_GOAL, "active_calories_burned_goal", GoalMappers.ACTIVE_CALORIES_BURNED),
        dailyGoal(HealthCategory.ACTIVE_TIME_GOAL, "active_time_goal", GoalMappers.ACTIVE_TIME),
        // One record per local day, which Samsung Health only filters by local date.
        RecordCapability(
            category = HealthCategory.ENERGY_SCORE,
            recordType = "energy_score",
            readOperations = setOf(ReadOperation.DATE_RANGE, ReadOperation.CHANGES),
            pageSize = MEASUREMENTS_PER_PAGE,
            mapper = EnergyScoreMapper,
            projection = "read_model.current_energy_score",
        ),
        dailyGoal(HealthCategory.STEPS_GOAL, "steps_goal", GoalMappers.STEPS),

        // The profile has no window: there is one per Data Owner, read whole on every run.
        ProfileCapability(
            category = HealthCategory.USER_PROFILE,
            snapshotType = "user_profile",
            mapper = ProfileMapper,
            projection = "read_model.user_profile",
        ),
    )

    /** The entries a synchronization run reads, in display order. */
    val synchronized: List<HealthCapability> = entries.filterIsInstance<HealthCapability>()

    fun of(category: HealthCategory): CatalogEntry = entries.first { it.category == category }

    private fun measurement(
        category: HealthCategory,
        recordType: String,
        associatedData: AssociatedData = AssociatedData.None,
    ) = NotImplemented(
        category = category,
        identity = CapabilityIdentity.SamsungUid(recordType),
        readOperations = RECORD_READS,
        pagination = Pagination.Records(MEASUREMENTS_PER_PAGE),
        associatedData = associatedData,
    )

    /**
     * A measurement carrying the series it was binned from, which travels inside it. Those Samsung Health
     * associates with a night are read here too, never through the sleep. See ADR 0023.
     */
    private fun binnedMeasurement(category: HealthCategory, recordType: String, mapper: RecordMapper) = RecordCapability(
        category = category,
        recordType = recordType,
        readOperations = RECORD_READS,
        pageSize = MEASUREMENTS_PER_PAGE,
        mapper = mapper,
        projection = "read_model.current_$recordType",
        associatedData = AssociatedData.Nested("binning_data"),
    )

    /** A measurement of plain fields, with nothing nested in it and nothing associated to read beside it. */
    private fun plainMeasurement(category: HealthCategory, recordType: String, mapper: RecordMapper) = RecordCapability(
        category = category,
        recordType = recordType,
        readOperations = RECORD_READS,
        pageSize = MEASUREMENTS_PER_PAGE,
        mapper = mapper,
        projection = "read_model.current_$recordType",
    )

    /**
     * What the Samsung Health Monitor of a Galaxy Watch reports. Without one, or where its features are not
     * offered, the history is simply empty.
     */
    private fun monitor(category: HealthCategory, recordType: String, mapper: RecordMapper) = RecordCapability(
        category = category,
        recordType = recordType,
        readOperations = RECORD_READS,
        pageSize = MEASUREMENTS_PER_PAGE,
        mapper = mapper,
        projection = "read_model.current_$recordType",
    )

    private fun dailyGoal(category: HealthCategory, snapshotType: String, mapper: GoalMapper) = SnapshotCapability(
        category = category,
        snapshotType = snapshotType,
        window = SnapshotWindowUnit.LOCAL_DAY,
        windowsPerRead = DAYS_PER_READ,
        mapper = mapper,
        projection = "read_model.current_daily_goal",
    )
}
