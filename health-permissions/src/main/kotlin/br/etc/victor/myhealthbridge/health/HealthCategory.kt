package br.etc.victor.myhealthbridge.health

import androidx.annotation.StringRes

enum class HealthCategoryGroup(@param:StringRes val label: Int) {
    ACTIVITY(R.string.health_group_activity),
    FOOD_AND_HYDRATION(R.string.health_group_food_and_hydration),
    SLEEP(R.string.health_group_sleep),
    HEALTH_MEASUREMENTS(R.string.health_group_health_measurements),
    GOALS_AND_INDICATORS(R.string.health_group_goals_and_indicators),
    PROFILE(R.string.health_group_profile),
}

/**
 * The cataloged Samsung Health capabilities this application can read.
 *
 * The catalog is deliberate and versioned here: upgrading the SDK never adds a category on its own,
 * and a capability the SDK exposes without an entry below is unimplemented, never authorized.
 * Declaration order is display order.
 */
enum class HealthCategory(
    val id: String,
    val group: HealthCategoryGroup,
    @param:StringRes val label: Int,
) {
    ACTIVITY_SUMMARY("activity_summary", HealthCategoryGroup.ACTIVITY, R.string.health_category_activity_summary),
    EXERCISE("exercise", HealthCategoryGroup.ACTIVITY, R.string.health_category_exercise),
    EXERCISE_LOCATION("exercise_location", HealthCategoryGroup.ACTIVITY, R.string.health_category_exercise_location),
    FLOORS_CLIMBED("floors_climbed", HealthCategoryGroup.ACTIVITY, R.string.health_category_floors_climbed),
    STEPS("steps", HealthCategoryGroup.ACTIVITY, R.string.health_category_steps),

    NUTRITION("nutrition", HealthCategoryGroup.FOOD_AND_HYDRATION, R.string.health_category_nutrition),
    NUTRITION_GOAL("nutrition_goal", HealthCategoryGroup.FOOD_AND_HYDRATION, R.string.health_category_nutrition_goal),
    WATER_INTAKE("water_intake", HealthCategoryGroup.FOOD_AND_HYDRATION, R.string.health_category_water_intake),
    WATER_INTAKE_GOAL("water_intake_goal", HealthCategoryGroup.FOOD_AND_HYDRATION, R.string.health_category_water_intake_goal),

    SLEEP("sleep", HealthCategoryGroup.SLEEP, R.string.health_category_sleep),
    SLEEP_APNEA("sleep_apnea", HealthCategoryGroup.SLEEP, R.string.health_category_sleep_apnea),
    SLEEP_GOAL("sleep_goal", HealthCategoryGroup.SLEEP, R.string.health_category_sleep_goal),
    SKIN_TEMPERATURE("skin_temperature", HealthCategoryGroup.SLEEP, R.string.health_category_skin_temperature),

    BLOOD_GLUCOSE("blood_glucose", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_blood_glucose),
    BLOOD_OXYGEN("blood_oxygen", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_blood_oxygen),
    BLOOD_PRESSURE("blood_pressure", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_blood_pressure),
    BODY_COMPOSITION("body_composition", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_body_composition),
    BODY_TEMPERATURE("body_temperature", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_body_temperature),
    HEART_RATE("heart_rate", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_heart_rate),
    IRREGULAR_HEART_RHYTHM("irregular_heart_rhythm", HealthCategoryGroup.HEALTH_MEASUREMENTS, R.string.health_category_irregular_heart_rhythm),

    ACTIVE_CALORIES_BURNED_GOAL("active_calories_burned_goal", HealthCategoryGroup.GOALS_AND_INDICATORS, R.string.health_category_active_calories_burned_goal),
    ACTIVE_TIME_GOAL("active_time_goal", HealthCategoryGroup.GOALS_AND_INDICATORS, R.string.health_category_active_time_goal),
    ENERGY_SCORE("energy_score", HealthCategoryGroup.GOALS_AND_INDICATORS, R.string.health_category_energy_score),
    STEPS_GOAL("steps_goal", HealthCategoryGroup.GOALS_AND_INDICATORS, R.string.health_category_steps_goal),

    USER_PROFILE("user_profile", HealthCategoryGroup.PROFILE, R.string.health_category_user_profile),
    ;

    /** The category this one is shown under, while keeping its own Permission State. */
    val shownUnder: HealthCategory?
        get() = if (this == EXERCISE_LOCATION) EXERCISE else null

    companion object {
        fun byId(id: String): HealthCategory? = entries.firstOrNull { it.id == id }
    }
}
