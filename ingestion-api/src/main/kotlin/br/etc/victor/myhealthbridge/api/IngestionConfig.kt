package br.etc.victor.myhealthbridge.api

/**
 * The limits that keep a personal instance predictable under an oversized or slow request.
 *
 * A value outside its range aborts startup instead of quietly clamping, because an operator who
 * mistyped a limit needs to know before the API starts serving under it.
 */
class IngestionConfig(
    val maxItems: Int,
    val maxBytes: Int,
    val timeoutSeconds: Int,
    val poolMaxSize: Int,
    val poolAcquireTimeoutMs: Long,
) {
    companion object {
        const val DEFAULT_MAX_ITEMS = 500
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val DEFAULT_POOL_MAX_SIZE = 5
        const val DEFAULT_POOL_ACQUIRE_TIMEOUT_MS = 5_000L

        fun fromEnvironment(environment: (String) -> String? = System::getenv): IngestionConfig =
            IngestionConfig(
                maxItems = environment.number("INGESTION_MAX_ITEMS", DEFAULT_MAX_ITEMS, 1..10_000).toInt(),
                maxBytes = environment.number("INGESTION_MAX_BYTES", DEFAULT_MAX_BYTES, 1_024..64 * 1024 * 1024).toInt(),
                timeoutSeconds = environment.number("INGESTION_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS, 1..600).toInt(),
                poolMaxSize = environment.number("DATABASE_POOL_MAX_SIZE", DEFAULT_POOL_MAX_SIZE, 1..50).toInt(),
                poolAcquireTimeoutMs = environment.number(
                    "DATABASE_POOL_ACQUIRE_TIMEOUT_MS",
                    DEFAULT_POOL_ACQUIRE_TIMEOUT_MS,
                    250..60_000,
                ),
            )

        private fun ((String) -> String?).number(variable: String, default: Number, allowed: IntRange): Long {
            val configured = this(variable) ?: return default.toLong()
            val value = configured.toLongOrNull()
                ?: error("Environment variable $variable is not a number")
            require(value in allowed.first..allowed.last) {
                "Environment variable $variable must be between ${allowed.first} and ${allowed.last}"
            }
            return value
        }
    }
}
