package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of Samsung Health sleep records.
 *
 * A night is reported as a record whose sessions carry the stages it went through, which exist
 * nowhere but inside it: a stage has no identity of its own, so it travels in the envelope of the
 * sleep that holds it, the way the route travels inside an exercise.
 *
 * What Samsung Health associates with a night but identifies on its own — the blood oxygen and skin
 * temperature measured while asleep — is not part of this record. Those are Health Records of their
 * own categories, read and delivered by them.
 *
 * No field is declared as an enum, for the reason the exercise mapper declares none: the stage is
 * carried, never interpreted, so a constant this build does not know is nothing a maintainer has to
 * teach it.
 */
object SleepMapper : RecordMapper {

    override val version: String = "samsung-health-sleep/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    /**
     * Nothing about a night is required beyond the period every envelope states: a duration, a score or
     * a session the source did not report is absent rather than invented.
     */
    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        put(
            "sleep",
            buildJsonObject {
                (record.fields[DURATION] as? SourceValue.Number)?.let { duration ->
                    put(
                        "duration",
                        buildJsonObject {
                            put("value", JsonPrimitive(duration.value))
                            put("unit", SECONDS)
                        },
                    )
                }
                (record.fields[SCORE] as? SourceValue.Number)?.let { put("score", JsonPrimitive(it.value)) }
                sessions(record)
            },
        )
    }

    /**
     * The sessions of the night, each with its stages, in the order the source reported them.
     *
     * Like a point of a route, a session and a stage state their units in the name of each key. One
     * that does not say when it began and ended is not a stretch of sleep anyone could place, and is
     * left out; so is a stage that does not say which stage it was.
     */
    private fun JsonObjectBuilder.sessions(record: SourceRecord) {
        val sessions = (record.fields[SESSIONS] as? SourceValue.Series)?.entries.orEmpty().mapNotNull(::session)
        if (sessions.isEmpty()) return
        put("sessions", JsonArray(sessions))
    }

    private fun session(session: Map<String, SourceValue>): JsonObject? {
        val start = session[START_TIME] as? SourceValue.Text ?: return null
        val end = session[END_TIME] as? SourceValue.Text ?: return null
        val stages = (session[STAGES] as? SourceValue.Series)?.entries.orEmpty().mapNotNull(::stage)

        return buildJsonObject {
            put("start", start.value)
            put("end", end.value)
            (session[DURATION_OF_SESSION] as? SourceValue.Number)?.let { put("durationSeconds", JsonPrimitive(it.value)) }
            if (stages.isNotEmpty()) put("stages", JsonArray(stages))
        }
    }

    private fun stage(stage: Map<String, SourceValue>): JsonObject? {
        val start = stage[START_TIME] as? SourceValue.Text ?: return null
        val end = stage[END_TIME] as? SourceValue.Text ?: return null
        val name = stage[STAGE] as? SourceValue.Text ?: return null

        return buildJsonObject {
            put("start", start.value)
            put("end", end.value)
            put("stage", name.value)
        }
    }

    private const val DURATION = "sleep_duration"
    private const val SCORE = "sleep_score"
    private const val SESSIONS = "sessions"
    private const val START_TIME = "start_time"
    private const val END_TIME = "end_time"
    private const val DURATION_OF_SESSION = "duration"
    private const val STAGES = "stages"
    private const val STAGE = "stage"

    private const val SECONDS = "s"
}
