package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The deploy artifacts against the CodeLab Application Stack contract.
 *
 * These assertions are the only place where a drift between what the application reads, what the CI
 * hands the platform and what the compose file declares is caught before a deploy discovers it.
 */
class DeploymentContractTest {

    private val compose = DeploymentArtifacts.compose
    private val workflow = DeploymentArtifacts.workflow
    private val stackConfiguration = DeploymentArtifacts.stackConfiguration

    @Test
    fun `the stack supplies every database variable the application reads`() {
        val missing = DeploymentArtifacts.databaseVariables - stackConfiguration.keys

        assertEquals(emptySet<String>(), missing, "the deploy declares no APPENV_ for $missing")
    }

    @Test
    fun `only the password can reach the API blank`() {
        // A repository variable nobody set renders empty, and the API refuses to start on a blank
        // value, so everything a repository may leave unset carries a fallback. The password cannot:
        // it has no default, which is why the deploy checks it instead.
        val blankable = (stackConfiguration - "DATABASE_PASS")
            .filterValues { it.startsWith("\${{") && !it.contains("||") }

        assertEquals(emptyMap<String, String>(), blankable, "an unset variable would reach the API blank")
        assertTrue(workflow.contains("for name in DATABASE_PASS;"), "the deploy does not check the password")
    }

    @Test
    fun `the stack supplies every variable the compose file interpolates`() {
        // IMAGE_TAG is the action's own input, not part of the stack's configuration.
        val interpolated = Regex("""\$\{([A-Z][A-Z0-9_]*)""")
            .findAll(compose)
            .map { it.groupValues[1] }
            .toSet() - "IMAGE_TAG"

        assertEquals(emptySet<String>(), interpolated - stackConfiguration.keys)
    }

    @Test
    fun `the deploy goes through the shared action, under a name the platform accepts`() {
        assertTrue(
            workflow.contains("uses: https://git.codelab.tec.br/codelab/deploy-stack@master"),
            "the delivery does not go through codelab/deploy-stack",
        )

        val app = Regex("""app: (\S+)""").find(workflow)?.groupValues?.get(1)

        assertEquals("myhealth", app)
        assertTrue(Regex("^[a-z0-9][a-z0-9-]{0,31}$").matches(app!!), "the action rejects the app name $app")
    }

    @Test
    fun `the stack runs the image the CI publishes, identified by the commit SHA`() {
        val image = Regex("""IMAGE: (\S+)""").find(workflow)?.groupValues?.get(1)

        assertEquals("git.codelab.tec.br/vctrtvfrrr/myhealth-api", image)
        assertTrue(workflow.contains("TAG: \${{ github.sha }}"), "the image tag is not the commit SHA")
        assertTrue(workflow.contains("""docker push "${'$'}{IMAGE}:${'$'}{TAG}""""), "the SHA tag is not published")
        assertTrue(workflow.contains("image-tag: \${{ env.TAG }}"), "the deploy runs a tag other than the built one")
        assertTrue(compose.contains("image: $image:\${IMAGE_TAG"), "the compose does not run the published image")
    }

    @Test
    fun `the delivery waits for the whole suite`() {
        assertTrue(workflow.contains("needs: build"), "the deploy job does not wait for the build job")
        assertTrue(workflow.contains("./gradlew check"), "the build job does not run the suite")
    }

    @Test
    fun `the image serves the port the stack routes to`() {
        val dockerfile = DeploymentArtifacts.root.resolve("ingestion-api/Dockerfile").readText()
        val port = Regex("""EXPOSE (\d+)""").find(dockerfile)?.groupValues?.get(1)

        assertTrue(dockerfile.contains("USER 1000"), "the platform requires uid 1000")
        assertTrue(compose.contains("loadbalancer.server.port=$port"), "Traefik routes to a port the image does not serve")
        assertTrue(compose.contains("http://127.0.0.1:$port/ready"), "the healthcheck probes a port the image does not serve")
    }

    @Test
    fun `no credential and no VPS address is versioned`() {
        val password = stackConfiguration["DATABASE_PASS"]

        assertTrue(
            password != null && password.startsWith("\${{ secrets."),
            "the database password is versioned as the literal $password",
        )
        assertEquals(
            emptyList<String>(),
            (stackConfiguration - "DATABASE_PASS").values.filter { it.contains("secrets.") },
            "the deploy carries a secret it does not need",
        )

        // The stack reaches the shared PostgreSQL by its network alias, so no address of the VPS
        // itself is versioned; the public host of the application is, and is not one.
        assertEquals("postgres", stackConfiguration["DATABASE_HOST"])
    }

    @Test
    fun `the observability generation is empty`() {
        val observability = DeploymentArtifacts.root.resolve("observability")

        assertTrue(observability.isDirectory, "the empty generation is not declared anywhere")
        assertEquals(
            emptyList<String>(),
            observability.walkTopDown().filter { it.extension == "json" }.map { it.name }.toList(),
            "the first version publishes an empty observability generation on purpose",
        )
    }
}
