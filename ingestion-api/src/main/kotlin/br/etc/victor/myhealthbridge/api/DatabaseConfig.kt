package br.etc.victor.myhealthbridge.api

class DatabaseConfig(
    host: String,
    port: Int,
    name: String,
    val user: String,
    val password: String,
) {
    val jdbcUrl: String = "jdbc:postgresql://$host:$port/$name"

    override fun toString(): String = "DatabaseConfig(jdbcUrl=$jdbcUrl, user=$user)"

    companion object {
        private const val DEFAULT_PORT = 5432

        fun fromEnvironment(environment: (String) -> String? = System::getenv): DatabaseConfig =
            DatabaseConfig(
                host = environment.required("DATABASE_HOST"),
                port = environment("DATABASE_PORT")?.ifBlank { null }?.toInt() ?: DEFAULT_PORT,
                name = environment.required("DATABASE_NAME"),
                user = environment.required("DATABASE_USER"),
                password = environment.required("DATABASE_PASS"),
            )

        /**
         * A blank value is a missing one: the deploy renders every unset CI secret as an empty
         * variable, so accepting it would start the API against configuration nobody supplied.
         */
        private fun ((String) -> String?).required(variable: String): String =
            this(variable)?.ifBlank { null } ?: error("Missing required environment variable $variable")
    }
}
