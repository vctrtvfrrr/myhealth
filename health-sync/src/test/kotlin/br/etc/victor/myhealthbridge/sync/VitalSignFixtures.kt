package br.etc.victor.myhealthbridge.sync

import java.time.Instant

/** A synthetic blood pressure reading, as the adapter hands one over. Every value is invented. */
fun bloodPressureRecord(
    uid: String = "uid-blood-pressure-1",
    start: Instant = Instant.parse("2026-08-10T10:00:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T10:01:00Z"),
    systolic: String? = "118.0",
    diastolic: String? = "76.0",
    mean: String? = "90.0",
    pulseRate: String? = "64",
    medicationTaken: Boolean? = false,
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    updateTime = updateTime,
    fields = buildMap {
        systolic?.let { put("systolic", number(it)) }
        diastolic?.let { put("diastolic", number(it)) }
        mean?.let { put("mean", number(it)) }
        pulseRate?.let { put("pulse", number(it)) }
        medicationTaken?.let { put("medication", SourceValue.Flag(it)) }
    },
)

/** A synthetic body composition measurement with every field the SDK exposes. Every value is invented. */
fun bodyCompositionRecord(
    uid: String = "uid-body-composition-1",
    start: Instant = Instant.parse("2026-08-10T07:00:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T07:02:00Z"),
    weight: String? = "70.5",
    height: String? = "175.0",
    bodyFat: String? = "18.5",
    skeletalMuscle: String? = "42.0",
    muscleMass: String? = "45.5",
    basalMetabolicRate: String? = "1650",
    bodyFatMass: String? = "13.0",
    fatFreeMass: String? = "57.5",
    fatFree: String? = "81.5",
    skeletalMuscleMass: String? = "29.6",
    totalBodyWater: String? = "42.1",
    bodyMassIndex: String? = "23.0",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    updateTime = updateTime,
    fields = buildMap {
        weight?.let { put("weight", number(it)) }
        height?.let { put("height", number(it)) }
        bodyFat?.let { put("body_fat", number(it)) }
        skeletalMuscle?.let { put("skeletal_muscle", number(it)) }
        muscleMass?.let { put("muscle_mass", number(it)) }
        basalMetabolicRate?.let { put("basal_metabolic_rate", number(it)) }
        bodyFatMass?.let { put("body_fat_mass", number(it)) }
        fatFreeMass?.let { put("fat_free_mass", number(it)) }
        fatFree?.let { put("fat_free", number(it)) }
        skeletalMuscleMass?.let { put("skeletal_muscle_mass", number(it)) }
        totalBodyWater?.let { put("total_body_water", number(it)) }
        bodyMassIndex?.let { put("bmi", number(it)) }
    },
)

/** A synthetic body temperature reading, as the adapter hands one over. Every value is invented. */
fun bodyTemperatureRecord(
    uid: String = "uid-body-temperature-1",
    start: Instant = Instant.parse("2026-08-10T08:00:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T08:00:30Z"),
    temperature: String? = "36.6",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    updateTime = updateTime,
    fields = buildMap { temperature?.let { put("temperature", number(it)) } },
)
