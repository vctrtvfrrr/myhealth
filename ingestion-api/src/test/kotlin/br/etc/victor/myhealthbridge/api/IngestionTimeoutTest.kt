package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An ingestion that cannot finish must leave nothing behind.
 *
 * The block is produced the way a real one would be, by holding a lock on the table the API has to
 * write to, so the timeout has to interrupt a statement that is genuinely waiting.
 */
@Tag("integration")
class IngestionTimeoutTest : IngestionApiTest(mapOf("INGESTION_TIMEOUT_SECONDS" to "2")) {

    @Test
    fun `answers 503 and rolls the whole ingestion back when it cannot finish in time`() {
        val ingestionsBefore = countOf("select count(*) from ingestion")
        val resultsBefore = countOf("select count(*) from ingestion_item")
        val pool = Executors.newSingleThreadExecutor()

        val response = try {
            api.query { connection ->
                connection.autoCommit = false
                connection.createStatement().use {
                    it.execute("lock table observed_record_version in access exclusive mode")
                }

                val blocked = pool.submit(
                    Callable {
                        api.postBatch(
                            Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-timed-out"))),
                            token,
                        )
                    },
                )
                val answer = blocked.get(2, TimeUnit.MINUTES)
                connection.rollback()
                answer
            }
        } finally {
            pool.shutdown()
        }

        assertEquals(503, response.status)
        assertEquals("ingestion_temporarily_unavailable", problemCode(response.body))
        assertEquals(0, versionsOf("uid-timed-out").size)
        assertEquals(
            ingestionsBefore,
            countOf("select count(*) from ingestion"),
            "the ingestion row survived a transaction that never committed",
        )
        assertEquals(
            resultsBefore,
            countOf("select count(*) from ingestion_item"),
            "a positional result survived a transaction that never committed",
        )
    }

    @Test
    fun `keeps serving after an ingestion that timed out`() {
        assertEquals(200, send(Envelopes.heartRate(samsungUid = "uid-after-timeout")).status)
    }
}
