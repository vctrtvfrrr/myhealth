package br.etc.victor.myhealthbridge.health

import kotlinx.coroutines.CompletableDeferred

class FakeSamsungHealthGateway(
    var granted: Set<HealthCategory> = emptySet(),
) : SamsungHealthGateway {

    var queryFailure: SamsungHealthAvailability? = null
    var requestFailure: SamsungHealthAvailability? = null

    /** What the consent flow grants out of the requested set. */
    var grantsOnRequest: Set<HealthCategory> = emptySet()

    val requests = mutableListOf<Set<HealthCategory>>()

    var beforeEachOperation: suspend () -> Unit = {}

    override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> {
        beforeEachOperation()
        return queryFailure?.let { SamsungHealthOutcome.Failed(it) } ?: SamsungHealthOutcome.Observed(granted)
    }

    override suspend fun requestReadPermissions(categories: Set<HealthCategory>): SamsungHealthOutcome<Unit> {
        beforeEachOperation()
        requests += categories
        requestFailure?.let { return SamsungHealthOutcome.Failed(it) }
        granted = granted + categories.intersect(grantsOnRequest)
        return SamsungHealthOutcome.Observed(Unit)
    }
}

class InMemoryPermissionHistoryStore : PermissionHistoryStore {

    var observation: PermissionObservation? = null
    var writes: Int = 0

    /** Holds back the answer of the next read, which still sees the history as it was when it started. */
    var delayNextRead: CompletableDeferred<Unit>? = null

    override suspend fun read(): PermissionObservation? {
        val answer = observation
        delayNextRead?.let { held ->
            delayNextRead = null
            held.await()
        }
        return answer
    }

    override suspend fun write(observation: PermissionObservation) {
        this.observation = observation
        writes++
    }
}
