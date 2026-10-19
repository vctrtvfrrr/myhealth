package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.HealthCapabilities
import com.samsung.android.sdk.health.data.permission.AccessType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthCategoryCoverageTest {

    @Test
    fun `catalogs every readable data type the pinned SDK exposes`() {
        assertEquals(25, exposedDataTypeNames().size)
        assertEquals(exposedDataTypeNames(), HealthCategory.entries.map { it.dataType.name }.toSet())
    }

    @Test
    fun `finds no category of the pinned SDK outside the capability catalog`() {
        assertEquals(emptySet<String>(), SamsungUncatalogedCategories.read())
    }

    @Test
    fun `asks for the read permission of every entry of the capability catalog`() {
        assertEquals(
            HealthCapabilities.entries.map { it.permission.dataType.name }.toSet(),
            catalogReadPermissions().map { it.dataType.name }.toSet(),
        )
    }

    @Test
    fun `maps each category to a distinct data type`() {
        val types = HealthCategory.entries.map { it.dataType.name }

        assertEquals(types.size, types.toSet().size)
    }

    @Test
    fun `builds read permissions only`() {
        val permissions = readPermissions(HealthCategory.entries)

        assertEquals(HealthCategory.entries.size, permissions.size)
        assertEquals(setOf(AccessType.READ), permissions.map { it.accessType }.toSet())
    }
}
