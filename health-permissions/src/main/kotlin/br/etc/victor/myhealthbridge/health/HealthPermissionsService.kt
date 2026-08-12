package br.etc.victor.myhealthbridge.health

import java.time.Clock

sealed interface CheckResult {

    data class Observed(val observation: PermissionObservation) : CheckResult

    /** Nothing was persisted: the previous observation, if any, stays valid but outdated. */
    data class Unavailable(val availability: SamsungHealthAvailability) : CheckResult
}

class HealthPermissionsService(
    private val gateway: SamsungHealthGateway,
    private val store: PermissionHistoryStore,
    private val clock: Clock,
) {

    /**
     * Requests observed in this process but not yet written, because a request only becomes part of
     * the history together with the observation that shows its effect.
     */
    private var unwrittenRequests: Set<HealthCategory> = emptySet()

    suspend fun lastObservation(): PermissionObservation? = store.read()

    suspend fun check(): CheckResult {
        return when (val granted = gateway.grantedReadCategories()) {
            is SamsungHealthOutcome.Failed -> CheckResult.Unavailable(granted.availability)
            is SamsungHealthOutcome.Observed -> {
                val observation = PermissionObservation.from(
                    previous = store.read(),
                    granted = granted.value,
                    requested = unwrittenRequests,
                    observedAt = clock.instant(),
                )
                store.write(observation)
                unwrittenRequests = emptySet()
                CheckResult.Observed(observation)
            }
        }
    }

    suspend fun request(categories: Set<HealthCategory>): CheckResult {
        if (categories.isEmpty()) return check()

        return when (val request = gateway.requestReadPermissions(categories)) {
            is SamsungHealthOutcome.Failed -> CheckResult.Unavailable(request.availability)
            is SamsungHealthOutcome.Observed -> {
                unwrittenRequests = unwrittenRequests + categories
                check()
            }
        }
    }
}
