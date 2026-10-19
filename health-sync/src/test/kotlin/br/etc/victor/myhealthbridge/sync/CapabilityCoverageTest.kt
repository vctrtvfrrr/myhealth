package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.LocalDateTime

/**
 * The uniform criterion every implemented entry of the capability catalog meets: a mapper that turns a
 * synthetic fixture into an observation, and a read model view the API actually creates for it.
 */
class CapabilityCoverageTest {

    private val implemented = HealthCapabilities.entries.filter { it.implemented }

    private val readAt = ZonedInstant("2026-08-10T18:20:00Z", "-03:00")

    @TestFactory
    fun `maps a synthetic fixture of every implemented category`() = implemented.map { entry ->
        DynamicTest.dynamicTest(entry.category.id) {
            val fixture = SyntheticFixtures.of(entry.category) ?: fail("no synthetic fixture")

            when (entry) {
                is RecordCapability -> assertMapsRecord(entry.mapper, fixture)
                is SnapshotCapability -> {
                    val window = SnapshotWindow(START, entry.window.advance(START, 1))
                    val observation = entry.mapper.map(window, (fixture as SyntheticFixture.Aggregate).value, readAt)
                    assertEquals(entry.mapperVersion, observation.mapperVersion)
                    assertNotNull(observation.content)
                }
                is ProfileCapability -> {
                    val observation = entry.mapper.map((fixture as SyntheticFixture.Profile).fields, readAt)
                    assertEquals(entry.mapperVersion, observation.mapperVersion)
                    assertNotNull(observation.content)
                }
                is PermissionOnly -> {
                    val carrier = HealthCapabilities.of(entry.carrier) as RecordCapability
                    assertMapsRecord(carrier.mapper, fixture)
                }
                is NotImplemented -> error("a gap is never implemented")
            }
        }
    }

    @TestFactory
    fun `projects every implemented category into a view the API creates`() = implemented.map { entry ->
        DynamicTest.dynamicTest(entry.category.id) {
            assertTrue(entry.projection in createdViews(), "${entry.projection} is created by no migration")
        }
    }

    @TestFactory
    fun `declares no mapper nor projection for a category not implemented`() =
        HealthCapabilities.entries.filterNot { it.implemented }.map { entry ->
            DynamicTest.dynamicTest(entry.category.id) {
                assertNull(entry.mapperVersion)
                assertNull(entry.projection)
                assertNull(SyntheticFixtures.of(entry.category), "a fixture for a mapper that does not exist")
            }
        }

    private fun assertMapsRecord(mapper: RecordMapper, fixture: SyntheticFixture) {
        val envelope = mapper.map((fixture as SyntheticFixture.Record).record)

        assertEquals(mapper.version, envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Present, "the fixture maps to ${envelope.state}")
    }

    private fun createdViews(): Set<String> {
        val root = File(System.getProperty("repositoryRoot") ?: error("Missing repositoryRoot"))
        return File(root, "ingestion-api/src/main/resources/db/migration")
            .listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .flatMap { VIEW.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()
    }

    private companion object {
        val START: LocalDateTime = LocalDateTime.parse("2026-08-10T14:00:00")

        val VIEW = Regex("""create\s+(?:or\s+replace\s+)?view\s+([\w.]+)""", RegexOption.IGNORE_CASE)
    }
}
