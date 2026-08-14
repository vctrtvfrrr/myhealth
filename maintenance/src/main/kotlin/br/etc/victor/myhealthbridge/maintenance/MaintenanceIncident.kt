package br.etc.victor.myhealthbridge.maintenance

import androidx.annotation.StringRes
import br.etc.victor.myhealthbridge.health.HealthCategory
import java.time.Duration
import java.time.Instant

/**
 * A condition the code cannot resolve on its own, named by what has to be fixed.
 *
 * The code names the defect and never the occurrence, which is what lets the hourly synchronization
 * meet the same condition again without telling the Data Owner a second time.
 */
enum class MaintenanceCode(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val action: Int,
    /**
     * Whether the condition is expected to pass without anyone touching the code.
     *
     * A transient one is still recorded from the first occurrence, because the moment it started is
     * what decides when it stops being transient, but it is only notified once it has outlasted
     * [MaintenancePolicy.transientGrace].
     */
    val transient: Boolean = false,
) {
    UNMAPPABLE_RECORD(
        id = "unmappable_record",
        title = R.string.maintenance_unmappable_record,
        action = R.string.maintenance_unmappable_record_action,
    ),

    UNKNOWN_ENUM(
        id = "unknown_enum",
        title = R.string.maintenance_unknown_enum,
        action = R.string.maintenance_unknown_enum_action,
    ),

    CONTRACT_INCOMPATIBLE(
        id = "contract_incompatible",
        title = R.string.maintenance_contract_incompatible,
        action = R.string.maintenance_contract_incompatible_action,
    ),

    PERMISSION_REVOKED(
        id = "permission_revoked",
        title = R.string.maintenance_permission_revoked,
        action = R.string.maintenance_permission_revoked_action,
    ),

    UNRECOVERABLE_CURSOR(
        id = "unrecoverable_cursor",
        title = R.string.maintenance_unrecoverable_cursor,
        action = R.string.maintenance_unrecoverable_cursor_action,
    ),

    UNSUPPORTED_PLATFORM(
        id = "unsupported_platform",
        title = R.string.maintenance_unsupported_platform,
        action = R.string.maintenance_unsupported_platform_action,
    ),

    SAMSUNG_HEALTH_UNREACHABLE(
        id = "samsung_health_unreachable",
        title = R.string.maintenance_samsung_health_unreachable,
        action = R.string.maintenance_samsung_health_unreachable_action,
        transient = true,
    ),

    INGESTION_UNREACHABLE(
        id = "ingestion_unreachable",
        title = R.string.maintenance_ingestion_unreachable,
        action = R.string.maintenance_ingestion_unreachable_action,
        transient = true,
    ),
    ;

    companion object {
        fun byId(id: String): MaintenanceCode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What makes two occurrences the same Maintenance Incident.
 *
 * [category] is null when the defect is not one category's: a contract both sides cannot agree on is
 * the same defect wherever it is met, and naming a category would raise one notification per category
 * for a single thing to fix.
 *
 * [detail] tells apart two incidents of the same code that need different fixes. It carries no
 * observed content: only names the code itself defines, such as a rejection code or the enum field a
 * mapper does not interpret.
 */
data class IncidentIdentity(
    val code: MaintenanceCode,
    val category: HealthCategory? = null,
    val detail: String? = null,
) {
    /** The stable name of this incident, which is what a notification is raised and replaced under. */
    val key: String get() = "${code.id}|${category?.id.orEmpty()}|${detail.orEmpty()}"
}

/**
 * One Maintenance Incident as it stands now: what is wrong, since when, and how often it was met.
 *
 * It holds nothing that was observed from the Data Owner's Consolidated Health Data. That is the
 * whole reason it exists as its own value instead of a captured failure: a diagnostic that could
 * carry a measurement, a coordinate or a token would be one more copy of the data it reports about.
 */
data class MaintenanceIncident(
    val identity: IncidentIdentity,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val occurrences: Long,
) {

    fun seenAt(at: Instant): MaintenanceIncident = copy(
        lastSeenAt = maxOf(lastSeenAt, at),
        occurrences = occurrences + 1,
    )

    /**
     * Whether the Data Owner is worth telling about this.
     *
     * A transient condition waits out [MaintenancePolicy.transientGrace] from the moment it started,
     * so a network that comes back within it never becomes a notification.
     */
    fun worthNotifying(policy: MaintenancePolicy): Boolean =
        !identity.code.transient || Duration.between(firstSeenAt, lastSeenAt) >= policy.transientGrace

    companion object {

        fun first(identity: IncidentIdentity, at: Instant): MaintenanceIncident = MaintenanceIncident(
            identity = identity,
            firstSeenAt = at,
            lastSeenAt = at,
            occurrences = 1,
        )
    }
}

/** The bounds the maintenance channel works under. */
data class MaintenancePolicy(
    /**
     * How long a transient condition may last before it stops being one.
     *
     * A synchronization that succeeds ends the outage, so this is time without any successful
     * synchronization rather than time since the first failure of a run.
     */
    val transientGrace: Duration = Duration.ofHours(24),
)
