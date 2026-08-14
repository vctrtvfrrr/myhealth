package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService

// What the synchronization reports to the maintenance channel, in one place. The scope of an incident
// is the scope of the defect it names: a category is given only where fixing one category would leave
// the others alone. Anything wider is reported without one, so that a single thing to fix stays a
// single notification however many categories meet it.

/** The mapper does not interpret a constant Samsung Health reported, which only the mapper can fix. */
internal suspend fun MaintenanceService.reportUnknownEnums(capability: HealthCapability, record: SourceRecord) {
    capability.mapper.unknownEnums(record).forEach {
        report(MaintenanceCode.UNKNOWN_ENUM, capability.category, detail = it)
    }
}

/**
 * The API could not turn an observation into an Observed Record Version.
 *
 * The rejection codes are the detail because they are the contract's own vocabulary, they name which
 * mapper defect this is, and they echo nothing that was submitted.
 */
internal suspend fun MaintenanceService.reportUnmappableRecord(
    capability: HealthCapability,
    codes: List<RejectionCode>,
) = report(
    code = MaintenanceCode.UNMAPPABLE_RECORD,
    category = capability.category,
    detail = codes.joinToString(",") { it.wireValue },
)

/**
 * Samsung Health could not serve a read.
 *
 * An unrecognized outcome reaches this as [SamsungHealthAvailability.TemporarilyUnavailable], so a
 * condition that will pass on its own is never reported as a platform this build cannot support.
 */
internal suspend fun MaintenanceService.reportUnavailable(availability: SamsungHealthAvailability) = report(
    when (availability) {
        is SamsungHealthAvailability.Unsupported -> MaintenanceCode.UNSUPPORTED_PLATFORM
        else -> MaintenanceCode.SAMSUNG_HEALTH_UNREACHABLE
    },
)
