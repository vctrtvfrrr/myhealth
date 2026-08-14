package br.etc.victor.myhealthbridge

import android.content.Context
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.data.permissionHistoryStore
import br.etc.victor.myhealthbridge.maintenance.MaintenancePolicy
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService
import br.etc.victor.myhealthbridge.maintenance.data.maintenanceStore
import br.etc.victor.myhealthbridge.samsung.SamsungHealthDataGateway
import br.etc.victor.myhealthbridge.samsung.SamsungRecordSource
import br.etc.victor.myhealthbridge.sync.ChangeImporter
import br.etc.victor.myhealthbridge.sync.HistoryImporter
import br.etc.victor.myhealthbridge.sync.HttpIngestionClient
import br.etc.victor.myhealthbridge.sync.OutboxSender
import br.etc.victor.myhealthbridge.sync.SyncPolicy
import br.etc.victor.myhealthbridge.sync.SyncRun
import br.etc.victor.myhealthbridge.sync.SyncService
import br.etc.victor.myhealthbridge.sync.data.SyncStores
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * The application's long lived collaborators, built once.
 *
 * The permissions service is shared with the screen on purpose: a synchronization observes the same
 * Permission State history the Data Owner is looking at, so a revocation seen by one is seen by both.
 */
class SyncGraph(context: Context) {

    private val stores = SyncStores(context)

    private val runs = Mutex()

    private val policy = SyncPolicy()

    val permissions = HealthPermissionsService(
        gateway = SamsungHealthDataGateway(context),
        store = permissionHistoryStore(context),
        clock = Clock.systemUTC(),
    )

    val syncStore = stores.sync

    val endpoints = stores.endpoints

    val maintenance = MaintenanceService(
        store = maintenanceStore(context),
        notifier = AndroidMaintenanceNotifier(context),
        policy = MaintenancePolicy(),
        clock = Clock.systemUTC(),
    )

    private val source = SamsungRecordSource(context)

    private val service = SyncService(
        permissions = permissions,
        importer = HistoryImporter(
            source = source,
            store = syncStore,
            maintenance = maintenance,
            policy = policy,
            // Local time, because the import walks the history in the terms Samsung Health filters on.
            clock = Clock.systemDefaultZone(),
        ),
        changes = ChangeImporter(
            source = source,
            store = syncStore,
            maintenance = maintenance,
            policy = policy,
            clock = Clock.systemUTC(),
        ),
        sender = OutboxSender(
            store = syncStore,
            endpoints = endpoints,
            client = HttpIngestionClient(),
            maintenance = maintenance,
            policy = policy,
        ),
        store = syncStore,
        maintenance = maintenance,
        policy = policy,
        clock = Clock.systemUTC(),
    )

    /**
     * One run at a time: the hourly schedule and a manual request are separate WorkManager requests,
     * and two of them reading the same cursor would each import what the other already staged.
     */
    suspend fun sync(run: SyncRun) = runs.withLock {
        when (run) {
            SyncRun.INCREMENTAL -> Unit
            SyncRun.INITIAL_LOAD -> service.startInitialLoad()
            SyncRun.FULL_RECONCILIATION -> service.reconcile()
        }
        service.sync()
    }
}
