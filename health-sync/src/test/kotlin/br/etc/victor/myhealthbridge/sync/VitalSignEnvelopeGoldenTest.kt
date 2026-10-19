package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact documents this application sends for blood pressure, body composition and body temperature.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mappers emit is what the read model was proven to show.
 */
class VitalSignEnvelopeGoldenTest {

    @Test
    fun `pins the document a blood pressure reading is sent as`() {
        val envelope = BloodPressureMapper.map(bloodPressureRecord(uid = "uid-golden-blood-pressure", medicationTaken = true))

        assertEquals(GOLDEN_BLOOD_PRESSURE, encode(envelope))
    }

    @Test
    fun `pins the document a body composition measurement is sent as`() {
        val envelope = BodyCompositionMapper.map(bodyCompositionRecord(uid = "uid-golden-body-composition"))

        assertEquals(GOLDEN_BODY_COMPOSITION, encode(envelope))
    }

    @Test
    fun `pins the document a body temperature reading is sent as`() {
        val envelope = BodyTemperatureMapper.map(bodyTemperatureRecord(uid = "uid-golden-body-temperature"))

        assertEquals(GOLDEN_BODY_TEMPERATURE, encode(envelope))
    }

    private fun encode(envelope: HealthRecordEnvelope): String =
        IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope)

    private companion object {
        const val GOLDEN_BLOOD_PRESSURE = """{"samsungUid":"uid-golden-blood-pressure","observedAt":{"instant":"2026-08-10T10:01:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-pressure/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"systolic":118.0,"diastolic":76.0,"mean":90.0,"pulse":64,"medication":true},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodPressure":{"systolic":{"value":118.0,"unit":"mm[Hg]"},"diastolic":{"value":76.0,"unit":"mm[Hg]"},"mean":{"value":90.0,"unit":"mm[Hg]"},"pulseRate":64,"medicationTaken":true}}}}"""

        const val GOLDEN_BODY_COMPOSITION = """{"samsungUid":"uid-golden-body-composition","observedAt":{"instant":"2026-08-10T07:02:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-body-composition/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T07:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T07:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"weight":70.5,"height":175.0,"body_fat":18.5,"skeletal_muscle":42.0,"muscle_mass":45.5,"basal_metabolic_rate":1650,"body_fat_mass":13.0,"fat_free_mass":57.5,"fat_free":81.5,"skeletal_muscle_mass":29.6,"total_body_water":42.1,"bmi":23.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bodyComposition":{"weight":{"value":70.5,"unit":"kg"},"height":{"value":175.0,"unit":"cm"},"bodyFat":{"value":18.5,"unit":"%"},"skeletalMuscle":{"value":42.0,"unit":"%"},"muscleMass":{"value":45.5,"unit":"%"},"basalMetabolicRate":{"value":1650,"unit":"kcal/d"},"bodyFatMass":{"value":13.0,"unit":"kg"},"fatFreeMass":{"value":57.5,"unit":"kg"},"fatFree":{"value":81.5,"unit":"%"},"skeletalMuscleMass":{"value":29.6,"unit":"kg"},"totalBodyWater":{"value":42.1,"unit":"L"},"bodyMassIndex":23.0}}}}"""

        const val GOLDEN_BODY_TEMPERATURE = """{"samsungUid":"uid-golden-body-temperature","observedAt":{"instant":"2026-08-10T08:00:30Z","offset":"-03:00"},"mapperVersion":"samsung-health-body-temperature/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T08:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T08:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"temperature":36.6},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bodyTemperature":{"temperature":{"value":36.6,"unit":"Cel"}}}}}"""
    }
}
