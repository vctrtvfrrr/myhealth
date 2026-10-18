package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.SnapshotBatch
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The step snapshot of one local hour, rendered from synthetic aggregates. Every count is invented. */
class StepsMapperTest {

    private val window = SnapshotWindow(
        start = LocalDateTime.parse("2026-08-10T14:00:00"),
        end = LocalDateTime.parse("2026-08-10T15:00:00"),
    )

    private val readAt = ZonedInstant("2026-08-10T18:20:00Z", "-03:00")

    @Test
    fun `renders the hour's total as the snapshot's content`() {
        val observation = StepsMapper.map(window, number("1234"), readAt)

        assertEquals(LocalInterval("2026-08-10T14:00:00", "2026-08-10T15:00:00"), observation.window)
        assertEquals(readAt, observation.observedAt)
        assertEquals(SourceScope.PLATFORM, observation.sourceScope)
        assertEquals("samsung-health-steps/1", observation.mapperVersion)
        assertEquals(JsonPrimitive(1234.toBigDecimal()), observation.content!!["steps"])
    }

    /** An hour Samsung Health counted nothing in is observed as empty, never as an invented zero. */
    @Test
    fun `renders an hour without an aggregate as an empty observation`() {
        assertNull(StepsMapper.map(window, aggregate = null, readAt).content)
    }

    /** A zero the source did report is what it said, which is not the same as saying nothing. */
    @Test
    fun `keeps a reported zero apart from an empty hour`() {
        assertEquals(JsonPrimitive(0.toBigDecimal()), StepsMapper.map(window, number("0"), readAt).content!!["steps"])
    }

    /** Content the API will refuse, so it becomes a Mapping Pendency instead of a guessed count. */
    @Test
    fun `renders an aggregate that is not a count as content without steps`() {
        assertEquals(JsonObject(emptyMap()), StepsMapper.map(window, SourceValue.Text("many"), readAt).content)
    }

    /**
     * The exact document a batch of step snapshots is sent as. The same literal is posted at
     * `AppSnapshotReadModelTest` in `ingestion-api`, against a real API and a real PostgreSQL, so that
     * what the mapper emits is what the read model was proven to show.
     */
    @Test
    fun `pins the version 3 batch a step snapshot is sent in`() {
        val batch = SnapshotBatch(
            snapshotType = "steps",
            items = listOf(
                StepsMapper.map(window, number("1234"), readAt),
                StepsMapper.map(
                    SnapshotWindow(LocalDateTime.parse("2026-08-10T15:00:00"), LocalDateTime.parse("2026-08-10T16:00:00")),
                    aggregate = null,
                    readAt,
                ),
            ),
        )

        assertEquals(GOLDEN, IngestionContract.json.encodeToString(SnapshotBatch.serializer(), batch))
    }

    private companion object {
        const val GOLDEN =
            """{"snapshotType":"steps","items":[{"window":{"start":"2026-08-10T14:00:00","end":"2026-08-10T15:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1","content":{"steps":1234}},{"window":{"start":"2026-08-10T15:00:00","end":"2026-08-10T16:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1"}],"contractVersion":3}"""
    }
}
