package br.etc.victor.myhealthbridge.health.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.etc.victor.myhealthbridge.health.CheckResult
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HealthPermissionsViewModel(
    private val service: HealthPermissionsService,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthPermissionsUiState())
    val state: StateFlow<HealthPermissionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val last = service.lastObservation() ?: return@launch
            _state.update { current ->
                // A check that answered while this read was in flight already holds a newer observation.
                if (current.observedAt != null) current
                else current.copy(observedAt = last.observedAt, states = last.states)
            }
        }
    }

    fun check() = whileIdle { service.check() }

    fun request(category: HealthCategory) = whileIdle { service.request(setOf(category)) }

    fun requestAllPending() = whileIdle { service.request(_state.value.pending) }

    fun resolveAvailability() {
        val availability = _state.value.availability
        if (availability !is SamsungHealthAvailability.ActionRequired) return
        val resolution = availability.resolution ?: return
        whileIdle {
            resolution.resolve()
            service.check()
        }
    }

    /** Only one Samsung Health operation runs at a time, so conflicting actions stay disabled. */
    private fun whileIdle(operation: suspend () -> CheckResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = operation()
            _state.update { current ->
                when (result) {
                    is CheckResult.Observed -> current.copy(
                        busy = false,
                        availability = SamsungHealthAvailability.Ready,
                        observedAt = result.observation.observedAt,
                        lastCheckFailed = false,
                        states = result.observation.states,
                    )

                    is CheckResult.Unavailable -> current.copy(
                        busy = false,
                        availability = result.availability,
                        lastCheckFailed = true,
                    )
                }
            }
        }
    }
}
