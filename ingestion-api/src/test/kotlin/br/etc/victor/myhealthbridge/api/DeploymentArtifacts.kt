package br.etc.victor.myhealthbridge.api

import java.io.File

/**
 * The deploy artifacts of the `myhealth` Application Stack, read the way the platform reads
 * them: the compose file, the workflow that invokes `codelab/deploy-stack`, and the `APPENV_*`
 * mapping that becomes the stack's `.env` on the VPS.
 */
object DeploymentArtifacts {

    val root: File = File(System.getProperty("repositoryRoot") ?: error("Missing repositoryRoot"))

    val composeFile: File = root.resolve("compose.yml")

    val compose: String = composeFile.readText()

    val workflow: String = root.resolve(".gitea/workflows/ci.yml").readText()

    /** What the deploy step hands the action: `APPENV_<name>` is written to the stack `.env` as `<name>`. */
    val stackConfiguration: Map<String, String> = Regex("""APPENV_([A-Z0-9_]+): *(.+)""")
        .findAll(workflow)
        .associate { match -> match.groupValues[1] to match.groupValues[2].trim().trim('"') }

    /** The environment the application reads to build its database configuration. */
    val databaseVariables: Set<String> = buildSet {
        DatabaseConfig.fromEnvironment { variable ->
            add(variable)
            "1"
        }
    }

    /** The value the CI secret or variable would render to, so a rendering can be exercised without one. */
    fun rendered(name: String): String {
        val declared = stackConfiguration.getValue(name)
        return if (declared.startsWith("\${{")) "rendered-${name.lowercase().replace('_', '-')}" else declared
    }
}
