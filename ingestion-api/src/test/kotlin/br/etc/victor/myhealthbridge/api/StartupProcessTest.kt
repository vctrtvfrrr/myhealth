package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts the packaged distribution as a real process, which is the only place where the
 * "migrate before serving, or exit" sequence actually lives.
 */
@Tag("integration")
@Testcontainers
class StartupProcessTest {

    /** What Ktor logs the moment the engine binds the port, and therefore starts serving. */
    private val LISTENING = "Responding at"

    @Container
    private val postgres = PostgreSQLContainer("postgres:17")

    @TempDir
    private lateinit var workingDirectory: Path

    private val port = ServerSocket(0).use { it.localPort }

    @Test
    fun `serves health once migrations succeed`() {
        val api = start()

        try {
            assertEquals(HttpURLConnection.HTTP_OK, awaitHealth(api))
            assertTrue(output().contains(LISTENING), "the engine never announced itself as $LISTENING")
        } finally {
            api.destroy()
        }
    }

    @Test
    fun `logs the failing script and exits without ever opening the port`() {
        val api = start(extraClasspath = brokenMigrations())

        assertTrue(api.waitFor(2, TimeUnit.MINUTES), "the process never exited")
        assertEquals(1, api.exitValue())
        assertTrue(output().contains("V1__broken.sql"), "the log does not name the failing script")
        assertFalse(output().contains(LISTENING), "the engine started before the migration failed")
        assertThrows<ConnectException> { Socket("localhost", port).close() }
    }

    private fun start(extraClasspath: String? = null): Process {
        val distribution = System.getProperty("distributionDir")
        val classpath = listOfNotNull(extraClasspath, "$distribution/lib/*").joinToString(File.pathSeparator)

        val builder = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            classpath,
            "br.etc.victor.myhealthbridge.api.ApplicationKt",
        )
        builder.environment() += mapOf(
            "DATABASE_HOST" to postgres.host,
            "DATABASE_PORT" to postgres.firstMappedPort.toString(),
            "DATABASE_NAME" to postgres.databaseName,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASS" to postgres.password,
            "PORT" to port.toString(),
        )
        builder.redirectErrorStream(true)
        builder.redirectOutput(logFile())

        return builder.start()
    }

    private fun awaitHealth(api: Process): Int {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (System.nanoTime() < deadline) {
            check(api.isAlive) { "the process exited before serving:\n${output()}" }
            runCatching {
                val connection = URI("http://localhost:$port/health").toURL().openConnection() as HttpURLConnection
                return connection.responseCode
            }
            Thread.sleep(200)
        }
        throw AssertionError("the process never served /health:\n${output()}")
    }

    private fun brokenMigrations() = File(javaClass.getResource("/broken-migrations")!!.toURI()).absolutePath

    private fun logFile() = workingDirectory.resolve("api.log").toFile()

    private fun output() = logFile().takeIf { it.exists() }?.readText().orEmpty()
}
