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

class BloodPressureMapperTest {

    private val mapper = BloodPressureMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(bloodPressureRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-blood-pressure/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record, keeping the instant in UTC beside the original offset`() {
        val envelope = mapper.map(bloodPressureRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T10:01:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T10:00:00Z", period.start.instant)
        assertEquals("2026-08-10T10:00:00Z", period.end.instant)
        assertEquals("-03:00", period.end.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(bloodPressureRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(bloodPressureRecord()), mapper.map(bloodPressureRecord()))
    }

    @Test
    fun `normalizes the pressures into millimeters of mercury`() {
        val pressure = pressureOf(mapper.map(bloodPressureRecord()))

        assertEquals("118.0" to "mm[Hg]", quantity(pressure, "systolic"))
        assertEquals("76.0" to "mm[Hg]", quantity(pressure, "diastolic"))
        assertEquals("90.0" to "mm[Hg]", quantity(pressure, "mean"))
    }

    /** Samsung Health documents no unit for the pulse rate, so none is claimed for it. */
    @Test
    fun `carries the pulse rate and the medication as the source reported them`() {
        val pressure = pressureOf(mapper.map(bloodPressureRecord(medicationTaken = true)))

        assertEquals(JsonPrimitive(64.toBigDecimal()), pressure["pulseRate"])
        assertEquals(JsonPrimitive(true), pressure["medicationTaken"])
    }

    @Test
    fun `leaves out what the source did not report`() {
        val pressure = pressureOf(mapper.map(bloodPressureRecord(mean = null, pulseRate = null, medicationTaken = null)))

        assertNull(pressure["mean"])
        assertNull(pressure["pulseRate"])
        assertNull(pressure["medicationTaken"])
    }

    /** A reading without both pressures is not substituted, so the API rejects it and it stays a mapping pendency. */
    @Test
    fun `leaves the normalized reading out when the source did not report both pressures`() {
        listOf(bloodPressureRecord(systolic = null), bloodPressureRecord(diastolic = null)).forEach { record ->
            assertNull((mapper.map(record).state as RecordState.Present).normalizedPayload["bloodPressure"])
        }
    }

    @Test
    fun `preserves everything the source reported`() {
        val fields = (mapper.map(bloodPressureRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("118.0", (fields["systolic"] as JsonPrimitive).content)
        assertEquals("64", (fields["pulse"] as JsonPrimitive).content)
        assertEquals("false", (fields["medication"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-blood-pressure-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-blood-pressure/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun pressureOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["bloodPressure"] as JsonObject

    private fun quantity(node: JsonObject, name: String): Pair<String, String> {
        val quantity = node[name] as JsonObject
        return (quantity["value"] as JsonPrimitive).content to (quantity["unit"] as JsonPrimitive).content
    }
}
