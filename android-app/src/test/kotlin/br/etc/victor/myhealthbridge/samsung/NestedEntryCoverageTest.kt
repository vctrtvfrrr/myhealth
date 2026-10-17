package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.data.entries.ExerciseLocation
import com.samsung.android.sdk.health.data.data.entries.ExerciseLog
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SwimmingLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Modifier
import kotlin.reflect.KProperty1

/**
 * The values nested inside a record are not described by any data type, so the SDK's own field list
 * cannot vouch for them the way it does for a record's fields. What the pinned SDK exposes on each
 * nested type is its public getters, and an upgrade adding one would otherwise be dropped in silence.
 */
class NestedEntryCoverageTest {

    @TestFactory
    fun `reads every public value the pinned SDK exposes on a nested entry`(): List<DynamicTest> = listOf(
        coverageOf(HeartRate::class.java, heartRateEntries),
        coverageOf(ExerciseSession::class.java, exerciseSessionEntries),
        coverageOf(ExerciseLocation::class.java, exerciseLocationEntries),
        coverageOf(ExerciseLog::class.java, exerciseLogEntries),
        coverageOf(SwimmingLog::class.java, swimmingLogEntries),
        coverageOf(SwimmingLog.SwimmingInterval::class.java, swimmingIntervalEntries),
    )

    private fun <T> coverageOf(type: Class<T>, entries: Entries<T>): DynamicTest = dynamicTest(type.simpleName) {
        assertEquals(publicValuesOf(type), entries.map { (_, read) -> (read as KProperty1<*, *>).name }.toSet())
    }

    /** A `$` marks what the Kotlin compiler generated or mangled for module-internal use, not a value. */
    private fun publicValuesOf(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        .filter { it.parameterCount == 0 && '$' !in it.name }
        .mapNotNull { propertyNameOf(it.name) }
        .toSet()

    private fun propertyNameOf(getter: String): String? = when {
        getter.startsWith("get") && getter.length > 3 -> getter.removePrefix("get").replaceFirstChar(Char::lowercaseChar)
        getter.startsWith("is") && getter.length > 2 -> getter
        else -> null
    }
}
