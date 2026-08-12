package br.etc.victor.myhealthbridge.health.ui

import br.etc.victor.myhealthbridge.health.FakeSamsungHealthGateway
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.InMemoryPermissionHistoryStore
import br.etc.victor.myhealthbridge.health.PermissionObservation
import br.etc.victor.myhealthbridge.health.PermissionState
import br.etc.victor.myhealthbridge.health.Remediation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthPermissionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakeSamsungHealthGateway()
    private val store = InMemoryPermissionHistoryStore()
    private val clock = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC)
    private val service = HealthPermissionsService(gateway, store, clock)

    @BeforeEach
    fun useTestMainDispatcher() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun restoreMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun `shows the unknown query state until a check succeeds`() = runTest(dispatcher) {
        val viewModel = HealthPermissionsViewModel(service)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.queryUnknown)
        assertFalse(viewModel.state.value.outdated)
        assertTrue(viewModel.state.value.states.isEmpty())
    }

    @Test
    fun `starts from the last observation Room kept`() = runTest(dispatcher) {
        store.observation = PermissionObservation.from(
            previous = null,
            granted = setOf(HealthCategory.STEPS),
            requested = emptySet(),
            observedAt = clock.instant(),
        )

        val viewModel = HealthPermissionsViewModel(service)
        advanceUntilIdle()

        assertEquals(clock.instant(), viewModel.state.value.observedAt)
        assertEquals(PermissionState.GRANTED, viewModel.state.value.states[HealthCategory.STEPS])
    }

    @Test
    fun `never lets the restored observation replace a newer one`() = runTest(dispatcher) {
        store.observation = PermissionObservation.from(
            previous = null,
            granted = setOf(HealthCategory.STEPS),
            requested = emptySet(),
            observedAt = clock.instant().minusSeconds(3600),
        )
        val restore = CompletableDeferred<Unit>()
        store.delayNextRead = restore

        val viewModel = HealthPermissionsViewModel(service)
        runCurrent()

        viewModel.check()
        advanceUntilIdle()
        restore.complete(Unit)
        advanceUntilIdle()

        assertEquals(clock.instant(), viewModel.state.value.observedAt)
        assertEquals(PermissionState.REVOKED, viewModel.state.value.states[HealthCategory.STEPS])
    }

    @Test
    fun `marks the observation as outdated when the newest check fails`() = runTest(dispatcher) {
        val viewModel = HealthPermissionsViewModel(service)
        viewModel.check()
        advanceUntilIdle()

        val availability = SamsungHealthAvailability.ActionRequired(Remediation.SAMSUNG_HEALTH_SETUP)
        gateway.queryFailure = availability
        viewModel.check()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.outdated)
        assertFalse(state.queryUnknown)
        assertEquals(availability, state.availability)
        assertEquals(clock.instant(), state.observedAt)
    }

    @Test
    fun `runs a single Samsung Health operation at a time`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        gateway.beforeEachOperation = { gate.await() }

        val viewModel = HealthPermissionsViewModel(service)
        viewModel.check()
        runCurrent()
        viewModel.check()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, store.writes)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `requests only the categories that were never requested`() = runTest(dispatcher) {
        gateway.granted = setOf(HealthCategory.STEPS)
        val viewModel = HealthPermissionsViewModel(service)
        viewModel.check()
        advanceUntilIdle()

        viewModel.requestAllPending()
        advanceUntilIdle()

        assertEquals(HealthCategory.entries.toSet() - HealthCategory.STEPS, gateway.requests.single())
    }
}
