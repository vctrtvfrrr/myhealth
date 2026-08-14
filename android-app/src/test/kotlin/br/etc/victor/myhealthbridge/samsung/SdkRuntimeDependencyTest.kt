package br.etc.victor.myhealthbridge.samsung

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * What the Samsung Health SDK needs at runtime and does not ask for.
 *
 * The SDK ships as a bare AAR, so it carries no dependency metadata: a library it uses is in the APK
 * only because this build declares it. Nothing else notices when one is missing — the code compiles,
 * every test that does not touch the SDK passes, and the failure appears on a phone as an SDK class
 * that cannot be defined at all, because the interface it implements is absent.
 *
 * Loading the classes is the check. A name that no longer resolves is either a dependency that was
 * dropped or one an SDK upgrade renamed, and both have to be answered before a build reaches a device.
 */
class SdkRuntimeDependencyTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Every request the SDK builds is parcelable through this, including the reads.
            "kotlinx.parcelize.Parceler",
            "com.google.gson.Gson",
        ],
    )
    fun `carries what the SDK needs at runtime`(className: String) {
        assertDoesNotThrow { Class.forName(className) }
    }
}
