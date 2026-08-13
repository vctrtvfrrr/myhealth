package br.etc.victor.myhealthbridge.samsung

import android.content.Context
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthGateway
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore

/**
 * The only place Samsung Health Data SDK types are used for permissions.
 *
 * The SDK reports read access as the currently granted subset of the permissions it is asked about,
 * so a category missing from the answer only means it is not granted now.
 */
class SamsungHealthDataGateway(
    private val context: Context,
    private val foregroundActivity: () -> android.app.Activity? = { ForegroundActivity.current },
) : SamsungHealthGateway {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }

    private val outcomes = SamsungOutcomes(foregroundActivity)

    override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> = outcomes.observing {
        store.getGrantedPermissions(readPermissions(HealthCategory.entries))
            .mapNotNullTo(mutableSetOf()) { categoriesByDataTypeName[it.dataType.name] }
    }

    override suspend fun requestReadPermissions(categories: Set<HealthCategory>): SamsungHealthOutcome<Unit> {
        val activity = foregroundActivity()
            ?: return SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("no_foreground_activity"))

        // The set the flow answers is dropped on purpose: the application re-queries afterwards
        // instead of trusting a result that an Activity recreation could take away.
        return when (val outcome = outcomes.observing { store.requestPermissions(readPermissions(categories), activity) }) {
            is SamsungHealthOutcome.Failed -> outcome
            is SamsungHealthOutcome.Observed -> SamsungHealthOutcome.Observed(Unit)
        }
    }
}
