-- Heart rate as a ranged measurement, the way blood oxygen and skin temperature already are. Version 2 of
-- its mapper sends the rate with the range it moved in and the series it was binned from; version 1 sent
-- the rate alone, as `heartRate.value`, and a record it delivered keeps a null range and no series until
-- a Full Reconciliation reads it again under version 2.
--
-- The view is dropped rather than replaced so that the range sits beside the rate and the local columns
-- stay last. The read account keeps reading it through the default privileges of `read-access.sql`.
drop view read_model.current_heart_rate;

create view read_model.current_heart_rate as
select
    i.record_type,
    i.samsung_uid,
    coalesce(
        v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' -> 'rate' ->> 'value',
        v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' ->> 'value'
    )::numeric as beats_per_minute,
    coalesce(
        v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' -> 'rate' ->> 'unit',
        v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' ->> 'unit'
    ) as unit,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' -> 'min' ->> 'value')::numeric
        as min_beats_per_minute,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' -> 'max' ->> 'value')::numeric
        as max_beats_per_minute,
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

create view read_model.current_heart_rate_series as
select
    i.samsung_uid,
    bin.ordinality as position,
    (bin.value ->> 'start')::timestamptz as start_at,
    (bin.value ->> 'end')::timestamptz as end_at,
    (bin.value ->> 'rateBeatsPerMinute')::numeric as beats_per_minute,
    (bin.value ->> 'minBeatsPerMinute')::numeric as min_beats_per_minute,
    (bin.value ->> 'maxBeatsPerMinute')::numeric as max_beats_per_minute,
    ((bin.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((bin.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'heartRate' -> 'series', '[]'::jsonb)
) with ordinality as bin (value, ordinality)
where v.record_type = 'heart_rate' and v.state = 'present';
