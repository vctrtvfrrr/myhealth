package br.etc.victor.myhealthbridge.samsung

import br.etc.victor.myhealthbridge.health.HealthCategory
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes

internal val HealthCategory.dataType: DataType
    get() = when (this) {
        HealthCategory.ACTIVITY_SUMMARY -> DataTypes.ACTIVITY_SUMMARY
        HealthCategory.EXERCISE -> DataTypes.EXERCISE
        HealthCategory.EXERCISE_LOCATION -> DataTypes.EXERCISE_LOCATION
        HealthCategory.FLOORS_CLIMBED -> DataTypes.FLOORS_CLIMBED
        HealthCategory.STEPS -> DataTypes.STEPS
        HealthCategory.NUTRITION -> DataTypes.NUTRITION
        HealthCategory.NUTRITION_GOAL -> DataTypes.NUTRITION_GOAL
        HealthCategory.WATER_INTAKE -> DataTypes.WATER_INTAKE
        HealthCategory.WATER_INTAKE_GOAL -> DataTypes.WATER_INTAKE_GOAL
        HealthCategory.SLEEP -> DataTypes.SLEEP
        HealthCategory.SLEEP_APNEA -> DataTypes.SLEEP_APNEA
        HealthCategory.SLEEP_GOAL -> DataTypes.SLEEP_GOAL
        HealthCategory.SKIN_TEMPERATURE -> DataTypes.SKIN_TEMPERATURE
        HealthCategory.BLOOD_GLUCOSE -> DataTypes.BLOOD_GLUCOSE
        HealthCategory.BLOOD_OXYGEN -> DataTypes.BLOOD_OXYGEN
        HealthCategory.BLOOD_PRESSURE -> DataTypes.BLOOD_PRESSURE
        HealthCategory.BODY_COMPOSITION -> DataTypes.BODY_COMPOSITION
        HealthCategory.BODY_TEMPERATURE -> DataTypes.BODY_TEMPERATURE
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE
        HealthCategory.IRREGULAR_HEART_RHYTHM -> DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION
        HealthCategory.ACTIVE_CALORIES_BURNED_GOAL -> DataTypes.ACTIVE_CALORIES_BURNED_GOAL
        HealthCategory.ACTIVE_TIME_GOAL -> DataTypes.ACTIVE_TIME_GOAL
        HealthCategory.ENERGY_SCORE -> DataTypes.ENERGY_SCORE
        HealthCategory.STEPS_GOAL -> DataTypes.STEPS_GOAL
        HealthCategory.USER_PROFILE -> DataTypes.USER_PROFILE
    }

internal fun readPermissions(categories: Iterable<HealthCategory>): Set<Permission> =
    categories.mapTo(mutableSetOf()) { Permission.of(it.dataType, AccessType.READ) }

internal val categoriesByDataTypeName: Map<String, HealthCategory> =
    HealthCategory.entries.associateBy { it.dataType.name }
