package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of Samsung Health blood pressure readings.
 *
 * A reading without both pressures is emitted without a normalized reading rather than with an invented
 * one, so the API rejects it and it stays a mapping pendency. Samsung Health documents no unit for the
 * pulse rate, so none is claimed for it here.
 */
object BloodPressureMapper : RecordMapper {

    override val version: String = "samsung-health-blood-pressure/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        val systolic = record.number(SYSTOLIC) ?: return@buildJsonObject
        val diastolic = record.number(DIASTOLIC) ?: return@buildJsonObject
        put(
            "bloodPressure",
            buildJsonObject {
                put("systolic", quantityOf(systolic, MILLIMETERS_OF_MERCURY))
                put("diastolic", quantityOf(diastolic, MILLIMETERS_OF_MERCURY))
                record.number(MEAN)?.let { put("mean", quantityOf(it, MILLIMETERS_OF_MERCURY)) }
                record.number(PULSE_RATE)?.let { put("pulseRate", JsonPrimitive(it.value)) }
                (record.fields[MEDICATION_TAKEN] as? SourceValue.Flag)?.let { put("medicationTaken", it.value) }
            },
        )
    }

    private const val SYSTOLIC = "systolic"
    private const val DIASTOLIC = "diastolic"
    private const val MEAN = "mean"
    private const val PULSE_RATE = "pulse"
    private const val MEDICATION_TAKEN = "medication"

    private const val MILLIMETERS_OF_MERCURY = "mm[Hg]"
}

/**
 * The one mapper of Samsung Health body composition measurements.
 *
 * The weight is the one field Samsung Health requires, so it is the one a normalized measurement cannot
 * do without; every other field is carried only when the source reported it, in the unit Samsung Health
 * documents for it. The body mass index is computed by Samsung Health from height and weight and carries
 * no documented unit, so none is claimed for it.
 */
object BodyCompositionMapper : RecordMapper {

    override val version: String = "samsung-health-body-composition/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    private val measures: List<Triple<String, String, String>> = listOf(
        Triple("height", "height", CENTIMETERS),
        Triple("body_fat", "bodyFat", PERCENT),
        Triple("skeletal_muscle", "skeletalMuscle", PERCENT),
        Triple("muscle_mass", "muscleMass", PERCENT),
        Triple("basal_metabolic_rate", "basalMetabolicRate", KILOCALORIES_PER_DAY),
        Triple("body_fat_mass", "bodyFatMass", KILOGRAMS),
        Triple("fat_free_mass", "fatFreeMass", KILOGRAMS),
        Triple("fat_free", "fatFree", PERCENT),
        Triple("skeletal_muscle_mass", "skeletalMuscleMass", KILOGRAMS),
        Triple("total_body_water", "totalBodyWater", LITERS),
    )

    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        val weight = record.number(WEIGHT) ?: return@buildJsonObject
        put(
            "bodyComposition",
            buildJsonObject {
                put("weight", quantityOf(weight, KILOGRAMS))
                measures.forEach { (field, key, unit) -> record.number(field)?.let { put(key, quantityOf(it, unit)) } }
                record.number(BODY_MASS_INDEX)?.let { put("bodyMassIndex", JsonPrimitive(it.value)) }
            },
        )
    }

    private const val WEIGHT = "weight"
    private const val BODY_MASS_INDEX = "bmi"

    private const val KILOGRAMS = "kg"
    private const val CENTIMETERS = "cm"
    private const val PERCENT = "%"
    private const val KILOCALORIES_PER_DAY = "kcal/d"
    private const val LITERS = "L"
}

/** The one mapper of Samsung Health body temperature readings, which hold nothing but the temperature. */
object BodyTemperatureMapper : RecordMapper {

    override val version: String = "samsung-health-body-temperature/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        buildJsonObject {
            val temperature = record.number(TEMPERATURE) ?: return@buildJsonObject
            put("bodyTemperature", buildJsonObject { put("temperature", quantityOf(temperature, CELSIUS)) })
        },
    )

    private const val TEMPERATURE = "temperature"
    private const val CELSIUS = "Cel"
}

private fun SourceRecord.number(field: String): SourceValue.Number? = fields[field] as? SourceValue.Number

private fun quantityOf(number: SourceValue.Number, unit: String): JsonObject = buildJsonObject {
    put("value", JsonPrimitive(number.value))
    put("unit", unit)
}
