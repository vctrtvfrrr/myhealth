package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.HealthCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthCapabilitiesTest {

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
        assertTrue(capability.projected)
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
        assertTrue(capability.projected)
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
        assertTrue(capability.projected)
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
        assertTrue(capability.projected)
    }

    /** One wire name, one meaning: a snapshot type sharing a record type's name would be read as either. */
    @Test
    fun `names every capability on the wire distinctly`() {
        val wireTypes = HealthCapabilities.entries.map { it.wireType }

        assertEquals(wireTypes.size, wireTypes.toSet().size)
    }

    /** The route is read as part of the exercise, so it is never a capability of its own. */
    @Test
    fun `catalogs no capability for the exercise location`() {
        assertNull(HealthCapabilities.of(HealthCategory.EXERCISE_LOCATION))
    }

    @Test
    fun `catalogs a category at most once`() {
        val categories = HealthCapabilities.entries.map { it.category }

        assertEquals(categories.size, categories.toSet().size)
    }
}
