-- The goals now carry their units, as an hour of activity does: each quantity is a `{value, unit}` read
-- in the one unit the API validated it in, and named after that unit. A goal sent before it carried its
-- unit, by version 1 of its mapper, was in the same unit and is read as the same goal.
--
-- A day is marked as changed when its typed goal differs from the one of the day observed before it, so
-- that the day the units arrived is no change of goal. A day observed without a goal leaves this view,
-- but still counts as the day before the next one.
drop view read_model.current_daily_goal;

create view read_model.current_daily_goal as
select
    goal,
    day,
    steps,
    active_calories_burned_kilocalories,
    active_time_seconds,
    calories_kilocalories,
    amount_milliliters,
    bed_time,
    wake_up_time,
    changed,
    partial,
    observed_at,
    observed_at_offset,
    source_scope,
    mapper_version
from (
    select
        *,
        (steps, active_calories_burned_kilocalories, active_time_seconds, calories_kilocalories,
            amount_milliliters, bed_time, wake_up_time)
            is distinct from lag((steps, active_calories_burned_kilocalories, active_time_seconds,
                calories_kilocalories, amount_milliliters, bed_time, wake_up_time))
                over (partition by goal order by day)
            as changed
    from (
        select
            snapshot_type as goal,
            cast(window_start as date) as day,
            empty,
            (content ->> 'steps')::bigint as steps,
            case when snapshot_type = 'active_calories_burned_goal'
                then coalesce(content -> 'activeCaloriesBurned' ->> 'value', content ->> 'calories')::numeric
            end as active_calories_burned_kilocalories,
            coalesce(content -> 'activeTime' ->> 'value', content ->> 'active_time')::numeric as active_time_seconds,
            case when snapshot_type = 'nutrition_goal'
                then coalesce(content -> 'calories' ->> 'value', content ->> 'calories')::numeric
            end as calories_kilocalories,
            coalesce(content -> 'amount' ->> 'value', content ->> 'amount')::numeric as amount_milliliters,
            coalesce(content ->> 'bedTime', content ->> 'bed_time')::time as bed_time,
            coalesce(content ->> 'wakeUpTime', content ->> 'wake_up_time')::time as wake_up_time,
            partial,
            observed_at,
            observed_at_offset,
            source_scope,
            mapper_version
        from read_model.current_observed_snapshot
        where snapshot_type in (
            'active_calories_burned_goal',
            'active_time_goal',
            'nutrition_goal',
            'sleep_goal',
            'steps_goal',
            'water_intake_goal'
        )
    ) typed
) goal
where not empty;
