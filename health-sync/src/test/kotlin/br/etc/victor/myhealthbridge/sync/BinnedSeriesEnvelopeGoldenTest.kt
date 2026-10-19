package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact documents this application sends for the measurements that carry a binned series.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mappers emit is what the read model was proven to show.
 */
class BinnedSeriesEnvelopeGoldenTest {

    @Test
    fun `pins the document a blood oxygen measurement is sent as`() {
        val envelope = BloodOxygenMapper.map(
            bloodOxygenRecord(
                uid = "uid-golden-blood-oxygen",
                bins = listOf(
                    oxygenBin(end = "2026-08-10T03:05:00Z", saturation = "95.0"),
                    oxygenBin(start = "2026-08-10T03:05:00Z", saturation = "97.0", min = null, max = null),
                ),
            ),
        )

        assertEquals(GOLDEN_BLOOD_OXYGEN, encode(envelope))
    }

    @Test
    fun `pins the document a skin temperature measurement is sent as`() {
        val envelope = SkinTemperatureMapper.map(skinTemperatureRecord(uid = "uid-golden-skin-temperature"))

        assertEquals(GOLDEN_SKIN_TEMPERATURE, encode(envelope))
    }

    @Test
    fun `pins the document a blood glucose reading is sent as`() {
        val envelope = BloodGlucoseMapper.map(
            bloodGlucoseRecord(
                uid = "uid-golden-blood-glucose",
                sampleSourceType = "UNDEFINED",
                mealStatus = "AFTER_LUNCH",
                mealTime = "2026-08-10T10:00:00Z",
                insulinInjected = "4.0",
                medicationTaken = true,
                bins = listOf(glucoseBin(), glucoseBin(at = "2026-08-10T11:05:00Z", glucose = "5.9")),
            ),
        )

        assertEquals(GOLDEN_BLOOD_GLUCOSE, encode(envelope))
    }

    private fun encode(envelope: HealthRecordEnvelope): String =
        IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope)

    private companion object {
        const val GOLDEN_BLOOD_OXYGEN = """{"samsungUid":"uid-golden-blood-oxygen","observedAt":{"instant":"2026-08-10T03:15:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-oxygen/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"spo2":96.0,"min":93.0,"max":98.0,"binning_data":[{"oxygen_saturation":95.0,"min":93.0,"max":98.0,"start_time":"2026-08-10T03:00:00Z","end_time":"2026-08-10T03:05:00Z"},{"oxygen_saturation":97.0,"start_time":"2026-08-10T03:05:00Z","end_time":"2026-08-10T03:10:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodOxygen":{"saturation":{"value":96.0,"unit":"%"},"min":{"value":93.0,"unit":"%"},"max":{"value":98.0,"unit":"%"},"series":[{"start":"2026-08-10T03:00:00Z","end":"2026-08-10T03:05:00Z","saturationPercent":95.0,"minPercent":93.0,"maxPercent":98.0},{"start":"2026-08-10T03:05:00Z","end":"2026-08-10T03:10:00Z","saturationPercent":97.0}]}}}}"""

        const val GOLDEN_SKIN_TEMPERATURE = """{"samsungUid":"uid-golden-skin-temperature","observedAt":{"instant":"2026-08-10T03:15:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-skin-temperature/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"temperature":33.5,"min":33.1,"max":33.9,"binning_data":[{"skin_temperature":33.5,"min":33.1,"max":33.9,"start_time":"2026-08-10T03:00:00Z","end_time":"2026-08-10T03:10:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"skinTemperature":{"temperature":{"value":33.5,"unit":"Cel"},"min":{"value":33.1,"unit":"Cel"},"max":{"value":33.9,"unit":"Cel"},"series":[{"start":"2026-08-10T03:00:00Z","end":"2026-08-10T03:10:00Z","temperatureCelsius":33.5,"minCelsius":33.1,"maxCelsius":33.9}]}}}}"""

        const val GOLDEN_BLOOD_GLUCOSE = """{"samsungUid":"uid-golden-blood-glucose","observedAt":{"instant":"2026-08-10T11:05:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-glucose/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T11:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T11:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"glucose":5.4,"measurement_type":"PLASMA","sample_source_type":"UNDEFINED","meal_type":"AFTER_LUNCH","meal_time":"2026-08-10T10:00:00Z","insulin_injected":4.0,"medication":true,"binning_data":[{"glucose":5.4,"timestamp":"2026-08-10T11:00:00Z"},{"glucose":5.9,"timestamp":"2026-08-10T11:05:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodGlucose":{"glucose":{"value":5.4,"unit":"mmol/L"},"measurementType":"PLASMA","mealStatus":"AFTER_LUNCH","mealTime":"2026-08-10T10:00:00Z","insulinInjected":4.0,"medicationTaken":true,"series":[{"at":"2026-08-10T11:00:00Z","glucoseMmolPerLiter":5.4},{"at":"2026-08-10T11:05:00Z","glucoseMmolPerLiter":5.9}]}}}}"""
    }
}
