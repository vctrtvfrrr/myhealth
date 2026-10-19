package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of Samsung Health blood oxygen records.
 *
 * A measurement carries the series it was binned from, which has no identity of its own and travels
 * inside the record the way the stages travel inside a night. A measurement Samsung Health associates
 * with a sleep is still one of these, read by this category and never through the sleep. See ADR 0023.
 */
object BloodOxygenMapper : RecordMapper {

    override val version: String = "samsung-health-blood-oxygen/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        rangedMeasurement(
            record,
            payloadKey = "bloodOxygen",
            valueKey = "saturation",
            sourceField = "spo2",
            binField = "oxygen_saturation",
            unit = PERCENT,
            binUnitSuffix = "Percent",
        ),
    )

    private const val PERCENT = "%"
}

/**
 * The one mapper of Samsung Health skin temperature records, which the source only measures during
 * sleep. Like blood oxygen, it carries its binned series inside the record and is read by its own
 * category, never through the sleep it was measured in. See ADR 0023.
 */
object SkinTemperatureMapper : RecordMapper {

    override val version: String = "samsung-health-skin-temperature/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        rangedMeasurement(
            record,
            payloadKey = "skinTemperature",
            valueKey = "temperature",
            sourceField = "temperature",
            binField = "skin_temperature",
            unit = CELSIUS,
            binUnitSuffix = "Celsius",
        ),
    )

    private const val CELSIUS = "Cel"
}

/**
 * The one mapper of Samsung Health blood glucose records.
 *
 * The enums say how the reading was taken and how it relates to a meal. They are carried under the
 * constant the source names them with, except `UNDEFINED`, which is how the SDK says the source did
 * not state it and is therefore absent from the normalized payload.
 */
object BloodGlucoseMapper : RecordMapper {

    override val version: String = "samsung-health-blood-glucose/1"

    override val knownEnums: Map<String, Set<String>> = mapOf(
        MEASUREMENT_TYPE to setOf(UNDEFINED, "WHOLE_BLOOD", "PLASMA", "SERUM"),
        SAMPLE_SOURCE_TYPE to setOf(UNDEFINED, "VENOUS", "CAPILLARY"),
        MEAL_STATUS to setOf(
            UNDEFINED,
            "FASTING",
            "AFTER_MEAL",
            "BEFORE_BREAKFAST",
            "AFTER_BREAKFAST",
            "BEFORE_LUNCH",
            "AFTER_LUNCH",
            "BEFORE_DINNER",
            "AFTER_DINNER",
            "AFTER_BED_TIME",
            "AFTER_SNACK",
            "BEFORE_MEAL",
            "GENERAL",
            "BEFORE_SLEEP",
        ),
    )

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        val glucose = record.fields[GLUCOSE] as? SourceValue.Number ?: return@buildJsonObject
        put(
            "bloodGlucose",
            buildJsonObject {
                put("glucose", quantityOf(glucose, MILLIMOLES_PER_LITER))
                stated(record, MEASUREMENT_TYPE)?.let { put("measurementType", it) }
                stated(record, SAMPLE_SOURCE_TYPE)?.let { put("sampleSourceType", it) }
                stated(record, MEAL_STATUS)?.let { put("mealStatus", it) }
                (record.fields[MEAL_TIME] as? SourceValue.Text)?.let { put("mealTime", it.value) }
                // Samsung Health documents no unit for the insulin, so none is claimed for it here.
                (record.fields[INSULIN_INJECTED] as? SourceValue.Number)?.let { put("insulinInjected", JsonPrimitive(it.value)) }
                (record.fields[MEDICATION_TAKEN] as? SourceValue.Flag)?.let { put("medicationTaken", it.value) }
                series(record) { reading ->
                    val at = reading[TIMESTAMP] as? SourceValue.Text ?: return@series null
                    val level = reading[GLUCOSE] as? SourceValue.Number ?: return@series null
                    buildJsonObject {
                        put("at", at.value)
                        put("glucoseMmolPerLiter", JsonPrimitive(level.value))
                    }
                }
            },
        )
    }

    private fun stated(record: SourceRecord, field: String): String? =
        (record.fields[field] as? SourceValue.Text)?.value?.takeIf { it != UNDEFINED }

    private const val GLUCOSE = "glucose"
    private const val MEASUREMENT_TYPE = "measurement_type"
    private const val SAMPLE_SOURCE_TYPE = "sample_source_type"
    private const val MEAL_STATUS = "meal_type"
    private const val MEAL_TIME = "meal_time"
    private const val INSULIN_INJECTED = "insulin_injected"
    private const val MEDICATION_TAKEN = "medication"
    private const val TIMESTAMP = "timestamp"
    private const val UNDEFINED = "UNDEFINED"

    private const val MILLIMOLES_PER_LITER = "mmol/L"
}

/**
 * A measurement reported as a value with the range it moved in, binned over its period.
 *
 * An absent value is emitted as absent rather than substituted, so the API rejects the item and it
 * stays a mapping pendency instead of becoming an invented reading. Inside the series each bin states
 * its unit in the name of each key, the way a point of a route does: a series repeats its unit as
 * often as its values.
 */
private fun rangedMeasurement(
    record: SourceRecord,
    payloadKey: String,
    valueKey: String,
    sourceField: String,
    binField: String,
    unit: String,
    binUnitSuffix: String,
): JsonObject = buildJsonObject {
    val value = record.fields[sourceField] as? SourceValue.Number ?: return@buildJsonObject
    put(
        payloadKey,
        buildJsonObject {
            put(valueKey, quantityOf(value, unit))
            (record.fields[MIN] as? SourceValue.Number)?.let { put("min", quantityOf(it, unit)) }
            (record.fields[MAX] as? SourceValue.Number)?.let { put("max", quantityOf(it, unit)) }
            series(record) { bin ->
                val start = bin[START_TIME] as? SourceValue.Text ?: return@series null
                val end = bin[END_TIME] as? SourceValue.Text ?: return@series null
                val measured = bin[binField] as? SourceValue.Number ?: return@series null
                buildJsonObject {
                    put("start", start.value)
                    put("end", end.value)
                    put("$valueKey$binUnitSuffix", JsonPrimitive(measured.value))
                    (bin[MIN] as? SourceValue.Number)?.let { put("min$binUnitSuffix", JsonPrimitive(it.value)) }
                    (bin[MAX] as? SourceValue.Number)?.let { put("max$binUnitSuffix", JsonPrimitive(it.value)) }
                }
            }
        },
    )
}

/**
 * The binned series of the record, in the order the source reported it. A bin that does not say when
 * it was or what it measured is not one anyone could place, and is left out; a record binned over
 * nothing is normalized without a series rather than with an empty one.
 */
private fun JsonObjectBuilder.series(record: SourceRecord, bin: (Map<String, SourceValue>) -> JsonObject?) {
    val bins = (record.fields[BINNING_DATA] as? SourceValue.Series)?.entries.orEmpty().mapNotNull(bin)
    if (bins.isEmpty()) return
    put("series", JsonArray(bins))
}

private fun quantityOf(number: SourceValue.Number, unit: String): JsonObject = buildJsonObject {
    put("value", JsonPrimitive(number.value))
    put("unit", unit)
}

private const val BINNING_DATA = "binning_data"
private const val MIN = "min"
private const val MAX = "max"
private const val START_TIME = "start_time"
private const val END_TIME = "end_time"
