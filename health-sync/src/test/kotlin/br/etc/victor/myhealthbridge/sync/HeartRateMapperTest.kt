package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class HeartRateMapperTest {

    private val mapper = HeartRateMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(sourceRecord(uid = "samsung-uid-42"))

        assertEquals("samsung-uid-42", envelope.samsungUid)
        assertEquals("samsung-health-heart-rate/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record`() {
        val envelope = mapper.map(
            sourceRecord(updateTime = Instant.parse("2026-08-10T22:00:00Z"), offset = ZoneOffset.ofHours(-3)),
        )

        assertEquals("2026-08-10T22:00:00Z", envelope.observedAt.instant)
        assertEquals("-03:00", envelope.observedAt.offset)
    }

    @Test
    fun `maps the same record identically on every import`() {
        val record = sourceRecord()

        assertEquals(mapper.map(record), mapper.map(record))
    }

    @Test
    fun `keeps the period in UTC beside the original offset`() {
        val envelope = mapper.map(
            sourceRecord(
                start = Instant.parse("2026-08-10T21:59:00Z"),
                end = Instant.parse("2026-08-10T21:59:30Z"),
                offset = ZoneOffset.ofHoursMinutes(5, 30),
            ),
        )

        val period = (envelope.state as RecordState.Present).period
        assertEquals("2026-08-10T21:59:00Z", period.start.instant)
        assertEquals("2026-08-10T21:59:30Z", period.end.instant)
        assertEquals("+05:30", period.start.offset)
        assertEquals("+05:30", period.end.offset)
    }

    @Test
    fun `reads a measurement without an end as a period that starts and ends with it`() {
        val start = Instant.parse("2026-08-10T21:59:00Z")

        val period = (mapper.map(sourceRecord(start = start, end = null)).state as RecordState.Present).period

        assertEquals(period.start, period.end)
    }

    @Test
    fun `falls back to the start when the source reported no modification time`() {
        val envelope = mapper.map(
            sourceRecord(start = Instant.parse("2026-08-10T21:59:00Z"), updateTime = null),
        )

        assertEquals("2026-08-10T21:59:00Z", envelope.observedAt.instant)
    }

    @Test
    fun `leaves the instant standing alone when the source reported no local context`() {
        val envelope = mapper.map(sourceRecord(offset = null))

        assertEquals("+00:00", envelope.observedAt.offset)
    }

    @Test
    fun `writes the UTC offset in the form the contract accepts`() {
        val envelope = mapper.map(sourceRecord(offset = ZoneOffset.UTC))

        assertEquals("+00:00", envelope.observedAt.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(sourceRecord(sourceAppId = "com.example.shealth", sourceDeviceId = "device-1"))

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `keeps an absent source identity as the explicit unknown`() {
        val envelope = mapper.map(sourceRecord(sourceAppId = null, sourceDeviceId = ""))

        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `normalizes the measurement into beats per minute`() {
        val envelope = mapper.map(sourceRecord(fields = mapOf("heart_rate" to number("72.5"))))

        val heartRate = (envelope.state as RecordState.Present).normalizedPayload["heartRate"] as JsonObject
        assertEquals("72.5", (heartRate["value"] as JsonPrimitive).content)
        assertEquals("/min", (heartRate["unit"] as JsonPrimitive).content)
    }

    @Test
    fun `leaves the normalized measurement out when the source did not report one`() {
        val envelope = mapper.map(sourceRecord(fields = mapOf("min" to number("60"))))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["heartRate"])
    }

    @Test
    fun `preserves every public field the source reported`() {
        val envelope = mapper.map(
            sourceRecord(
                fields = mapOf(
                    "heart_rate" to number("72.0"),
                    "min" to number("58.0"),
                    "max" to number("131.0"),
                    "measurement_type" to SourceValue.Text("MANUAL"),
                    "binning_data" to SourceValue.Series(
                        listOf(
                            mapOf("heart_rate" to number("70.0"), "start_time" to SourceValue.Text("2026-08-10T21:59:00Z")),
                            mapOf("heart_rate" to number("74.0"), "start_time" to SourceValue.Text("2026-08-10T21:59:10Z")),
                        ),
                    ),
                ),
            ),
        )

        val source = (envelope.state as RecordState.Present).sourcePayload
        val fields = source["fields"] as JsonObject
        assertEquals("72.0", (fields["heart_rate"] as JsonPrimitive).content)
        assertEquals("58.0", (fields["min"] as JsonPrimitive).content)
        assertEquals("131.0", (fields["max"] as JsonPrimitive).content)
        assertEquals("MANUAL", (fields["measurement_type"] as JsonPrimitive).content)

        val series = fields["binning_data"] as JsonArray
        assertEquals(2, series.size)
        assertEquals("74.0", ((series[1] as JsonObject)["heart_rate"] as JsonPrimitive).content)
    }

    @Test
    fun `preserves the client identity the source attached`() {
        val envelope = mapper.map(sourceRecord(clientDataId = "client-1", clientVersion = 3))

        val client = (envelope.state as RecordState.Present).sourcePayload["client"] as JsonObject
        assertEquals("client-1", (client["dataId"] as JsonPrimitive).content)
        assertEquals("3", (client["version"] as JsonPrimitive).content)
    }

    @Test
    fun `keeps a decimal exactly as the source reported it`() {
        val envelope = mapper.map(sourceRecord(fields = mapOf("heart_rate" to number("72.4000001"))))

        val heartRate = (envelope.state as RecordState.Present).normalizedPayload["heartRate"] as JsonObject
        assertEquals("72.4000001", (heartRate["value"] as JsonPrimitive).content)
    }
}
