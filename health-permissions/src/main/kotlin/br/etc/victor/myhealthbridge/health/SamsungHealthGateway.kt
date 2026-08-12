package br.etc.victor.myhealthbridge.health

/**
 * The replaceable boundary in front of Samsung Health. Samsung SDK types stay behind it.
 *
 * Read access only: the gateway exposes no write operation and never builds a WRITE permission.
 */
interface SamsungHealthGateway {

    /**
     * The currently granted read set. Answering it at all is what makes Samsung Health
     * [SamsungHealthAvailability.Ready], so availability is never asked for separately.
     */
    suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>>

    /**
     * Runs the consent flow for the READ permission of each category.
     *
     * A cancelled flow and a partial grant are both completed requests: they answer [SamsungHealthOutcome.Observed],
     * because the application must not invent a cause Samsung Health did not report.
     */
    suspend fun requestReadPermissions(categories: Set<HealthCategory>): SamsungHealthOutcome<Unit>
}

sealed interface SamsungHealthOutcome<out T> {

    data class Observed<out T>(val value: T) : SamsungHealthOutcome<T>

    /** Carries why Samsung Health could not serve the operation; the availability is never [SamsungHealthAvailability.Ready]. */
    data class Failed(val availability: SamsungHealthAvailability) : SamsungHealthOutcome<Nothing>
}
