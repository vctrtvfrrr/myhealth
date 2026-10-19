package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.Nutrient
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.floorsClimbed
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.nutrition
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.waterIntake
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of a meal, a water intake and a climb, on the terms heart rate set: the
 * latest observation wins by when it was observed, not by when it arrived, and every earlier one is kept.
 */
@Tag("integration")
class CurrentConsumptionTest : IngestionApiTest() {

    /** One of the three categories, whose record states [value] in the column [valueColumn] of [view]. */
    private class Kind(
        val recordType: String,
        val mapperVersion: String,
        val view: String,
        val valueColumn: String,
        val envelope: (uid: String, observedAt: String, value: String) -> JsonObject,
    )

    private val kinds = listOf(
        Kind(
            recordType = ConsumptionEnvelopes.NUTRITION_RECORD_TYPE,
            mapperVersion = ConsumptionEnvelopes.NUTRITION_MAPPER_VERSION,
            view = "current_nutrition",
            valueColumn = "calories_kcal",
        ) { uid, observedAt, value -> nutrition(samsungUid = uid, observedAt = observedAt, calories = Nutrient(value, "kcal")) },
        Kind(
            recordType = ConsumptionEnvelopes.WATER_INTAKE_RECORD_TYPE,
            mapperVersion = ConsumptionEnvelopes.WATER_INTAKE_MAPPER_VERSION,
            view = "current_water_intake",
            valueColumn = "amount_ml",
        ) { uid, observedAt, value -> waterIntake(samsungUid = uid, observedAt = observedAt, amount = value) },
        Kind(
            recordType = ConsumptionEnvelopes.FLOORS_CLIMBED_RECORD_TYPE,
            mapperVersion = ConsumptionEnvelopes.FLOORS_CLIMBED_MAPPER_VERSION,
            view = "current_floors_climbed",
            valueColumn = "floors",
        ) { uid, observedAt, value -> floorsClimbed(samsungUid = uid, observedAt = observedAt, floors = value) },
    )

    private val earlier = "2026-08-10T15:02:00Z"
    private val later = "2026-08-11T10:00:00Z"

    @Test
    fun `answers a record it already holds as already present`() = kinds.forEach { kind ->
        val uid = "uid-repeated-${kind.recordType}"
        val record = kind.envelope(uid, earlier, "30.5")
        send(kind, record)

        val response = send(kind, record)

        assertEquals(listOf("already_present"), statuses(response.body), kind.recordType)
        assertEquals(1, versionsOf(uid).size, kind.recordType)
        assertEquals(1, countOf(rowsOf(kind.view, uid)), kind.recordType)
    }

    @Test
    fun `moves the current record to a newer observation without erasing the earlier version`() = kinds.forEach { kind ->
        val uid = "uid-edited-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, "30.5"))

        send(kind, kind.envelope(uid, later, "32.5"))

        assertEquals("32.5", valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer record current when an older one is ingested afterwards`() = kinds.forEach { kind ->
        val uid = "uid-late-${kind.recordType}"
        send(kind, kind.envelope(uid, later, "32.5"))

        send(kind, kind.envelope(uid, earlier, "30.5"))

        assertEquals("32.5", valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `takes a removed record out of the read model, keeping what was observed`() = kinds.forEach { kind ->
        val uid = "uid-removed-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, "30.5"))

        send(kind, Envelopes.removal(samsungUid = uid, mapperVersion = kind.mapperVersion))

        assertEquals(0, countOf(rowsOf(kind.view, uid)), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `rebuilds the read model from the preserved envelopes, identically`() {
        kinds.forEach { kind ->
            val uid = "uid-rebuilt-${kind.recordType}"
            send(kind, kind.envelope(uid, earlier, "30.5"))
            send(kind, kind.envelope(uid, later, "32.5"))
        }
        val before = kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") }

        api.administer("projection", "rebuild")

        assertEquals(before, kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") })
        assertEquals(listOf("32.5", "32.5", "32.5"), before)
    }

    private fun send(kind: Kind, item: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = kind.recordType, items = listOf(item)), token)

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun valueOf(kind: Kind, samsungUid: String): String? = api.query { connection ->
        connection.prepareStatement("select ${kind.valueColumn}::text from read_model.${kind.view} where samsung_uid = ?").use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }.single()
}
