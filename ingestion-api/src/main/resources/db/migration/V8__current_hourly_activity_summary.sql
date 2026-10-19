-- The activity Samsung Health totals in every local hour it measured any in, merged across the phone and
-- the wearables. Each total is read in the one unit the API validated it in, and stays null when the
-- source reported none, so a daily total is a plain sum over the hours. An hour observed as empty leaves
-- this view, as it leaves the one of the steps.
create view read_model.current_hourly_activity_summary as
select
    window_start,
    window_end,
    (content -> 'activeCaloriesBurned' ->> 'value')::numeric as active_calories_burned_kilocalories,
    (content -> 'caloriesBurned' ->> 'value')::numeric as calories_burned_kilocalories,
    (content -> 'activeTime' ->> 'value')::numeric as active_time_seconds,
    (content -> 'distance' ->> 'value')::numeric as distance_meters,
    partial,
    observed_at,
    observed_at_offset,
    source_scope,
    mapper_version
from read_model.current_observed_snapshot
where snapshot_type = 'activity_summary' and not empty;
