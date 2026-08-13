package br.etc.victor.myhealthbridge

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.etc.victor.myhealthbridge.sync.ui.SyncRequests
import java.util.concurrent.TimeUnit

/**
 * Runs one synchronization outside any screen, so that it survives the application being closed.
 *
 * It always succeeds: how the run ended is recorded per Health Category and shown on the screen, and
 * a WorkManager retry on top of the hourly request would only read the same history twice.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val application = applicationContext as MyHealthBridgeApplication
        application.graph.sync(startInitialLoad = inputData.getBoolean(START_INITIAL_LOAD, false))
        return Result.success()
    }

    companion object {
        const val START_INITIAL_LOAD: String = "start_initial_load"
    }
}

/**
 * Asks for synchronization runs.
 *
 * The hourly request carries no promise of an exact time, which is what WorkManager offers; it only
 * requires a network of any kind, because the Data Owner asked for the history to reach the API
 * rather than for it to wait for a particular connection.
 */
class SyncScheduler(private val context: Context) : SyncRequests {

    fun scheduleHourly() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HOURLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    override fun requestInitialLoad() = request(startInitialLoad = true)

    override fun requestSync() = request(startInitialLoad = false)

    private fun request(startInitialLoad: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(Data.Builder().putBoolean(SyncWorker.START_INITIAL_LOAD, startInitialLoad).build())
                .build(),
        )
    }

    private companion object {
        const val HOURLY = "hourly-sync"
        const val MANUAL = "manual-sync"
    }
}
