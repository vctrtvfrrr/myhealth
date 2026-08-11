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
                port = environment("DATABASE_PORT")?.toInt() ?: DEFAULT_PORT,
                name = environment.required("DATABASE_NAME"),
                user = environment.required("DATABASE_USER"),
                password = environment.required("DATABASE_PASS"),
            )

        private fun ((String) -> String?).required(variable: String): String =
            this(variable) ?: error("Missing required environment variable $variable")
    }
}
