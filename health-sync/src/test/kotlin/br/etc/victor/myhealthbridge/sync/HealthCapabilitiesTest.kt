package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.health.HealthCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthCapabilitiesTest {

    @Test
    fun `catalogs every Health Category of the adopted SDK once, in display order`() {
        assertEquals(HealthCategory.entries, HealthCapabilities.entries.map { it.category })
    }

    @Test
    fun `marks what this build covers as implemented and nothing else`() {
        val implemented = HealthCapabilities.entries.filter { it.implemented }.map { it.category }.toSet()

        assertEquals(
            setOf(
                HealthCategory.ACTIVITY_SUMMARY,
                HealthCategory.EXERCISE,
                HealthCategory.EXERCISE_LOCATION,
                HealthCategory.STEPS,
                HealthCategory.SLEEP,
                HealthCategory.SKIN_TEMPERATURE,
                HealthCategory.BLOOD_GLUCOSE,
                HealthCategory.BLOOD_OXYGEN,
                HealthCategory.HEART_RATE,
                HealthCategory.USER_PROFILE,
            ) + GOALS,
            implemented,
        )
    }

    /** The classification ticket #29 settled from what the SDK's own types expose. */
    @Test
    fun `classifies every category as a Health Record, an Observed Snapshot or a permission only`() {
        val kinds = HealthCapabilities.entries.groupingBy { it.kind }.eachCount()

        assertEquals(
            mapOf(
                CoverageKind.HEALTH_RECORD to 15,
                CoverageKind.OBSERVED_SNAPSHOT to 9,
                CoverageKind.PERMISSION_ONLY to 1,
            ),
            kinds,
        )
        assertEquals(CoverageKind.PERMISSION_ONLY, HealthCapabilities.of(HealthCategory.EXERCISE_LOCATION).kind)
        assertEquals(CoverageKind.OBSERVED_SNAPSHOT, HealthCapabilities.of(HealthCategory.USER_PROFILE).kind)
        assertEquals(CoverageKind.HEALTH_RECORD, HealthCapabilities.of(HealthCategory.ENERGY_SCORE).kind)
    }

    @Test
    fun `synchronizes only the implemented Health Records and Observed Snapshots`() {
        assertEquals(
            listOf(
                HealthCategory.ACTIVITY_SUMMARY,
                HealthCategory.EXERCISE,
                HealthCategory.STEPS,
                HealthCategory.NUTRITION_GOAL,
                HealthCategory.WATER_INTAKE_GOAL,
                HealthCategory.SLEEP,
                HealthCategory.SLEEP_GOAL,
                HealthCategory.SKIN_TEMPERATURE,
                HealthCategory.BLOOD_GLUCOSE,
                HealthCategory.BLOOD_OXYGEN,
                HealthCategory.HEART_RATE,
                HealthCategory.ACTIVE_CALORIES_BURNED_GOAL,
                HealthCategory.ACTIVE_TIME_GOAL,
                HealthCategory.STEPS_GOAL,
                HealthCategory.USER_PROFILE,
            ),
            HealthCapabilities.synchronized.map { it.category },
        )
    }

    @Test
    fun `declares heart rate end to end`() {
        val capability = HealthCapabilities.of(HealthCategory.HEART_RATE) as RecordCapability

        assertEquals(HealthCategory.HEART_RATE, capability.category)
        assertEquals("heart_rate", capability.recordType)
        assertEquals(HeartRateMapper, capability.mapper)
        assertTrue(ReadOperation.TIME_RANGE in capability.readOperations)
        assertTrue(capability.supportsChanges)
        assertFalse(capability.hasAssociatedData)
        assertTrue(capability.pageSize > 0)
    }

    @Test
    fun `declares exercise end to end, with the data associated to it`() {
        val capability = HealthCapabilities.of(HealthCategory.EXERCISE) as RecordCapability

        assertEquals(HealthCategory.EXERCISE, capability.category)
        assertEquals("exercise", capability.recordType)
        assertEquals(ExerciseMapper, capability.mapper)
        assertTrue(ReadOperation.TIME_RANGE in capability.readOperations)
        assertTrue(capability.supportsChanges)
        assertTrue(capability.hasAssociatedData)
        assertTrue(capability.pageSize > 0)
    }

    /**
     * The stages travel inside the sleep they belong to, and the blood oxygen and skin temperature
     * Samsung Health associates with a night are records of their own categories, not data of the sleep.
     */
    @Test
    fun `declares sleep end to end, with nothing associated to read beside it`() {
        val capability = HealthCapabilities.of(HealthCategory.SLEEP) as RecordCapability

        assertEquals(HealthCategory.SLEEP, capability.category)
        assertEquals("sleep", capability.recordType)
        assertEquals(SleepMapper, capability.mapper)
        assertTrue(ReadOperation.TIME_RANGE in capability.readOperations)
        assertTrue(capability.supportsChanges)
        assertFalse(capability.hasAssociatedData)
        assertTrue(capability.pageSize > 0)
    }

    /**
     * Their binned series has no identity of its own and travels inside the record, the way the stages
     * travel inside a night. The blood oxygen and skin temperature a night associates are read here, by
     * their own categories, and never through the sleep. See ADR 0023.
     */
    @Test
    fun `declares blood glucose, blood oxygen and skin temperature end to end, each carrying its series`() {
        mapOf(
            HealthCategory.BLOOD_GLUCOSE to ("blood_glucose" to BloodGlucoseMapper),
            HealthCategory.BLOOD_OXYGEN to ("blood_oxygen" to BloodOxygenMapper),
            HealthCategory.SKIN_TEMPERATURE to ("skin_temperature" to SkinTemperatureMapper),
        ).forEach { (category, expected) ->
            val capability = HealthCapabilities.of(category) as RecordCapability

            assertEquals(expected.first, capability.recordType)
            assertEquals(expected.second, capability.mapper)
            assertEquals("read_model.current_${expected.first}", capability.projection)
            assertTrue(ReadOperation.TIME_RANGE in capability.readOperations)
            assertTrue(capability.supportsChanges)
            assertFalse(capability.hasAssociatedData)
            assertEquals(AssociatedData.Nested("binning_data"), capability.associatedData)
            assertTrue(capability.pageSize > 0)
        }
    }

    /**
     * Samsung Health exposes steps only as an aggregate, with no UID and no modification time, so they are
     * read by local hour into Observed Snapshots and never become a Health Record. See ADR 0025.
     */
    @Test
    fun `declares steps as an hourly observed snapshot read by aggregation`() {
        val capability = HealthCapabilities.of(HealthCategory.STEPS) as SnapshotCapability

        assertEquals("steps", capability.snapshotType)
        assertEquals(setOf(ReadOperation.AGGREGATE), capability.readOperations)
        assertEquals(SnapshotWindowUnit.LOCAL_HOUR, capability.window)
        assertEquals(StepsMapper, capability.mapper)
        assertTrue(capability.windowsPerRead > 0)
    }

    /** The activity totals are aggregates too, kept by local hour so that a day is a sum over them. */
    @Test
    fun `declares the activity summary as an hourly observed snapshot read by aggregation`() {
        val capability = HealthCapabilities.of(HealthCategory.ACTIVITY_SUMMARY) as SnapshotCapability

        assertEquals("activity_summary", capability.snapshotType)
        assertEquals(setOf(ReadOperation.AGGREGATE), capability.readOperations)
        assertEquals(SnapshotWindowUnit.LOCAL_HOUR, capability.window)
        assertEquals(ActivitySummaryMapper, capability.mapper)
        assertEquals("read_model.current_hourly_activity_summary", capability.projection)
    }

    /**
     * The profile is one per Data Owner and summarizes no stretch of time, so it is read whole, with no
     * filter and no page, into the one Observed Snapshot without a Snapshot Window. See ADR 0025.
     */
    @Test
    fun `declares the user profile as one observed snapshot without a window, read whole`() {
        val capability = HealthCapabilities.of(HealthCategory.USER_PROFILE) as ProfileCapability

        assertEquals("user_profile", capability.snapshotType)
        assertEquals(CapabilityIdentity.Snapshot("user_profile", window = null), capability.identity)
        assertEquals(setOf(ReadOperation.PROFILE), capability.readOperations)
        assertEquals(Pagination.Unpaged, capability.pagination)
        assertEquals(ProfileMapper.VERSION, capability.mapperVersion)
        assertEquals("read_model.user_profile", capability.projection)
    }

    /**
     * Samsung Health keeps the goal in force on each past local day and exposes it only as the last value
     * of that day, so every goal is read day by day into Observed Snapshots. See ADR 0025.
     */
    @Test
    fun `declares every goal as a daily observed snapshot read by aggregation`() {
        val goals = GOALS.map { HealthCapabilities.of(it) as SnapshotCapability }

        assertEquals(
            listOf(
                "active_calories_burned_goal",
                "active_time_goal",
                "nutrition_goal",
                "sleep_goal",
                "steps_goal",
                "water_intake_goal",
            ),
            goals.map { it.snapshotType },
        )
        goals.forEach { goal ->
            assertEquals(setOf(ReadOperation.AGGREGATE), goal.readOperations)
            assertEquals(SnapshotWindowUnit.LOCAL_DAY, goal.window)
            assertEquals(SourceScope.ALL_SOURCES, goal.mapper.sourceScope)
            assertEquals("read_model.current_daily_goal", goal.projection)
            assertTrue(goal.windowsPerRead > 0)
        }
    }

    /** One wire name, one meaning: a snapshot type sharing a record type's name would be read as either. */
    @Test
    fun `names every capability on the wire distinctly`() {
        val wireTypes = HealthCapabilities.entries.mapNotNull { it.identity.wireType }

        assertEquals(wireTypes.size, wireTypes.toSet().size)
    }

    /** The route is read as part of the exercise, so it is never synchronized on its own. */
    @Test
    fun `covers the exercise location through the exercise that carries the route`() {
        val entry = HealthCapabilities.of(HealthCategory.EXERCISE_LOCATION) as PermissionOnly

        assertEquals(HealthCategory.EXERCISE, entry.carrier)
        assertEquals(emptySet<ReadOperation>(), entry.readOperations)
        assertNull(entry.identity.wireType)
    }

    @Test
    fun `catalogs a category at most once`() {
        val categories = HealthCapabilities.entries.map { it.category }

        assertEquals(categories.size, categories.toSet().size)
    }

    private companion object {
        val GOALS = listOf(
            HealthCategory.ACTIVE_CALORIES_BURNED_GOAL,
            HealthCategory.ACTIVE_TIME_GOAL,
            HealthCategory.NUTRITION_GOAL,
            HealthCategory.SLEEP_GOAL,
            HealthCategory.STEPS_GOAL,
            HealthCategory.WATER_INTAKE_GOAL,
        )
    }
}
