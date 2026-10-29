-- Every session of an exercise, as version 2 of its mapper carries it: the metrics of each session, its
-- log by instant, its swim and its own leg of the route. Version 1 carried the totals and one route for
-- the whole exercise; a record it delivered keeps no session, no log and no swim, and a route without a
-- session position, until a Full Reconciliation reads it again under version 2.
--
-- The exercise type is named in Portuguese by a table of its own rather than inside the view, so that a
-- constant a later SDK introduces is shown raw until a migration names it. The table is read by the view
-- under the privileges of its owner, so the read account needs no grant on it.
create table exercise_type_label (
    exercise_type text primary key,
    label text not null
);

insert into exercise_type_label (exercise_type, label) values
    ('UNDEFINED', 'Indefinido'),
    ('OTHER', 'Outro'),
    ('WALKING', 'Caminhada'),
    ('RUNNING', 'Corrida'),
    ('STAIR_CLIMBING', 'Subida de escadas'),
    ('TRACK_RUNNING', 'Corrida na pista'),
    ('BASEBALL', 'Beisebol'),
    ('SOFTBALL', 'Softbol'),
    ('CRICKET', 'Críquete'),
    ('GOLF', 'Golfe'),
    ('BOWLING', 'Boliche'),
    ('HOCKEY', 'Hóquei'),
    ('RUGBY', 'Rúgbi'),
    ('BASKETBALL', 'Basquete'),
    ('SOCCER', 'Futebol'),
    ('HANDBALL', 'Handebol'),
    ('AMERICAN_FOOTBALL', 'Futebol americano'),
    ('VOLLEYBALL', 'Vôlei'),
    ('BEACH_VOLLEYBALL', 'Vôlei de praia'),
    ('SQUASH', 'Squash'),
    ('TENNIS', 'Tênis'),
    ('BADMINTON', 'Badminton'),
    ('TABLE_TENNIS', 'Tênis de mesa'),
    ('RACQUETBALL', 'Raquetebol'),
    ('BOXING', 'Boxe'),
    ('MARTIAL_ARTS', 'Artes marciais'),
    ('BALLET', 'Balé'),
    ('DANCING', 'Dança'),
    ('BALLROOM_DANCING', 'Dança de salão'),
    ('PILATES', 'Pilates'),
    ('YOGA', 'Ioga'),
    ('STRETCHING', 'Alongamento'),
    ('JUMP_ROPE', 'Pular corda'),
    ('HULA_HOOPING', 'Bambolê'),
    ('PUSH_UPS', 'Flexões'),
    ('PULL_UPS', 'Barra fixa'),
    ('SIT_UPS', 'Abdominais'),
    ('CIRCUIT_TRAINING', 'Treino em circuito'),
    ('MOUNTAIN_CLIMBERS', 'Escalador'),
    ('JUMPING_JACKS', 'Polichinelos'),
    ('BURPEES', 'Burpees'),
    ('BENCH_PRESS', 'Supino'),
    ('SQUATS', 'Agachamentos'),
    ('LUNGES', 'Avanços'),
    ('LEG_PRESSES', 'Leg press'),
    ('LEG_EXTENSIONS', 'Cadeira extensora'),
    ('LEG_CURLS', 'Mesa flexora'),
    ('BACK_EXTENSIONS', 'Extensão lombar'),
    ('LAT_PULLDOWNS', 'Puxada alta'),
    ('DEADLIFTS', 'Levantamento terra'),
    ('SHOULDER_PRESSES', 'Desenvolvimento de ombros'),
    ('FRONT_RAISES', 'Elevação frontal'),
    ('LATERAL_RAISES', 'Elevação lateral'),
    ('CRUNCH', 'Abdominal crunch'),
    ('LEG_RAISES', 'Elevação de pernas'),
    ('PLANK', 'Prancha'),
    ('ARM_CURLS', 'Rosca bíceps'),
    ('ARM_EXTENSIONS', 'Extensão de tríceps'),
    ('SKATERS', 'Patinador'),
    ('HIGH_KNEES', 'Elevação de joelhos'),
    ('INLINE_SKATING', 'Patinação inline'),
    ('HANG_GLIDING', 'Asa-delta'),
    ('ARCHERY', 'Tiro com arco'),
    ('HORSEBACK_RIDING', 'Equitação'),
    ('BIKING', 'Ciclismo'),
    ('FLYING_DISC', 'Frisbee'),
    ('ROLLER_SKATING', 'Patinação'),
    ('AEROBICS', 'Aeróbica'),
    ('HIKING', 'Trilha'),
    ('ROCK_CLIMBING', 'Escalada'),
    ('BACKPACKING', 'Mochilão'),
    ('MOUNTAIN_BIKING', 'Mountain bike'),
    ('ORIENTEERING', 'Orientação'),
    ('POOL_SWIMMING', 'Natação em piscina'),
    ('AQUA_AEROBICS', 'Hidroginástica'),
    ('CANOEING', 'Canoagem'),
    ('SAILING', 'Vela'),
    ('SCUBA_DIVING', 'Mergulho autônomo'),
    ('SNORKELING', 'Snorkel'),
    ('KAYAKING', 'Caiaque'),
    ('KITESURFING', 'Kitesurf'),
    ('RAFTING', 'Rafting'),
    ('ROWING', 'Remo'),
    ('WINDSURFING', 'Windsurf'),
    ('YACHTING', 'Iatismo'),
    ('WATER_SKIING', 'Esqui aquático'),
    ('STEP_MACHINE', 'Step'),
    ('WEIGHT_MACHINE', 'Aparelho de musculação'),
    ('STATIONARY_BIKING', 'Bicicleta ergométrica'),
    ('ROWING_MACHINE', 'Remo ergômetro'),
    ('TREADMILL', 'Esteira'),
    ('ELLIPTICAL', 'Elíptico'),
    ('STAIR_CLIMBING_MACHINE', 'Simulador de escada'),
    ('CROSS_COUNTRY_SKIING', 'Esqui cross-country'),
    ('SKIING', 'Esqui'),
    ('ICE_DANCING', 'Dança no gelo'),
    ('ICE_SKATING', 'Patinação no gelo'),
    ('ICE_HOCKEY', 'Hóquei no gelo'),
    ('SNOWBOARDING', 'Snowboard'),
    ('ALPINE_SKIING', 'Esqui alpino'),
    ('SNOWSHOEING', 'Caminhada com raquetes de neve'),
    ('TRIATHLON', 'Triatlo'),
    ('DUATHLON', 'Duatlo'),
    ('AQUATHLON', 'Aquatlo'),
    ('AQUABIKE', 'Aquabike'),
    ('CROSS_TRIATHLON', 'Triatlo cross'),
    ('CROSS_DUATHLON', 'Duatlo cross'),
    ('BREAK', 'Pausa'),
    ('COOL_DOWN', 'Desaquecimento'),
    ('WARM_UP', 'Aquecimento'),
    ('TRANSITION', 'Transição'),
    ('ZUMBA', 'Zumba'),
    ('OPEN_WATER_SWIMMING', 'Natação em águas abertas');

-- Dropped rather than replaced so that the title and the label sit beside the type and the local columns
-- stay last. The read account keeps reading both views through the default privileges of
-- `read-access.sql`.
drop view read_model.current_exercise;

create view read_model.current_exercise as
select
    i.record_type,
    i.samsung_uid,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' ->> 'type' as exercise_type,
    coalesce(l.label, v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' ->> 'type') as exercise_label,
    v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' ->> 'customTitle' as custom_title,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'duration' ->> 'value')::numeric
        as duration_seconds,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'distance' ->> 'value')::numeric
        as distance_meters,
    (v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'calories' ->> 'value')::numeric
        as calories_kilocalories,
    v.period_start,
    v.period_start_offset,
    v.period_end,
    v.period_end_offset,
    v.observed_at,
    v.observed_at_offset,
    v.envelope -> 'sourceProvenance' -> 'sourceApp' ->> 'id' as source_app,
    v.envelope -> 'sourceProvenance' -> 'sourceDevice' ->> 'id' as source_device,
    v.mapper_version,
    (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) as period_start_local,
    (v.period_end at time zone 'UTC') + cast(v.period_end_offset as interval) as period_end_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
left join exercise_type_label l on l.exercise_type = v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' ->> 'type'
where v.record_type = 'exercise' and v.state = 'present';

-- One row per session, in the order the source reported them. Nothing is combined across sessions: the
-- totals of the exercise stay where ADR 0019 put them.
create view read_model.current_exercise_session as
select
    i.samsung_uid,
    session.ordinality as position,
    (session.value ->> 'start')::timestamptz as start_at,
    (session.value ->> 'end')::timestamptz as end_at,
    (session.value ->> 'durationSeconds')::numeric as duration_seconds,
    session.value ->> 'type' as exercise_type,
    coalesce(l.label, session.value ->> 'type') as exercise_label,
    session.value ->> 'customTitle' as custom_title,
    (session.value ->> 'count')::numeric as count,
    session.value ->> 'countType' as count_type,
    (session.value ->> 'caloriesKilocalories')::numeric as calories_kilocalories,
    (session.value ->> 'distanceMeters')::numeric as distance_meters,
    (session.value ->> 'inclineDistanceMeters')::numeric as incline_distance_meters,
    (session.value ->> 'declineDistanceMeters')::numeric as decline_distance_meters,
    (session.value ->> 'altitudeGainMeters')::numeric as altitude_gain_meters,
    (session.value ->> 'altitudeLossMeters')::numeric as altitude_loss_meters,
    (session.value ->> 'minAltitudeMeters')::numeric as min_altitude_meters,
    (session.value ->> 'maxAltitudeMeters')::numeric as max_altitude_meters,
    (session.value ->> 'meanSpeedMetersPerSecond')::numeric as mean_speed_meters_per_second,
    (session.value ->> 'maxSpeedMetersPerSecond')::numeric as max_speed_meters_per_second,
    (session.value ->> 'meanCadencePerMinute')::numeric as mean_cadence_per_minute,
    (session.value ->> 'maxCadencePerMinute')::numeric as max_cadence_per_minute,
    (session.value ->> 'meanCalorieBurnRateKilocaloriesPerHour')::numeric
        as mean_calorie_burn_rate_kilocalories_per_hour,
    (session.value ->> 'maxCalorieBurnRateKilocaloriesPerHour')::numeric
        as max_calorie_burn_rate_kilocalories_per_hour,
    (session.value ->> 'meanPowerWatts')::numeric as mean_power_watts,
    (session.value ->> 'maxPowerWatts')::numeric as max_power_watts,
    (session.value ->> 'meanRpm')::numeric as mean_rpm,
    (session.value ->> 'maxRpm')::numeric as max_rpm,
    (session.value ->> 'meanHeartRateBeatsPerMinute')::numeric as mean_heart_rate_beats_per_minute,
    (session.value ->> 'minHeartRateBeatsPerMinute')::numeric as min_heart_rate_beats_per_minute,
    (session.value ->> 'maxHeartRateBeatsPerMinute')::numeric as max_heart_rate_beats_per_minute,
    (session.value ->> 'vo2Max')::numeric as vo2_max,
    (session.value ->> 'autoDetected')::boolean as auto_detected,
    session.value ->> 'comment' as comment,
    (session.value -> 'swimming' ->> 'poolLengthMeters')::numeric as pool_length_meters,
    (session.value -> 'swimming' ->> 'totalDistanceMeters')::numeric as swimming_distance_meters,
    (session.value -> 'swimming' ->> 'totalDurationSeconds')::numeric as swimming_duration_seconds,
    ((session.value ->> 'start')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as start_at_local,
    ((session.value ->> 'end')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as end_at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
left join exercise_type_label l on l.exercise_type = session.value ->> 'type'
where v.record_type = 'exercise' and v.state = 'present';

create view read_model.current_exercise_log as
select
    i.samsung_uid,
    session.ordinality as session_position,
    entry.ordinality as position,
    (entry.value ->> 'at')::timestamptz as at,
    (entry.value ->> 'heartRateBeatsPerMinute')::numeric as heart_rate_beats_per_minute,
    (entry.value ->> 'cadencePerMinute')::numeric as cadence_per_minute,
    (entry.value ->> 'count')::numeric as count,
    (entry.value ->> 'powerWatts')::numeric as power_watts,
    (entry.value ->> 'speedMetersPerSecond')::numeric as speed_meters_per_second,
    ((entry.value ->> 'at')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
cross join lateral jsonb_array_elements(coalesce(session.value -> 'log', '[]'::jsonb))
    with ordinality as entry (value, ordinality)
where v.record_type = 'exercise' and v.state = 'present';

-- An interval states no instant of its own, so this view has no local column: it is placed by the session
-- it belongs to.
create view read_model.current_exercise_swimming_interval as
select
    i.samsung_uid,
    session.ordinality as session_position,
    swim.ordinality as position,
    (swim.value ->> 'durationSeconds')::numeric as duration_seconds,
    swim.value ->> 'strokeType' as stroke_type,
    (swim.value ->> 'strokeCount')::numeric as stroke_count,
    (swim.value ->> 'interval')::numeric as interval_number
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral jsonb_array_elements(
    coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'sessions', '[]'::jsonb)
) with ordinality as session (value, ordinality)
cross join lateral jsonb_array_elements(coalesce(session.value -> 'swimming' -> 'intervals', '[]'::jsonb))
    with ordinality as swim (value, ordinality)
where v.record_type = 'exercise' and v.state = 'present';

-- Version 2 carries the route inside each session; version 1 carried one route for the whole exercise,
-- whose points have no session to name and keep the position they had across it. The view is dropped
-- so that the session position sits before the position, as it does in the sleep stages.
drop view read_model.current_exercise_location;

create view read_model.current_exercise_location as
select
    i.samsung_uid,
    point.session_position,
    point.position,
    (point.value ->> 'at')::timestamptz as at,
    (point.value ->> 'latitudeDegrees')::numeric as latitude_degrees,
    (point.value ->> 'longitudeDegrees')::numeric as longitude_degrees,
    (point.value ->> 'altitudeMeters')::numeric as altitude_meters,
    (point.value ->> 'accuracyMeters')::numeric as accuracy_meters,
    ((point.value ->> 'at')::timestamptz at time zone 'UTC') + cast(v.period_start_offset as interval) as at_local
from current_health_record c
join observed_record_version v on v.id = c.observed_record_version_id
join health_record_identity i on i.id = c.health_record_identity_id
cross join lateral (
    select null::bigint as session_position, whole.ordinality as position, whole.value
    from jsonb_array_elements(
        coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'route', '[]'::jsonb)
    ) with ordinality as whole (value, ordinality)
    union all
    select session.ordinality, leg.ordinality, leg.value
    from jsonb_array_elements(
        coalesce(v.envelope -> 'state' -> 'normalizedPayload' -> 'exercise' -> 'sessions', '[]'::jsonb)
    ) with ordinality as session (value, ordinality)
    cross join lateral jsonb_array_elements(coalesce(session.value -> 'route', '[]'::jsonb))
        with ordinality as leg (value, ordinality)
) as point
where v.record_type = 'exercise' and v.state = 'present';
