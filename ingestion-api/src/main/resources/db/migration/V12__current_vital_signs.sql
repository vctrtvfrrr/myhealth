-- The read surface of blood pressure, body composition and body temperature: readings of plain fields.
-- On the terms heart rate set: derived from the Observed Record Version the projection points at,
-- reading nothing but the normalized payload, and leaving out a removed reading instead of showing it
-- with empty columns. A measure the source did not report is null.

-- The pulse rate carries no unit because Samsung Health documents none.
create view read_model.current_blood_pressure as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_pressure' and v.state = 'present';

-- Each measure in the unit Samsung Health documents for it, muscle mass as a percentage included. The
-- body mass index is computed by Samsung Health and carries no documented unit.
create view read_model.current_body_composition as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'body_composition' and v.state = 'present';

create view read_model.current_body_temperature as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'body_temperature' and v.state = 'present';
