package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime

/**
 * The inventory a batch declared, as the store evaluates it.
 *
 * An inventory that cannot be read is still answered, as [Invalid], rather than refusing the batch it
 * arrived in: the observations beside it are sound, and the only thing lost is a conclusion that no
 * malformed declaration could support anyway.
 */
sealed interface DeclaredInventory {

    val recordType: String

    data class Valid(
        override val recordType: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val readAt: Instant,
        val samsungUids: Set<String>,
    ) : DeclaredInventory

    data class Invalid(override val recordType: String) : DeclaredInventory

    companion object {

        fun read(recordType: String, node: JsonElement): DeclaredInventory {
            val inventory = node as? JsonObject ?: return Invalid(recordType)
            val interval = inventory["interval"] as? JsonObject ?: return Invalid(recordType)
            val start = interval.text("start")?.let(::parseLocal) ?: return Invalid(recordType)
            val end = interval.text("end")?.let(::parseLocal) ?: return Invalid(recordType)
            val readAt = inventory.text("readAt")?.let(::parseUtc) ?: return Invalid(recordType)
            val uids = (inventory["samsungUids"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.takeIf { uid -> uid.isString }?.content }
                ?: return Invalid(recordType)

            if (!start.isBefore(end)) return Invalid(recordType)
            if (uids.any { it.isNullOrEmpty() || it.length > MAX_UID_LENGTH }) return Invalid(recordType)
            return Valid(recordType, start, end, readAt, uids.filterNotNull().toSet())
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun parseLocal(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text) }.getOrNull()

        private fun parseUtc(text: String): Instant? =
            if (!text.endsWith("Z")) null else runCatching { Instant.parse(text) }.getOrNull()

        private const val MAX_UID_LENGTH = 256
    }
}

/**
 * The observation of a removal concluded from a declared absence, rendered from the version it ends.
 *
 * It is dated like the version it supersedes rather than by when the absence was seen. It wins over
 * that version because it arrived later, and loses to any edit the source made afterwards: a removal
 * concluded wrongly, because the API had not yet learned that a record moved out of the interval, is
 * undone by the observation of that move instead of outliving it. Dating it by the inventory would
 * also make it render differently on every inventory, which is a new version of the same absence.
 */
internal fun concludedRemovalOf(current: JsonObject): JsonObject = buildJsonObject {
    put("samsungUid", current.getValue("samsungUid"))
    put("observedAt", current.getValue("observedAt"))
    put("mapperVersion", current.getValue("mapperVersion"))
    putJsonObject("sourceProvenance") {
        putJsonObject("sourceApp") { put("kind", "unknown") }
        putJsonObject("sourceDevice") { put("kind", "unknown") }
    }
    putJsonObject("state") {
        put("kind", "removed")
        (current["state"] as? JsonObject)?.get("period")?.let { put("period", it) }
    }
    put("concludedBy", "declared_absence")
}
