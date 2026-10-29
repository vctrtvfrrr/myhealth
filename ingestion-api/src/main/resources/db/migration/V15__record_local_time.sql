-- Record Local Time (ADR 0029): every instant a view carries with an offset is also exposed as the wall
-- clock of that offset, a `timestamp` appended at the end of the view as `<instant>_local`. It is the UTC
-- instant plus the offset as an interval, which `at time zone` cannot do because it reads a numeric offset
-- with the POSIX sign. What a record carries inside it, a session, a stage, a bin, a reading or a route
-- point, has no offset of its own and is read under the offset its record starts at. Snapshot Windows are
-- already local and stay as they are.
--
-- The views still do not aggregate: each panel groups by `local_bucket`, the one place where a week
-- starts on Sunday, as it does in the phone's calendar.
create function read_model.local_bucket(granularity text, local_time timestamp) returns timestamp
    language sql immutable strict parallel safe as $$
    select case granularity
        when 'week' then date_trunc('day', local_time) - extract(dow from local_time) * interval '1 day'
        else date_trunc(granularity, local_time)
    end
$$;

create or replace view read_model.current_heart_rate as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' ->> 'value')::numeric as beats_per_minute,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' ->> 'unit' as unit,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'heart_rate' and v.state = 'present';

create or replace view read_model.current_exercise as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' ->> 'type' as exercise_type,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'duration' ->> 'value')::numeric
        as duration_seconds,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'distance' ->> 'value')::numeric
        as distance_meters,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'calories' ->> 'value')::numeric
        as calories_kilocalories,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'exercise' and v.state = 'present';

create or replace view read_model.current_exercise_location as
select
    i.samsung_uid,
    point.ordinality as position,
    (point.value ->> 'at')::timestamptz as at,
    (point.value ->> 'latitudeDegrees')::numeric as latitude_degrees,
    (point.value ->> 'longitudeDegrees')::numeric as longitude_degrees,
    (point.value ->> 'altitudeMeters')::numeric as altitude_meters,
    (point.value ->> 'accuracyMeters')::numeric as accuracy_meters,
    ((point.value ->> 'at')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'route', '[]'::jsonb)
) with ordinality as point (value, ordinality)
where v.record_type = 'exercise' and v.state = 'present';

create or replace view read_model.current_sleep as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' -> 'duration' ->> 'value')::numeric
        as duration_seconds,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' ->> 'score')::numeric as sleep_score,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'sleep' and v.state = 'present';

create or replace view read_model.current_sleep_session as
select
    i.samsung_uid,
    session.ordinality as position,
    (session.value ->> 'start')::timestamptz as start_at,
    (session.value ->> 'end')::timestamptz as end_at,
    (session.value ->> 'durationSeconds')::numeric as duration_seconds,
    ((session.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((session.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
where v.record_type = 'sleep' and v.state = 'present';

create or replace view read_model.current_sleep_stage as
select
    i.samsung_uid,
    session.ordinality as session_position,
    stage.ordinality as position,
    (stage.value ->> 'start')::timestamptz as start_at,
    (stage.value ->> 'end')::timestamptz as end_at,
    stage.value ->> 'stage' as stage,
    ((stage.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((stage.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
cross join lateral jsonb_array_elements(coalesce(session.value -> 'stages', '[]'::jsonb))
    with ordinality as stage (value, ordinality)
where v.record_type = 'sleep' and v.state = 'present';

create or replace view read_model.current_blood_oxygen as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodOxygen' -> 'saturation' ->> 'value')::numeric
        as oxygen_saturation_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodOxygen' -> 'min' ->> 'value')::numeric
        as min_oxygen_saturation_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodOxygen' -> 'max' ->> 'value')::numeric
        as max_oxygen_saturation_percent,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_oxygen' and v.state = 'present';

create or replace view read_model.current_blood_oxygen_series as
select
    i.samsung_uid,
    bin.ordinality as position,
    (bin.value ->> 'start')::timestamptz as start_at,
    (bin.value ->> 'end')::timestamptz as end_at,
    (bin.value ->> 'saturationPercent')::numeric as oxygen_saturation_percent,
    (bin.value ->> 'minPercent')::numeric as min_oxygen_saturation_percent,
    (bin.value ->> 'maxPercent')::numeric as max_oxygen_saturation_percent,
    ((bin.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((bin.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodOxygen' -> 'series', '[]'::jsonb)
) with ordinality as bin (value, ordinality)
where v.record_type = 'blood_oxygen' and v.state = 'present';

create or replace view read_model.current_skin_temperature as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'skinTemperature' -> 'temperature' ->> 'value')::numeric
        as temperature_celsius,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'skinTemperature' -> 'min' ->> 'value')::numeric
        as min_temperature_celsius,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'skinTemperature' -> 'max' ->> 'value')::numeric
        as max_temperature_celsius,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'skin_temperature' and v.state = 'present';

create or replace view read_model.current_skin_temperature_series as
select
    i.samsung_uid,
    bin.ordinality as position,
    (bin.value ->> 'start')::timestamptz as start_at,
    (bin.value ->> 'end')::timestamptz as end_at,
    (bin.value ->> 'temperatureCelsius')::numeric as temperature_celsius,
    (bin.value ->> 'minCelsius')::numeric as min_temperature_celsius,
    (bin.value ->> 'maxCelsius')::numeric as max_temperature_celsius,
    ((bin.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((bin.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'skinTemperature' -> 'series', '[]'::jsonb)
) with ordinality as bin (value, ordinality)
where v.record_type = 'skin_temperature' and v.state = 'present';

create or replace view read_model.current_blood_glucose as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' -> 'glucose' ->> 'value')::numeric
        as glucose_mmol_per_liter,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'measurementType' as measurement_type,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'sampleSourceType' as sample_source_type,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'mealStatus' as meal_status,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'mealTime')::timestamptz as meal_at,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'insulinInjected')::numeric
        as insulin_injected,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' ->> 'medicationTaken')::boolean
        as medication_taken,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_glucose' and v.state = 'present';

create or replace view read_model.current_blood_glucose_series as
select
    i.samsung_uid,
    reading.ordinality as position,
    (reading.value ->> 'at')::timestamptz as at,
    (reading.value ->> 'glucoseMmolPerLiter')::numeric as glucose_mmol_per_liter,
    ((reading.value ->> 'at')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' -> 'series', '[]'::jsonb)
) with ordinality as reading (value, ordinality)
where v.record_type = 'blood_glucose' and v.state = 'present';

create or replace view read_model.current_blood_pressure as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodPressure' -> 'systolic' ->> 'value')::numeric as systolic_mmhg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodPressure' -> 'diastolic' ->> 'value')::numeric as diastolic_mmhg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodPressure' -> 'mean' ->> 'value')::numeric as mean_mmhg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodPressure' ->> 'pulseRate')::integer as pulse_rate,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodPressure' ->> 'medicationTaken')::boolean as medication_taken,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_pressure' and v.state = 'present';

create or replace view read_model.current_body_composition as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'weight' ->> 'value')::numeric as weight_kg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'height' ->> 'value')::numeric as height_cm,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'bodyFat' ->> 'value')::numeric as body_fat_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'skeletalMuscle' ->> 'value')::numeric
        as skeletal_muscle_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'muscleMass' ->> 'value')::numeric
        as muscle_mass_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'basalMetabolicRate' ->> 'value')::numeric
        as basal_metabolic_rate_kcal_per_day,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'bodyFatMass' ->> 'value')::numeric
        as body_fat_mass_kg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'fatFreeMass' ->> 'value')::numeric
        as fat_free_mass_kg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'fatFree' ->> 'value')::numeric as fat_free_percent,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'skeletalMuscleMass' ->> 'value')::numeric
        as skeletal_muscle_mass_kg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' -> 'totalBodyWater' ->> 'value')::numeric
        as total_body_water_liters,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyComposition' ->> 'bodyMassIndex')::numeric as body_mass_index,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'body_composition' and v.state = 'present';

create or replace view read_model.current_body_temperature as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'bodyTemperature' -> 'temperature' ->> 'value')::numeric
        as temperature_celsius,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'body_temperature' and v.state = 'present';

create or replace view read_model.current_nutrition as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' ->> 'mealType' as meal_type,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' ->> 'title' as title,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'calories' ->> 'value')::numeric as calories_kcal,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'totalFat' ->> 'value')::numeric as total_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'saturatedFat' ->> 'value')::numeric as saturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'polysaturatedFat' ->> 'value')::numeric as polysaturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'monosaturatedFat' ->> 'value')::numeric as monosaturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'transFat' ->> 'value')::numeric as trans_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'carbohydrate' ->> 'value')::numeric as carbohydrate_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'dietaryFiber' ->> 'value')::numeric as dietary_fiber_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'sugar' ->> 'value')::numeric as sugar_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'protein' ->> 'value')::numeric as protein_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'cholesterol' ->> 'value')::numeric as cholesterol_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'sodium' ->> 'value')::numeric as sodium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'potassium' ->> 'value')::numeric as potassium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'vitaminA' ->> 'value')::numeric as vitamin_a_ug,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'vitaminC' ->> 'value')::numeric as vitamin_c_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'calcium' ->> 'value')::numeric as calcium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'iron' ->> 'value')::numeric as iron_mg,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'nutrition' and v.state = 'present';

create or replace view read_model.current_water_intake as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'waterIntake' -> 'amount' ->> 'value')::numeric as amount_ml,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'water_intake' and v.state = 'present';

create or replace view read_model.current_floors_climbed as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'floorsClimbed' ->> 'floors')::numeric as floors,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'floors_climbed' and v.state = 'present';

create or replace view read_model.current_irregular_heart_rhythm as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'irregularHeartRhythm' ->> 'status' as status,
    v.period_start as detected_at,
    v.period_start_offset as detected_at_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as detected_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'irregular_heart_rhythm' and v.state = 'present';

create or replace view read_model.current_sleep_apnea as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'sleepApnea' ->> 'result' as result,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'sleep_apnea' and v.state = 'present';

create or replace view read_model.current_energy_score as
select
    i.record_type,
    i.samsung_uid,
    cast((v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as date) as local_date,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'energyScore' ->> 'score')::numeric as score,
    v.period_start,
    v.period_start_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'energy_score' and v.state = 'present';
