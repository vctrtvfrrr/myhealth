package br.etc.victor.myhealthbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsScreen
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsViewModel
import br.etc.victor.myhealthbridge.maintenance.ui.DiagnosticsScreen
import br.etc.victor.myhealthbridge.maintenance.ui.DiagnosticsViewModel
import br.etc.victor.myhealthbridge.samsung.ForegroundActivity
import br.etc.victor.myhealthbridge.sync.ui.SyncScreen
import br.etc.victor.myhealthbridge.sync.ui.SyncViewModel

class MainActivity : ComponentActivity() {

    private val graph: SyncGraph get() = (application as MyHealthBridgeApplication).graph

    private val permissionsViewModel: HealthPermissionsViewModel by viewModels {
        viewModelFactory { initializer { HealthPermissionsViewModel(graph.permissions) } }
    }

    private val syncViewModel: SyncViewModel by viewModels {
        viewModelFactory {
            initializer {
                SyncViewModel(
                    store = graph.syncStore,
                    endpoints = graph.endpoints,
                    requests = SyncScheduler(applicationContext),
                )
            }
        }
    }

    private val diagnosticsViewModel: DiagnosticsViewModel by viewModels {
        viewModelFactory { initializer { DiagnosticsViewModel(graph.maintenance) } }
    }

    /**
     * The screen an intent asked for, which is how a maintenance notification opens the diagnostics.
     *
     * It is state rather than an initial value because the activity is single top: the notification
     * that arrives while the application is open reaches [onNewIntent], not another [onCreate].
     */
    private var opened by mutableStateOf(MainTab.PERMISSIONS)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened = MainTab.of(intent?.action)
        askToNotify()

        setContent {
            MyHealthBridgeApp {
                MainScreen(opened, permissionsViewModel, syncViewModel, diagnosticsViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened = MainTab.of(intent.action)
    }

    override fun onResume() {
        super.onResume()
        ForegroundActivity.bind(this)
    }

    override fun onPause() {
        ForegroundActivity.unbind(this)
        super.onPause()
    }

    /**
     * Asked for here because the maintenance channel is the only thing that notifies, and a synchronization
     * runs without any screen: by the time an incident is raised there is nobody to ask.
     */
    private fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    opened: MainTab,
    permissionsViewModel: HealthPermissionsViewModel,
    syncViewModel: SyncViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
) {
    var selected by rememberSaveable(opened) { mutableStateOf(opened) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            TabRow(selectedTabIndex = selected.ordinal) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        text = { Text(stringResource(tab.title)) },
                    )
                }
            }
            when (selected) {
                MainTab.PERMISSIONS -> HealthPermissionsScreen(permissionsViewModel)
                MainTab.SYNC -> SyncScreen(syncViewModel)
                MainTab.DIAGNOSTICS -> DiagnosticsScreen(diagnosticsViewModel)
            }
        }
    }
}

@Composable
fun MyHealthBridgeApp(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}
