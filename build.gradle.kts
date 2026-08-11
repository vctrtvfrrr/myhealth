plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register<Exec>("devUp") {
    group = "docker"
    description = "Rebuilds the ingestion API image and starts the local development stack."
    dependsOn(":ingestion-api:buildImage")
    commandLine("docker", "compose", "-f", "compose.dev.yml", "up")
}
