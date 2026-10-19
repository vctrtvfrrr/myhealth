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

class SkinTemperatureMapperTest {

    private val mapper = SkinTemperatureMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(skinTemperatureRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-skin-temperature/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record, keeping the period in UTC beside the original offset`() {
        val envelope = mapper.map(skinTemperatureRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T03:15:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T03:00:00Z", period.start.instant)
        assertEquals("2026-08-10T03:10:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(skinTemperatureRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(skinTemperatureRecord()), mapper.map(skinTemperatureRecord()))
    }

    @Test
    fun `normalizes the temperature and its range into degrees Celsius`() {
        val temperature = temperatureOf(mapper.map(skinTemperatureRecord()))

        assertEquals("33.5" to "Cel", quantity(temperature, "temperature"))
        assertEquals("33.1" to "Cel", quantity(temperature, "min"))
        assertEquals("33.9" to "Cel", quantity(temperature, "max"))
    }

    @Test
    fun `leaves out a range the source did not report`() {
        val temperature = temperatureOf(mapper.map(skinTemperatureRecord(min = null, max = null)))

        assertNull(temperature["min"])
        assertNull(temperature["max"])
    }

    @Test
    fun `leaves the normalized measurement out when the source did not report one`() {
        val envelope = mapper.map(skinTemperatureRecord(temperature = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["skinTemperature"])
    }

    @Test
    fun `projects every bin of the series, in the order the source reported them`() {
        val record = skinTemperatureRecord(
            bins = listOf(
                skinTemperatureBin(start = "2026-08-10T03:00:00Z", end = "2026-08-10T03:05:00Z", temperature = "33.2"),
                skinTemperatureBin(start = "2026-08-10T03:05:00Z", end = "2026-08-10T03:10:00Z", temperature = "33.8", min = null, max = null),
            ),
        )

        val series = temperatureOf(mapper.map(record))["series"] as JsonArray
        assertEquals(2, series.size)

        val first = series[0] as JsonObject
        assertEquals("2026-08-10T03:00:00Z", text(first, "start"))
        assertEquals("2026-08-10T03:05:00Z", text(first, "end"))
        assertEquals("33.2", text(first, "temperatureCelsius"))
        assertEquals("33.1", text(first, "minCelsius"))
        assertEquals("33.9", text(first, "maxCelsius"))

        val second = series[1] as JsonObject
        assertEquals("33.8", text(second, "temperatureCelsius"))
        assertNull(second["maxCelsius"])
    }

    @Test
    fun `leaves out a bin that does not say when it was or what it measured`() {
        val record = skinTemperatureRecord(
            bins = listOf(
                skinTemperatureBin(start = null),
                skinTemperatureBin(end = null),
                skinTemperatureBin(temperature = null),
                skinTemperatureBin(temperature = "34.0"),
            ),
        )

        val series = temperatureOf(mapper.map(record))["series"] as JsonArray
        assertEquals(listOf("34.0"), series.map { text(it as JsonObject, "temperatureCelsius") })
    }

    @Test
    fun `leaves the series out of a measurement the source binned nothing for`() {
        assertNull(temperatureOf(mapper.map(skinTemperatureRecord(bins = null)))["series"])
    }

    @Test
    fun `preserves everything the source reported, series included`() {
        val fields = (mapper.map(skinTemperatureRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("33.5", (fields["temperature"] as JsonPrimitive).content)
        assertEquals("33.9", (fields["max"] as JsonPrimitive).content)
        val bin = (fields["binning_data"] as JsonArray)[0] as JsonObject
        assertEquals("33.5", (bin["skin_temperature"] as JsonPrimitive).content)
        assertEquals("2026-08-10T03:10:00Z", (bin["end_time"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-skin-temperature-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-skin-temperature/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun temperatureOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["skinTemperature"] as JsonObject

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content

    private fun quantity(node: JsonObject, name: String): Pair<String, String> {
        val quantity = node[name] as JsonObject
        return text(quantity, "value") to text(quantity, "unit")
    }
}
