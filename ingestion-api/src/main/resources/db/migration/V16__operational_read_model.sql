-- The Health Category of the batch an Ingestion carried, its `recordType` or `snapshotType`. Every batch
-- names one, so the API writes it from now on; an Ingestion recorded before that gets it derived from
-- what it stored, an accepted or already present item or else its inventory. One that stored neither,
-- because every item was rejected, stays without a category: nothing it kept says which one it was.
alter table ingestion add column health_category text;

update ingestion i
set health_category = coalesce(
    (
        select coalesce(v.record_type, o.snapshot_type)
        from ingestion_item item
        left join observed_record_version v on v.id = item.observed_record_version_id
        left join snapshot_observation o on o.id = item.snapshot_observation_id
        where item.ingestion_id = i.id and item.status <> 'rejected'
        order by item.position
        limit 1
    ),
    (select d.record_type from declared_inventory d where d.ingestion_id = i.id)
);

-- Not valid, so that it binds every Ingestion recorded from now on and leaves the legacy nulls alone.
alter table ingestion add constraint ingestion_names_its_health_category check (health_category is not null) not valid;

create index ingestion_by_received_at on ingestion (received_at);

-- The synchronization as the API saw it, one row per Ingestion, for the operational panels. It carries
-- no health content and no Ingestion Device, and leaves grouping in time to each panel's own query.
create view read_model.ingestion_outcome as
select
    i.id as ingestion_id,
    i.received_at,
    i.health_category,
    i.contract_version,
    i.item_count,
    items.accepted as accepted_count,
    items.already_present as already_present_count,
    items.rejected as rejected_count,
    d.status as inventory_status,
    d.removal_count as concluded_removal_count
from ingestion i
cross join lateral (
    select
        count(*) filter (where item.status = 'accepted') as accepted,
        count(*) filter (where item.status = 'already_present') as already_present,
        count(*) filter (where item.status = 'rejected') as rejected
    from ingestion_item item
    where item.ingestion_id = i.id
) items
left join declared_inventory d on d.ingestion_id = i.id;

-- One row per rejection code of every rejected item: an item refused for two reasons counts under both.
create view read_model.ingestion_rejection as
select
    i.id as ingestion_id,
    i.received_at,
    i.health_category,
    i.contract_version,
    item.position,
    code.rejection_code
from ingestion_item item
join ingestion i on i.id = item.ingestion_id
cross join lateral unnest(item.rejection_codes) as code (rejection_code)
where item.status = 'rejected';
