-- What an application declared it saw over a stretch of local time, and what the API concluded from
-- it. The declared identities themselves are not kept: the versions they prove present are already
-- preserved, and a list of Samsung UIDs per inventory would be a second, ever growing copy of them.
--
-- An invalid inventory is recorded too, without the interval it failed to declare, so that an audit of
-- what an ingestion carried does not depend on the inventory having been well formed.
create table declared_inventory (
    ingestion_id uuid primary key references ingestion (id),
    record_type text not null,
    interval_start timestamp,
    interval_end timestamp,
    read_at timestamptz,
    declared_count integer not null,
    status text not null check (status in ('concluded', 'incomplete', 'invalid')),
    removal_count integer not null,
    constraint declared_inventory_shape check (
        case status
            when 'invalid' then removal_count = 0
            else interval_start < interval_end and read_at is not null
                and (status = 'concluded' or removal_count = 0)
        end
    )
);

-- Which Observed Record Versions of removal an inventory concluded, so that a removal nobody reported
-- can always be traced to the declaration it was concluded from.
create table concluded_removal (
    ingestion_id uuid not null references declared_inventory (ingestion_id),
    observed_record_version_id bigint not null references observed_record_version (id),
    primary key (ingestion_id, observed_record_version_id)
);
