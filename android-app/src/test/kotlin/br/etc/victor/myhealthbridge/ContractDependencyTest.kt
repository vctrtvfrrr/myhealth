package br.etc.victor.myhealthbridge

import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractDependencyTest {

    @Test
    fun `reaches the transport contract from the app`() {
        assertTrue(IngestionContract.CURRENT_VERSION > 0)
    }
}
