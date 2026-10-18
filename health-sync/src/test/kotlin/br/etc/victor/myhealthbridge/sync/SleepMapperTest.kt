package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SleepMapperTest {

    private val mapper = SleepMapper

    private val staged = sleepRecord(
        sessions = listOf(
            sleepSession(
                stages = listOf(
                    sleepStage(start = "2026-08-10T02:00:00Z", end = "2026-08-10T02:40:00Z", stage = "LIGHT"),
                    sleepStage(start = "2026-08-10T02:40:00Z", end = "2026-08-10T03:30:00Z", stage = "DEEP"),
                ),
            ),
        ),
    )

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(sleepRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-sleep/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record`() {
        val envelope = mapper.map(sleepRecord(updateTime = Instant.parse("2026-08-10T09:35:00Z")))

        assertEquals("2026-08-10T09:35:00Z", envelope.observedAt.instant)
        assertEquals("-03:00", envelope.observedAt.offset)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(staged), mapper.map(staged))
    }

    @Test
    fun `keeps the period in UTC beside the original offset`() {
        val period = (mapper.map(sleepRecord()).state as RecordState.Present).period

        assertEquals("2026-08-10T02:00:00Z", period.start.instant)
        assertEquals("2026-08-10T09:30:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
        assertEquals("-03:00", period.end.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(sleepRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `normalizes how long the night was and the score the source gave it`() {
        val sleep = sleepOf(mapper.map(sleepRecord()))

        assertEquals("27000.000" to "s", quantity(sleep, "duration"))
        assertEquals("82", (sleep["score"] as JsonPrimitive).content)
    }

    @Test
    fun `leaves out a duration and a score the source did not report`() {
        val sleep = sleepOf(mapper.map(sleepRecord(duration = null, score = null)))

        assertNull(sleep["duration"])
        assertNull(sleep["score"])
    }

    @Test
    fun `projects every session with its stages, in the order the source reported them`() {
        val record = sleepRecord(
            sessions = listOf(
                sleepSession(
                    start = "2026-08-10T02:00:00Z",
                    end = "2026-08-10T04:00:00Z",
                    duration = "7200.000",
                    stages = listOf(sleepStage(stage = "LIGHT"), sleepStage(start = "2026-08-10T02:40:00Z", stage = "REM")),
                ),
                sleepSession(start = "2026-08-10T05:00:00Z", end = "2026-08-10T09:30:00Z", duration = "16200.000"),
            ),
        )

        val sessions = sleepOf(mapper.map(record))["sessions"] as JsonArray
        assertEquals(2, sessions.size)

        val first = sessions[0] as JsonObject
        assertEquals("2026-08-10T02:00:00Z", (first["start"] as JsonPrimitive).content)
        assertEquals("2026-08-10T04:00:00Z", (first["end"] as JsonPrimitive).content)
        assertEquals("7200.000", (first["durationSeconds"] as JsonPrimitive).content)

        val stages = first["stages"] as JsonArray
        assertEquals(listOf("LIGHT", "REM"), stages.map { ((it as JsonObject)["stage"] as JsonPrimitive).content })
        assertEquals("2026-08-10T02:40:00Z", ((stages[1] as JsonObject)["start"] as JsonPrimitive).content)
        assertEquals("2026-08-10T02:40:00Z", ((stages[0] as JsonObject)["end"] as JsonPrimitive).content)
    }

    /** A night Samsung Health staged nothing for is normalized without stages, not with an empty list. */
    @Test
    fun `leaves the stages out of a session the source staged nothing for`() {
        val session = (sleepOf(mapper.map(sleepRecord()))["sessions"] as JsonArray)[0] as JsonObject

        assertNull(session["stages"])
        assertEquals("27000.000", (session["durationSeconds"] as JsonPrimitive).content)
    }

    @Test
    fun `leaves out a session or a stage that does not say when it happened`() {
        val record = sleepRecord(
            sessions = listOf(
                sleepSession(start = null),
                sleepSession(stages = listOf(sleepStage(end = null), sleepStage(stage = null), sleepStage(stage = "AWAKE"))),
            ),
        )

        val sessions = sleepOf(mapper.map(record))["sessions"] as JsonArray
        assertEquals(1, sessions.size)
        val stages = (sessions[0] as JsonObject)["stages"] as JsonArray
        assertEquals(listOf("AWAKE"), stages.map { ((it as JsonObject)["stage"] as JsonPrimitive).content })
    }

    @Test
    fun `leaves the sessions out of a night the source reported none for`() {
        val sleep = sleepOf(mapper.map(sleepRecord(sessions = null)))

        assertNull(sleep["sessions"])
        assertEquals("27000.000" to "s", quantity(sleep, "duration"))
    }

    @Test
    fun `preserves everything the source reported, stages and enum constants included`() {
        val fields = fieldsOf(mapper.map(staged))

        assertEquals("27000.000", (fields["sleep_duration"] as JsonPrimitive).content)
        assertEquals("82", (fields["sleep_score"] as JsonPrimitive).content)
        val session = (fields["sessions"] as JsonArray)[0] as JsonObject
        val stage = (session["stages"] as JsonArray)[1] as JsonObject
        assertEquals("DEEP", (stage["stage"] as JsonPrimitive).content)
        assertEquals("2026-08-10T02:40:00Z", (stage["start_time"] as JsonPrimitive).content)
    }

    /** A stage the pinned SDK does not name yet is still a stage: it is carried, not refused or reported. */
    @Test
    fun `carries a stage constant this build does not know`() {
        val record = sleepRecord(sessions = listOf(sleepSession(stages = listOf(sleepStage(stage = "NAP")))))

        val session = (sleepOf(mapper.map(record))["sessions"] as JsonArray)[0] as JsonObject
        assertEquals("NAP", (((session["stages"] as JsonArray)[0] as JsonObject)["stage"] as JsonPrimitive).content)
        assertEquals(emptyList<String>(), mapper.unknownEnums(record))
    }

    @Test
    fun `leaves the instant standing alone when the source reported no local context`() {
        val record = sourceRecord(offset = null, fields = mapOf("sleep_duration" to number("3600.000")))

        assertEquals("+00:00", mapper.map(record).observedAt.offset)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-sleep-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceApp)
        assertEquals("2026-08-12T09:00:00Z", envelope.observedAt.instant)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun sleepOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["sleep"] as JsonObject

    private fun fieldsOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).sourcePayload["fields"] as JsonObject

    private fun quantity(sleep: JsonObject, name: String): Pair<String, String> {
        val quantity = sleep[name] as JsonObject
        return (quantity["value"] as JsonPrimitive).content to (quantity["unit"] as JsonPrimitive).content
    }
}
