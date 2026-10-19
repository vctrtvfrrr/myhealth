package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The profile as the ingestion keeps it: the one Observed Snapshot without a Snapshot Window, re-read on
 * every run and stored only when it changes, so that its typed view is the current profile together
 * with the history of its changes. See ADR 0025.
 */
@Tag("integration")
class UserProfileSnapshotTest : IngestionApiTest() {

    /**
     * One story, because there is one profile: every test of this class shares the API and its database,
     * so a second test storing a profile would be a change of this one.
     */
    @Test
    fun `keeps the current profile and the history of its changes, re-reads leaving no trace`() {
        val first = sendProfile(Snapshots.profile(observedAt = "2026-08-10T12:00:00Z"))
        val reread = sendProfile(Snapshots.profile(observedAt = "2026-08-10T13:00:00Z"))

        assertEquals(200, first.status, first.body)
        assertEquals(listOf("accepted"), statuses(first.body))
        assertEquals(listOf("already_present"), statuses(reread.body))
        assertEquals(
            listOf("2026-08-10 12:00:00|t|f|2000-01-01|170.5|65|GENDER_UNKNOWN|Synthetic|all_sources"),
            profileRows(),
        )

        sendProfile(Snapshots.profile(observedAt = "2026-08-11T12:00:00Z", content = Snapshots.profileContent(weight = 64.0)))
        sendProfile(
            Snapshots.profile(
                observedAt = "2026-08-12T12:00:00Z",
                content = Snapshots.profileContent(dateOfBirth = null, weight = null, nickname = null),
            ),
        )
        sendProfile(Snapshots.profile(observedAt = "2026-08-13T12:00:00Z", content = null))

        assertEquals(
            listOf(
                "2026-08-10 12:00:00|f|f|2000-01-01|170.5|65|GENDER_UNKNOWN|Synthetic|all_sources",
                "2026-08-11 12:00:00|f|f|2000-01-01|170.5|64|GENDER_UNKNOWN|Synthetic|all_sources",
                "2026-08-12 12:00:00|f|f|null|170.5|null|GENDER_UNKNOWN|null|all_sources",
                "2026-08-13 12:00:00|t|t|null|null|null|null|null|all_sources",
            ),
            profileRows(),
        )
    }

    @Test
    fun `rejects, storing nothing, a profile with a window, an unknown mapper or a field it cannot be`() {
        val window = buildJsonObject {
            put("start", "2026-08-10T00:00:00")
            put("end", "2026-08-11T00:00:00")
        }
        val before = profileRows()

        val response = sendProfile(
            Snapshots.profile(window = window),
            Snapshots.profile(mapperVersion = "samsung-health-user-profile/99"),
            Snapshots.profile(content = Snapshots.profileContent(height = -1.0)),
            Snapshots.profile(content = Snapshots.profileContent(weight = 0.0)),
            Snapshots.profile(content = Snapshots.profileContent(gender = "GENDER_OTHER")),
            Snapshots.profile(content = JsonObject(Snapshots.profileContent() + ("height" to JsonPrimitive("tall")))),
            Snapshots.profile(content = JsonObject(Snapshots.profileContent() + ("nickname" to JsonPrimitive(7)))),
            Snapshots.profile(content = JsonObject(Snapshots.profileContent() + ("date_of_birth" to JsonPrimitive(2000)))),
        )

        assertEquals(200, response.status, response.body)
        assertEquals(
            listOf(
                listOf("invalid_identity"),
                listOf("unsupported_mapper"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
            ),
            codes(response.body),
        )
        assertEquals(before, profileRows())
    }

    private fun sendProfile(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Snapshots.batch(snapshotType = Snapshots.USER_PROFILE, items = items.toList()), token)

    private fun profileRows(): List<String> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                select (observed_at at time zone 'UTC')::text, current, empty, date_of_birth, height_cm, weight_kg,
                    gender, nickname, source_scope
                from read_model.user_profile
                order by observed_at
                """.trimIndent(),
            ).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) ?: "null" })
                }
            }
        }
    }
}
