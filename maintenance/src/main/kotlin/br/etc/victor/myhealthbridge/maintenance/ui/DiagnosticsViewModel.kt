package br.etc.victor.myhealthbridge.maintenance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.etc.victor.myhealthbridge.maintenance.MaintenanceIncident
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the diagnostics screen shows: the open Maintenance Incidents and nothing else.
 *
 * There is no failure detail beside them on purpose. Anything a failing read or a rejected item
 * carried came from the Data Owner's Consolidated Health Data, and a screen is not where a second
 * copy of it belongs.
 */
data class DiagnosticsUiState(val incidents: List<MaintenanceIncident> = emptyList())

class DiagnosticsViewModel(maintenance: MaintenanceService) : ViewModel() {

    val state: StateFlow<DiagnosticsUiState> = maintenance.observe()
        .map(::DiagnosticsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())
}
