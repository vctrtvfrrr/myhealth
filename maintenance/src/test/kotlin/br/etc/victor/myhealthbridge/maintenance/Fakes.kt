package br.etc.victor.myhealthbridge.maintenance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InMemoryMaintenanceStore : MaintenanceStore {

    private val incidents = MutableStateFlow(emptyMap<String, MaintenanceIncident>())

    override suspend fun read(identity: IncidentIdentity): MaintenanceIncident? = incidents.value[identity.key]

    override suspend fun write(incident: MaintenanceIncident) {
        incidents.value = incidents.value + (incident.identity.key to incident)
    }

    override suspend fun transient(): List<MaintenanceIncident> =
        incidents.value.values.filter { it.identity.code.transient }

    override suspend fun forget(identities: List<IncidentIdentity>) {
        incidents.value = incidents.value - identities.map { it.key }.toSet()
    }

    override fun observe(): Flow<List<MaintenanceIncident>> = incidents.map { it.values.toList() }
}

class RecordingNotifier : MaintenanceNotifier {

    val posted = mutableListOf<MaintenanceIncident>()

    val withdrawn = mutableListOf<IncidentIdentity>()

    override fun post(incident: MaintenanceIncident) {
        posted += incident
    }

    override fun withdraw(identity: IncidentIdentity) {
        withdrawn += identity
    }
}

/** A clock the test moves by hand, because the grace period is measured in whole days. */
class MovableClock(var now: Instant) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
