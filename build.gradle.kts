plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Without a bound every test JVM may take a quarter of the host's memory, and the CI runner shares
// its host with the deployed stacks, with no swap to absorb a spike.
subprojects {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "1g"
    }
}

tasks.register<Exec>("devUp") {
    group = "docker"
    description = "Rebuilds the ingestion API image and starts the local development stack."
    dependsOn(":ingestion-api:buildImage")
    commandLine("docker", "compose", "-f", "compose.dev.yml", "up")
}
