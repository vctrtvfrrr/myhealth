package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.SupportedContractRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * A live API that lost PostgreSQL must say so, and must not pretend to have stored anything.
 *
 * The order is deliberate: the database is taken away for good half way through, which is exactly the
 * failure readiness exists to report.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IngestionUnavailableTest : IngestionApiTest() {

    @Test
    @Order(1)
    fun `is alive and ready while the database is reachable`() {
        assertEquals(200, api.get("/health").status)
        assertEquals(200, api.get("/ready").status)
        assertEquals(200, send(Envelopes.heartRate(samsungUid = "uid-before-loss")).status)
    }

    @Test
    @Order(2)
    fun `stays alive but stops being ready once the database is gone`() {
        postgres.stop()

        assertEquals(200, api.get("/health").status, "a live process must not report itself dead")
        assertEquals(503, api.get("/ready").status)
    }

    @Test
    @Order(3)
    fun `answers 503 without any positional result once the database is gone`() {
        val response = send(Envelopes.heartRate(samsungUid = "uid-sentinel-after-loss"))

        assertEquals(503, response.status)
        assertEquals("ingestion_temporarily_unavailable", problemCode(response.body))
        assertFalse(response.body.contains("results"), "a refused batch must carry no positional results")
    }

    /**
     * Coupling this to the database would make an outage look like a Contract Incompatibility, which is
     * the opposite diagnosis: it would send the Data Owner to update an application that was correct.
     */
    @Test
    @Order(4)
    fun `still publishes the supported contract range once the database is gone`() {
        val response = api.get("/ingestion-contract")

        assertEquals(200, response.status)
        assertEquals(
            IngestionContract.json.encodeToString(
                SupportedContractRange.serializer(),
                SupportedContractRange.PUBLISHED,
            ),
            response.body,
        )
    }

    @Test
    @Order(5)
    fun `never wrote the failure details into the log`() {
        val logs = api.logs()

        assertFalse(logs.contains("uid-sentinel-after-loss"), "the log leaked a Samsung UID")
        assertFalse(logs.contains(token), "the log leaked the device token")
    }
}
