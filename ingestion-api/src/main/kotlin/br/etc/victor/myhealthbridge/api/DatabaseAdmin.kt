package br.etc.victor.myhealthbridge.api

import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Purging everything the API keeps, as a subcommand of the same artifact: the Data Owner's final
 * control over the remote copy.
 *
 * There is no selective counterpart on purpose. Deleting one Health Record would not stop the next
 * import from bringing it back, so the only deletion offered is the one after which the import
 * starting over is the whole point.
 */
object DatabaseAdmin {

    const val USAGE: String = "usage: database purge <database name>"

    const val PURGED: String = "purged"

    /**
     * Never migrates, before or after: purging must work on a schema left in a failed state, and the
     * API that starts next is what migrates from empty.
     */
    fun run(arguments: List<String>, config: DatabaseConfig, print: (String) -> Unit) {
        require(arguments.size == 3 && arguments[1] == "purge") { USAGE }
        require(arguments[2] == config.name) {
            "refusing to purge: the confirmation does not name the configured database ${config.name}"
        }

        // Flyway cleans only the schema it migrates, and keeps a schema it did not create itself, so a
        // read_model left behind empty would make the next startup fail on creating it again.
        DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { connection ->
            connection.createStatement().use { it.execute("drop schema if exists read_model cascade") }
        }
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.user, config.password)
            .cleanDisabled(false)
            .load()
            .clean()
        print(PURGED)
    }
}
