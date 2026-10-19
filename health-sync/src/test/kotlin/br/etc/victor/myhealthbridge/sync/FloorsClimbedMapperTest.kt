package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FloorsClimbedMapperTest {

    private val mapper = FloorsClimbedMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(floorsClimbedRecord(uid = "samsung-uid-5"))

        assertEquals("samsung-uid-5", envelope.samsungUid)
        assertEquals("samsung-health-floors-climbed/1", envelope.mapperVersion)
    }

    @Test
    fun `keeps the period the floors were climbed in, with its original offset`() {
        val envelope = mapper.map(floorsClimbedRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T09:11:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T09:00:00Z", period.start.instant)
        assertEquals("2026-08-10T09:10:00Z", period.end.instant)
        assertEquals("-03:00", period.end.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(floorsClimbedRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(floorsClimbedRecord()), mapper.map(floorsClimbedRecord()))
    }

    /** Samsung Health documents no unit for it, so the count is carried bare rather than with a guessed one. */
    @Test
    fun `carries the floors as the bare count the source reported`() {
        val climbed = climbedOf(mapper.map(floorsClimbedRecord(floors = "4.5")))

        assertEquals("4.5", (climbed["floors"] as JsonPrimitive).content)
        assertEquals(setOf("floors"), climbed.keys)
    }

    @Test
    fun `leaves the normalized climb out when the source did not report the floors`() {
        val envelope = mapper.map(floorsClimbedRecord(floors = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["floorsClimbed"])
    }

    @Test
    fun `preserves everything the source reported`() {
        val fields = (mapper.map(floorsClimbedRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("3.0", (fields["floor"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-floors-climbed-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-floors-climbed/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun climbedOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["floorsClimbed"] as JsonObject
}
