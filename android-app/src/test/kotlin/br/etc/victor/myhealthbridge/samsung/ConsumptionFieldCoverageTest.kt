package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class ConsumptionFieldCoverageTest {

    @TestFactory
    fun `reads every public field the pinned SDK exposes`(): List<DynamicTest> = listOf(
        coverageOf(DataTypes.NUTRITION, nutritionFields),
        coverageOf(DataTypes.WATER_INTAKE, waterIntakeFields),
        coverageOf(DataTypes.FLOORS_CLIMBED, floorsClimbedFields),
    )

    /** The mappers read these names without depending on the SDK, so the SDK has to answer to them. */
    @TestFactory
    fun `names the fields the way the mappers read them`(): List<DynamicTest> = listOf(
        namesOf(
            "nutrition",
            nutritionFields,
            "meal_type",
            "title",
            "calories",
            "total_fat",
            "saturated_fat",
            "polysaturated_fat",
            "monosaturated_fat",
            "trans_fat",
            "carbohydrate",
            "dietary_fiber",
            "sugar",
            "protein",
            "cholesterol",
            "sodium",
            "potassium",
            "vitamin_a",
            "vitamin_c",
            "calcium",
            "iron",
        ),
        namesOf("water_intake", waterIntakeFields, "amount"),
        namesOf("floors_climbed", floorsClimbedFields, "floor"),
    )

    private fun coverageOf(type: DataType, fields: List<Field<*>>): DynamicTest = dynamicTest(type.name) {
        assertEquals(type.allFields.map { it.name }.toSet(), fields.map { it.name }.toSet())
    }

    private fun namesOf(type: String, fields: List<Field<*>>, vararg names: String): DynamicTest = dynamicTest(type) {
        assertEquals(names.toSet(), fields.map { it.name }.toSet())
    }
}
