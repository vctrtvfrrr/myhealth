package br.etc.victor.myhealthbridge.health

import java.time.Instant

enum class PermissionState {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
    REVOKED,
}

/**
 * What the application knows about one Health Category after the last successful check.
 *
 * Samsung Health only reports the currently granted set, so [PermissionState.DENIED] and
 * [PermissionState.REVOKED] are local inferences drawn from [requestObserved] and [grantObserved].
 */
data class PermissionRecord(
    val category: HealthCategory,
    val requestObserved: Boolean = false,
    val grantObserved: Boolean = false,
    val granted: Boolean = false,
) {
    val state: PermissionState
        get() = when {
            granted -> PermissionState.GRANTED
            grantObserved -> PermissionState.REVOKED
            requestObserved -> PermissionState.DENIED
            else -> PermissionState.NOT_REQUESTED
        }
}

/** One atomic check: every cataloged category as seen at [observedAt]. */
data class PermissionObservation(
    val observedAt: Instant,
    val records: Map<HealthCategory, PermissionRecord>,
) {
    fun stateOf(category: HealthCategory): PermissionState =
        records[category]?.state ?: PermissionState.NOT_REQUESTED

    val states: Map<HealthCategory, PermissionState>
        get() = HealthCategory.entries.associateWith(::stateOf)

    companion object {

        fun from(
            previous: PermissionObservation?,
            granted: Set<HealthCategory>,
            requested: Set<HealthCategory>,
            observedAt: Instant,
        ): PermissionObservation {
            val records = HealthCategory.entries.associateWith { category ->
                val before = previous?.records?.get(category)
                PermissionRecord(
                    category = category,
                    requestObserved = before?.requestObserved == true || category in requested,
                    grantObserved = before?.grantObserved == true || category in granted,
                    granted = category in granted,
                )
            }
            return PermissionObservation(observedAt, records)
        }
    }
}

/** The operational history kept in Room, holding no health content of any kind. */
interface PermissionHistoryStore {

    /** The last successful observation, or null when no check ever succeeded. */
    suspend fun read(): PermissionObservation?

    /** Replaces the whole history in a single transaction. */
    suspend fun write(observation: PermissionObservation)
}
