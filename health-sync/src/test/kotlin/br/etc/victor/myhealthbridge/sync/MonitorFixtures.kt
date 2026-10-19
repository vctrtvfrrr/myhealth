package br.etc.victor.myhealthbridge.sync

import java.time.Instant

/** A synthetic irregular heart rhythm notification, as the adapter hands one over. Every value is invented. */
fun irregularHeartRhythmRecord(
    uid: String = "uid-irregular-heart-rhythm-1",
    start: Instant = Instant.parse("2026-08-10T04:20:00Z"),
    end: Instant? = Instant.parse("2026-08-10T04:20:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T04:25:00Z"),
    status: String? = "DETECTED",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap { status?.let { put("status", SourceValue.Text(it)) } },
)

/** A synthetic sleep apnea assessment over one night, as the adapter hands one over. Every value is invented. */
fun sleepApneaRecord(
    uid: String = "uid-sleep-apnea-1",
    start: Instant = Instant.parse("2026-08-10T02:00:00Z"),
    end: Instant? = Instant.parse("2026-08-10T09:30:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T09:45:00Z"),
    result: String? = "NOT_DETECTED",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap { result?.let { put("result", SourceValue.Text(it)) } },
)

/**
 * A synthetic energy score, which Samsung Health keeps one of per local day, starting at its start and
 * with no end. Every value is invented.
 */
fun energyScoreRecord(
    uid: String = "uid-energy-score-1",
    start: Instant = Instant.parse("2026-08-10T03:00:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T10:00:00Z"),
    score: String? = "82.0",
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = null,
    updateTime = updateTime,
    fields = buildMap { score?.let { put("total_score", number(it)) } },
)
