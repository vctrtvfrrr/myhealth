package br.etc.victor.myhealthbridge.samsung

import com.samsung.android.sdk.health.data.request.DataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The read model names every exercise type in Portuguese and shows the raw constant of one it cannot
 * name. That fallback is for a constant a later SDK introduces; one the pinned SDK already exposes must
 * have its name, so a new SDK fails here until a migration gives it one.
 */
class ExerciseTypeLabelCoverageTest {

    @Test
    fun `names in Portuguese every exercise type the pinned SDK exposes`() {
        val exposed = DataType.ExerciseType.PredefinedExerciseType.entries.map { it.name }.toSet()

        assertEquals(exposed, labelled())
    }

    private fun labelled(): Set<String> {
        val root = File(System.getProperty("repositoryRoot") ?: error("Missing repositoryRoot"))
        val migrations = root.resolve("ingestion-api/src/main/resources/db/migration").listFiles().orEmpty()
        return migrations.flatMap { migration ->
            LABEL_INSERT.findAll(migration.readText()).flatMap { insert -> LABELLED_CONSTANT.findAll(insert.value) }
                .map { it.groupValues[1] }
                .toList()
        }.toSet()
    }

    private companion object {
        val LABEL_INSERT = Regex("""insert into exercise_type_label[^;]*;""")

        /** The constant opening each row of the insert, as in `('RUNNING', 'Corrida')`. */
        val LABELLED_CONSTANT = Regex("""\('([A-Z0-9_]+)',""")
    }
}
