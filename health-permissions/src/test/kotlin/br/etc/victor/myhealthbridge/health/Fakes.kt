package br.etc.victor.myhealthbridge.health

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

    override suspend fun read(): PermissionObservation? = observation

    override suspend fun write(observation: PermissionObservation) {
        this.observation = observation
        writes++
    }
}
