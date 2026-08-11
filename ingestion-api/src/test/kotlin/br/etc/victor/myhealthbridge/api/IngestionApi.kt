package br.etc.victor.myhealthbridge.api

import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * The packaged API as a real process in front of a real PostgreSQL.
 *
 * This is the seam the ingestion behaviour is verified through: the distribution the image ships,
 * started over HTTP, checked by reading the database it wrote to. Nothing here reaches into a
 * repository or into Flyway.
 */
class IngestionApi(
    private val postgres: PostgreSQLContainer<*>,
    private val workingDirectory: Path,
    private val settings: Map<String, String> = emptyMap(),
    private val name: String = "api",
) {
    val port: Int = ServerSocket(0).use { it.localPort }

    private val logFile: File get() = workingDirectory.resolve("$name.log").toFile()

    private var process: Process? = null

    fun start(): IngestionApi {
        process = launch(emptyList(), logFile)
        awaitLiveness()
        return this
    }

    fun stop() {
        process?.destroy()
        process?.waitFor(1, TimeUnit.MINUTES)
        process = null
    }

    fun restart() {
        stop()
        start()
    }

    fun logs(): String = logFile.takeIf { it.exists() }?.readText().orEmpty()

    /** Runs a device subcommand in its own process and returns everything it printed. */
    fun device(vararg arguments: String): String {
        val output = workingDirectory.resolve("$name-device-${arguments.joinToString("-")}.log").toFile()
        val command = launch(arguments.toList(), output)
        check(command.waitFor(2, TimeUnit.MINUTES)) { "the device subcommand never exited" }
        check(command.exitValue() == 0) { "the device subcommand failed: ${output.readText()}" }
        return output.readText()
    }

    fun provision(label: String): String = tokenIn(device("device", "create", label))

    fun rotate(label: String): String = tokenIn(device("device", "rotate", label))

    fun tokenIn(output: String): String = output.lineSequence()
        .first { it.startsWith(DeviceAdmin.TOKEN_PREFIX) }
        .removePrefix(DeviceAdmin.TOKEN_PREFIX)
        .trim()

    fun get(path: String): Response = call(path, "GET", null, null, emptyMap(), false)

    fun post(
        path: String = "/ingestions",
        body: ByteArray,
        token: String?,
        contentType: String? = "application/json",
        headers: Map<String, String> = emptyMap(),
        chunked: Boolean = false,
    ): Response = call(path, "POST", body, token?.let { "Bearer $it" }, headers + contentTypeOf(contentType), chunked)

    fun postBatch(body: String, token: String?): Response = post(body = body.toByteArray(), token = token)

    fun <T> query(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun contentTypeOf(contentType: String?) =
        contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap()

    private fun call(
        path: String,
        method: String,
        body: ByteArray?,
        authorization: String?,
        headers: Map<String, String>,
        chunked: Boolean,
    ): Response {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

        if (body != null) {
            connection.doOutput = true
            if (chunked) connection.setChunkedStreamingMode(CHUNK_BYTES)
            connection.outputStream.use { it.write(body) }
        }

        val status = connection.responseCode
        val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
        return Response(status, text, connection.contentType)
    }

    private fun launch(arguments: List<String>, output: File): Process {
        val distribution = System.getProperty("distributionDir")
        val builder = ProcessBuilder(
            listOfNotNull(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                "$distribution/lib/*",
                "br.etc.victor.myhealthbridge.api.ApplicationKt",
            ) + arguments,
        )
        builder.environment() += mapOf(
            "DATABASE_HOST" to postgres.host,
            "DATABASE_PORT" to postgres.firstMappedPort.toString(),
            "DATABASE_NAME" to postgres.databaseName,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASS" to postgres.password,
            "PORT" to port.toString(),
        ) + settings
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(output))
        return builder.start()
    }

    private fun awaitLiveness() {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (System.nanoTime() < deadline) {
            check(process!!.isAlive) { "the process exited before serving:\n${logs()}" }
            if (runCatching { get("/health").status }.getOrNull() == 200) return
            Thread.sleep(200)
        }
        throw AssertionError("the process never served /health:\n${logs()}")
    }

    data class Response(val status: Int, val body: String, val contentType: String?)

    private companion object {
        const val CHUNK_BYTES = 8 * 1024
    }
}
