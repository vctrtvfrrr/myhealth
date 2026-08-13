package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class OutboxSenderTest {

    private val store = FakeSyncStore()
    private val endpoints = FakeEndpointStore()
    private val policy = SyncPolicy(batchItems = 2)

    private suspend fun stage(vararg uids: String) {
        val importer = HistoryImporter(
            source = FakeRecordSource(listOf(page(uids.map { sourceRecord(uid = it) }))),
            store = store,
            policy = policy,
            clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC),
        )
        store.writeCursor(SyncCursor(heartRate.category).startingInitialLoad(LocalDateTime.of(2026, 8, 11, 9, 0)))
        importer.import(heartRate)
    }

    private fun sender(client: FakeIngestionClient) = OutboxSender(store, endpoints, client, policy)

    private fun allAccepted(status: ItemStatus = ItemStatus.ACCEPTED) = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, status) })
    }

    @Test
    fun `clears an item the API accepted`() = runTest {
        stage("a", "b")

        val result = sender(allAccepted()).drain(heartRate)

        assertSame(SendResult.Drained, result)
        assertTrue(store.staged.isEmpty())
    }

    @Test
    fun `clears an item the API already had`() = runTest {
        stage("a")

        sender(allAccepted(ItemStatus.ALREADY_PRESENT)).drain(heartRate)

        assertTrue(store.staged.isEmpty())
    }

    @Test
    fun `sends batches of the configured size, homogeneous by record type`() = runTest {
        stage("a", "b", "c")
        val client = allAccepted()

        sender(client).drain(heartRate)

        assertEquals(listOf(2, 1), client.batches.map { it.items.size })
        assertEquals(setOf("heart_rate"), client.batches.map { it.recordType }.toSet())
        assertEquals(setOf(IngestionContract.CURRENT_VERSION), client.batches.map { it.contractVersion }.toSet())
    }

    @Test
    fun `keeps a rejected item as a mapping pendency and delivers the rest`() = runTest {
        stage("a", "b")
        val client = FakeIngestionClient { batch ->
            SendOutcome.Delivered(
                batch.items.mapIndexed { index, envelope ->
                    if (envelope.samsungUid == "a") {
                        ItemResult(index, ItemStatus.REJECTED, listOf(RejectionCode.INVALID_PAYLOAD))
                    } else {
                        ItemResult(index, ItemStatus.ACCEPTED)
                    }
                },
            )
        }

        val result = sender(client).drain(heartRate)

        assertSame(SendResult.Drained, result)
        assertEquals(listOf("a"), store.staged.map { it.item.samsungUid })
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), store.staged.single().codes)
    }

    @Test
    fun `never sends a mapping pendency again`() = runTest {
        stage("a")
        val rejecting = FakeIngestionClient { batch ->
            SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.REJECTED, emptyList()) })
        }
        sender(rejecting).drain(heartRate)

        val client = allAccepted()
        sender(client).drain(heartRate)

        assertTrue(client.batches.isEmpty())
    }

    @Test
    fun `keeps everything when the API cannot be reached`() = runTest {
        stage("a")

        val result = sender(FakeIngestionClient { SendOutcome.Unreachable }).drain(heartRate)

        assertEquals(SendResult.Halted(SyncOutcome.INGESTION_UNAVAILABLE), result)
        assertEquals(1, store.staged.size)
    }

    @Test
    fun `reports a contract incompatibility as its own outcome`() = runTest {
        stage("a")

        val result = sender(
            FakeIngestionClient { SendOutcome.Refused(BatchErrorCode.CONTRACT_VERSION_TOO_OLD) },
        ).drain(heartRate)

        assertEquals(SendResult.Halted(SyncOutcome.CONTRACT_INCOMPATIBLE), result)
    }

    @Test
    fun `reports a refused device token as a configuration problem`() = runTest {
        stage("a")

        val result = sender(
            FakeIngestionClient { SendOutcome.Refused(BatchErrorCode.INVALID_DEVICE_TOKEN) },
        ).drain(heartRate)

        assertEquals(SendResult.Halted(SyncOutcome.NOT_CONFIGURED), result)
    }

    @Test
    fun `delivers nothing before the ingestion endpoint is configured`() = runTest {
        stage("a")
        val client = allAccepted()

        val result = OutboxSender(store, FakeEndpointStore(endpoint = null), client, policy).drain(heartRate)

        assertEquals(SendResult.Halted(SyncOutcome.NOT_CONFIGURED), result)
        assertTrue(client.batches.isEmpty())
    }
}
