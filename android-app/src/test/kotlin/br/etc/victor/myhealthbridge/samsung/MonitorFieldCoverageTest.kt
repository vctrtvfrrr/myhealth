package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.sync.IrregularHeartRhythmMapper
import br.etc.victor.myhealthbridge.sync.SleepApneaMapper
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class MonitorFieldCoverageTest {

    @TestFactory
    fun `reads every public field the pinned SDK exposes`(): List<DynamicTest> = listOf(
        coverageOf(DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION, irregularHeartRhythmFields),
        coverageOf(DataTypes.SLEEP_APNEA, sleepApneaFields),
        coverageOf(DataTypes.ENERGY_SCORE, energyScoreFields),
    )

    /** The mappers read these names without depending on the SDK, so the SDK has to answer to them. */
    @TestFactory
    fun `names the fields the way the mappers read them`(): List<DynamicTest> = listOf(
        namesOf("irregular_heart_rhythm", irregularHeartRhythmFields, "status"),
        namesOf("sleep_apnea", sleepApneaFields, "result"),
        namesOf("energy_score", energyScoreFields, "total_score"),
    )

    @Test
    fun `knows every constant the pinned SDK declares for the monitors`() {
        assertEquals(
            DataType.IrregularHeartRhythmNotificationType.IrregularHeartRhythmStatus.entries.map { it.name }.toSet(),
            IrregularHeartRhythmMapper.knownEnums.getValue("status"),
        )
        assertEquals(
            DataType.SleepApneaType.DetectedSign.entries.map { it.name }.toSet(),
            SleepApneaMapper.knownEnums.getValue("result"),
        )
    }

    private fun coverageOf(type: DataType, fields: List<Field<*>>): DynamicTest = dynamicTest(type.name) {
        assertEquals(type.allFields.map { it.name }.toSet(), fields.map { it.name }.toSet())
    }

    private fun namesOf(type: String, fields: List<Field<*>>, vararg names: String): DynamicTest = dynamicTest(type) {
        assertEquals(names.toSet(), fields.map { it.name }.toSet())
    }
}
