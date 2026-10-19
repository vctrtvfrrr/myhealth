package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class VitalSignFieldCoverageTest {

    @TestFactory
    fun `reads every public field the pinned SDK exposes`(): List<DynamicTest> = listOf(
        coverageOf(DataTypes.BLOOD_PRESSURE, bloodPressureFields),
        coverageOf(DataTypes.BODY_COMPOSITION, bodyCompositionFields),
        coverageOf(DataTypes.BODY_TEMPERATURE, bodyTemperatureFields),
    )

    /** The mappers read these names without depending on the SDK, so the SDK has to answer to them. */
    @TestFactory
    fun `names the fields the way the mappers read them`(): List<DynamicTest> = listOf(
        namesOf("blood_pressure", bloodPressureFields, "systolic", "diastolic", "mean", "pulse", "medication"),
        namesOf(
            "body_composition",
            bodyCompositionFields,
            "weight",
            "height",
            "body_fat",
            "skeletal_muscle",
            "muscle_mass",
            "basal_metabolic_rate",
            "body_fat_mass",
            "fat_free_mass",
            "fat_free",
            "skeletal_muscle_mass",
            "total_body_water",
            "bmi",
        ),
        namesOf("body_temperature", bodyTemperatureFields, "temperature"),
    )

    private fun coverageOf(type: DataType, fields: List<Field<*>>): DynamicTest = dynamicTest(type.name) {
        assertEquals(type.allFields.map { it.name }.toSet(), fields.map { it.name }.toSet())
    }

    private fun namesOf(type: String, fields: List<Field<*>>, vararg names: String): DynamicTest = dynamicTest(type) {
        assertEquals(names.toSet(), fields.map { it.name }.toSet())
    }
}
