package br.etc.victor.myhealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsScreen
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyHealthBridgeApp {
                MainScreen(permissionsViewModel, syncViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ForegroundActivity.bind(this)
    }

    override fun onPause() {
        ForegroundActivity.unbind(this)
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsViewModel: HealthPermissionsViewModel,
    syncViewModel: SyncViewModel,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val titles = remember { listOf(R.string.tab_permissions, R.string.tab_sync) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            TabRow(selectedTabIndex = selected) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(stringResource(title)) },
                    )
                }
            }
            when (selected) {
                0 -> HealthPermissionsScreen(permissionsViewModel)
                else -> SyncScreen(syncViewModel)
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
