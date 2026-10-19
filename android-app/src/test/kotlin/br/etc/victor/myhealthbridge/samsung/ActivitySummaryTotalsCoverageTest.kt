package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.sync.ActivitySummaryMapper
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.request.DataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class ActivitySummaryTotalsCoverageTest {

    @Test
    fun `reads every aggregate the pinned SDK exposes for the activity summary`() {
        val exposed = DataType.ActivitySummaryType::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == AggregateOperation::class.java }
            .map { it.get(null) }
            .toSet()

        assertEquals(exposed, activitySummaryTotals.map { it.operation }.toSet())
    }

    @Test
    fun `hands each total over under the name the mapper reads it by`() {
        assertEquals(
            setOf(
                ActivitySummaryMapper.TOTAL_ACTIVE_CALORIES_BURNED,
                ActivitySummaryMapper.TOTAL_CALORIES_BURNED,
                ActivitySummaryMapper.TOTAL_ACTIVE_TIME,
                ActivitySummaryMapper.TOTAL_DISTANCE,
            ),
            activitySummaryTotals.map { it.name }.toSet(),
        )
    }
}
