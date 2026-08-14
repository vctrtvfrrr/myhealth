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
        val capability = requireNotNull(HealthCapabilities.of(HealthCategory.HEART_RATE))

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
        val capability = requireNotNull(HealthCapabilities.of(HealthCategory.EXERCISE))

        assertEquals(HealthCategory.EXERCISE, capability.category)
        assertEquals("exercise", capability.recordType)
        assertEquals(ExerciseMapper, capability.mapper)
        assertTrue(ReadOperation.TIME_RANGE in capability.readOperations)
        assertTrue(capability.supportsChanges)
        assertTrue(capability.hasAssociatedData)
        assertTrue(capability.pageSize > 0)
        assertTrue(capability.projected)
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
