create table ingestion_device (
    id bigserial primary key,
    device_label text not null unique,
    token_digest bytea not null unique,
    created_at timestamptz not null,
    revoked_at timestamptz
);

create table health_record_identity (
    id bigserial primary key,
    record_type text not null,
    samsung_uid text not null,
    unique (record_type, samsung_uid)
);

create table observed_record_version (
    id bigserial primary key,
    health_record_identity_id bigint not null references health_record_identity (id),
    content_digest bytea not null,
    record_type text not null,
    state text not null check (state in ('present', 'removed')),
    observed_at timestamptz not null,
    observed_at_offset text not null,
    period_start timestamptz,
    period_start_offset text,
    period_end timestamptz,
    period_end_offset text,
    mapper_version text not null,
    envelope jsonb not null,
    first_received_at timestamptz not null,
    unique (health_record_identity_id, content_digest)
);

create index observed_record_version_by_identity_observation
    on observed_record_version (health_record_identity_id, observed_at desc);

create table ingestion (
    id uuid primary key,
    ingestion_device_id bigint not null references ingestion_device (id),
    contract_version integer not null,
    item_count integer not null,
    received_at timestamptz not null
);

-- A rejected position stores its codes and nothing else: no rejected JSON, no payload fragment and
-- no plaintext Samsung UID, so an audit of failures cannot become a second copy of health content.
create table ingestion_item (
    ingestion_id uuid not null references ingestion (id),
    position integer not null,
    status text not null check (status in ('accepted', 'already_present', 'rejected')),
    observed_record_version_id bigint references observed_record_version (id),
    rejection_codes text [] not null default '{}',
    primary key (ingestion_id, position),
    constraint ingestion_item_result_shape check (
        case status
            when 'rejected' then observed_record_version_id is null and cardinality(rejection_codes) > 0
            else observed_record_version_id is not null and cardinality(rejection_codes) = 0
        end
    )
);

create function reject_observed_record_version_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'observed record versions are immutable'
        using errcode = 'restrict_violation';
end;
$$;

-- Statement level, so that even a mutation matching no row fails loudly instead of looking allowed.
create trigger observed_record_version_is_immutable
    before update or delete or truncate on observed_record_version
    for each statement execute function reject_observed_record_version_mutation();
