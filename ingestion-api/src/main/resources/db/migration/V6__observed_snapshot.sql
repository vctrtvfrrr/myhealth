-- Observed Snapshots live beside Health Records and share none of their constraints: a snapshot has no
-- Samsung UID and no source instant, so its identity is its type and its Snapshot Window, and its
-- observations are dated by when the application read them. See ADR 0025.
--
-- The window is local time, the clock Samsung Health groups an aggregate by. A snapshot that summarizes
-- no stretch of time has no window at all, and the identity index treats that absence as one value.
create table observed_snapshot (
    id bigserial primary key,
    snapshot_type text not null,
    window_start timestamp,
    window_end timestamp,
    constraint observed_snapshot_window_shape check (
        (window_start is null and window_end is null) or window_start < window_end
    )
);

create unique index observed_snapshot_identity on observed_snapshot (
    snapshot_type,
    coalesce(window_start, '-infinity'::timestamp),
    coalesce(window_end, '-infinity'::timestamp)
);

-- Nothing here is unique on the content digest: a value that returns to one seen before is a change
-- from the observation before it, so the same content may be stored many times over a snapshot's life.
create table snapshot_observation (
    id bigserial primary key,
    observed_snapshot_id bigint not null references observed_snapshot (id),
    content_digest bytea not null,
    snapshot_type text not null,
    observed_at timestamptz not null,
    observed_at_offset text not null,
    source_scope text not null,
    mapper_version text not null,
    empty boolean not null,
    observation jsonb not null,
    first_received_at timestamptz not null
);

create index snapshot_observation_by_snapshot_observation
    on snapshot_observation (observed_snapshot_id, observed_at desc, first_received_at desc, id desc);

create trigger snapshot_observation_is_immutable
    before update or delete or truncate on snapshot_observation
    for each statement execute function reject_observed_record_version_mutation();

-- A position of a snapshot batch points at the observation that stands for it: the one it stored, or
-- the earlier one it matched and left standing as the first moment that content was seen.
alter table ingestion_item add column snapshot_observation_id bigint references snapshot_observation (id);

alter table ingestion_item drop constraint ingestion_item_result_shape;

alter table ingestion_item add constraint ingestion_item_result_shape check (
    case status
        when 'rejected' then observed_record_version_id is null and snapshot_observation_id is null
            and cardinality(rejection_codes) > 0
        else num_nonnulls(observed_record_version_id, snapshot_observation_id) = 1
            and cardinality(rejection_codes) = 0
    end
);

-- Which observation is the latest known state of a snapshot, and nothing else, on the terms ADR 0012
-- sets for the Current Health Record.
create table current_observed_snapshot (
    observed_snapshot_id bigint primary key references observed_snapshot (id),
    snapshot_observation_id bigint not null references snapshot_observation (id)
);

-- The one definition of the Current Observed Snapshot, shared by the ingestion and the rebuild: the
-- largest observation time wins, so an observation that arrives late never becomes current.
create function project_current_observed_snapshot(snapshot_ids bigint[]) returns integer language plpgsql as $$
declare
    projected integer;
begin
    perform id from observed_snapshot where id = any (snapshot_ids) order by id for update;

    insert into current_observed_snapshot (observed_snapshot_id, snapshot_observation_id)
    select distinct on (o.observed_snapshot_id) o.observed_snapshot_id, o.id
    from snapshot_observation o
    where o.observed_snapshot_id = any (snapshot_ids)
    order by o.observed_snapshot_id, o.observed_at desc, o.first_received_at desc, o.id desc
    on conflict (observed_snapshot_id)
        do update set snapshot_observation_id = excluded.snapshot_observation_id;

    get diagnostics projected = row_count;
    return projected;
end;
$$;

-- Every current snapshot, whatever its type. An observation made before its window ended is partial,
-- judged in local time: the observation instant shifted by the offset it was read under, which
-- `at time zone` cannot do because it reads a numeric offset with the POSIX sign.
--
-- An empty window stays here with no content, because it is what the source currently says about it.
create view read_model.current_observed_snapshot as
select
    s.snapshot_type,
    s.window_start,
    s.window_end,
    o.observed_at,
    o.observed_at_offset,
    s.window_end is not null
        and (o.observed_at at time zone 'UTC') + cast(o.observed_at_offset as interval) < s.window_end
        as partial,
    o.empty,
    nullif(o.observation -> 'content', 'null'::jsonb) as content,
    o.source_scope,
    o.mapper_version
from current_observed_snapshot c
join snapshot_observation o on o.id = c.snapshot_observation_id
join observed_snapshot s on s.id = c.observed_snapshot_id;

-- The steps of every local hour Samsung Health counted any in. An hour observed as empty leaves this
-- view, as a removed Health Record leaves its own, so a daily total is a plain sum over it.
create view read_model.current_hourly_steps as
select
    window_start,
    window_end,
    (content ->> 'steps')::bigint as steps,
    partial,
    observed_at,
    observed_at_offset,
    source_scope,
    mapper_version
from read_model.current_observed_snapshot
where snapshot_type = 'steps' and not empty;
