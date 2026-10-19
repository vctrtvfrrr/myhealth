-- The read surface of a meal, a water intake and a climb. On the terms heart rate set: derived from the
-- Observed Record Version the projection points at, reading nothing but the normalized payload, and
-- leaving out a removed record instead of showing it with empty columns.

-- The meal type is the constant the source named it with; one the source left undefined is null, and so
-- is every nutrient that was not logged. Each column names the unit Samsung Health documents for it.
create view read_model.current_nutrition as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' ->> 'mealType' as meal_type,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' ->> 'title' as title,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'calories' ->> 'value')::numeric as calories_kcal,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'totalFat' ->> 'value')::numeric as total_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'saturatedFat' ->> 'value')::numeric as saturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'polysaturatedFat' ->> 'value')::numeric as polysaturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'monosaturatedFat' ->> 'value')::numeric as monosaturated_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'transFat' ->> 'value')::numeric as trans_fat_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'carbohydrate' ->> 'value')::numeric as carbohydrate_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'dietaryFiber' ->> 'value')::numeric as dietary_fiber_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'sugar' ->> 'value')::numeric as sugar_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'protein' ->> 'value')::numeric as protein_g,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'cholesterol' ->> 'value')::numeric as cholesterol_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'sodium' ->> 'value')::numeric as sodium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'potassium' ->> 'value')::numeric as potassium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'vitaminA' ->> 'value')::numeric as vitamin_a_ug,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'vitaminC' ->> 'value')::numeric as vitamin_c_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'calcium' ->> 'value')::numeric as calcium_mg,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'nutrition' -> 'iron' ->> 'value')::numeric as iron_mg,
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
where v.record_type = 'nutrition' and v.state = 'present';

create view read_model.current_water_intake as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'waterIntake' -> 'amount' ->> 'value')::numeric as amount_ml,
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
where v.record_type = 'water_intake' and v.state = 'present';

-- The count carries no unit because Samsung Health documents none.
create view read_model.current_floors_climbed as
select
    i.record_type,
    i.samsung_uid,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'floorsClimbed' ->> 'floors')::numeric as floors,
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
where v.record_type = 'floors_climbed' and v.state = 'present';
