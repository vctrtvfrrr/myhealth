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

class BodyTemperatureMapperTest {

    private val mapper = BodyTemperatureMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(bodyTemperatureRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-body-temperature/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record, keeping the instant in UTC beside the original offset`() {
        val envelope = mapper.map(bodyTemperatureRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T08:00:30Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T08:00:00Z", period.start.instant)
        assertEquals("2026-08-10T08:00:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(bodyTemperatureRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(bodyTemperatureRecord()), mapper.map(bodyTemperatureRecord()))
    }

    @Test
    fun `normalizes the temperature into degrees Celsius`() {
        val temperature = temperatureOf(mapper.map(bodyTemperatureRecord()))["temperature"] as JsonObject

        assertEquals("36.6", (temperature["value"] as JsonPrimitive).content)
        assertEquals("Cel", (temperature["unit"] as JsonPrimitive).content)
    }

    /** An absent temperature is not substituted, so the API rejects it and it stays a mapping pendency. */
    @Test
    fun `leaves the normalized temperature out when the source did not report one`() {
        val envelope = mapper.map(bodyTemperatureRecord(temperature = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["bodyTemperature"])
    }

    @Test
    fun `preserves everything the source reported`() {
        val fields = (mapper.map(bodyTemperatureRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("36.6", (fields["temperature"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-body-temperature-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-body-temperature/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun temperatureOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["bodyTemperature"] as JsonObject
}
