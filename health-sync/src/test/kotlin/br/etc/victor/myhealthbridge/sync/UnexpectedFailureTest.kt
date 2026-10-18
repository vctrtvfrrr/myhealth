package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthGateway
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * An exception nobody anticipated is a defect in the code, so it is reported like every other one
 * instead of ending the run without a trace, and it stays inside the Health Category it happened in.
 */
class UnexpectedFailureTest {

    private val now = Instant.parse("2026-08-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    /** What reading the history looked like on 2026-08-14, when a runtime dependency of the SDK was missing. */
    private val missingDependency = NoClassDefFoundError("com/samsung/android/sdk/health/data/Missing")

    private class SwitchableGateway(var failure: Throwable? = null) : SamsungHealthGateway {

        override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> {
            failure?.let { throw it }
            return SamsungHealthOutcome.Observed(setOf(HealthCategory.HEART_RATE, HealthCategory.EXERCISE))
        }

        override suspend fun requestReadPermissions(categories: Set<HealthCategory>) =
            SamsungHealthOutcome.Observed(Unit)
    }

    /** Holds one record of [failing] and nothing of any other category, and fails reading it while [failure] is set. */
    private class FailingSource(private val failing: HealthCategory, var failure: Throwable?) : HealthRecordSource {

        private val records = FakeRecordSource(listOf(page(listOf(sourceRecord()))))

        private val nothing = FakeRecordSource(emptyList())

        override suspend fun readPage(capability: HealthCapability, window: ReadWindow, pageToken: String?) =
            failIfAsked(capability) ?: sourceOf(capability).readPage(capability, window, pageToken)

        override suspend fun readChanges(capability: HealthCapability, window: ChangeWindow, pageToken: String?) =
            failIfAsked(capability) ?: sourceOf(capability).readChanges(capability, window, pageToken)

        private fun sourceOf(capability: HealthCapability) = if (capability.category == failing) records else nothing

        private fun failIfAsked(capability: HealthCapability): Nothing? {
            val failure = failure
            if (capability.category == failing && failure != null) throw failure
            return null
        }
    }

    private val gateway = SwitchableGateway()

    private val accepting = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) })
    }

    private fun service(source: HealthRecordSource, client: IngestionClient = accepting): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(gateway, InMemoryPermissionHistory(), clock),
            importer = HistoryImporter(source, store, channel, policy, clock),
            changes = ChangeImporter(source, store, channel, policy, clock),
            sender = OutboxSender(store, FakeEndpointStore(), client, channel, policy),
            store = store,
            maintenance = channel,
            policy = policy,
            clock = clock,
        )
    }

    private fun failureIn(category: HealthCategory, failure: Throwable) = IncidentIdentity(
        code = MaintenanceCode.UNEXPECTED_FAILURE,
        category = category,
        detail = failure.javaClass.name,
    )

    @Test
    fun `reports an exception thrown while reading a category as a defect in the code`() = runTest {
        service(FailingSource(HealthCategory.HEART_RATE, missingDependency)).sync(SyncRun.INITIAL_LOAD)

        assertEquals(listOf(failureIn(HealthCategory.HEART_RATE, missingDependency)), maintenance.reported)
        assertEquals(1, maintenance.posted.size, "a defect in the code is told at once, not held as transient")
    }

    @Test
    fun `reports an exception thrown while delivering a category`() = runTest {
        val failure = IllegalStateException("closed")
        val client = FakeIngestionClient { throw failure }

        service(FailingSource(HealthCategory.HEART_RATE, failure = null), client).sync(SyncRun.INITIAL_LOAD)

        assertEquals(listOf(failureIn(HealthCategory.HEART_RATE, failure)), maintenance.reported)
    }

    @Test
    fun `records the attempt of the failing category and lets the others advance`() = runTest {
        service(FailingSource(HealthCategory.HEART_RATE, missingDependency)).sync(SyncRun.INITIAL_LOAD)

        val failed = store.cursor(HealthCategory.HEART_RATE)!!
        assertEquals(SyncOutcome.UNEXPECTED_FAILURE, failed.lastOutcome)
        assertEquals(now, failed.lastAttemptAt)
        assertEquals(SyncOutcome.SUCCEEDED, store.cursor(HealthCategory.EXERCISE)!!.lastOutcome)
    }

    @Test
    fun `coalesces equivalent occurrences into one incident`() = runTest {
        val source = FailingSource(HealthCategory.HEART_RATE, IllegalStateException("first message"))
        service(source).sync(SyncRun.INITIAL_LOAD)
        source.failure = IllegalStateException("a different message")

        service(source).sync(SyncRun.INCREMENTAL)

        assertEquals(1, maintenance.reported.size)
        assertEquals(2L, maintenance.posted.last().occurrences)
    }

    @Test
    fun `tells apart two exceptions of different types`() = runTest {
        val source = FailingSource(HealthCategory.HEART_RATE, IllegalStateException())
        service(source).sync(SyncRun.INITIAL_LOAD)
        source.failure = missingDependency

        service(source).sync(SyncRun.INCREMENTAL)

        assertEquals(2, maintenance.reported.size)
    }

    /**
     * A message can be built out of what was being read, and a stack trace can name it too: only the
     * type of the exception is the code's own vocabulary.
     */
    @Test
    fun `keeps the exception message and its stack trace out of the incident`() = runTest {
        val failure = IllegalArgumentException("heart_rate 72 from device-1 with token secret-token")

        service(FailingSource(HealthCategory.HEART_RATE, failure)).sync(SyncRun.INITIAL_LOAD)

        val rendered = maintenance.reported.joinToString(" ") { it.key }
        listOf("72", "device-1", "secret-token", "heart_rate 72", "UnexpectedFailureTest").forEach {
            assertFalse(rendered.contains(it), "the incident quoted $it")
        }
        assertEquals(listOf("java.lang.IllegalArgumentException"), maintenance.reported.map { it.detail })
    }

    @Test
    fun `ends the incident once the category synchronizes again`() = runTest {
        val source = FailingSource(HealthCategory.HEART_RATE, missingDependency)
        service(source).sync(SyncRun.INITIAL_LOAD)
        source.failure = null

        service(source).sync(SyncRun.INCREMENTAL)

        assertTrue(maintenance.reported.isEmpty())
        assertEquals(listOf(failureIn(HealthCategory.HEART_RATE, missingDependency)), maintenance.withdrawn)
    }

    /** Before any category is reached, the defect is not one category's to name. */
    @Test
    fun `reports an exception outside every category without naming one, and ends it once a run completes`() = runTest {
        gateway.failure = missingDependency
        val source = FailingSource(HealthCategory.HEART_RATE, failure = null)

        service(source).sync(SyncRun.INITIAL_LOAD)
        val reported = maintenance.reported
        gateway.failure = null
        service(source).sync(SyncRun.INITIAL_LOAD)

        val expected = IncidentIdentity(MaintenanceCode.UNEXPECTED_FAILURE, detail = missingDependency.javaClass.name)
        assertEquals(listOf(expected), reported)
        assertTrue(maintenance.reported.isEmpty())
        assertEquals(listOf(expected), maintenance.withdrawn)
    }
}
