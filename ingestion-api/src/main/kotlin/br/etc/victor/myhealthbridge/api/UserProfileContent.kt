package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The content of a profile observation, field by field as Samsung Health names them. Every field is
 * optional, because the Data Owner may never have filled one in, but a field that is there has to be
 * what the read model casts it to.
 *
 * The date of birth stays text: Samsung Health does not document its format, so nothing here parses it.
 */
internal object UserProfileContent {

    private const val MAX_TEXT_LENGTH = 256

    /** The genders the adopted SDK defines; another one is a newer SDK, kept as a Mapping Pendency. */
    private val GENDERS = setOf("GENDER_UNKNOWN", "GENDER_MALE", "GENDER_FEMALE")

    /** In centimeters, far beyond any person, so it catches mapper bugs rather than tall people. */
    private val PLAUSIBLE_HEIGHT = BigDecimal.ZERO..BigDecimal(300)

    /** In kilograms, with the same intent. */
    private val PLAUSIBLE_WEIGHT = BigDecimal.ZERO..BigDecimal(700)

    fun validate(content: JsonObject): List<RejectionCode> {
        val valid = content["date_of_birth"].isTextOrAbsent() &&
            content["nickname"].isTextOrAbsent() &&
            content["gender"].let { it == null || it.text() in GENDERS } &&
            content["height"].isPositiveWithin(PLAUSIBLE_HEIGHT) &&
            content["weight"].isPositiveWithin(PLAUSIBLE_WEIGHT)
        return if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    private fun JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_TEXT_LENGTH }

    private fun JsonElement?.isTextOrAbsent(): Boolean = this == null || text() != null

    private fun JsonElement?.isPositiveWithin(range: ClosedRange<BigDecimal>): Boolean {
        if (this == null) return true
        val number = (this as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }
        return number != null && number > BigDecimal.ZERO && number in range
    }
}
