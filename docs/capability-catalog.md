# Catálogo de capacidades

Gerado de `HealthCapabilities` (`health-sync`) e verificado por `CapabilityCatalogDocumentTest`: não edite à mão. Quando o catálogo muda, o teste falha e grava a versão nova em `health-sync/build/capability-catalog.md`, que substitui esta.

Uma entrada por Health Category do Samsung Health Data SDK adotado. Uma categoria que o SDK expõe e o catálogo não declara não é sincronizada e vira Maintenance Incident (`uncataloged_category`). Tamanhos de página e de leitura são escolhas deste repositório, não limites do SDK.

Implementadas: 13 de 25.

| Categoria | Classe | Situação | Permissão | Leituras | Paginação | Mudanças | Dados associados | Identidade | Mapper | Projeção |
|---|---|---|---|---|---|---|---|---|---|---|
| `activity_summary` | Observed Snapshot | implementada | `activity_summary` | `AGGREGATE` | 744 janelas por leitura | não | — | `activity_summary` + hora local | `samsung-health-activity-summary/1` | `read_model.current_hourly_activity_summary` |
| `exercise` | Health Record | implementada | `exercise` | `TIME_RANGE`, `CHANGES`, `ASSOCIATED` | 50 registros por página | sim | `sessions`, `sessions.route` | `exercise` + Samsung UID | `samsung-health-exercise/1` | `read_model.current_exercise` |
| `exercise_location` | só permissão | implementada | `exercise_location` | — | — | não | — | dentro de `exercise` | — | `read_model.current_exercise_location` |
| `floors_climbed` | Health Record | não implementada | `floors_climbed` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `floors_climbed` + Samsung UID | — | — |
| `steps` | Observed Snapshot | implementada | `steps` | `AGGREGATE` | 744 janelas por leitura | não | — | `steps` + hora local | `samsung-health-steps/1` | `read_model.current_hourly_steps` |
| `nutrition` | Health Record | não implementada | `nutrition` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `nutrition` + Samsung UID | — | — |
| `nutrition_goal` | Observed Snapshot | implementada | `nutrition_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `nutrition_goal` + dia local | `samsung-health-nutrition-goal/1` | `read_model.current_daily_goal` |
| `water_intake` | Health Record | não implementada | `water_intake` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `water_intake` + Samsung UID | — | — |
| `water_intake_goal` | Observed Snapshot | implementada | `water_intake_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `water_intake_goal` + dia local | `samsung-health-water-intake-goal/1` | `read_model.current_daily_goal` |
| `sleep` | Health Record | implementada | `sleep` | `TIME_RANGE`, `CHANGES` | 100 registros por página | sim | `sessions`, `sessions.stages` | `sleep` + Samsung UID | `samsung-health-sleep/1` | `read_model.current_sleep` |
| `sleep_apnea` | Health Record | não implementada | `sleep_apnea` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `sleep_apnea` + Samsung UID | — | — |
| `sleep_goal` | Observed Snapshot | implementada | `sleep_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `sleep_goal` + dia local | `samsung-health-sleep-goal/1` | `read_model.current_daily_goal` |
| `skin_temperature` | Health Record | não implementada | `skin_temperature` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | `binning_data` | `skin_temperature` + Samsung UID | — | — |
| `blood_glucose` | Health Record | não implementada | `blood_glucose` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | `binning_data` | `blood_glucose` + Samsung UID | — | — |
| `blood_oxygen` | Health Record | não implementada | `blood_oxygen` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | `binning_data` | `blood_oxygen` + Samsung UID | — | — |
| `blood_pressure` | Health Record | não implementada | `blood_pressure` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `blood_pressure` + Samsung UID | — | — |
| `body_composition` | Health Record | não implementada | `body_composition` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `body_composition` + Samsung UID | — | — |
| `body_temperature` | Health Record | não implementada | `body_temperature` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `body_temperature` + Samsung UID | — | — |
| `heart_rate` | Health Record | implementada | `heart_rate` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | `binning_data` | `heart_rate` + Samsung UID | `samsung-health-heart-rate/1` | `read_model.current_heart_rate` |
| `irregular_heart_rhythm` | Health Record | não implementada | `irregular_heart_rhythm` | `TIME_RANGE`, `CHANGES` | 500 registros por página | sim | — | `irregular_heart_rhythm` + Samsung UID | — | — |
| `active_calories_burned_goal` | Observed Snapshot | implementada | `active_calories_burned_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `active_calories_burned_goal` + dia local | `samsung-health-active-calories-burned-goal/1` | `read_model.current_daily_goal` |
| `active_time_goal` | Observed Snapshot | implementada | `active_time_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `active_time_goal` + dia local | `samsung-health-active-time-goal/1` | `read_model.current_daily_goal` |
| `energy_score` | Health Record | não implementada | `energy_score` | `DATE_RANGE`, `CHANGES` | 500 registros por página | sim | — | `energy_score` + Samsung UID | — | — |
| `steps_goal` | Observed Snapshot | implementada | `steps_goal` | `AGGREGATE` | 366 janelas por leitura | não | — | `steps_goal` + dia local | `samsung-health-steps-goal/1` | `read_model.current_daily_goal` |
| `user_profile` | Observed Snapshot | implementada | `user_profile` | `PROFILE` | — | não | — | `user_profile`, sem janela | `samsung-health-user-profile/1` | `read_model.user_profile` |
