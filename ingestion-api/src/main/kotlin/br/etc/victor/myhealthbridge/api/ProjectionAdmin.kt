package br.etc.victor.myhealthbridge.api

import java.sql.Connection

/**
 * Rebuilding the projection from the preserved envelopes, as a subcommand of the same artifact.
 *
 * The projection is derived data, so it must be regenerable without the Samsung Health: this is how
 * an operator recovers it after a projection bug or a change to what "current" means, and how the
 * derivation is proved to depend on nothing that was not preserved.
 */
object ProjectionAdmin {

    const val USAGE: String = "usage: projection rebuild"

    const val PROJECTED_PREFIX: String = "projected="

    fun run(arguments: List<String>, connection: Connection, print: (String) -> Unit) {
        require(arguments.size == 2 && arguments[1] == "rebuild") { USAGE }

        connection.autoCommit = false
        try {
            // Discarding first is what makes this a rebuild rather than an update: whatever the table
            // held cannot survive into the result and pass as derived.
            connection.createStatement().use { it.executeUpdate("delete from current_health_record") }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select project_current_health_record(array(select id from health_record_identity))",
                ).use { row ->
                    check(row.next()) { "the projection returned no row" }
                    print(PROJECTED_PREFIX + row.getInt(1))
                }
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }
}
