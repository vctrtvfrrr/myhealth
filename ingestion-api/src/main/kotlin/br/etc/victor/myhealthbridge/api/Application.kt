package br.etc.victor.myhealthbridge.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * One artifact, two jobs: it serves ingestion, or it runs a device subcommand and exits.
 *
 * Provisioning never opens the HTTP port, which keeps token management off the public surface of a
 * personal instance.
 */
fun main(arguments: Array<String>) {
    if (arguments.isNotEmpty()) administer(arguments.toList()) else serve()
}

private val startupLogger = LoggerFactory.getLogger("br.etc.victor.myhealthbridge.api.Startup")

private fun serve() {
    val startup = runCatching {
        val database = DatabaseConfig.fromEnvironment()
        val ingestion = IngestionConfig.fromEnvironment()
        migrate(database)
        Startup(ingestion, pool(database, ingestion))
    }.getOrElse { failure ->
        startupLogger.error("Startup aborted before serving any request", failure)
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread(startup.pool::close))

    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) {
        module(startup.pool, startup.ingestion)
    }.start(wait = true)
}

private fun administer(arguments: List<String>) {
    try {
        val database = DatabaseConfig.fromEnvironment()
        migrate(database)
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
            DeviceAdmin.run(arguments, connection, ::println)
        }
    } catch (failure: Exception) {
        System.err.println(failure.message ?: DeviceAdmin.USAGE)
        exitProcess(1)
    }
}

private class Startup(val ingestion: IngestionConfig, val pool: HikariDataSource)

/** The pool opens only after migrations succeed, and only before the HTTP port is opened. */
private fun pool(database: DatabaseConfig, ingestion: IngestionConfig): HikariDataSource {
    val settings = HikariConfig().apply {
        jdbcUrl = database.jdbcUrl
        username = database.user
        password = database.password
        maximumPoolSize = ingestion.poolMaxSize
        connectionTimeout = ingestion.poolAcquireTimeoutMs
        poolName = "ingestion"
    }
    return HikariDataSource(settings)
}

fun Application.module(dataSource: DataSource, config: IngestionConfig) {
    val endpoint = IngestionEndpoint(
        devices = IngestionDevices(dataSource, config.timeoutSeconds),
        store = IngestionStore(dataSource, config.timeoutSeconds),
        config = config,
    )

    routing {
        liveness()
        readiness(dataSource)
        ingestion(endpoint)
    }
}
