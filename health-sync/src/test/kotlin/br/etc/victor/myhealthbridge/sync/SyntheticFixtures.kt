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
        HealthCategory.ACTIVITY_SUMMARY to SyntheticFixture.Aggregate(activity()),
        HealthCategory.EXERCISE to SyntheticFixture.Record(exerciseRecord()),
        HealthCategory.EXERCISE_LOCATION to SyntheticFixture.Record(
            exerciseRecord(sessions = listOf(session(route = listOf(location())))),
        ),
        HealthCategory.FLOORS_CLIMBED to SyntheticFixture.Record(floorsClimbedRecord()),
        HealthCategory.STEPS to SyntheticFixture.Aggregate(number("1234")),
        HealthCategory.NUTRITION to SyntheticFixture.Record(nutritionRecord()),
        HealthCategory.WATER_INTAKE to SyntheticFixture.Record(waterIntakeRecord()),
        HealthCategory.SLEEP to SyntheticFixture.Record(sleepRecord(sessions = listOf(sleepSession(stages = listOf(sleepStage()))))),
        HealthCategory.SKIN_TEMPERATURE to SyntheticFixture.Record(skinTemperatureRecord()),
        HealthCategory.BLOOD_GLUCOSE to SyntheticFixture.Record(bloodGlucoseRecord(bins = listOf(glucoseBin()))),
        HealthCategory.BLOOD_OXYGEN to SyntheticFixture.Record(bloodOxygenRecord()),
        HealthCategory.BLOOD_PRESSURE to SyntheticFixture.Record(bloodPressureRecord()),
        HealthCategory.BODY_COMPOSITION to SyntheticFixture.Record(bodyCompositionRecord()),
        HealthCategory.BODY_TEMPERATURE to SyntheticFixture.Record(bodyTemperatureRecord()),
        HealthCategory.HEART_RATE to SyntheticFixture.Record(heartRateRecord()),
        HealthCategory.ACTIVE_CALORIES_BURNED_GOAL to SyntheticFixture.Aggregate(number("450")),
        HealthCategory.ACTIVE_TIME_GOAL to SyntheticFixture.Aggregate(number("5400.000")),
        HealthCategory.NUTRITION_GOAL to SyntheticFixture.Aggregate(number("2100.0")),
        HealthCategory.SLEEP_GOAL to SyntheticFixture.Aggregate(
            SourceValue.Nested(
                mapOf(
                    SleepGoalMapper.BED_TIME to SourceValue.Text("23:15"),
                    SleepGoalMapper.WAKE_UP_TIME to SourceValue.Text("06:45"),
                ),
            ),
        ),
        HealthCategory.STEPS_GOAL to SyntheticFixture.Aggregate(number("6000")),
        HealthCategory.WATER_INTAKE_GOAL to SyntheticFixture.Aggregate(number("2500.0")),
        HealthCategory.USER_PROFILE to SyntheticFixture.Profile(syntheticProfile()),
        HealthCategory.IRREGULAR_HEART_RHYTHM to SyntheticFixture.Record(irregularHeartRhythmRecord()),
        HealthCategory.SLEEP_APNEA to SyntheticFixture.Record(sleepApneaRecord()),
        HealthCategory.ENERGY_SCORE to SyntheticFixture.Record(energyScoreRecord()),
    )

    fun of(category: HealthCategory): SyntheticFixture? = byCategory[category]
}
