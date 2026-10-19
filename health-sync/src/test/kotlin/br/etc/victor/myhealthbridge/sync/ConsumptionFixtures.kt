package br.etc.victor.myhealthbridge.sync

import java.time.Instant

/** A synthetic glass of water, as the adapter hands one over. Every value is invented. */
fun waterIntakeRecord(
    uid: String = "uid-water-intake-1",
    start: Instant = Instant.parse("2026-08-10T12:30:00Z"),
    end: Instant? = start,
    updateTime: Instant? = Instant.parse("2026-08-10T12:31:00Z"),
    amount: String? = "250.0",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap { amount?.let { put("amount", number(it)) } },
)

/** A synthetic climb, as the adapter hands one over. Every value is invented. */
fun floorsClimbedRecord(
    uid: String = "uid-floors-climbed-1",
    start: Instant = Instant.parse("2026-08-10T09:00:00Z"),
    end: Instant? = Instant.parse("2026-08-10T09:10:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T09:11:00Z"),
    floors: String? = "3.0",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap { floors?.let { put("floor", number(it)) } },
)

/** A synthetic meal, as the adapter hands one over. Every value is invented. */
fun nutritionRecord(
    uid: String = "uid-nutrition-1",
    start: Instant = Instant.parse("2026-08-10T15:00:00Z"),
    end: Instant? = start,
    updateTime: Instant? = Instant.parse("2026-08-10T15:02:00Z"),
    mealType: String? = "LUNCH",
    title: String? = "Synthetic lunch",
    calories: String? = "640.0",
    nutrients: Map<String, String> = mapOf("protein" to "32.5", "sodium" to "780.0", "vitamin_a" to "120.0"),
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap {
        mealType?.let { put("meal_type", SourceValue.Text(it)) }
        title?.let { put("title", SourceValue.Text(it)) }
        calories?.let { put("calories", number(it)) }
        nutrients.forEach { (name, value) -> put(name, number(value)) }
    },
)
