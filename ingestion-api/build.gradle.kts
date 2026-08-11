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
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the tests that require a Docker daemon."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    dependsOn(tasks.installDist)
    systemProperty("distributionDir", layout.buildDirectory.dir("install/${project.name}").get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.register<Exec>("buildImage") {
    group = "docker"
    description = "Builds the ingestion API image from the distribution produced by installDist."
    dependsOn(tasks.installDist)
    workingDir = projectDir
    commandLine("docker", "build", "--tag", "myhealthbridge-api:local", ".")
}
