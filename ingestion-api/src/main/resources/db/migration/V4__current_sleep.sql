-- The read surface of sleep, on the terms the exercise one set: derived from the Observed Record
-- Version the projection points at, reading nothing but the normalized payload, and leaving out a
-- removed night instead of showing it with empty columns.
create view read_model.current_sleep as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'sleep' and v.state = 'present';

-- The sessions of a night, one row each, in the order the source reported them. A night is usually
-- one session; a night broken by waking up for a while is more than one.
create view read_model.current_sleep_session as
select
    i.samsung_uid,
    session.ordinality as position,
    (session.value ->> 'start')::timestamptz as start_at,
    (session.value ->> 'end')::timestamptz as end_at,
    (session.value ->> 'durationSeconds')::numeric as duration_seconds
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
where v.record_type = 'sleep' and v.state = 'present';

-- The stages, relationally: one row per stage, named by the session it belongs to and its place in it.
-- Like the route of an exercise, a stage only exists inside the record that carries it, so it is
-- reached through the same projection, and a session Samsung Health staged nothing for contributes no
-- row here while still being a row of the sessions.
create view read_model.current_sleep_stage as
select
    i.samsung_uid,
    session.ordinality as session_position,
    stage.ordinality as position,
    (stage.value ->> 'start')::timestamptz as start_at,
    (stage.value ->> 'end')::timestamptz as end_at,
    stage.value ->> 'stage' as stage
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'sleep' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
cross join lateral jsonb_array_elements(coalesce(session.value -> 'stages', '[]'::jsonb))
    with ordinality as stage (value, ordinality)
where v.record_type = 'sleep' and v.state = 'present';
