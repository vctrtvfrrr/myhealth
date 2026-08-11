package br.etc.victor.myhealthbridge.api

import org.flywaydb.core.Flyway

fun migrate(config: DatabaseConfig) {
    Flyway.configure()
        .dataSource(config.jdbcUrl, config.user, config.password)
        .load()
        .migrate()
}
