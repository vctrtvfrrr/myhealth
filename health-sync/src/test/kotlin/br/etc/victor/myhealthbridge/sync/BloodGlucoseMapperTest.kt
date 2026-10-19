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

class BloodGlucoseMapperTest {

    private val mapper = BloodGlucoseMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(bloodGlucoseRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-blood-glucose/1", envelope.mapperVersion)
    }

    @Test
    fun `reads a reading without an end as a period that starts and ends with it`() {
        val envelope = mapper.map(bloodGlucoseRecord(end = null))
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T11:05:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T11:00:00Z", period.start.instant)
        assertEquals("2026-08-10T11:00:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(bloodGlucoseRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(bloodGlucoseRecord()), mapper.map(bloodGlucoseRecord()))
    }

    @Test
    fun `normalizes the glucose level into millimoles per liter`() {
        val glucose = glucoseOf(mapper.map(bloodGlucoseRecord(glucose = "5.4")))["glucose"] as JsonObject

        assertEquals("5.4", text(glucose, "value"))
        assertEquals("mmol/L", text(glucose, "unit"))
    }

    @Test
    fun `carries how the reading was taken and how it relates to a meal`() {
        val glucose = glucoseOf(
            mapper.map(
                bloodGlucoseRecord(
                    measurementType = "WHOLE_BLOOD",
                    sampleSourceType = "VENOUS",
                    mealStatus = "AFTER_LUNCH",
                    mealTime = "2026-08-10T10:00:00Z",
                    insulinInjected = "4.0",
                    medicationTaken = true,
                ),
            ),
        )

        assertEquals("WHOLE_BLOOD", text(glucose, "measurementType"))
        assertEquals("VENOUS", text(glucose, "sampleSourceType"))
        assertEquals("AFTER_LUNCH", text(glucose, "mealStatus"))
        assertEquals("2026-08-10T10:00:00Z", text(glucose, "mealTime"))
        assertEquals("4.0", text(glucose, "insulinInjected"))
        assertEquals("true", text(glucose, "medicationTaken"))
    }

    /** `UNDEFINED` is how the SDK says the source did not state it, which the read model shows as absent. */
    @Test
    fun `leaves out what the source left undefined or did not report`() {
        val glucose = glucoseOf(
            mapper.map(bloodGlucoseRecord(measurementType = "UNDEFINED", sampleSourceType = "UNDEFINED", mealStatus = null)),
        )

        assertNull(glucose["measurementType"])
        assertNull(glucose["sampleSourceType"])
        assertNull(glucose["mealStatus"])
        assertNull(glucose["mealTime"])
        assertNull(glucose["insulinInjected"])
        assertNull(glucose["medicationTaken"])
        assertNull(glucose["series"])
    }

    @Test
    fun `leaves the normalized reading out when the source did not report a glucose level`() {
        val envelope = mapper.map(bloodGlucoseRecord(glucose = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["bloodGlucose"])
    }

    @Test
    fun `projects every reading of the series, in the order the source reported them`() {
        val record = bloodGlucoseRecord(
            bins = listOf(
                glucoseBin(at = "2026-08-10T11:00:00Z", glucose = "5.4"),
                glucoseBin(at = null),
                glucoseBin(glucose = null),
                glucoseBin(at = "2026-08-10T11:05:00Z", glucose = "5.9"),
            ),
        )

        val series = glucoseOf(mapper.map(record))["series"] as JsonArray
        assertEquals(
            listOf("2026-08-10T11:00:00Z" to "5.4", "2026-08-10T11:05:00Z" to "5.9"),
            series.map { text(it as JsonObject, "at") to text(it, "glucoseMmolPerLiter") },
        )
    }

    @Test
    fun `preserves everything the source reported, series and enum constants included`() {
        val record = bloodGlucoseRecord(medicationTaken = false, bins = listOf(glucoseBin()))
        val fields = (mapper.map(record).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("5.4", (fields["glucose"] as JsonPrimitive).content)
        assertEquals("FASTING", (fields["meal_type"] as JsonPrimitive).content)
        assertEquals("PLASMA", (fields["measurement_type"] as JsonPrimitive).content)
        assertEquals("false", (fields["medication"] as JsonPrimitive).content)
        val bin = (fields["binning_data"] as JsonArray)[0] as JsonObject
        assertEquals("2026-08-10T11:00:00Z", (bin["timestamp"] as JsonPrimitive).content)
    }

    @Test
    fun `knows every constant the pinned SDK names for the reading's enums`() {
        val record = bloodGlucoseRecord(measurementType = "SERUM", sampleSourceType = "UNDEFINED", mealStatus = "BEFORE_SLEEP")

        assertEquals(emptyList<String>(), mapper.unknownEnums(record))
    }

    @Test
    fun `reports a constant it does not know while still carrying it`() {
        val record = bloodGlucoseRecord(mealStatus = "DURING_MEAL")

        assertEquals(listOf("meal_type=DURING_MEAL"), mapper.unknownEnums(record))
        assertEquals("DURING_MEAL", text(glucoseOf(mapper.map(record)), "mealStatus"))
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-blood-glucose-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-blood-glucose/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun glucoseOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["bloodGlucose"] as JsonObject

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content
}
