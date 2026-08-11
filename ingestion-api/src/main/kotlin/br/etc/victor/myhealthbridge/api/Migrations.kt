package br.etc.victor.myhealthbridge.api

import org.flywaydb.core.Flyway
import javax.sql.DataSource

fun migrate(config: DatabaseConfig) {
    Flyway.configure()
        .dataSource(config.jdbcUrl, config.user, config.password)
        .load()
        .migrate()
}

/**
 * Whether the API may take traffic: it can reach the database and the schema it was built against is
 * fully applied.
 *
 * A live process that lost the database, or one whose schema is behind or left in a failed state, must
 * be taken out of rotation instead of ingesting against an incompatible schema.
 */
fun isReady(dataSource: DataSource): Boolean = runCatching {
    val info = Flyway.configure().dataSource(dataSource).load().info()
    info.pending().isEmpty() && info.all().none { it.state.isFailed }
}.getOrDefault(false)
