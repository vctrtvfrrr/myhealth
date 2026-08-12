package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.health.HealthCategory
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class HealthCategoryCoverageTest {

    @Test
    fun `catalogs every readable data type the pinned SDK exposes`() {
        val exposed = DataTypes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && DataType::class.java.isAssignableFrom(it.type) }
            .map { (it.get(null) as DataType).name }
            .toSet()

        assertEquals(exposed, HealthCategory.entries.map { it.dataType.name }.toSet())
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
