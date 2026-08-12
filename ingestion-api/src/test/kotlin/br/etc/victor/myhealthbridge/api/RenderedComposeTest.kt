package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The compose file as the VPS sees it: rendered by Compose itself, in a directory that holds the
 * `.env` the action writes from the `APPENV_*` variables, with `IMAGE_TAG` exported the way the
 * action exports it. Reading the source file would never prove that the interpolation resolves.
 */
@Tag("integration")
class RenderedComposeTest {

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    @TempDir
    private lateinit var stackDirectory: Path

    @Test
    fun `runs the published image at the deployed commit`() {
        assertEquals(
            "git.codelab.tec.br/vctrtvfrrr/myhealth-api:$commit",
            api().string("image"),
        )
    }

    @Test
    fun `hands the application every database variable, under the name it reads`() {
        val environment = api().getValue("environment").jsonObject

        DeploymentArtifacts.databaseVariables.forEach { variable ->
            assertEquals(
                DeploymentArtifacts.rendered(variable),
                environment[variable]?.jsonPrimitive?.contentOrNull,
                "the container never receives $variable",
            )
        }
    }

    @Test
    fun `routes one host, on the platform networks`() {
        val service = api()
        val rule = service.getValue("labels").jsonObject.string("traefik.http.routers.myhealth.rule")

        assertTrue(rule != null && Regex("""^Host\(`[a-z0-9.-]+`\)$""").matches(rule), "the router rule is $rule")
        assertEquals(setOf("web", "postgres"), service.getValue("networks").jsonObject.keys)
    }

    @Test
    fun `survives the VPS restarting, and never a schema it did not migrate`() {
        // Migrations run at startup and the process exits when they fail, so restarting is the whole
        // recovery story for a database that was not reachable yet.
        assertEquals("unless-stopped", api().string("restart"))
    }

    @Test
    fun `declares the platform networks as external`() {
        val networks = rendered().getValue("networks").jsonObject

        assertEquals(setOf("web", "postgres"), networks.keys)
        networks.forEach { (name, declaration) ->
            assertTrue(
                declaration.jsonObject["external"]?.jsonPrimitive?.contentOrNull == "true",
                "the stack would create its own $name network instead of joining the platform's",
            )
        }
    }

    @Test
    fun `expects exactly the one service the platform will watch for`() {
        assertEquals(setOf("api"), rendered().getValue("services").jsonObject.keys)
    }

    /**
     * A manual `docker compose` on the host — the decommissioning procedure runs one — must still
     * render without the action's `IMAGE_TAG`. It falls back to a tag the CI never publishes, so it
     * can fail on a missing image but never run some other commit under the current one's name.
     */
    @Test
    fun `renders without the deployed commit, onto a tag nobody publishes`() {
        val (status, output) = config(commit = null)

        assertEquals(0, status, "the compose file does not render without IMAGE_TAG: $output")
        assertTrue(
            Json.parseToJsonElement(output).jsonObject.getValue("services").jsonObject
                .getValue("api").jsonObject.string("image")!!.endsWith(":latest"),
        )
        assertFalse(DeploymentArtifacts.workflow.contains(":latest"), "the CI publishes the fallback tag")
    }

    private fun api(): JsonObject = rendered().getValue("services").jsonObject.getValue("api").jsonObject

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun rendered(): JsonObject {
        val (status, output) = config(commit)

        assertEquals(0, status, "the compose file does not render: $output")

        return Json.parseToJsonElement(output).jsonObject
    }

    /** Reproduces what the action does on the host: the app's `.env`, then `docker compose config`. */
    private fun config(commit: String?): Pair<Int, String> {
        val compose = stackDirectory.resolve("compose.yml").toFile()
        DeploymentArtifacts.composeFile.copyTo(compose, overwrite = true)
        stackDirectory.resolve(".env").toFile().writeText(
            DeploymentArtifacts.stackConfiguration.keys.joinToString("\n") { name ->
                "$name=${DeploymentArtifacts.rendered(name)}"
            },
        )

        val process = ProcessBuilder("docker", "compose", "-f", compose.absolutePath, "config", "--format", "json")
            .redirectErrorStream(true)
            .also { builder -> commit?.let { builder.environment()["IMAGE_TAG"] = it } }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "docker compose config never returned")

        return process.exitValue() to output
    }
}
