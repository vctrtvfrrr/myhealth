package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.HealthCategory

/** What Samsung Health hands the adapter for one category, invented from end to end. */
sealed interface SyntheticFixture {

    data class Record(val record: SourceRecord) : SyntheticFixture

    /** The aggregate of one Snapshot Window. */
    data class Aggregate(val value: SourceValue) : SyntheticFixture

    /** The whole profile, field by field. */
    data class Profile(val fields: Map<String, SourceValue>) : SyntheticFixture
}

/**
 * One synthetic fixture per implemented category of the capability catalog: covering a category is
 * adding its fixture here, and the catalog test fails until it is.
 *
 * Every value is invented: no fixture in this repository may come from a real measurement, a real
 * night or a place the Data Owner has been.
 */
object SyntheticFixtures {

    private val byCategory: Map<HealthCategory, SyntheticFixture> = mapOf(
        HealthCategory.EXERCISE to SyntheticFixture.Record(exerciseRecord()),
        HealthCategory.EXERCISE_LOCATION to SyntheticFixture.Record(
            exerciseRecord(sessions = listOf(session(route = listOf(location())))),
        ),
        HealthCategory.STEPS to SyntheticFixture.Aggregate(number("1234")),
        HealthCategory.SLEEP to SyntheticFixture.Record(sleepRecord(sessions = listOf(sleepSession(stages = listOf(sleepStage()))))),
        HealthCategory.HEART_RATE to SyntheticFixture.Record(sourceRecord()),
        HealthCategory.USER_PROFILE to SyntheticFixture.Profile(syntheticProfile()),
    )

    fun of(category: HealthCategory): SyntheticFixture? = byCategory[category]
}
