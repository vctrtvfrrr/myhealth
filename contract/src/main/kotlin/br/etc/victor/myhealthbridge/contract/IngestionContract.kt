package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.json.Json
import java.math.BigInteger

/**
 * Identifies the wire contract shared by the Android app and the ingestion API.
 *
 * The version belongs to the transport surface so that both sides can be released independently. The
 * Supported Contract Range lives here, and not in the API's configuration, because the minimum
 * accepted version is part of the contract: moving it without a release is exactly the silent change
 * this negotiation exists to prevent.
 */
object IngestionContract {
    const val CURRENT_VERSION: Int = 1

    /**
     * The bottom of the Supported Contract Range.
     *
     * It equals [CURRENT_VERSION] because only one version has ever existed, and it is a separate
     * constant because the two mean different things: one is what this build speaks, the other is the
     * oldest it still accepts. See ADR 0007.
     */
    const val MINIMUM_VERSION: Int = 1

    /**
     * What a client should be sending, which is advice rather than a bound.
     *
     * It sits inside the Supported Contract Range: a client below it is still accepted, and is only
     * being told that it has fallen behind.
     */
    const val RECOMMENDED_VERSION: Int = 1

    const val MEDIA_TYPE: String = "application/json"

    const val MINIMUM_HEADER: String = "Ingestion-Contract-Minimum"

    const val MAXIMUM_HEADER: String = "Ingestion-Contract-Maximum"

    const val RECOMMENDED_HEADER: String = "Ingestion-Contract-Recommended"

    /**
     * Where a declared version falls relative to the Supported Contract Range, or null when it is
     * inside it.
     *
     * The two sides of the range are distinct codes because the remediation is opposite: below it the
     * application has to be updated, above it this API has to be. A single code would force the client
     * to compare numbers to learn what to do, which is the work a stable code exists to spare it.
     *
     * It takes a [BigInteger] because JSON integers have no width limit: a version too large to hold in
     * an `Int` is still a well formed integer above the range, and answering it as a malformed document
     * would send the client to fix the wrong thing.
     */
    fun incompatibilityOf(declaredVersion: BigInteger): BatchErrorCode? = when {
        declaredVersion < MINIMUM_VERSION.toBigInteger() -> BatchErrorCode.CONTRACT_VERSION_TOO_OLD
        declaredVersion > CURRENT_VERSION.toBigInteger() -> BatchErrorCode.CONTRACT_VERSION_TOO_NEW
        else -> null
    }

    /**
     * Unknown properties are ignored so that a newer client may add fields to an envelope without
     * a coordinated release, while every known invariant stays enforced.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
    }
}
