package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeartRateFieldCoverageTest {

    @Test
    fun `reads every public field the pinned SDK exposes for heart rate`() {
        val exposed = DataTypes.HEART_RATE.allFields.map { it.name }.toSet()

        assertEquals(exposed, heartRateFields.map { it.name }.toSet())
    }
}
