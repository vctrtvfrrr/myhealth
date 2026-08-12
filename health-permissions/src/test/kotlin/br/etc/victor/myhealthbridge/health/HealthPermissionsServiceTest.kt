package br.etc.victor.myhealthbridge.health

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HealthPermissionsServiceTest {

    private val gateway = FakeSamsungHealthGateway()
    private val store = InMemoryPermissionHistoryStore()
    private var now = Instant.parse("2026-08-11T10:00:00Z")
    private val service = HealthPermissionsService(
        gateway = gateway,
        store = store,
        clock = object : Clock() {
            override fun instant(): Instant = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        },
    )

    @Test
    fun `records one observation covering the whole catalog`() = runTest {
        gateway.granted = setOf(HealthCategory.STEPS)

        val result = service.check() as CheckResult.Observed

        assertEquals(now, result.observation.observedAt)
        assertEquals(HealthCategory.entries.size, result.observation.records.size)
        assertEquals(PermissionState.GRANTED, result.observation.stateOf(HealthCategory.STEPS))
        assertEquals(PermissionState.NOT_REQUESTED, result.observation.stateOf(HealthCategory.SLEEP))
        assertEquals(1, store.writes)
    }

    @Test
    fun `writes nothing while Samsung Health needs remediation`() = runTest {
        val availability = SamsungHealthAvailability.ActionRequired(Remediation.SAMSUNG_HEALTH_SETUP)
        gateway.queryFailure = availability

        val result = service.check() as CheckResult.Unavailable

        assertEquals(availability, result.availability)
        assertNull(store.observation)
        assertEquals(0, store.writes)
    }

    @Test
    fun `keeps the previous observation untouched when the query fails`() = runTest {
        gateway.granted = setOf(HealthCategory.STEPS)
        service.check()
        val recorded = store.observation

        gateway.queryFailure = SamsungHealthAvailability.TemporarilyUnavailable("timeout")
        val result = service.check() as CheckResult.Unavailable

        assertEquals(SamsungHealthAvailability.TemporarilyUnavailable("timeout"), result.availability)
        assertSame(recorded, store.observation)
        assertEquals(1, store.writes)
    }

    @Test
    fun `infers denied for a category whose request completed without a grant`() = runTest {
        val result = service.request(setOf(HealthCategory.STEPS, HealthCategory.SLEEP)) as CheckResult.Observed

        assertEquals(listOf(setOf(HealthCategory.STEPS, HealthCategory.SLEEP)), gateway.requests)
        assertEquals(PermissionState.DENIED, result.observation.stateOf(HealthCategory.STEPS))
        assertEquals(PermissionState.DENIED, result.observation.stateOf(HealthCategory.SLEEP))
    }

    @Test
    fun `infers granted for the categories the consent flow granted`() = runTest {
        gateway.grantsOnRequest = setOf(HealthCategory.STEPS)

        val result = service.request(setOf(HealthCategory.STEPS, HealthCategory.SLEEP)) as CheckResult.Observed

        assertEquals(PermissionState.GRANTED, result.observation.stateOf(HealthCategory.STEPS))
        assertEquals(PermissionState.DENIED, result.observation.stateOf(HealthCategory.SLEEP))
    }

    @Test
    fun `infers revoked when a category that was granted stops being granted`() = runTest {
        gateway.granted = setOf(HealthCategory.STEPS)
        service.check()

        gateway.granted = emptySet()
        val result = service.check() as CheckResult.Observed

        assertEquals(PermissionState.REVOKED, result.observation.stateOf(HealthCategory.STEPS))
    }

    @Test
    fun `does not mark a request whose consent flow could not run`() = runTest {
        gateway.requestFailure = SamsungHealthAvailability.TemporarilyUnavailable()

        service.request(setOf(HealthCategory.STEPS)) as CheckResult.Unavailable
        gateway.requestFailure = null
        val result = service.check() as CheckResult.Observed

        assertEquals(PermissionState.NOT_REQUESTED, result.observation.stateOf(HealthCategory.STEPS))
    }

    @Test
    fun `writes an observed request only with the observation that shows its effect`() = runTest {
        gateway.queryFailure = SamsungHealthAvailability.TemporarilyUnavailable()

        service.request(setOf(HealthCategory.STEPS)) as CheckResult.Unavailable
        assertNull(store.observation)

        gateway.queryFailure = null
        val result = service.check() as CheckResult.Observed

        assertEquals(PermissionState.DENIED, result.observation.stateOf(HealthCategory.STEPS))
    }

    @Test
    fun `claims no history it lost, only the grants Samsung Health still reports`() = runTest {
        gateway.granted = setOf(HealthCategory.STEPS)
        service.request(setOf(HealthCategory.STEPS, HealthCategory.SLEEP))

        store.observation = null

        val result = service.check() as CheckResult.Observed
        assertEquals(PermissionState.GRANTED, result.observation.stateOf(HealthCategory.STEPS))
        assertEquals(PermissionState.NOT_REQUESTED, result.observation.stateOf(HealthCategory.SLEEP))
    }

    @Test
    fun `advances the successful check time on every observation`() = runTest {
        service.check()
        now = now.plusSeconds(60)
        val result = service.check() as CheckResult.Observed

        assertTrue(result.observation.observedAt == now)
    }
}
