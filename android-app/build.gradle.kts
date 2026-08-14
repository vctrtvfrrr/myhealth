import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Samsung Health Data SDK is not redistributable, so the artifact stays out of Git and every
// build proves it is the pinned one before compiling against it.
val samsungHealthSdk = layout.projectDirectory.file("libs/samsung-health-data-api-1.1.0.aar")
val samsungHealthSdkSha256 = "f5d3d83cf00b97d0bb1b1db4da076e861eb1c3e6e704d89a34e68909d2f38654"

val verifySamsungHealthSdk = tasks.register("verifySamsungHealthSdk") {
    group = "verification"
    description = "Checks that the pinned Samsung Health Data SDK is installed under android-app/libs."

    val artifact = samsungHealthSdk.asFile
    val expected = samsungHealthSdkSha256
    val install = "./gradlew :android-app:installSamsungHealthSdk -Psamsung.health.sdk=<path to the downloaded AAR>"

    doLast {
        if (!artifact.exists()) {
            throw GradleException(
                "Samsung Health Data SDK v1.1.0 is missing at ${artifact.path}.\n" +
                    "Download it from https://developer.samsung.com/health/data/overview.html and run:\n  $install",
            )
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException(
                "Samsung Health Data SDK at ${artifact.path} has SHA-256 $actual, expected $expected.\n" +
                    "Replace it with the v1.1.0 artifact from https://developer.samsung.com/health/data/overview.html.",
            )
        }
    }
}

tasks.register("installSamsungHealthSdk") {
    group = "build setup"
    description = "Copies the downloaded Samsung Health Data SDK into android-app/libs."

    val source = providers.gradleProperty("samsung.health.sdk")
    val target = samsungHealthSdk.asFile

    doLast {
        val path = source.orNull
            ?: throw GradleException("Point at the downloaded artifact with -Psamsung.health.sdk=<path to the AAR>.")
        val downloaded = File(path)
        if (!downloaded.isFile) throw GradleException("No Samsung Health Data SDK artifact at $path.")

        target.parentFile.mkdirs()
        downloaded.copyTo(target, overwrite = true)
    }
}

// The release APK is installed by hand over the previous one, so it has to carry the same key every
// time: a different signature makes Android refuse the update and the only way through would be an
// uninstall, which takes the outbox with it. The keystore and its credentials never enter Git.
val releaseSigningEnvironment = listOf(
    "MYHEALTH_RELEASE_KEYSTORE",
    "MYHEALTH_RELEASE_KEYSTORE_PASSWORD",
    "MYHEALTH_RELEASE_KEY_ALIAS",
    "MYHEALTH_RELEASE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).map(String::trim).filter(String::isNotEmpty) }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks that the release signing key is configured in the environment."

    val environment = releaseSigningEnvironment

    doLast {
        val missing = environment.filterValues { !it.isPresent }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The release build is signed with the stable key, and ${missing.joinToString()} " +
                    "${if (missing.size == 1) "is" else "are"} not set.\n" +
                    "See the release APK section of the README for where the keystore lives and how to point at it.",
            )
        }
        val keystore = File(environment.getValue("MYHEALTH_RELEASE_KEYSTORE").get())
        if (!keystore.isFile) {
            throw GradleException("No release keystore at ${keystore.path}.")
        }
    }
}

android {
    namespace = "br.etc.victor.myhealthbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.etc.victor.myhealthbridge"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseSigningEnvironment.getValue("MYHEALTH_RELEASE_KEYSTORE").orNull?.let(::File)
            storePassword = releaseSigningEnvironment.getValue("MYHEALTH_RELEASE_KEYSTORE_PASSWORD").orNull
            keyAlias = releaseSigningEnvironment.getValue("MYHEALTH_RELEASE_KEY_ALIAS").orNull
            keyPassword = releaseSigningEnvironment.getValue("MYHEALTH_RELEASE_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.named("preBuild") {
    dependsOn(verifySamsungHealthSdk)
}

// The release variant registers its anchor task after this script is evaluated.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":health-permissions"))
    implementation(project(":maintenance"))
    implementation(project(":health-sync"))
    implementation(files(samsungHealthSdk))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
