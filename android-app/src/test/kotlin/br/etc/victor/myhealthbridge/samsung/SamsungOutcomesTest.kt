package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.health.Remediation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import com.samsung.android.sdk.health.data.error.AuthorizationException
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.InvalidRequestException
import com.samsung.android.sdk.health.data.error.PlatformInternalException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SamsungOutcomesTest {

    private val outcomes = SamsungOutcomes { null }

    @ParameterizedTest
    @ValueSource(
        ints = [
            ErrorCode.ERR_PLATFORM_NOT_INSTALLED,
            ErrorCode.ERR_OLD_VERSION_PLATFORM,
            ErrorCode.ERR_PLATFORM_DISABLED,
            ErrorCode.ERR_PLATFORM_NOT_INITIALIZED,
        ],
    )
    fun `asks for Samsung Health to be set up, offering the SDK's resolution`(code: Int) {
        val availability = outcomes.availabilityOf(ResolvablePlatformException(code, "setup", true))

        assertEquals(Remediation.SAMSUNG_HEALTH_SETUP, (availability as SamsungHealthAvailability.ActionRequired).remediation)
        assertNotNull(availability.resolution)
    }

    @Test
    fun `asks for Samsung Health to be set up when its own signature is not an official one`() {
        val availability = outcomes.availabilityOf(AuthorizationException(ErrorCode.ERR_INVALID_PLATFORM_SIGNATURE, "signature"))

        assertEquals(Remediation.SAMSUNG_HEALTH_SETUP, (availability as SamsungHealthAvailability.ActionRequired).remediation)
        assertNull(availability.resolution)
    }

    @ParameterizedTest
    @ValueSource(ints = [ErrorCode.ERR_ACCESS_CONTROL, ErrorCode.ERR_INVALID_CALLER])
    fun `says the application is not recognized when Samsung Health refuses it`(code: Int) {
        val availability = outcomes.availabilityOf(AuthorizationException(code, "refused"))

        assertEquals(
            Remediation.APPLICATION_NOT_RECOGNIZED,
            (availability as SamsungHealthAvailability.ActionRequired).remediation,
        )
    }

    /** A request this build got wrong is a bug to report, not a setting for the Data Owner to change. */
    @ParameterizedTest
    @ValueSource(ints = [ErrorCode.ERR_INVALID_UID, ErrorCode.ERR_INVALID_INPUT])
    fun `keeps a request it built wrong temporary rather than blaming the setup`(code: Int) {
        val availability = outcomes.availabilityOf(InvalidRequestException(code, "invalid"))

        assertEquals(SamsungHealthAvailability.TemporarilyUnavailable("samsung_error_$code"), availability)
    }

    @ParameterizedTest
    @ValueSource(ints = [ErrorCode.ERR_UNSUPPORTED_OPERATION, ErrorCode.ERR_CHILD_ACCOUNT_ACCESS])
    fun `reports a device that can never be served as unsupported`(code: Int) {
        assertEquals(
            SamsungHealthAvailability.Unsupported("samsung_error_$code"),
            outcomes.availabilityOf(AuthorizationException(code, "unsupported")),
        )
    }

    @Test
    fun `keeps an error it does not recognize temporary`() {
        assertEquals(
            SamsungHealthAvailability.TemporarilyUnavailable("samsung_error_9004"),
            outcomes.availabilityOf(PlatformInternalException(ErrorCode.ERR_CONNECTION_FAIL, "connection")),
        )
    }

    /** The code is what tells apart the conditions one message covers; the SDK's own text never leaves. */
    @Test
    fun `carries the code of the condition and none of the SDK's text`() {
        val availability = outcomes.availabilityOf(AuthorizationException(ErrorCode.ERR_ACCESS_CONTROL, "uid 10123 at 0x7f"))

        assertEquals("samsung_error_2003", availability.diagnosticCode)
    }

    @Test
    fun `names the exception when the SDK gives no code`() {
        val outcome = outcomes.observing { throw HealthDataException(null, "no code") }

        assertEquals(
            SamsungHealthAvailability.TemporarilyUnavailable("HealthDataException"),
            (outcome as SamsungHealthOutcome.Failed).availability,
        )
    }
}
