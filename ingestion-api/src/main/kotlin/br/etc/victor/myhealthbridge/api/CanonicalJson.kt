package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Renders a JSON document in the single form that two semantically equal observations must share.
 *
 * Object keys are ordered, array order is preserved, numbers are reduced to one decimal form, and an
 * absent property stays distinguishable from an explicit null. The result is what gets hashed, so
 * this rendering is the actual definition of "the same Observed Record Version".
 */
object CanonicalJson {

    /**
     * Returns null for a document this rendering cannot express, which is a number whose exponent is
     * outside what a decimal can carry. JSON allows `1e9999999999`; a decimal cannot hold it, and a
     * digest cannot be computed over a value nobody can represent.
     */
    fun renderOrNull(element: JsonElement): String? =
        runCatching { StringBuilder().apply { write(element) }.toString() }.getOrNull()

    fun digest(canonical: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            JsonNull -> append("null")
            is JsonPrimitive -> writePrimitive(element)
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }

            is JsonObject -> {
                append('{')
                element.entries.sortedWith(compareBy(UTF_8_ORDER) { it.key })
                    .forEachIndexed { index, (key, value) ->
                        if (index > 0) append(',')
                        append(JsonPrimitive(key).toString())
                        append(':')
                        write(value)
                    }
                append('}')
            }
        }
    }

    private fun StringBuilder.writePrimitive(primitive: JsonPrimitive) {
        when {
            primitive.isString -> append(primitive.toString())
            primitive.content == "true" || primitive.content == "false" -> append(primitive.content)
            else -> append(BigDecimal(primitive.content).stripTrailingZeros().toString())
        }
    }

    /**
     * Code point order, expressed as UTF-8 byte order so that a key outside the basic plane sorts the
     * same way in any language that reimplements this digest.
     */
    private val UTF_8_ORDER = Comparator<String> { left, right ->
        val first = left.toByteArray(Charsets.UTF_8)
        val second = right.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(first.size, second.size)) {
            val difference = (first[index].toInt() and 0xFF) - (second[index].toInt() and 0xFF)
            if (difference != 0) return@Comparator difference
        }
        first.size - second.size
    }
}
