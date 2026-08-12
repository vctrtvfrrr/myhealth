package br.etc.victor.myhealthbridge.samsung

import android.content.Context
import br.etc.victor.myhealthbridge.health.AvailabilityResolution
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.Remediation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthGateway
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only place Samsung Health Data SDK types are used.
 *
 * The SDK reports read access as the currently granted subset of the permissions it is asked about,
 * so a category missing from the answer only means it is not granted now.
 */
class SamsungHealthDataGateway(
    private val context: Context,
    private val foregroundActivity: () -> android.app.Activity? = { ForegroundActivity.current },
) : SamsungHealthGateway {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }

    override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> = observing {
        store.getGrantedPermissions(readPermissions(HealthCategory.entries))
            .mapNotNullTo(mutableSetOf()) { categoriesByDataTypeName[it.dataType.name] }
    }

    override suspend fun requestReadPermissions(categories: Set<HealthCategory>): SamsungHealthOutcome<Unit> {
        val activity = foregroundActivity()
            ?: return SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("no_foreground_activity"))

        // The set the flow answers is dropped on purpose: the application re-queries afterwards
        // instead of trusting a result that an Activity recreation could take away.
        return when (val outcome = observing { store.requestPermissions(readPermissions(categories), activity) }) {
            is SamsungHealthOutcome.Failed -> outcome
            is SamsungHealthOutcome.Observed -> SamsungHealthOutcome.Observed(Unit)
        }
    }

    private inline fun <T> observing(operation: () -> T): SamsungHealthOutcome<T> =
        try {
            SamsungHealthOutcome.Observed(operation())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HealthDataException) {
            SamsungHealthOutcome.Failed(availabilityOf(failure))
        } catch (failure: RuntimeException) {
            SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable(failure.javaClass.simpleName))
        }

    private fun availabilityOf(failure: HealthDataException): SamsungHealthAvailability {
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

    private fun resolutionOf(failure: HealthDataException): AvailabilityResolution? {
        if (failure !is ResolvablePlatformException || !failure.hasResolution) return null
        return AvailabilityResolution { foregroundActivity()?.let(failure::resolve) }
    }
}
