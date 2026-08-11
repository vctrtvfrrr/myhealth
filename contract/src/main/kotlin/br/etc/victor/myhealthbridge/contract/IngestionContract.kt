package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.json.Json

/**
 * Identifies the wire contract shared by the Android app and the ingestion API.
 *
 * The version belongs to the transport surface so that both sides can be released independently.
 * How the two sides negotiate this version is not decided yet.
 */
object IngestionContract {
    const val CURRENT_VERSION: Int = 1

    const val MEDIA_TYPE: String = "application/json"

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
