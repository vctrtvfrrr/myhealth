package br.etc.victor.myhealthbridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTabTest {

    /** What a maintenance notification carries, which is the whole of how it opens the diagnostics. */
    @Test
    fun `opens the diagnostics for the action the maintenance notification names`() {
        assertEquals(MainTab.DIAGNOSTICS, MainTab.of(MainTab.DIAGNOSTICS_ACTION))
    }

    @Test
    fun `opens where it always did for anything else`() {
        assertEquals(MainTab.PERMISSIONS, MainTab.of(null))
        assertEquals(MainTab.PERMISSIONS, MainTab.of("android.intent.action.MAIN"))
    }
}
