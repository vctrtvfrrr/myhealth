package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact documents this application sends for a meal, a glass of water and a climb.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mappers emit is what the read model was proven to show.
 */
class ConsumptionEnvelopeGoldenTest {

    @Test
    fun `pins the document a meal is sent as`() {
        val envelope = NutritionMapper.map(
            nutritionRecord(
                uid = "uid-golden-nutrition",
                nutrients = mapOf("total_fat" to "21.0", "protein" to "32.5", "sodium" to "780.0", "vitamin_a" to "120.0"),
            ),
        )

        assertEquals(GOLDEN_NUTRITION, encode(envelope))
    }

    @Test
    fun `pins the document a water intake is sent as`() {
        val envelope = WaterIntakeMapper.map(waterIntakeRecord(uid = "uid-golden-water-intake"))

        assertEquals(GOLDEN_WATER_INTAKE, encode(envelope))
    }

    @Test
    fun `pins the document a climb is sent as`() {
        val envelope = FloorsClimbedMapper.map(floorsClimbedRecord(uid = "uid-golden-floors-climbed"))

        assertEquals(GOLDEN_FLOORS_CLIMBED, encode(envelope))
    }

    private fun encode(envelope: HealthRecordEnvelope): String =
        IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope)

    private companion object {
        const val GOLDEN_NUTRITION = """{"samsungUid":"uid-golden-nutrition","observedAt":{"instant":"2026-08-10T15:02:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-nutrition/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T15:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T15:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"meal_type":"LUNCH","title":"Synthetic lunch","calories":640.0,"total_fat":21.0,"protein":32.5,"sodium":780.0,"vitamin_a":120.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"nutrition":{"mealType":"LUNCH","title":"Synthetic lunch","calories":{"value":640.0,"unit":"kcal"},"totalFat":{"value":21.0,"unit":"g"},"protein":{"value":32.5,"unit":"g"},"sodium":{"value":780.0,"unit":"mg"},"vitaminA":{"value":120.0,"unit":"ug"}}}}}"""

        const val GOLDEN_WATER_INTAKE = """{"samsungUid":"uid-golden-water-intake","observedAt":{"instant":"2026-08-10T12:31:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-water-intake/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T12:30:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T12:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"amount":250.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"waterIntake":{"amount":{"value":250.0,"unit":"mL"}}}}}"""

        const val GOLDEN_FLOORS_CLIMBED = """{"samsungUid":"uid-golden-floors-climbed","observedAt":{"instant":"2026-08-10T09:11:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-floors-climbed/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"floor":3.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"floorsClimbed":{"floors":3.0}}}}"""
    }
}
