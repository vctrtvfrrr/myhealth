package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant

/**
 * Turns the profile Samsung Health holds for the Data Owner into an observation of the one Observed
 * Snapshot that has no Snapshot Window.
 *
 * Every field is personal data: it travels to the API and nowhere else, never into a log.
 */
object ProfileMapper {

    const val VERSION: String = "samsung-health-user-profile/1"

    /** The profile is not aggregated over any source, so it stands for all of them. */
    val sourceScope: SourceScope = SourceScope.ALL_SOURCES

    /**
     * The fields keep the names the source gives them, and a field the Data Owner never filled in is
     * left out. A null [profile] is no profile at all, observed as empty.
     */
    fun map(profile: Map<String, SourceValue>?, observedAt: ZonedInstant): SnapshotObservation = SnapshotObservation(
        window = null,
        observedAt = observedAt,
        sourceScope = sourceScope,
        mapperVersion = VERSION,
        content = profile?.let(::jsonOf),
    )
}
