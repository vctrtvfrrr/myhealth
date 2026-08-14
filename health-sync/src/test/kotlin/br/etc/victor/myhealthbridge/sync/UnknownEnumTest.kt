package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A mapper is taught its enums by hand, so a constant Samsung Health adds later arrives as one it does
 * not know. Nothing is dropped when that happens: the observation is preserved as reported, and the
 * gap is reported to whoever owns the mapper.
 */
class UnknownEnumTest {

    private object SleepStageMapper : RecordMapper {

        override val version: String = "test-sleep-stage/1"

        override val knownEnums: Map<String, Set<String>> = mapOf("stage" to setOf("AWAKE", "LIGHT"))

        override fun map(record: SourceRecord): HealthRecordEnvelope = removalOf(record.uid, record.startTime)
    }

    private val capability = HealthCapability(
        category = HealthCategory.SLEEP,
        recordType = "sleep",
        readOperations = setOf(ReadOperation.TIME_RANGE),
        pageSize = 100,
        mapper = SleepStageMapper,
        projected = false,
    )

    private fun record(fields: Map<String, SourceValue>) = sourceRecord(fields = fields)

    @Test
    fun `says nothing about a constant the mapper knows`() {
        val unknown = SleepStageMapper.unknownEnums(record(mapOf("stage" to SourceValue.Text("AWAKE"))))

        assertTrue(unknown.isEmpty())
    }

    @Test
    fun `names the field and the constant the mapper does not interpret`() {
        val unknown = SleepStageMapper.unknownEnums(record(mapOf("stage" to SourceValue.Text("DEEP"))))

        assertEquals(listOf("stage=DEEP"), unknown)
    }

    @Test
    fun `reads the constants nested inside a series`() {
        val series = SourceValue.Series(
            listOf(
                mapOf("stage" to SourceValue.Text("LIGHT")),
                mapOf("stage" to SourceValue.Text("REM")),
                mapOf("stage" to SourceValue.Text("REM")),
            ),
        )

        val unknown = SleepStageMapper.unknownEnums(record(mapOf("samples" to series)))

        assertEquals(listOf("stage=REM"), unknown, "the same gap in every sample is one gap")
    }

    /** A field the mapper never declared may hold anything the source wants, including free text. */
    @Test
    fun `never quotes a field the mapper did not declare as an enum`() {
        val unknown = SleepStageMapper.unknownEnums(record(mapOf("comment" to SourceValue.Text("a note"))))

        assertTrue(unknown.isEmpty())
    }

    @Test
    fun `reports the gap while still staging the observation`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC)
        val store = FakeSyncStore()
        val maintenance = FakeMaintenance()
        val source = FakeRecordSource(listOf(page(listOf(record(mapOf("stage" to SourceValue.Text("DEEP")))))))
        store.writeCursor(
            SyncCursor(capability.category)
                .startingInitialLoad(LocalDateTime.of(2026, 8, 11, 9, 0), Instant.parse("2026-08-11T00:00:00Z")),
        )

        HistoryImporter(source, store, maintenanceService(maintenance, clock), SyncPolicy(), clock)
            .import(capability)

        assertEquals(
            listOf(IncidentIdentity(MaintenanceCode.UNKNOWN_ENUM, HealthCategory.SLEEP, "stage=DEEP")),
            maintenance.reported,
        )
        assertEquals(1, store.staged.size)
    }
}
