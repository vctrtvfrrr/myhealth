package br.etc.victor.myhealthbridge.maintenance

import br.etc.victor.myhealthbridge.health.HealthCategory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MaintenanceServiceTest {

    private val start = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = MovableClock(start)
    private val store = InMemoryMaintenanceStore()
    private val notifier = RecordingNotifier()

    private val service = MaintenanceService(store, notifier, MaintenancePolicy(), clock)

    @Test
    fun `tells the Data Owner as soon as something needs the code`() = runTest {
        service.report(MaintenanceCode.CONTRACT_INCOMPATIBLE)

        val posted = notifier.posted.single()
        assertEquals(MaintenanceCode.CONTRACT_INCOMPATIBLE, posted.identity.code)
        assertEquals(1L, posted.occurrences)
        assertEquals(start, posted.firstSeenAt)
    }

    /** The hourly synchronization meets the same defect every hour; the Data Owner is told once. */
    @Test
    fun `keeps equivalent incidents as one notification with an updated count`() = runTest {
        service.report(MaintenanceCode.UNRECOVERABLE_CURSOR, HealthCategory.HEART_RATE)
        clock.now = start.plus(Duration.ofHours(1))
        service.report(MaintenanceCode.UNRECOVERABLE_CURSOR, HealthCategory.HEART_RATE)

        assertEquals(1, notifier.posted.map { it.identity.key }.distinct().size)

        val last = notifier.posted.last()
        assertEquals(2L, last.occurrences)
        assertEquals(start, last.firstSeenAt)
        assertEquals(clock.now, last.lastSeenAt)
    }

    @Test
    fun `tells apart what needs a different fix`() = runTest {
        service.report(MaintenanceCode.UNMAPPABLE_RECORD, HealthCategory.HEART_RATE, detail = "invalid_payload")
        service.report(MaintenanceCode.UNMAPPABLE_RECORD, HealthCategory.HEART_RATE, detail = "invalid_unit")
        service.report(MaintenanceCode.UNMAPPABLE_RECORD, HealthCategory.STEPS, detail = "invalid_unit")

        assertEquals(3, notifier.posted.map { it.identity.key }.distinct().size)
        assertTrue(notifier.posted.all { it.occurrences == 1L })
    }

    @Test
    fun `holds a transient failure until it has lasted a whole day`() = runTest {
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)
        clock.now = start.plus(Duration.ofHours(23))
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)

        assertTrue(notifier.posted.isEmpty(), "a network that comes back within the day is not maintenance")

        clock.now = start.plus(Duration.ofHours(24))
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)

        assertEquals(3L, notifier.posted.single().occurrences)
    }

    /** It is recorded from the first failure even so: that is what dates the start of the outage. */
    @Test
    fun `records a transient failure it does not notify`() = runTest {
        service.report(MaintenanceCode.SAMSUNG_HEALTH_UNREACHABLE)

        val recorded = store.read(IncidentIdentity(MaintenanceCode.SAMSUNG_HEALTH_UNREACHABLE))
        assertEquals(start, recorded?.firstSeenAt)
    }

    @Test
    fun `a successful synchronization ends every transient incident`() = runTest {
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)
        service.report(MaintenanceCode.UNRECOVERABLE_CURSOR, HealthCategory.HEART_RATE)

        service.clearTransient()

        assertEquals(
            listOf(IncidentIdentity(MaintenanceCode.INGESTION_UNREACHABLE)),
            notifier.withdrawn,
        )
        assertNull(store.read(IncidentIdentity(MaintenanceCode.INGESTION_UNREACHABLE)))
        assertNotNull(store.read(IncidentIdentity(MaintenanceCode.UNRECOVERABLE_CURSOR, HealthCategory.HEART_RATE)))
    }

    /** The day is counted from the last success, so an outage that starts again starts over. */
    @Test
    fun `counts the day again after a synchronization got through`() = runTest {
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)
        clock.now = start.plus(Duration.ofHours(23))
        service.clearTransient()

        service.report(MaintenanceCode.INGESTION_UNREACHABLE)
        clock.now = start.plus(Duration.ofHours(46))
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)

        assertTrue(notifier.posted.isEmpty())

        clock.now = start.plus(Duration.ofHours(47))
        service.report(MaintenanceCode.INGESTION_UNREACHABLE)

        assertEquals(3L, notifier.posted.single().occurrences)
    }
}
