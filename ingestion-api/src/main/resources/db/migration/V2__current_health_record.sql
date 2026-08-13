-- The Current Health Record says which Observed Record Version is the latest known state of a Health
-- Record, and nothing else: every field a reader wants belongs to the version it points at, and
-- copying those fields here would be a second, divergeable copy of an immutable row.
create table current_health_record (
    health_record_identity_id bigint primary key references health_record_identity (id),
    observed_record_version_id bigint not null references observed_record_version (id)
);

-- The one definition of what "current" means, so that the incremental projection and a full rebuild
-- cannot drift: they are the same statement over a different set of identities.
--
-- It recomputes from the preserved versions instead of comparing against what arrived, so an
-- observation that reaches the API late never overwrites a newer one that is already projected.
create function project_current_health_record(identity_ids bigint[]) returns integer language plpgsql as $$
declare
    projected integer;
begin
    -- What a caller that wrote nothing needs: the rebuild would otherwise recompute while an
    -- ingestion commits a newer version underneath it, and project the one it happened to read. An
    -- ingestion already holds these rows from its own identity upsert, so here it costs nothing.
    -- The order is stable, not deadlock proof: the ingestion takes its identity locks in the order
    -- of the batch, long before this function is reached, and no lock taken here can undo that.
    perform id from health_record_identity where id = any (identity_ids) order by id for update;

    insert into current_health_record (health_record_identity_id, observed_record_version_id)
    select distinct on (v.health_record_identity_id) v.health_record_identity_id, v.id
    from observed_record_version v
    where v.health_record_identity_id = any (identity_ids)
    order by v.health_record_identity_id, v.observed_at desc, v.first_received_at desc, v.id desc
    on conflict (health_record_identity_id)
        do update set observed_record_version_id = excluded.observed_record_version_id;

    get diagnostics projected = row_count;
    return projected;
end;
$$;

select project_current_health_record(array(select id from health_record_identity));

-- The read surface. It is a separate schema so that read access can be granted over the projection
-- without reaching the tables the ingestion writes.
create schema read_model;

-- A removal is current state, not a current measurement: it carries no biometric content, so a
-- removed Health Record leaves this view instead of appearing in it with empty columns.
--
-- A null source app or device is the explicit unknown of the Source Provenance, which is what the
-- source reported and not a missing field.
create view read_model.current_heart_rate as
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
    v.mapper_version
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
where v.record_type = 'heart_rate' and v.state = 'present';
