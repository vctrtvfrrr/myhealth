package br.etc.victor.myhealthbridge.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.sql.DataSource

/**
 * The provisioned devices allowed to ingest.
 *
 * Only the digest of a token is ever stored, so a leaked database dump reveals no usable credential.
 */
class IngestionDevices(private val dataSource: DataSource, private val timeoutSeconds: Int) {

    /** Returns the internal device id, or null for a token that is missing, unknown or revoked. */
    fun authenticate(token: String): Long? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select id from ingestion_device where token_digest = ? and revoked_at is null",
            ).use { statement ->
                statement.queryTimeout = timeoutSeconds
                statement.setBytes(1, digestOf(token))
                statement.executeQuery().use { row ->
                    return if (row.next()) row.getLong(1) else null
                }
            }
        }
    }

    companion object {
        fun digestOf(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

        fun newToken(): String {
            val secret = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(secret)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        }

        private const val TOKEN_BYTES = 32
    }
}

/**
 * Provisioning, rotation and revocation, as subcommands of the same artifact.
 *
 * Keeping them out of HTTP is what keeps the public surface of a personal instance down to ingestion
 * and health checks.
 */
object DeviceAdmin {

    const val USAGE: String = "usage: device (create|rotate|revoke) <label>"

    /** The prefix that carries the one and only time a token is readable. */
    const val TOKEN_PREFIX: String = "token="

    fun run(arguments: List<String>, connection: Connection, print: (String) -> Unit) {
        require(arguments.size == 3 && arguments[0] == "device") { USAGE }
        val label = arguments[2]

        when (arguments[1]) {
            "create" -> print(TOKEN_PREFIX + create(connection, label))
            "rotate" -> print(TOKEN_PREFIX + rotate(connection, label))
            "revoke" -> {
                revoke(connection, label)
                print("revoked")
            }

            else -> error(USAGE)
        }
    }

    private fun create(connection: Connection, label: String): String {
        val token = IngestionDevices.newToken()
        connection.prepareStatement(
            "insert into ingestion_device (device_label, token_digest, created_at) values (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, label)
            statement.setBytes(2, IngestionDevices.digestOf(token))
            statement.setObject(3, Instant.now().atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
        return token
    }

    /**
     * Rotation replaces the digest in place, so the previous token stops working the moment the new
     * one exists. It also clears a revocation, which is how an operator recovers a compromised device
     * without provisioning a new one.
     */
    private fun rotate(connection: Connection, label: String): String {
        val token = IngestionDevices.newToken()
        connection.prepareStatement(
            "update ingestion_device set token_digest = ?, revoked_at = null where device_label = ?",
        ).use { statement ->
            statement.setBytes(1, IngestionDevices.digestOf(token))
            statement.setString(2, label)
            check(statement.executeUpdate() == 1) { "no ingestion device carries that label" }
        }
        return token
    }

    private fun revoke(connection: Connection, label: String) {
        connection.prepareStatement(
            "update ingestion_device set revoked_at = ? where device_label = ? and revoked_at is null",
        ).use { statement ->
            statement.setObject(1, Instant.now().atOffset(ZoneOffset.UTC))
            statement.setString(2, label)
            check(statement.executeUpdate() == 1) { "no active ingestion device carries that label" }
        }
    }
}
