package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class BinnedSeriesFieldCoverageTest {

    @TestFactory
    fun `reads every public field the pinned SDK exposes`(): List<DynamicTest> = listOf(
        coverageOf(DataTypes.BLOOD_OXYGEN, bloodOxygenFields),
        coverageOf(DataTypes.SKIN_TEMPERATURE, skinTemperatureFields),
        coverageOf(DataTypes.BLOOD_GLUCOSE, bloodGlucoseFields),
    )

    /** The mappers read these names without depending on the SDK, so the SDK has to answer to them. */
    @TestFactory
    fun `names the fields the way the mappers read them`(): List<DynamicTest> = listOf(
        namesOf("blood_oxygen", bloodOxygenFields, "spo2", "min", "max", "binning_data"),
        namesOf("skin_temperature", skinTemperatureFields, "temperature", "min", "max", "binning_data"),
        namesOf(
            "blood_glucose",
            bloodGlucoseFields,
            "glucose",
            "measurement_type",
            "sample_source_type",
            "meal_type",
            "meal_time",
            "insulin_injected",
            "medication",
            "binning_data",
        ),
    )

    private fun coverageOf(type: DataType, fields: List<Field<*>>): DynamicTest = dynamicTest(type.name) {
        assertEquals(type.allFields.map { it.name }.toSet(), fields.map { it.name }.toSet())
    }

    private fun namesOf(type: String, fields: List<Field<*>>, vararg names: String): DynamicTest = dynamicTest(type) {
        assertEquals(names.toSet(), fields.map { it.name }.toSet())
    }
}
