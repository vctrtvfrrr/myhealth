-- The read surface of what the watch monitors report, irregular heart rhythm notifications and sleep apnea
-- assessments, and of the daily energy score. On the terms heart rate set: derived from the Observed Record
-- Version the projection points at, reading nothing but the normalized payload, and leaving out a removed
-- record instead of showing it with empty columns.
--
-- A notification and an assessment are kept even when the source left their constant undefined, which is
-- then null here.

create view read_model.current_irregular_heart_rhythm as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'irregular_heart_rhythm' and v.state = 'present';

create view read_model.current_sleep_apnea as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'sleep_apnea' and v.state = 'present';

-- Samsung Health keeps one score per local day, dated at the start of that day with no end, and documents
-- no scale for it. The day is the local date of that start under the offset it was produced in, shifted by
-- adding the offset as an interval, which `at time zone` cannot do because it reads a numeric offset with
-- the POSIX sign.
create view read_model.current_energy_score as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'energy_score' and v.state = 'present';
