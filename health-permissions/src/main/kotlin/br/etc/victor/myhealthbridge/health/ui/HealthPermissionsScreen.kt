package br.etc.victor.myhealthbridge.health.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthCategoryGroup
import br.etc.victor.myhealthbridge.health.PermissionState
import br.etc.victor.myhealthbridge.health.R
import br.etc.victor.myhealthbridge.health.Remediation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HealthPermissionsScreen(viewModel: HealthPermissionsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.check()
        onPauseOrDispose {}
    }

    HealthPermissionsScreen(
        state = state,
        onRefresh = viewModel::check,
        onResolve = viewModel::resolveAvailability,
        onRequestAllPending = viewModel::requestAllPending,
        onRequest = viewModel::request,
        modifier = modifier,
    )
}

@Composable
fun HealthPermissionsScreen(
    state: HealthPermissionsUiState,
    onRefresh: () -> Unit,
    onResolve: () -> Unit,
    onRequestAllPending: () -> Unit,
    onRequest: (HealthCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AvailabilityCard(state, onResolve) }
        item { CheckStatus(state, onRefresh) }
        item {
            Button(
                onClick = onRequestAllPending,
                enabled = !state.busy && state.pending.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.health_request_all_pending))
            }
        }

        HealthCategoryGroup.entries.forEach { group ->
            item(key = group.name) {
                Text(
                    text = stringResource(group.label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                items = HealthCategory.entries.filter { it.group == group },
                key = HealthCategory::id,
            ) { category ->
                CategoryRow(
                    category = category,
                    state = state.states[category],
                    busy = state.busy,
                    onRequest = { onRequest(category) },
                )
            }
        }
    }
}

@Composable
private fun AvailabilityCard(state: HealthPermissionsUiState, onResolve: () -> Unit) {
    val availability = state.availability
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(availabilityMessage(availability)))
            val resolution = (availability as? SamsungHealthAvailability.ActionRequired)?.resolution
            if (resolution != null) {
                OutlinedButton(onClick = onResolve, enabled = !state.busy) {
                    Text(stringResource(R.string.health_availability_resolve))
                }
            }
        }
    }
}

@Composable
private fun CheckStatus(state: HealthPermissionsUiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) {
            Text(stringResource(R.string.health_checking), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.observedAt
                    ?.let { stringResource(R.string.health_last_check, formatCheckInstant(it)) }
                    ?: stringResource(R.string.health_never_checked),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(stringResource(R.string.health_refresh))
            }
        }
        if (state.queryUnknown) {
            Text(stringResource(R.string.health_query_unknown), style = MaterialTheme.typography.bodySmall)
        }
        if (state.outdated) {
            Text(stringResource(R.string.health_outdated), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CategoryRow(
    category: HealthCategory,
    state: PermissionState?,
    busy: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (category.shownUnder == null) 0.dp else 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(category.label), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(state?.let(::stateLabel) ?: R.string.health_state_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state == PermissionState.DENIED || state == PermissionState.REVOKED) {
                Text(
                    text = stringResource(R.string.health_settings_guidance),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state != PermissionState.GRANTED) {
            TextButton(onClick = onRequest, enabled = !busy) {
                Text(
                    stringResource(
                        if (state == PermissionState.DENIED || state == PermissionState.REVOKED) {
                            R.string.health_action_request_again
                        } else {
                            R.string.health_action_request
                        },
                    ),
                )
            }
        }
    }
}

private fun availabilityMessage(availability: SamsungHealthAvailability?): Int = when (availability) {
    null -> R.string.health_availability_unknown
    SamsungHealthAvailability.Ready -> R.string.health_availability_ready
    is SamsungHealthAvailability.TemporarilyUnavailable -> R.string.health_availability_temporarily_unavailable
    is SamsungHealthAvailability.Unsupported -> R.string.health_availability_unsupported
    is SamsungHealthAvailability.ActionRequired -> when (availability.remediation) {
        Remediation.SAMSUNG_HEALTH_SETUP -> R.string.health_availability_setup
        Remediation.APPLICATION_NOT_RECOGNIZED -> R.string.health_availability_not_recognized
    }
}

private fun stateLabel(state: PermissionState): Int = when (state) {
    PermissionState.NOT_REQUESTED -> R.string.health_state_not_requested
    PermissionState.GRANTED -> R.string.health_state_granted
    PermissionState.DENIED -> R.string.health_state_denied
    PermissionState.REVOKED -> R.string.health_state_revoked
}

private val checkInstantFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatCheckInstant(instant: Instant): String = checkInstantFormat.format(instant)
