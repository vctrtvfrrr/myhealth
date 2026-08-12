package br.etc.victor.myhealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.data.permissionHistoryStore
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsScreen
import br.etc.victor.myhealthbridge.health.ui.HealthPermissionsViewModel
import br.etc.victor.myhealthbridge.samsung.ForegroundActivity
import br.etc.victor.myhealthbridge.samsung.SamsungHealthDataGateway
import java.time.Clock

class MainActivity : ComponentActivity() {

    private val viewModel: HealthPermissionsViewModel by viewModels {
        viewModelFactory {
            initializer {
                HealthPermissionsViewModel(
                    HealthPermissionsService(
                        gateway = SamsungHealthDataGateway(applicationContext),
                        store = permissionHistoryStore(applicationContext),
                        clock = Clock.systemUTC(),
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyHealthBridgeApp {
                HealthPermissionsScreen(viewModel)
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

@Composable
fun MyHealthBridgeApp(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}
