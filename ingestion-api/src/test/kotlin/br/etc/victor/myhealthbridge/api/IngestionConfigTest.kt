package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IngestionConfigTest {

    @Test
    fun `falls back to the documented defaults`() {
        val config = IngestionConfig.fromEnvironment { null }

        assertEquals(500, config.maxItems)
        assertEquals(2 * 1024 * 1024, config.maxBytes)
        assertEquals(30, config.timeoutSeconds)
        assertEquals(5, config.poolMaxSize)
        assertEquals(5_000L, config.poolAcquireTimeoutMs)
    }

    @Test
    fun `reads every configured limit`() {
        val config = IngestionConfig.fromEnvironment(
            mapOf(
                "INGESTION_MAX_ITEMS" to "10",
                "INGESTION_MAX_BYTES" to "4096",
                "INGESTION_TIMEOUT_SECONDS" to "2",
                "DATABASE_POOL_MAX_SIZE" to "3",
                "DATABASE_POOL_ACQUIRE_TIMEOUT_MS" to "750",
            )::get,
        )

        assertEquals(10, config.maxItems)
        assertEquals(4096, config.maxBytes)
        assertEquals(2, config.timeoutSeconds)
        assertEquals(3, config.poolMaxSize)
        assertEquals(750L, config.poolAcquireTimeoutMs)
    }

    @Test
    fun `refuses to start on a limit that is not a number`() {
        val failure = assertThrows<IllegalStateException> {
            IngestionConfig.fromEnvironment(mapOf("INGESTION_MAX_ITEMS" to "many")::get)
        }

        assertEquals("Environment variable INGESTION_MAX_ITEMS is not a number", failure.message)
    }

    @Test
    fun `refuses to start on a limit outside its safe range`() {
        val failure = assertThrows<IllegalArgumentException> {
            IngestionConfig.fromEnvironment(mapOf("INGESTION_MAX_BYTES" to "0")::get)
        }

        assertEquals("Environment variable INGESTION_MAX_BYTES must be between 1024 and 67108864", failure.message)
    }

    @Test
    fun `refuses a timeout of zero, which would make every ingestion fail`() {
        assertThrows<IllegalArgumentException> {
            IngestionConfig.fromEnvironment(mapOf("INGESTION_TIMEOUT_SECONDS" to "0")::get)
        }
    }
}
