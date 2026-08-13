package br.etc.victor.myhealthbridge.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.etc.victor.myhealthbridge.sync.IngestionEndpoint
import br.etc.victor.myhealthbridge.sync.IngestionEndpointStore
import br.etc.victor.myhealthbridge.sync.SyncStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What a synchronization run asks for.
 *
 * The screen never runs one itself: a run has to survive the screen going away, so it is handed to
 * the scheduler and observed through the durable state it writes.
 */
interface SyncRequests {

    fun requestInitialLoad()

    fun requestSync()
}

class SyncViewModel(
    private val store: SyncStore,
    private val endpoints: IngestionEndpointStore,
    private val requests: SyncRequests,
) : ViewModel() {

    val state: StateFlow<SyncUiState> =
        combine(endpoints.observe(), store.observeOutbox(), store.observeCursors(), SyncUiState::of)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    fun saveEndpoint(baseUrl: String, deviceToken: String) {
        val endpoint = IngestionEndpoint(baseUrl.trim(), deviceToken.trim())
        if (endpoint.baseUrl.isEmpty() || endpoint.deviceToken.isEmpty()) return
        viewModelScope.launch { endpoints.write(endpoint) }
    }

    fun startInitialLoad() = requests.requestInitialLoad()

    fun syncNow() = requests.requestSync()
}
