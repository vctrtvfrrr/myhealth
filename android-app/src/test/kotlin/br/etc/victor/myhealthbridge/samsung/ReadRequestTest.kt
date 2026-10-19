package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.HealthCapabilities
import br.etc.victor.myhealthbridge.sync.ReadWindow
import br.etc.victor.myhealthbridge.sync.RecordCapability
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ReadRequestTest {

    private val energyScore = HealthCapabilities.of(HealthCategory.ENERGY_SCORE) as RecordCapability

    /**
     * Samsung Health filters the energy score only by local date, so the read widens to the days the window
     * touches. The SDK reads a date range as the starts of its first and last days, both included, which is
     * where each score starts: a window that starts within a day still reads that day's score, and the day
     * after the window's last is the end that makes the range reach it.
     */
    @Test
    fun `reads over every local day the window touches`() {
        val window = ReadWindow(
            from = LocalDateTime.parse("2026-08-03T06:30:00"),
            to = LocalDateTime.parse("2026-08-10T21:15:00"),
        )

        val request = localDateRequestOf(DataTypes.ENERGY_SCORE.readDataRequestBuilder, energyScore, window, pageToken = null)

        assertEquals(DataTypes.ENERGY_SCORE, request.dataType)
        assertEquals(LocalDateTime.parse("2026-08-03T00:00:00"), request.localTimeFilter!!.startTime)
        assertEquals(LocalDateTime.parse("2026-08-11T00:00:00"), request.localTimeFilter!!.endTime)
        assertTrue(request.localTimeFilter!!.isInclusiveStart)
        assertTrue(request.localTimeFilter!!.isInclusiveEnd)
        assertEquals(energyScore.pageSize, request.pageSize)
    }

    /**
     * A window that starts and ends on the same local day is the common one, since the hourly read starts where
     * the last score did. Samsung Health refuses a range that starts and ends at the same instant, so the read
     * always reaches into the day after the window's last.
     */
    @Test
    fun `reads a window within a single day as a range that is not empty`() {
        val window = ReadWindow(
            from = LocalDateTime.parse("2026-10-19T00:00:00"),
            to = LocalDateTime.parse("2026-10-19T18:30:00"),
        )

        val request = localDateRequestOf(DataTypes.ENERGY_SCORE.readDataRequestBuilder, energyScore, window, pageToken = null)

        assertTrue(request.localTimeFilter!!.startTime!!.isBefore(request.localTimeFilter!!.endTime))
    }

    @Test
    fun `continues the page it was given`() {
        val window = ReadWindow(LocalDateTime.parse("2026-08-03T00:00:00"), LocalDateTime.parse("2026-08-03T00:00:00"))

        val request = localDateRequestOf(DataTypes.ENERGY_SCORE.readDataRequestBuilder, energyScore, window, pageToken = "page-2")

        assertEquals("page-2", request.pageToken)
    }
}
