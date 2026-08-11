package br.etc.victor.myhealthbridge.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IngestionContractTest {

    @Test
    fun `pins the wire version so that a bump is a deliberate change`() {
        assertEquals("1", IngestionContract.CURRENT_VERSION)
    }
}
