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
     * where each score starts: a window that starts within a day still reads that day's score.
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
        assertEquals(LocalDateTime.parse("2026-08-10T00:00:00"), request.localTimeFilter!!.endTime)
        assertTrue(request.localTimeFilter!!.isInclusiveStart)
        assertTrue(request.localTimeFilter!!.isInclusiveEnd)
        assertEquals(energyScore.pageSize, request.pageSize)
    }

    @Test
    fun `continues the page it was given`() {
        val window = ReadWindow(LocalDateTime.parse("2026-08-03T00:00:00"), LocalDateTime.parse("2026-08-03T00:00:00"))

        val request = localDateRequestOf(DataTypes.ENERGY_SCORE.readDataRequestBuilder, energyScore, window, pageToken = "page-2")

        assertEquals("page-2", request.pageToken)
    }
}
