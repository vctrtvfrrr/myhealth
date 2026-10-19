package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.SnapshotBatch
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The profile snapshot, rendered from synthetic profiles. Every value is invented. */
class ProfileMapperTest {

    private val readAt = ZonedInstant("2026-08-10T18:20:00Z", "-03:00")

    @Test
    fun `renders every field of the profile as one observation without a window`() {
        val observation = ProfileMapper.map(syntheticProfile(), readAt)

        assertNull(observation.window)
        assertEquals(readAt, observation.observedAt)
        assertEquals(SourceScope.ALL_SOURCES, observation.sourceScope)
        assertEquals("samsung-health-user-profile/1", observation.mapperVersion)
        assertEquals(
            JsonObject(
                mapOf(
                    "date_of_birth" to JsonPrimitive("2000-01-01"),
                    "height" to JsonPrimitive("170.5".toBigDecimal()),
                    "weight" to JsonPrimitive("65.0".toBigDecimal()),
                    "gender" to JsonPrimitive("GENDER_UNKNOWN"),
                    "nickname" to JsonPrimitive("Synthetic"),
                ),
            ),
            observation.content,
        )
    }

    /** A field the Data Owner never filled in is left out, never invented. */
    @Test
    fun `leaves out a field the profile does not have`() {
        val observation = ProfileMapper.map(mapOf("height" to number("170.5")), readAt)

        assertEquals(JsonObject(mapOf("height" to JsonPrimitive("170.5".toBigDecimal()))), observation.content)
    }

    @Test
    fun `renders a profile with no field at all as empty content, not as an empty observation`() {
        assertEquals(JsonObject(emptyMap()), ProfileMapper.map(emptyMap(), readAt).content)
    }

    /** No profile is what the source currently says, so it is observed as empty. */
    @Test
    fun `renders a missing profile as an empty observation`() {
        assertNull(ProfileMapper.map(profile = null, readAt).content)
    }

    /**
     * The exact document a profile snapshot is sent as. The same literal is posted at
     * `AppSnapshotReadModelTest` in `ingestion-api`, against a real API and a real PostgreSQL.
     */
    @Test
    fun `pins the version 3 batch a profile snapshot is sent in`() {
        val batch = SnapshotBatch(snapshotType = "user_profile", items = listOf(ProfileMapper.map(syntheticProfile(), readAt)))

        assertEquals(GOLDEN, IngestionContract.json.encodeToString(SnapshotBatch.serializer(), batch))
    }

    private companion object {
        const val GOLDEN =
            """{"snapshotType":"user_profile","items":[{"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-user-profile/1","content":{"date_of_birth":"2000-01-01","height":170.5,"weight":65.0,"gender":"GENDER_UNKNOWN","nickname":"Synthetic"}}],"contractVersion":3}"""
    }
}
