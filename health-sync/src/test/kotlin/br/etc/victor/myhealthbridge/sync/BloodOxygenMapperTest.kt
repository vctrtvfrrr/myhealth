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

class BloodOxygenMapperTest {

    private val mapper = BloodOxygenMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(bloodOxygenRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-blood-oxygen/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record, keeping the period in UTC beside the original offset`() {
        val envelope = mapper.map(bloodOxygenRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T03:15:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T03:00:00Z", period.start.instant)
        assertEquals("2026-08-10T03:10:00Z", period.end.instant)
        assertEquals("-03:00", period.end.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(bloodOxygenRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(bloodOxygenRecord()), mapper.map(bloodOxygenRecord()))
    }

    @Test
    fun `normalizes the saturation and its range into percent`() {
        val oxygen = oxygenOf(mapper.map(bloodOxygenRecord()))

        assertEquals("96.0" to "%", quantity(oxygen, "saturation"))
        assertEquals("93.0" to "%", quantity(oxygen, "min"))
        assertEquals("98.0" to "%", quantity(oxygen, "max"))
    }

    @Test
    fun `leaves out a range the source did not report`() {
        val oxygen = oxygenOf(mapper.map(bloodOxygenRecord(min = null, max = null)))

        assertNull(oxygen["min"])
        assertNull(oxygen["max"])
    }

    /** An absent saturation is not substituted, so the API rejects it and it stays a mapping pendency. */
    @Test
    fun `leaves the normalized measurement out when the source did not report one`() {
        val envelope = mapper.map(bloodOxygenRecord(saturation = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["bloodOxygen"])
    }

    @Test
    fun `projects every bin of the series, in the order the source reported them`() {
        val record = bloodOxygenRecord(
            bins = listOf(
                oxygenBin(start = "2026-08-10T03:00:00Z", end = "2026-08-10T03:05:00Z", saturation = "95.0"),
                oxygenBin(start = "2026-08-10T03:05:00Z", end = "2026-08-10T03:10:00Z", saturation = "97.0", min = null, max = null),
            ),
        )

        val series = oxygenOf(mapper.map(record))["series"] as JsonArray
        assertEquals(2, series.size)

        val first = series[0] as JsonObject
        assertEquals("2026-08-10T03:00:00Z", text(first, "start"))
        assertEquals("2026-08-10T03:05:00Z", text(first, "end"))
        assertEquals("95.0", text(first, "saturationPercent"))
        assertEquals("93.0", text(first, "minPercent"))
        assertEquals("98.0", text(first, "maxPercent"))

        val second = series[1] as JsonObject
        assertEquals("97.0", text(second, "saturationPercent"))
        assertNull(second["minPercent"])
    }

    @Test
    fun `leaves out a bin that does not say when it was or what it measured`() {
        val record = bloodOxygenRecord(
            bins = listOf(oxygenBin(start = null), oxygenBin(end = null), oxygenBin(saturation = null), oxygenBin(saturation = "94.0")),
        )

        val series = oxygenOf(mapper.map(record))["series"] as JsonArray
        assertEquals(listOf("94.0"), series.map { text(it as JsonObject, "saturationPercent") })
    }

    @Test
    fun `leaves the series out of a measurement the source binned nothing for`() {
        assertNull(oxygenOf(mapper.map(bloodOxygenRecord(bins = null)))["series"])
        assertNull(oxygenOf(mapper.map(bloodOxygenRecord(bins = emptyList())))["series"])
    }

    @Test
    fun `preserves everything the source reported, series included`() {
        val fields = (mapper.map(bloodOxygenRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("96.0", (fields["spo2"] as JsonPrimitive).content)
        assertEquals("93.0", (fields["min"] as JsonPrimitive).content)
        val bin = (fields["binning_data"] as JsonArray)[0] as JsonObject
        assertEquals("96.0", (bin["oxygen_saturation"] as JsonPrimitive).content)
        assertEquals("2026-08-10T03:00:00Z", (bin["start_time"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-blood-oxygen-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-blood-oxygen/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun oxygenOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["bloodOxygen"] as JsonObject

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content

    private fun quantity(node: JsonObject, name: String): Pair<String, String> {
        val quantity = node[name] as JsonObject
        return text(quantity, "value") to text(quantity, "unit")
    }
}
