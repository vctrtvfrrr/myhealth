-- The profile, the one Observed Snapshot without a Snapshot Window. Only a change of it is ever stored
-- (ADR 0025), so every observation kept is a step of its history, and the last one in observation time
-- is the current profile. An observation of no profile at all stays here as empty, because it is what
-- the source said.
--
-- The date of birth is text: Samsung Health does not document its format.
create view read_model.user_profile as
select
    o.observed_at,
    o.observed_at_offset,
    o.id = c.snapshot_observation_id as current,
    o.empty,
    o.observation -> 'content' ->> 'date_of_birth' as date_of_birth,
    (o.observation -> 'content' ->> 'height')::numeric as height_cm,
    (o.observation -> 'content' ->> 'weight')::numeric as weight_kg,
    o.observation -> 'content' ->> 'gender' as gender,
    o.observation -> 'content' ->> 'nickname' as nickname,
    o.source_scope,
    o.mapper_version
from snapshot_observation o
join current_observed_snapshot c on c.observed_snapshot_id = o.observed_snapshot_id
where o.snapshot_type = 'user_profile';
