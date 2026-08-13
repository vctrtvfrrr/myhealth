package br.etc.victor.myhealthbridge.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.R
import br.etc.victor.myhealthbridge.sync.SyncOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SyncScreen(viewModel: SyncViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SyncScreen(
        state = state,
        onSaveEndpoint = viewModel::saveEndpoint,
        onStartInitialLoad = viewModel::startInitialLoad,
        onSyncNow = viewModel::syncNow,
        onReconcile = viewModel::reconcileNow,
        modifier = modifier,
    )
}

@Composable
fun SyncScreen(
    state: SyncUiState,
    onSaveEndpoint: (String, String) -> Unit,
    onStartInitialLoad: () -> Unit,
    onSyncNow: () -> Unit,
    onReconcile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { EndpointCard(state, onSaveEndpoint) }
        item { OutboxCard(state) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.initialLoadPending) {
                    Button(onClick = onStartInitialLoad, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sync_start_initial_load))
                    }
                }
                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = state.configured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_now))
                }
                Text(stringResource(R.string.sync_schedule), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onReconcile,
                    enabled = state.configured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_reconcile))
                }
                Text(stringResource(R.string.sync_reconcile_explanation), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                text = stringResource(R.string.sync_categories_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(state.categories, key = { it.category.id }) { CategoryCard(it) }
    }
}

@Composable
private fun EndpointCard(state: SyncUiState, onSave: (String, String) -> Unit) {
    var baseUrl by remember(state.endpoint?.baseUrl) { mutableStateOf(state.endpoint?.baseUrl.orEmpty()) }
    var token by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.sync_endpoint_section), style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.endpoint
                    ?.let { stringResource(R.string.sync_endpoint_configured, it.baseUrl) }
                    ?: stringResource(R.string.sync_endpoint_missing),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.sync_endpoint_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // The token is never read back from the store: it is a credential, so the field starts
            // empty and saving replaces what is there.
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.sync_endpoint_device_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onSave(baseUrl, token)
                    token = ""
                },
                enabled = baseUrl.isNotBlank() && token.isNotBlank(),
            ) {
                Text(stringResource(R.string.sync_endpoint_save))
            }
        }
    }
}

@Composable
private fun OutboxCard(state: SyncUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.sync_outbox_section), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sync_outbox_pending, state.outbox.pending))
            Text(stringResource(R.string.sync_outbox_mapping_pendencies, state.outbox.mappingPendencies))
            if (state.outbox.mappingPendencies > 0) {
                Text(
                    text = stringResource(R.string.sync_outbox_mapping_pendencies_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(state: CategorySyncState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(state.category.label), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(phaseLabel(state.phase)), style = MaterialTheme.typography.bodySmall)
            }
            state.initialLoadProgress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.sync_imported_records, state.importedRecords),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.lastAttemptAt
                    ?.let { stringResource(R.string.sync_last_attempt, formatInstant(it)) }
                    ?: stringResource(R.string.sync_last_attempt_never),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.lastSuccessAt
                    ?.let { stringResource(R.string.sync_last_success, formatInstant(it)) }
                    ?: stringResource(R.string.sync_last_success_never),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.lastOverlapAt
                    ?.let { stringResource(R.string.sync_last_overlap, formatInstant(it)) }
                    ?: stringResource(R.string.sync_last_overlap_never),
                style = MaterialTheme.typography.bodySmall,
            )
            state.outcome?.let {
                Text(stringResource(outcomeLabel(it)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun phaseLabel(phase: ImportPhase): Int = when (phase) {
    ImportPhase.NOT_STARTED -> R.string.sync_phase_not_started
    ImportPhase.INITIAL_LOAD -> R.string.sync_phase_initial_load
    ImportPhase.INCREMENTAL -> R.string.sync_phase_incremental
}

private fun outcomeLabel(outcome: SyncOutcome): Int = when (outcome) {
    SyncOutcome.SUCCEEDED -> R.string.sync_outcome_succeeded
    SyncOutcome.WAITING_PERMISSION -> R.string.sync_outcome_waiting_permission
    SyncOutcome.SAMSUNG_UNAVAILABLE -> R.string.sync_outcome_samsung_unavailable
    SyncOutcome.NOT_CONFIGURED -> R.string.sync_outcome_not_configured
    SyncOutcome.INGESTION_UNAVAILABLE -> R.string.sync_outcome_ingestion_unavailable
    SyncOutcome.CONTRACT_INCOMPATIBLE -> R.string.sync_outcome_contract_incompatible
    SyncOutcome.OUTBOX_FULL -> R.string.sync_outcome_outbox_full
    SyncOutcome.CURSOR_UNRECOVERABLE -> R.string.sync_outcome_cursor_unrecoverable
}

private val instantFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant): String = instantFormat.format(instant)
