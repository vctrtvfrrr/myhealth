package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DatabaseConfigTest {

    private val completeEnvironment = mapOf(
        "DATABASE_HOST" to "db.example",
        "DATABASE_PORT" to "6543",
        "DATABASE_NAME" to "myhealth",
        "DATABASE_USER" to "myhealth",
        "DATABASE_PASS" to "s3cr3t",
    )

    @Test
    fun `builds a jdbc url without credentials`() {
        val config = DatabaseConfig.fromEnvironment(completeEnvironment::get)

        assertEquals("jdbc:postgresql://db.example:6543/myhealth", config.jdbcUrl)
    }

    @Test
    fun `defaults the port to 5432`() {
        val config = DatabaseConfig.fromEnvironment((completeEnvironment - "DATABASE_PORT")::get)

        assertEquals("jdbc:postgresql://db.example:5432/myhealth", config.jdbcUrl)
    }

    @Test
    fun `names the missing variable`() {
        val failure = assertThrows<IllegalStateException> {
            DatabaseConfig.fromEnvironment((completeEnvironment - "DATABASE_NAME")::get)
        }

        assertEquals("Missing required environment variable DATABASE_NAME", failure.message)
    }

    @Test
    fun `treats a blank value as a missing variable`() {
        val failure = assertThrows<IllegalStateException> {
            DatabaseConfig.fromEnvironment((completeEnvironment + ("DATABASE_PASS" to ""))::get)
        }

        assertEquals("Missing required environment variable DATABASE_PASS", failure.message)
    }

    @Test
    fun `defaults the port when it comes blank`() {
        val config = DatabaseConfig.fromEnvironment((completeEnvironment + ("DATABASE_PORT" to ""))::get)

        assertEquals("jdbc:postgresql://db.example:5432/myhealth", config.jdbcUrl)
    }

    @Test
    fun `keeps the password out of its own text representation`() {
        val config = DatabaseConfig.fromEnvironment(completeEnvironment::get)

        assertFalse(config.toString().contains("s3cr3t"))
    }
}
