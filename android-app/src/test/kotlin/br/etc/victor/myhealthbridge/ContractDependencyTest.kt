package br.etc.victor.myhealthbridge

import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ContractDependencyTest {

    @Test
    fun `reaches the transport contract from the app`() {
        assertFalse(IngestionContract.CURRENT_VERSION.isBlank())
    }
}
