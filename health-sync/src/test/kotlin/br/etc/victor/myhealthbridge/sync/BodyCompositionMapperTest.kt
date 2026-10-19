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

class BodyCompositionMapperTest {

    private val mapper = BodyCompositionMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(bodyCompositionRecord(uid = "samsung-uid-9"))

        assertEquals("samsung-uid-9", envelope.samsungUid)
        assertEquals("samsung-health-body-composition/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record, keeping the instant in UTC beside the original offset`() {
        val envelope = mapper.map(bodyCompositionRecord())
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T07:02:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T07:00:00Z", period.start.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(bodyCompositionRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(bodyCompositionRecord()), mapper.map(bodyCompositionRecord()))
    }

    /** The units Samsung Health documents for each field, muscle mass as a percentage included. */
    @Test
    fun `normalizes every measure into the unit the source documents for it`() {
        val composition = compositionOf(mapper.map(bodyCompositionRecord()))

        assertEquals("70.5" to "kg", quantity(composition, "weight"))
        assertEquals("175.0" to "cm", quantity(composition, "height"))
        assertEquals("18.5" to "%", quantity(composition, "bodyFat"))
        assertEquals("42.0" to "%", quantity(composition, "skeletalMuscle"))
        assertEquals("45.5" to "%", quantity(composition, "muscleMass"))
        assertEquals("1650" to "kcal/d", quantity(composition, "basalMetabolicRate"))
        assertEquals("13.0" to "kg", quantity(composition, "bodyFatMass"))
        assertEquals("57.5" to "kg", quantity(composition, "fatFreeMass"))
        assertEquals("81.5" to "%", quantity(composition, "fatFree"))
        assertEquals("29.6" to "kg", quantity(composition, "skeletalMuscleMass"))
        assertEquals("42.1" to "L", quantity(composition, "totalBodyWater"))
    }

    /** Samsung Health computes it from height and weight and documents no unit, so none is claimed. */
    @Test
    fun `carries the body mass index as the source computed it`() {
        val composition = compositionOf(mapper.map(bodyCompositionRecord()))

        assertEquals(JsonPrimitive("23.0".toBigDecimal()), composition["bodyMassIndex"])
    }

    @Test
    fun `carries a weighing with nothing but the weight`() {
        val composition = compositionOf(
            mapper.map(
                bodyCompositionRecord(
                    height = null,
                    bodyFat = null,
                    skeletalMuscle = null,
                    muscleMass = null,
                    basalMetabolicRate = null,
                    bodyFatMass = null,
                    fatFreeMass = null,
                    fatFree = null,
                    skeletalMuscleMass = null,
                    totalBodyWater = null,
                    bodyMassIndex = null,
                ),
            ),
        )

        assertEquals(setOf("weight"), composition.keys)
    }

    /** The weight is the one field Samsung Health requires, so a record without it is not substituted. */
    @Test
    fun `leaves the normalized measurement out when the source did not report the weight`() {
        val envelope = mapper.map(bodyCompositionRecord(weight = null))

        assertNull((envelope.state as RecordState.Present).normalizedPayload["bodyComposition"])
    }

    @Test
    fun `preserves everything the source reported`() {
        val fields = (mapper.map(bodyCompositionRecord()).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals(12, fields.size)
        assertEquals("23.0", (fields["bmi"] as JsonPrimitive).content)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-body-composition-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-body-composition/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun compositionOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["bodyComposition"] as JsonObject

    private fun quantity(node: JsonObject, name: String): Pair<String, String> {
        val quantity = node[name] as JsonObject
        return (quantity["value"] as JsonPrimitive).content to (quantity["unit"] as JsonPrimitive).content
    }
}
