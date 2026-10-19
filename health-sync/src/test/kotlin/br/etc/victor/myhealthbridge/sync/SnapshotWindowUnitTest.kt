package br.etc.victor.myhealthbridge.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SnapshotWindowUnitTest {

    private fun at(time: String): LocalDateTime = LocalDateTime.parse(time)

    @Test
    fun `cuts local time into whole days for the goals Samsung Health keeps one of per day`() {
        val unit = SnapshotWindowUnit.LOCAL_DAY

        assertEquals(at("2026-08-10T00:00:00"), unit.floor(at("2026-08-10T14:35:00")))
        assertEquals(at("2026-08-11T00:00:00"), unit.ceiling(at("2026-08-10T14:35:00")))
        assertEquals(
            listOf(
                SnapshotWindow(at("2026-08-10T00:00:00"), at("2026-08-11T00:00:00")),
                SnapshotWindow(at("2026-08-11T00:00:00"), at("2026-08-12T00:00:00")),
            ),
            unit.windowsIn(at("2026-08-10T00:00:00"), at("2026-08-12T00:00:00")),
        )
    }
}
