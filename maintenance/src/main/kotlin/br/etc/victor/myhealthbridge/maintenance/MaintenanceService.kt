package br.etc.victor.myhealthbridge.maintenance

import br.etc.victor.myhealthbridge.health.HealthCategory
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/** The durable side of the maintenance channel: which incidents are open, and since when. */
interface MaintenanceStore {

    suspend fun read(identity: IncidentIdentity): MaintenanceIncident?

    suspend fun write(incident: MaintenanceIncident)

    /** The open incidents a successful synchronization would end. */
    suspend fun transient(): List<MaintenanceIncident>

    suspend fun forget(identities: List<IncidentIdentity>)

    fun observe(): Flow<List<MaintenanceIncident>>
}

/**
 * Where a Maintenance Incident reaches the Data Owner.
 *
 * [post] both raises and updates: an incident already on screen is replaced by its current count and
 * last occurrence, which is what keeps the hourly synchronization from stacking notifications.
 */
interface MaintenanceNotifier {

    fun post(incident: MaintenanceIncident)

    fun withdraw(identity: IncidentIdentity)
}

/**
 * The one place a condition needing the maintainer's attention becomes a Maintenance Incident.
 *
 * Every caller reports the condition it met and nothing else: whether that is worth a notification,
 * and whether it is the same one already raised, is decided here.
 */
class MaintenanceService(
    private val store: MaintenanceStore,
    private val notifier: MaintenanceNotifier,
    private val policy: MaintenancePolicy,
    private val clock: Clock,
) {

    /**
     * Records that [code] was met again, and tells the Data Owner if it is worth telling.
     *
     * [detail] must name something the code defines, never something a Health Record carried.
     */
    suspend fun report(code: MaintenanceCode, category: HealthCategory? = null, detail: String? = null) {
        val at = clock.instant()
        val identity = IncidentIdentity(code, category, detail)
        val incident = store.read(identity)?.seenAt(at) ?: MaintenanceIncident.first(identity, at)

        store.write(incident)
        if (incident.worthNotifying(policy)) notifier.post(incident)
    }

    /**
     * Ends one incident, because a run observed the condition it names to be gone.
     *
     * An incident that stayed open after its condition was resolved would be worse than no channel at
     * all: the screen would keep asking for a change to code that no longer needs one, and the Data
     * Owner would learn to disbelieve it. Nothing is kept as resolved — what is open is what is wrong,
     * and the condition returning is a new incident dated by its own first occurrence.
     */
    suspend fun resolve(code: MaintenanceCode, category: HealthCategory? = null, detail: String? = null) {
        val identity = IncidentIdentity(code, category, detail)
        if (store.read(identity) == null) return

        notifier.withdraw(identity)
        store.forget(listOf(identity))
    }

    /**
     * Ends every transient incident, which is what a successful synchronization means.
     *
     * Forgetting them rather than marking them resolved is deliberate: the grace period is measured
     * from the first failure after the last success, so an outage that starts again starts over.
     */
    suspend fun clearTransient() {
        val open = store.transient()
        if (open.isEmpty()) return

        open.forEach { notifier.withdraw(it.identity) }
        store.forget(open.map { it.identity })
    }

    fun observe(): Flow<List<MaintenanceIncident>> = store.observe()
}
