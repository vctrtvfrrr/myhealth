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
import br.etc.victor.myhealthbridge.sync.SyncRun
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
        application.graph.sync(runOf(inputData.getString(RUN)))
        return Result.success()
    }

    /** An input this build cannot read is the ordinary run, which never restarts an import. */
    private fun runOf(name: String?): SyncRun =
        SyncRun.entries.firstOrNull { it.name == name } ?: SyncRun.INCREMENTAL

    companion object {
        const val RUN: String = "run"
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

    override fun requestInitialLoad() = request(SyncRun.INITIAL_LOAD, MANUAL)

    override fun requestSync() = request(SyncRun.INCREMENTAL, MANUAL)

    /**
     * Under its own name, so that a queued ordinary run cannot swallow it: the unique work policy keeps
     * what is already queued, and a reconciliation the Data Owner asked for has to happen.
     */
    override fun requestReconciliation() = request(SyncRun.FULL_RECONCILIATION, RECONCILIATION)

    private fun request(run: SyncRun, uniqueName: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(Data.Builder().putString(SyncWorker.RUN, run.name).build())
                .build(),
        )
    }

    private companion object {
        const val HOURLY = "hourly-sync"
        const val MANUAL = "manual-sync"
        const val RECONCILIATION = "reconciliation"
    }
}
