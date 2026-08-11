package br.etc.victor.myhealthbridge.contract

/**
 * Identifies the wire contract shared by the Android app and the ingestion API.
 *
 * The version belongs to the transport surface so that both sides can be released independently.
 * How the two sides negotiate this version is not decided yet.
 */
object IngestionContract {
    const val CURRENT_VERSION: String = "1"
}
