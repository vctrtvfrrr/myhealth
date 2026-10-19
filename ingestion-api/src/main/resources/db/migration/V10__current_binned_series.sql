-- The read surface of the measurements that carry a binned series: blood oxygen, skin temperature and
-- blood glucose. On the terms the sleep set: derived from the Observed Record Version the projection
-- points at, reading nothing but the normalized payload, and leaving out a removed measurement instead
-- of showing it with empty columns. Each series is a view of its own, one row per bin, the way the
-- stages of a night are: a bin has no identity of its own and only exists inside the record carrying it.
--
-- The blood oxygen and skin temperature Samsung Health associates with a night are rows here like any
-- other; the night they belong to is the sleep whose period contains them.

create view read_model.current_blood_oxygen as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_oxygen' and v.state = 'present';

create view read_model.current_blood_oxygen_series as
select
    i.samsung_uid,
    bin.ordinality as position,
    (bin.value ->> 'start')::timestamptz as start_at,
    (bin.value ->> 'end')::timestamptz as end_at,
    (bin.value ->> 'saturationPercent')::numeric as oxygen_saturation_percent,
    (bin.value ->> 'minPercent')::numeric as min_oxygen_saturation_percent,
    (bin.value ->> 'maxPercent')::numeric as max_oxygen_saturation_percent
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodOxygen' -> 'series', '[]'::jsonb)
) with ordinality as bin (value, ordinality)
where v.record_type = 'blood_oxygen' and v.state = 'present';

create view read_model.current_skin_temperature as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'skin_temperature' and v.state = 'present';

create view read_model.current_skin_temperature_series as
select
    i.samsung_uid,
    bin.ordinality as position,
    (bin.value ->> 'start')::timestamptz as start_at,
    (bin.value ->> 'end')::timestamptz as end_at,
    (bin.value ->> 'temperatureCelsius')::numeric as temperature_celsius,
    (bin.value ->> 'minCelsius')::numeric as min_temperature_celsius,
    (bin.value ->> 'maxCelsius')::numeric as max_temperature_celsius
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'skinTemperature' -> 'series', '[]'::jsonb)
) with ordinality as bin (value, ordinality)
where v.record_type = 'skin_temperature' and v.state = 'present';

-- The context of a reading is the constant the source named it with; one the source left undefined is
-- null. The insulin carries no unit because Samsung Health documents none.
create view read_model.current_blood_glucose as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'blood_glucose' and v.state = 'present';

create view read_model.current_blood_glucose_series as
select
    i.samsung_uid,
    reading.ordinality as position,
    (reading.value ->> 'at')::timestamptz as at,
    (reading.value ->> 'glucoseMmolPerLiter')::numeric as glucose_mmol_per_liter
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'bloodGlucose' -> 'series', '[]'::jsonb)
) with ordinality as reading (value, ordinality)
where v.record_type = 'blood_glucose' and v.state = 'present';
