package br.etc.victor.myhealthbridge.health.ui

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.PermissionState
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import java.time.Instant

data class HealthPermissionsUiState(
    val busy: Boolean = false,
    val availability: SamsungHealthAvailability? = null,
    val observedAt: Instant? = null,
    val lastCheckFailed: Boolean = false,
    val states: Map<HealthCategory, PermissionState> = emptyMap(),
) {

    /** No check ever succeeded, so no Permission State can be claimed. */
    val queryUnknown: Boolean
        get() = observedAt == null

    val outdated: Boolean
        get() = lastCheckFailed && observedAt != null

    val pending: Set<HealthCategory>
        get() = states.filterValues { it == PermissionState.NOT_REQUESTED }.keys
}
