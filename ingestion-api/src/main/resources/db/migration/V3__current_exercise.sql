-- The read surface of the exercises, on the same terms the heart rate one is: derived from the
-- Observed Record Version the projection points at, and reading nothing but the normalized payload,
-- so the names Samsung Health gives its own fields never reach a query.
--
-- A removal is current state, not a current exercise: it carries no content, so a removed Health
-- Record leaves this view instead of appearing in it with empty columns.
create view read_model.current_exercise as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'exercise' and v.state = 'present';

-- The route, relationally: one row per location, because that is the shape a reader plots a track
-- from and the exercise itself has nothing to say per point.
--
-- The route only exists inside the exercise it belongs to, so this reaches it through the same
-- projection rather than through a table of its own. An exercise whose route Samsung Health did not
-- disclose contributes no row, which is why the lateral join is over the array and not the record.
create view read_model.current_exercise_location as
select
    i.samsung_uid,
    point.ordinality as position,
    (point.value ->> 'at')::timestamptz as at,
    (point.value ->> 'latitudeDegrees')::numeric as latitude_degrees,
    (point.value ->> 'longitudeDegrees')::numeric as longitude_degrees,
    (point.value ->> 'altitudeMeters')::numeric as altitude_meters,
    (point.value ->> 'accuracyMeters')::numeric as accuracy_meters
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'route', '[]'::jsonb)
) with ordinality as point (value, ordinality)
where v.record_type = 'exercise' and v.state = 'present';
