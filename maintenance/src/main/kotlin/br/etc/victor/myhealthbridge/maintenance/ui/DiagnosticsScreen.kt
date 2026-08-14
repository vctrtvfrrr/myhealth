package br.etc.victor.myhealthbridge.maintenance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.etc.victor.myhealthbridge.maintenance.MaintenanceIncident
import br.etc.victor.myhealthbridge.maintenance.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DiagnosticsScreen(state = state, modifier = modifier)
}

@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.diagnostics_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.incidents.isEmpty()) {
            item { Text(stringResource(R.string.diagnostics_empty)) }
        }
        items(state.incidents, key = { it.identity.key }) { IncidentCard(it) }
    }
}

@Composable
private fun IncidentCard(incident: MaintenanceIncident) {
    val identity = incident.identity

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(identity.code.title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = identity.category
                    ?.let { stringResource(R.string.diagnostics_category, stringResource(it.label)) }
                    ?: stringResource(R.string.diagnostics_category_all),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_code, identity.code.id),
                style = MaterialTheme.typography.bodySmall,
            )
            identity.detail?.let {
                Text(stringResource(R.string.diagnostics_detail, it), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(R.string.diagnostics_first_seen, formatInstant(incident.firstSeenAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_last_seen, formatInstant(incident.lastSeenAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.diagnostics_occurrences, incident.occurrences),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(identity.code.action), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val instantFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant): String = instantFormat.format(instant)
