package br.etc.victor.myhealthbridge.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HealthCategoryCatalogTest {

    @Test
    fun `catalogs every readable capability exactly once`() {
        val ids = HealthCategory.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(25, ids.size)
    }

    @Test
    fun `groups the catalog as the operational screen shows it`() {
        val sizes = HealthCategory.entries.groupingBy { it.group }.eachCount()

        assertEquals(
            mapOf(
                HealthCategoryGroup.ACTIVITY to 5,
                HealthCategoryGroup.FOOD_AND_HYDRATION to 4,
                HealthCategoryGroup.SLEEP to 4,
                HealthCategoryGroup.HEALTH_MEASUREMENTS to 7,
                HealthCategoryGroup.GOALS_AND_INDICATORS to 4,
                HealthCategoryGroup.PROFILE to 1,
            ),
            sizes,
        )
    }

    @Test
    fun `keeps every group contiguous in display order`() {
        val groupsInOrder = HealthCategory.entries.map { it.group }

        assertEquals(HealthCategoryGroup.entries, groupsInOrder.distinct())
        assertEquals(groupsInOrder.distinct().size, groupsInOrder.zipWithNext().count { (a, b) -> a != b } + 1)
    }

    @Test
    fun `shows exercise location under exercise without merging their states`() {
        assertEquals(HealthCategory.EXERCISE, HealthCategory.EXERCISE_LOCATION.shownUnder)
        assertNull(HealthCategory.EXERCISE.shownUnder)
        HealthCategory.entries.filter { it != HealthCategory.EXERCISE_LOCATION }.forEach { assertNull(it.shownUnder) }
    }

    @Test
    fun `resolves a category from its stable identity`() {
        assertEquals(HealthCategory.HEART_RATE, HealthCategory.byId("heart_rate"))
        assertNull(HealthCategory.byId("not_in_the_catalog"))
    }
}
