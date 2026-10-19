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

class WaterIntakeMapperTest {

    private val mapper = WaterIntakeMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(waterIntakeRecord(uid = "samsung-uid-7"))

        assertEquals("samsung-uid-7", envelope.samsungUid)
        assertEquals("samsung-health-water-intake/1", envelope.mapperVersion)
    }

    @Test
    fun `reads an intake without an end as a period that starts and ends with it`() {
        val envelope = mapper.map(waterIntakeRecord(end = null))
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T12:31:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T12:30:00Z", period.start.instant)
        assertEquals("2026-08-10T12:30:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(waterIntakeRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(waterIntakeRecord()), mapper.map(waterIntakeRecord()))
    }

    @Test
    fun `normalizes the amount into milliliters`() {
        val amount = intakeOf(mapper.map(waterIntakeRecord(amount = "330.0")))["amount"] as JsonObject

        assertEquals("330.0", text(amount, "value"))
        assertEquals("mL", text(amount, "unit"))
    }

    @Test
    fun `leaves the normalized intake out when the source did not report an amount`() {
        val envelope = mapper.map(waterIntakeRecord(amount = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["waterIntake"])
    }

    @Test
    fun `preserves everything the source reported`() {
        val fields = (mapper.map(waterIntakeRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("250.0", (fields["amount"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-water-intake-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-water-intake/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun intakeOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["waterIntake"] as JsonObject

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content
}
