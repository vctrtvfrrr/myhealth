package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.request.DataTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExerciseFieldCoverageTest {

    @Test
    fun `reads every public field the pinned SDK exposes for exercise`() {
        val exposed = DataTypes.EXERCISE.allFields.map { it.name }.toSet()

        assertEquals(exposed, exerciseFields.map { it.name }.toSet())
    }
}
