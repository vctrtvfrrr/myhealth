package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SleepFieldCoverageTest {

    @Test
    fun `reads every public field the pinned SDK exposes for sleep`() {
        val exposed = DataTypes.SLEEP.allFields.map { it.name }.toSet()

        assertEquals(exposed, sleepFields.map { it.name }.toSet())
    }

    /** The sleep mapper reads these names without depending on the SDK, so the SDK has to answer to them. */
    @Test
    fun `names the fields the way the sleep mapper reads them`() {
        assertEquals(setOf("sleep_duration", "sleep_score", "sessions"), sleepFields.map { it.name }.toSet())
    }
}
