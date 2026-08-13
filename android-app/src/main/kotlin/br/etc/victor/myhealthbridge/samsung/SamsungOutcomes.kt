package br.etc.victor.myhealthbridge.samsung

import android.app.Activity
import br.etc.victor.myhealthbridge.health.AvailabilityResolution
import br.etc.victor.myhealthbridge.health.Remediation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns the outcome of a Samsung Health operation into a Samsung Health Availability.
 *
 * Every adapter in front of the SDK reads failures the same way, so that reading records and querying
 * permissions can never disagree about whether the platform is available.
 */
internal class SamsungOutcomes(private val foregroundActivity: () -> Activity?) {

    inline fun <T> observing(operation: () -> T): SamsungHealthOutcome<T> =
        try {
            SamsungHealthOutcome.Observed(operation())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HealthDataException) {
            SamsungHealthOutcome.Failed(availabilityOf(failure))
        } catch (failure: RuntimeException) {
            SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable(failure.javaClass.simpleName))
        }

    fun availabilityOf(failure: HealthDataException): SamsungHealthAvailability {
        val diagnosticCode = failure.errorCode?.let { "samsung_error_$it" } ?: failure.javaClass.simpleName
        return when (failure.errorCode) {
            ErrorCode.ERR_PLATFORM_NOT_INSTALLED,
            ErrorCode.ERR_OLD_VERSION_PLATFORM,
            ErrorCode.ERR_PLATFORM_DISABLED,
            ErrorCode.ERR_PLATFORM_NOT_INITIALIZED,
            -> SamsungHealthAvailability.ActionRequired(
                remediation = Remediation.SAMSUNG_HEALTH_SETUP,
                resolution = resolutionOf(failure),
                diagnosticCode = diagnosticCode,
            )

            ErrorCode.ERR_INVALID_PLATFORM_SIGNATURE,
            ErrorCode.ERR_INVALID_CALLER,
            ErrorCode.ERR_INVALID_UID,
            ErrorCode.ERR_ACCESS_CONTROL,
            ErrorCode.ERR_INVALID_INPUT,
            -> SamsungHealthAvailability.ActionRequired(
                remediation = Remediation.APPLICATION_NOT_RECOGNIZED,
                resolution = resolutionOf(failure),
                diagnosticCode = diagnosticCode,
            )

            ErrorCode.ERR_UNSUPPORTED_OPERATION,
            ErrorCode.ERR_CHILD_ACCOUNT_ACCESS,
            -> SamsungHealthAvailability.Unsupported(diagnosticCode)

            else -> SamsungHealthAvailability.TemporarilyUnavailable(diagnosticCode)
        }
    }

    fun resolutionOf(failure: HealthDataException): AvailabilityResolution? {
        if (failure !is ResolvablePlatformException || !failure.hasResolution) return null
        return AvailabilityResolution { foregroundActivity()?.let(failure::resolve) }
    }
}
