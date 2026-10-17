package br.etc.victor.myhealthbridge.health

/**
 * The currently observed ability of Samsung Health to serve this application.
 *
 * It is derived from the outcome of real SDK operations and is never a persisted connection flag.
 * An outcome the adapter cannot recognize becomes [TemporarilyUnavailable], never [Unsupported].
 */
sealed interface SamsungHealthAvailability {

    /**
     * Which SDK condition this was, as a code this build defines, never the SDK's own failure text. It
     * is shown to the Data Owner because the same message covers several codes, and only the code says
     * which one a device produced.
     */
    val diagnosticCode: String?

    data object Ready : SamsungHealthAvailability {
        override val diagnosticCode: String? = null
    }

    data class ActionRequired(
        val remediation: Remediation,
        val resolution: AvailabilityResolution? = null,
        override val diagnosticCode: String? = null,
    ) : SamsungHealthAvailability

    data class TemporarilyUnavailable(
        override val diagnosticCode: String? = null,
    ) : SamsungHealthAvailability

    data class Unsupported(
        override val diagnosticCode: String? = null,
    ) : SamsungHealthAvailability
}

enum class Remediation {
    /** Samsung Health is absent, outdated, disabled, not initialized or not from an official source. */
    SAMSUNG_HEALTH_SETUP,

    /** Samsung Health refuses this application, which in developer mode means the mode is switched off. */
    APPLICATION_NOT_RECOGNIZED,
}

/** The official remediation the SDK offers for an [SamsungHealthAvailability.ActionRequired], when it offers one. */
fun interface AvailabilityResolution {
    suspend fun resolve()
}
