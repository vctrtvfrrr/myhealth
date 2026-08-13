-- Read access to the read model, for a login role that already exists.
--
-- Creating the role needs a privilege the runtime role of the API does not have, so provisioning it
-- is the database administrator's step and this script only hands it what it may read. Run this as
-- the runtime role, once, and again after a deploy that adds a view to `read_model`.
--
-- Nothing here grants anything on the ingestion tables: the read account reaches the preserved
-- envelopes only through the views, which run with the privileges of the role that owns them.

grant usage on schema read_model to myhealth_read;
grant select on all tables in schema read_model to myhealth_read;
alter default privileges in schema read_model grant select on tables to myhealth_read;
