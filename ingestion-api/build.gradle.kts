plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "br.etc.victor.myhealthbridge.api.ApplicationKt"
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.flyway.core)
    implementation(libs.hikaricp)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The tests read these files from the repository, so a change to them alone must run the tests again.
val repositoryInputs = files(rootDir.resolve("observability"), rootDir.resolve("docs/sql"))

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    systemProperty("repositoryRoot", rootDir.absolutePath)
    inputs.files(repositoryInputs).withPathSensitivity(PathSensitivity.RELATIVE)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the tests that require a Docker daemon."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // Every class brings its own PostgreSQL, API process, free port and directory, so classes run
    // side by side. Two at a time, because the runner shares its host with the deployed stacks.
    maxParallelForks = 2
    dependsOn(tasks.installDist)
    systemProperty("distributionDir", layout.buildDirectory.dir("install/${project.name}").get().asFile.absolutePath)
    systemProperty("repositoryRoot", rootDir.absolutePath)
    inputs.files(repositoryInputs).withPathSensitivity(PathSensitivity.RELATIVE)
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.register<JavaExec>("grafanaPreview") {
    group = "observability"
    description = "Writes the dashboards as the platform saves them, with the provisioning of the local preview."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "br.etc.victor.myhealthbridge.api.GrafanaPreviewKt"
    args(
        rootDir.resolve("observability/dashboards").absolutePath,
        rootProject.layout.buildDirectory.dir("grafana-preview").get().asFile.absolutePath,
    )
}

tasks.register<Exec>("buildImage") {
    group = "docker"
    description = "Builds the ingestion API image from the distribution produced by installDist."
    dependsOn(tasks.installDist)
    workingDir = projectDir
    val image = providers.gradleProperty("myhealth.image").getOrElse("myhealth-api:local")
    val labels = mapOf(
        "org.opencontainers.image.version" to providers.gradleProperty("myhealth.version").orNull,
        "org.opencontainers.image.revision" to providers.gradleProperty("myhealth.revision").orNull,
    ).filterValues { it != null }
    commandLine(
        listOf("docker", "build", "--tag", image) +
            labels.flatMap { (name, value) -> listOf("--label", "$name=$value") } +
            ".",
    )
}
