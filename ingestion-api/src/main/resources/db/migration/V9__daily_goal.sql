-- The goal of every kind Samsung Health held in force on each local day, typed by kind. Samsung Health
-- repeats a goal on every day it stays in force, so a day is marked as changed when its goal differs from
-- the one of the day observed before it; a day observed without a goal leaves this view, but still counts
-- as the day before the next one.
create view read_model.current_daily_goal as
select
    snapshot_type as goal,
    cast(window_start as date) as day,
    (content ->> 'steps')::bigint as steps,
    (content ->> 'calories')::numeric as calories,
    make_interval(secs => (content ->> 'active_time')::double precision) as active_time,
    (content ->> 'amount')::numeric as amount,
    (content ->> 'bed_time')::time as bed_time,
    (content ->> 'wake_up_time')::time as wake_up_time,
    changed,
    partial,
    observed_at,
    observed_at_offset,
    source_scope,
    mapper_version
from (
    select
        *,
        content is distinct from lag(content) over (partition by snapshot_type order by window_start) as changed
    from read_model.current_observed_snapshot
    where snapshot_type in (
        'active_calories_burned_goal',
        'active_time_goal',
        'nutrition_goal',
        'sleep_goal',
        'steps_goal',
        'water_intake_goal'
    )
) goal
where not empty;
