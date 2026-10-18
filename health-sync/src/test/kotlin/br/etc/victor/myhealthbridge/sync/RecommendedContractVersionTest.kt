package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionBatch
import br.etc.victor.myhealthbridge.contract.IngestionContract
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Falling behind the recommended Ingestion Contract Version is advice, not a Contract Incompatibility:
 * the batch is accepted, so the divergence is only logged and the delivery is answered as usual.
 */
class RecommendedContractVersionTest {

    private class Reply(val status: Int, val body: String, val recommended: String?)

    @Volatile
    private var reply = Reply(status = 200, body = DELIVERED, recommended = null)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/ingestions") { exchange ->
            exchange.requestBody.readBytes()
            reply.recommended?.let { exchange.responseHeaders.add(IngestionContract.RECOMMENDED_HEADER, it) }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val divergences = mutableListOf<Pair<Int, Int>>()
    private val client = HttpIngestionClient(
        recommendedVersionLog = { declared, recommended -> divergences += declared to recommended },
    )
    private val endpoint = IngestionEndpoint("http://127.0.0.1:${server.address.port}", deviceToken = "secret-token")

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a version below the recommended one is logged with both versions and still delivered`() = runTest {
        answer(status = 200, body = DELIVERED, recommended = "2")

        val outcome = client.send(endpoint, batchDeclaring(version = 1))

        assertInstanceOf(SendOutcome.Delivered::class.java, outcome)
        assertEquals(listOf(1 to 2), divergences)
    }

    @Test
    fun `a version equal to the recommended one logs nothing`() = runTest {
        answer(status = 200, body = DELIVERED, recommended = "2")

        client.send(endpoint, batchDeclaring(version = 2))

        assertTrue(divergences.isEmpty())
    }

    @Test
    fun `a version above the recommended one logs nothing`() = runTest {
        answer(status = 200, body = DELIVERED, recommended = "1")

        client.send(endpoint, batchDeclaring(version = 2))

        assertTrue(divergences.isEmpty())
    }

    @Test
    fun `a refusal carries the recommendation too and is still answered as the refusal it is`() = runTest {
        answer(status = 503, body = UNAVAILABLE, recommended = "2")

        val outcome = client.send(endpoint, batchDeclaring(version = 1))

        assertEquals(SendOutcome.Refused(BatchErrorCode.INGESTION_TEMPORARILY_UNAVAILABLE), outcome)
        assertEquals(listOf(1 to 2), divergences)
    }

    @Test
    fun `a missing or unreadable recommendation logs nothing and does not disturb the delivery`() = runTest {
        answer(status = 200, body = DELIVERED, recommended = null)
        assertInstanceOf(SendOutcome.Delivered::class.java, client.send(endpoint, batchDeclaring(version = 1)))

        answer(status = 200, body = DELIVERED, recommended = "two")
        assertInstanceOf(SendOutcome.Delivered::class.java, client.send(endpoint, batchDeclaring(version = 1)))

        assertTrue(divergences.isEmpty())
    }

    private fun answer(status: Int, body: String, recommended: String?) {
        reply = Reply(status, body, recommended)
    }

    private fun batchDeclaring(version: Int) =
        IngestionBatch(recordType = "heart_rate", items = emptyList(), contractVersion = version)

    private companion object {
        const val DELIVERED = """{"ingestionId":"ingestion-1","results":[]}"""
        const val UNAVAILABLE =
            """{"type":"about:blank","title":"Unavailable","status":503,"code":"ingestion_temporarily_unavailable"}"""
    }
}
