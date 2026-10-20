# O que o `read_model` expõe para os painéis do Grafana

Data: 2026-10-20

Ticket: #72 (mapa #69, "Visualizar a Personal Health History no Grafana").

## Pergunta

O que cada view do schema `read_model`, criada pelas migrations em `ingestion-api/src/main/resources/db/migration` (V1 a V14), expõe para alimentar um painel: colunas, tipos, unidades e Health Category de origem; como o tempo é representado; o formato de séries, sessões, estágios e rotas; como metas diárias e snapshots horários se relacionam com as métricas; se dá para recortar o dia pela hora local do registro só com o que já está exposto; e o que um painel parecido com o Samsung Health precisaria e nenhuma view expõe.

## Fontes

Todas primárias e locais, no estado do commit `2e9efe4` (master):

- Migrations: `ingestion-api/src/main/resources/db/migration/V1__ingestion.sql` a `V14__current_monitors_and_energy_score.sql`.
- Validação do normalized payload na API (unidades e faixas aceitas): `ingestion-api/src/main/kotlin/br/etc/victor/myhealthbridge/api/NormalizedPayloads.kt`.
- Mappers do app: `health-sync/src/main/kotlin/br/etc/victor/myhealthbridge/sync/RecordMapper.kt`, `ExerciseMapper.kt`, `SleepMapper.kt`, `SnapshotMapper.kt`, `ActivitySummaryMapper.kt`, `GoalMapper.kt`, `SnapshotImporter.kt`.
- Leitura do SDK: `android-app/src/main/kotlin/br/etc/victor/myhealthbridge/samsung/SamsungRecordSource.kt`.
- Testes de projeção: `ingestion-api/src/test/kotlin/br/etc/victor/myhealthbridge/api/` (`CurrentHealthRecordTest`, `CurrentMonitorsTest`, `ObservedSnapshotTest`, `DailyGoalTest` e os demais `Current*Test`).
- `docs/capability-catalog.md`, `docs/sql/read-access.sql`, `CONTEXT.md` e os ADRs 0002, 0012, 0013, 0014, 0019, 0023 e 0025.

## Resumo e conclusão

- **São 26 views**, todas no schema `read_model`, legíveis pela conta `myhealth_read` (`docs/sql/read-access.sql`). 21 vêm de Health Records via Current Health Record; 5 vêm de Observed Snapshots via Current Observed Snapshot. As 25 Health Categories do catálogo estão todas cobertas por alguma view (`docs/capability-catalog.md`, coluna "Projeção").
- **Unidade fica no nome da coluna**, nunca numa coluna à parte (a única exceção é `current_heart_rate.unit`, sempre `/min`). A API rejeita na ingestão qualquer quantidade fora da unidade esperada (`NormalizedPayloads.kt`), então o sufixo do nome é garantido.
- **Tempo de Health Record:** instantes `timestamptz` (UTC) mais o offset original do registro em `text` no formato `±HH:MM` (`period_start_offset`, `period_end_offset`, `observed_at_offset`). Não há fuso nomeado (IANA), só offset.
- **Tempo de Observed Snapshot:** a Snapshot Window é `timestamp` sem fuso, em hora local (`window_start`, `window_end`); metas expõem `day` (`date`). Não há offset da janela; o `observed_at_offset` é o do momento da leitura, não o da janela.
- **Recortar o dia pela hora local do registro é possível hoje, mas por expressão, não por coluna.** Para Health Records: `cast((period_start at time zone 'UTC') + cast(period_start_offset as interval) as date)`, a mesma fórmula que `current_energy_score.local_date` (V14) e `current_observed_snapshot.partial` (V6) já usam. Para snapshots, a janela já está em hora local. Só `current_energy_score` expõe a data local pronta. Confiança: alta (fórmula testada em `CurrentMonitorsTest`, offsets `-03:00` e `+09:00` caindo no mesmo dia local).
- **Linhas filhas (séries, sessões, estágios, pontos de rota) não trazem offset nem `record_type`**: o dia local delas exige join com a view pai por `samsung_uid` para pegar `period_start_offset`.
- **Metas** (`current_daily_goal`) são uma linha por tipo de meta por dia local, colunas esparsas; casam com as métricas pelo dia local. Não há meta para frequência cardíaca, peso, andares ou sinais vitais.
- **Lacunas principais:** frequência cardíaca sem mínimo, máximo e série (`binning_data`), exercício sem métricas por sessão (FC média/máxima, velocidade, cadência, ganho de altitude, VO2 max, log por instante), nenhuma coluna de hora/dia local pronta, offset desconhecido indistinguível de UTC, janelas de snapshot sem instante absoluto. Os dados de exercício e frequência cardíaca que faltam já estão preservados no source payload do envelope, mas a regra dos ADRs 0019 e 0013 impede lê-los por SQL: expô-los pede uma nova versão de mapper e uma migration.

## 1. Inventário das views

Colunas comuns a todas as views de Health Record (V2 a V14), salvo onde indicado:

| Coluna | Tipo | Significado |
|---|---|---|
| `record_type` | `text` | Tipo do registro (igual à Health Category). Ausente nas views filhas. |
| `samsung_uid` | `text` | Identificador do Samsung Health; junto com `record_type` forma a Health Record Identity (`V1__ingestion.sql`, `unique (record_type, samsung_uid)`). |
| `period_start`, `period_end` | `timestamptz` | Período do registro. Medida pontual tem início igual ao fim (`RecordMapper.kt`, `presentEnvelope`). |
| `period_start_offset`, `period_end_offset` | `text` | Offset do registro, `±HH:MM` (`RecordMapper.kt`, `offsetText`). |
| `observed_at`, `observed_at_offset` | `timestamptz`, `text` | Hora da última modificação na fonte (ADR 0014), não da leitura. |
| `source_app`, `source_device` | `text` | Source Provenance; `null` é o desconhecido explícito. |
| `mapper_version` | `text` | Versão do mapper que produziu o normalized payload. |

Todas as views de Health Record filtram `state = 'present'`: um registro removido na fonte some da view (V2, comentário de `current_heart_rate`).

### Health Records

| View | Health Category | Colunas específicas (tipo, unidade) | Migration |
|---|---|---|---|
| `current_heart_rate` | `heart_rate` | `beats_per_minute numeric`, `unit text` (sempre `/min`) | V2 |
| `current_exercise` | `exercise` | `exercise_type text` (constante do SDK), `duration_seconds numeric`, `distance_meters numeric`, `calories_kilocalories numeric` | V3 |
| `current_exercise_location` | `exercise_location` (dentro de `exercise`) | `samsung_uid`, `position bigint`, `at timestamptz`, `latitude_degrees`, `longitude_degrees`, `altitude_meters`, `accuracy_meters` (todos `numeric`) | V3 |
| `current_sleep` | `sleep` | `duration_seconds numeric`, `sleep_score numeric` (escala não documentada) | V4 |
| `current_sleep_session` | `sleep` | `samsung_uid`, `position bigint`, `start_at`, `end_at timestamptz`, `duration_seconds numeric` | V4 |
| `current_sleep_stage` | `sleep` | `samsung_uid`, `session_position bigint`, `position bigint`, `start_at`, `end_at timestamptz`, `stage text` (constante do SDK: `AWAKE`, `LIGHT`, `DEEP`, `REM` nos testes) | V4 |
| `current_blood_oxygen` | `blood_oxygen` | `oxygen_saturation_percent`, `min_oxygen_saturation_percent`, `max_oxygen_saturation_percent` (`numeric`, %) | V10 |
| `current_blood_oxygen_series` | `blood_oxygen` (`binning_data`) | `samsung_uid`, `position`, `start_at`, `end_at`, `oxygen_saturation_percent`, `min_…`, `max_…` | V10 |
| `current_skin_temperature` | `skin_temperature` | `temperature_celsius`, `min_temperature_celsius`, `max_temperature_celsius` (`numeric`, °C) | V10 |
| `current_skin_temperature_series` | `skin_temperature` (`binning_data`) | `samsung_uid`, `position`, `start_at`, `end_at`, `temperature_celsius`, `min_…`, `max_…` | V10 |
| `current_blood_glucose` | `blood_glucose` | `glucose_mmol_per_liter numeric`, `measurement_type`, `sample_source_type`, `meal_status text` (constantes), `meal_at timestamptz`, `insulin_injected numeric` (sem unidade documentada), `medication_taken boolean` | V10 |
| `current_blood_glucose_series` | `blood_glucose` (`binning_data`) | `samsung_uid`, `position`, `at timestamptz`, `glucose_mmol_per_liter numeric` | V10 |
| `current_blood_pressure` | `blood_pressure` | `systolic_mmhg`, `diastolic_mmhg`, `mean_mmhg numeric`, `pulse_rate integer` (sem unidade documentada), `medication_taken boolean` | V12 |
| `current_body_composition` | `body_composition` | `weight_kg`, `height_cm`, `body_fat_percent`, `skeletal_muscle_percent`, `muscle_mass_percent`, `basal_metabolic_rate_kcal_per_day`, `body_fat_mass_kg`, `fat_free_mass_kg`, `fat_free_percent`, `skeletal_muscle_mass_kg`, `total_body_water_liters`, `body_mass_index` (todos `numeric`) | V12 |
| `current_body_temperature` | `body_temperature` | `temperature_celsius numeric` | V12 |
| `current_nutrition` | `nutrition` | `meal_type text` (constante), `title text`, `calories_kcal`, `total_fat_g`, `saturated_fat_g`, `polysaturated_fat_g`, `monosaturated_fat_g`, `trans_fat_g`, `carbohydrate_g`, `dietary_fiber_g`, `sugar_g`, `protein_g`, `cholesterol_mg`, `sodium_mg`, `potassium_mg`, `vitamin_a_ug`, `vitamin_c_mg`, `calcium_mg`, `iron_mg` (todos `numeric`) | V13 |
| `current_water_intake` | `water_intake` | `amount_ml numeric` | V13 |
| `current_floors_climbed` | `floors_climbed` | `floors numeric` (sem unidade documentada) | V13 |
| `current_irregular_heart_rhythm` | `irregular_heart_rhythm` | `status text` (constante, pode ser `null`), `detected_at timestamptz`, `detected_at_offset text`; sem `period_end` | V14 |
| `current_sleep_apnea` | `sleep_apnea` | `result text` (constante, pode ser `null`) | V14 |
| `current_energy_score` | `energy_score` | `local_date date`, `score numeric` (escala não documentada); sem `period_end` | V14 |

### Observed Snapshots

| View | Health Category | Colunas | Migration |
|---|---|---|---|
| `current_observed_snapshot` | todas as de snapshot | `snapshot_type text`, `window_start`, `window_end timestamp` (sem fuso), `observed_at timestamptz`, `observed_at_offset text`, `partial boolean`, `empty boolean`, `content jsonb`, `source_scope text`, `mapper_version text` | V6 |
| `current_hourly_steps` | `steps` | `window_start`, `window_end`, `steps bigint`, `partial`, `observed_at`, `observed_at_offset`, `source_scope`, `mapper_version` | V6 |
| `current_hourly_activity_summary` | `activity_summary` | `window_start`, `window_end`, `active_calories_burned_kilocalories`, `calories_burned_kilocalories`, `active_time_seconds`, `distance_meters` (`numeric`), `partial`, `observed_at`, `observed_at_offset`, `source_scope`, `mapper_version` | V8 |
| `current_daily_goal` | `active_calories_burned_goal`, `active_time_goal`, `nutrition_goal`, `sleep_goal`, `steps_goal`, `water_intake_goal` | `goal text` (o `snapshot_type`), `day date`, `steps bigint`, `active_calories_burned_kilocalories`, `active_time_seconds`, `calories_kilocalories`, `amount_milliliters` (`numeric`), `bed_time`, `wake_up_time` (`time`), `changed boolean`, `partial`, `observed_at`, `observed_at_offset`, `source_scope`, `mapper_version` | V9, refeita em V11 |
| `user_profile` | `user_profile` | `observed_at`, `observed_at_offset`, `current boolean`, `empty boolean`, `date_of_birth text` (formato não documentado), `height_cm`, `weight_kg numeric`, `gender text`, `nickname text`, `source_scope`, `mapper_version` | V7 |

Observações sobre os snapshots:

- `current_hourly_steps`, `current_hourly_activity_summary` e `current_daily_goal` excluem janelas observadas como vazias (`where not empty`). Uma hora sem passos não tem linha; o painel precisa tratar ausência como zero (passos) ou como "sem meta" (metas).
- `source_scope` é `PLATFORM` para passos e atividade (agregado mesclado de celular e relógio pelo próprio Samsung Health) e `ALL_SOURCES` para metas (`SnapshotMapper.kt`, `ActivitySummaryMapper.kt`, `GoalMapper.kt`).
- `user_profile` não é só o perfil atual: traz toda a história de mudanças, com `current = true` na observação vigente (V7).

## 2. Como o tempo é representado

### Health Records

- O instante é `timestamptz` (UTC). O offset original é texto `±HH:MM`, escrito pelo app a partir do `zoneOffset` do registro no SDK (`RecordMapper.kt`, `presentEnvelope` e `offsetText`; `SamsungRecordSource.kt`, `toSourceRecord`). Teste: `CurrentHealthRecordTest` lê `"-03:00"` em `period_start_offset` e `observed_at_offset`.
- Não há fuso IANA, só o offset do momento do registro. Isso basta para a hora local do registro (é exatamente o que o Samsung Health usa), mas não para converter outros instantes.
- **Offset desconhecido vira `+00:00`.** Quando o SDK não informa offset, o mapper usa UTC (`RecordMapper.kt`: `record.zoneOffset ?: ZoneOffset.UTC`). Na view não há como distinguir "registro sem offset" de "registro feito em UTC". Para o Data Owner no Brasil isso desloca o dia local de um registro assim em 3 horas.
- Hora local de um registro: `(period_start at time zone 'UTC') + cast(period_start_offset as interval)`, resultado `timestamp` sem fuso. As migrations explicam por que não usar `at time zone period_start_offset`: o PostgreSQL lê offset numérico com o sinal POSIX invertido (comentários em V6 e V14).
- `observed_at` é a hora da última modificação na fonte (ADR 0014), útil para auditoria, não para eixo de tempo de painel.

### Linhas filhas

- `current_exercise_location.at`, `current_sleep_session.start_at/end_at`, `current_sleep_stage.start_at/end_at`, `current_*_series.start_at/end_at/at` são `timestamptz` em UTC, sem offset próprio. A API só aceita esses instantes em UTC com sufixo `Z` (`NormalizedPayloads.kt`, `instant`).
- Para a hora local, junte com a view pai por `samsung_uid` e aplique o `period_start_offset` do pai. O join só por `samsung_uid` é seguro porque pai e filho filtram o mesmo `record_type`.
- `current_exercise_location.at` é opcional (o ponto pode vir sem timestamp; `ExerciseMapper.kt`, `point`).

### Observed Snapshots

- A Snapshot Window é `timestamp` sem fuso, em hora local, no relógio em que o Samsung Health agrupa o agregado (V6, comentário de `observed_snapshot`; `SamsungRecordSource.kt`, `aggregate` usa `LocalTimeFilter` e `LocalTimeGroup` horário; `dailyGoal` usa `LocalDateFilter` diário).
- Hora local: `window_start` direto. Dia local: `cast(window_start as date)`. Metas: `day`.
- Não há offset da janela. `observed_at_offset` é o offset do celular no momento da leitura (`SnapshotImporter.kt`: `zoned(readAt, clock.zone.rules.getOffset(readAt))`), que pode diferir do da janela depois de uma viagem. Não há como converter uma janela em instante absoluto com segurança.
- `partial` é verdadeiro quando a leitura aconteceu antes do fim da janela, julgado em hora local (V6; teste `ObservedSnapshotTest`, "tells a partial window from a closed one in local time"). A hora e o dia correntes aparecem como parciais até a próxima leitura depois de fechados.

### Resposta: dá para recortar o dia pela hora local do registro?

Sim, para todas as métricas, com o que já está exposto:

| Fonte | Dia local |
|---|---|
| Health Record com período | `cast((period_start at time zone 'UTC') + cast(period_start_offset as interval) as date)` (ou `period_end` para atribuir uma noite de sono ao dia em que terminou) |
| Linha filha | o mesmo, com o offset do pai via join por `samsung_uid` |
| `current_energy_score` | `local_date` (já exposta) |
| `current_irregular_heart_rhythm` | a fórmula sobre `detected_at` e `detected_at_offset` |
| Snapshots horários | `cast(window_start as date)` |
| Metas | `day` |

Ressalvas:

- A fórmula precisa ser repetida em cada consulta; nenhuma view (exceto energy score) a expõe pronta.
- Registros sem offset na fonte caem no dia UTC (ver acima).
- Agrupar por hora, semana ou mês segue a mesma ideia: `date_trunc('hour' | 'week' | 'month', <hora local>)`. `date_trunc('week')` começa na segunda-feira (ISO); se o painel quiser semana começando no domingo, a consulta tem de deslocar.
- Implicação para o Grafana (inferência, a confirmar no ticket dos painéis): a hora local é um `timestamp` sem fuso, que o plugin PostgreSQL do Grafana trata como UTC. Para que o eixo mostre a hora de parede do registro, o dashboard precisaria usar fuso UTC e os macros de filtro de tempo (`$__timeFilter`) seriam aplicados sobre a hora local, não sobre o instante. Isso é coerente com "o seletor de tempo define o período" em hora local, mas é uma decisão do ticket de painéis.

## 3. Séries, sessões, estágios e rotas

- **Séries binned** (`current_blood_oxygen_series`, `current_skin_temperature_series`): uma linha por bin, com `start_at`/`end_at` e valor, mínimo e máximo do bin; `position` é a ordem em que a fonte reportou. A granularidade é a que o Samsung Health usou ao agrupar; o repositório não a fixa nem a documenta. O registro pai (`current_blood_oxygen`, `current_skin_temperature`) traz o valor resumido e o mínimo/máximo do período inteiro (V10).
- **Série de glicose** (`current_blood_glucose_series`): uma linha por leitura pontual (`at`), sem intervalo (V10).
- **Sessões de sono** (`current_sleep_session`): uma linha por sessão; uma noite costuma ter uma, uma noite interrompida tem mais (V4). `duration_seconds` é opcional.
- **Estágios de sono** (`current_sleep_stage`): uma linha por estágio, com `session_position` e `position`; `stage` é a constante do SDK sem tradução (ADR 0023). Uma sessão sem estágios aparece em sessões e não aqui. Um hipnograma (painel State timeline) sai direto desta view; o tempo total por estágio é `sum(end_at - start_at)` agrupado por `stage`.
- **Oxigênio e temperatura da pele durante o sono** não estão dentro do sono (ADR 0023): são registros próprios, e "pertencem à noite cujo período os contém" (comentário em V10). O painel junta por intervalo: `bo.period_start between s.period_start and s.period_end`.
- **Rota de exercício** (`current_exercise_location`): uma linha por ponto, ordenada por `position`, com latitude e longitude em graus e, opcionalmente, altitude e precisão em metros e o instante `at` (V3; ADR 0019). É o formato que o Geomap consome direto (latitude/longitude por linha). A rota é a concatenação das sessões do exercício, sem marcar onde uma sessão termina (`ExerciseMapper.kt`, `route`). Exercício sem rota (indoor ou permissão de localização negada) não tem linhas, e os dois casos não se distinguem (ADR 0019). A view não traz `record_type` nem offset; a variável "escolher um exercício" sai de `current_exercise` (`samsung_uid`, `exercise_type`, hora local de início).

## 4. Metas e snapshots horários em relação às métricas

`current_daily_goal` tem uma linha por tipo de meta por dia local em que o Samsung Health tinha a meta em vigor; ele repete a meta todo dia enquanto ela vale, e guarda a meta de um dia passado como era então (`GoalMapper.kt`). Só a coluna do tipo correspondente é preenchida. `changed` marca o dia em que a meta mudou em relação ao dia observado anterior (V11), útil como anotação.

| Meta (`goal`) | Coluna da meta | Métrica medida | Como casar |
|---|---|---|---|
| `steps_goal` | `steps` | `current_hourly_steps.steps` | `sum(steps)` por `cast(window_start as date)` = `day` |
| `active_calories_burned_goal` | `active_calories_burned_kilocalories` | `current_hourly_activity_summary.active_calories_burned_kilocalories` | soma por dia local da janela |
| `active_time_goal` | `active_time_seconds` | `current_hourly_activity_summary.active_time_seconds` | soma por dia local da janela |
| `nutrition_goal` | `calories_kilocalories` | `current_nutrition.calories_kcal` | soma por dia local de `period_start` (fórmula da seção 2) |
| `water_intake_goal` | `amount_milliliters` | `current_water_intake.amount_ml` | soma por dia local de `period_start` |
| `sleep_goal` | `bed_time`, `wake_up_time` (`time`) | `current_sleep` (`period_start`, `period_end`, `duration_seconds`) | comparar hora local de início e fim; a duração-alvo é `wake_up_time - bed_time` (atravessando a meia-noite) |

Notas:

- Os nomes das unidades divergem entre meta e métrica (`amount_milliliters` vs `amount_ml`; `calories_kilocalories` vs `calories_kcal`), mas a unidade é a mesma (`NormalizedPayloads.kt` e `GoalMapper.kt`: `mL` e `kcal`).
- Um dia observado sem meta sai da view; a linha de referência terá buracos nesses dias.
- Dia corrente: tanto a meta quanto os totais horários podem vir com `partial = true`.
- Passos e atividade são horários; o painel com "Agrupar por" hora usa a janela direto, e dia, semana e mês somam as horas. Não existe granularidade mais fina que a hora para passos, calorias, tempo ativo e distância.
- Andares (`current_floors_climbed`) são Health Records, não snapshots, e não têm meta.

## 5. Lacunas para um painel parecido com o Samsung Health

Categorizadas pelo que seria preciso para fechá-las.

### Dados lidos e preservados, mas não expostos (nova versão de mapper + migration)

Os campos abaixo já são lidos do SDK e guardados no source payload do envelope (`SamsungRecordSource.kt`), mas o normalized payload não os traz. Pelo ADR 0019 as views leem só o normalized payload, e pelo ADR 0013 a conta de leitura não alcança os envelopes. Expô-los pede uma nova versão do mapper, validação na API e uma view; registros antigos só ganham os campos quando forem relidos (por exemplo, numa Full Reconciliation), já que a versão do mapper entra no conteúdo observado.

- **Frequência cardíaca:** mínimo, máximo e a série (`binning_data`) de cada registro (`heartRateFields` lê `MIN_HEART_RATE`, `MAX_HEART_RATE`, `SERIES_DATA`; `HeartRateMapper` em `RecordMapper.kt` normaliza só o valor). O catálogo declara `binning_data` para `heart_rate`, mas não existe `current_heart_rate_series`. Hoje o gráfico de FC do dia tem um ponto por registro.
- **Exercício por sessão:** `custom_title`, FC média/mínima/máxima, velocidade média/máxima, cadência, potência, RPM, ganho e perda de altitude, altitude mínima/máxima, VO2 max, contagem (`count`, `count_type`), `auto_detected`, log por instante (`log`: FC, cadência, velocidade, potência) e log de natação (`exerciseSessionEntries` em `SamsungRecordSource.kt`). `current_exercise` expõe só tipo, duração, distância e calorias somadas. Ritmo e velocidade média podem ser derivados de distância/duração; o resto não.
- **Rótulo do exercício:** `exercise_type` é a constante do SDK, sem tradução; o painel precisa de um mapa de rótulos.

### Conveniências de consulta (só migration)

- Nenhuma coluna de hora local ou dia local pronta nos Health Records (só `current_energy_score.local_date`). Cada painel repete a fórmula do offset; um erro de sinal com `at time zone` é fácil de cometer.
- Linhas filhas sem offset e sem `record_type`: todo painel de série, estágio ou rota precisa de join com o pai.
- Nenhuma view diária pronta (passos por dia, calorias por dia, água por dia com a meta ao lado).

### Limitações de representação do tempo

- Offset desconhecido gravado como `+00:00`, indistinguível de UTC (`RecordMapper.kt`).
- Snapshot Window sem offset; não há como posicionar uma hora de passos num eixo de instantes absolutos. Para painéis que usam hora local como eixo isso não é problema.

### Métricas do Samsung Health fora do catálogo

O catálogo cobre 25 de 25 Health Categories do SDK adotado (`docs/capability-catalog.md`). Métricas que o app Samsung Health mostra e que não aparecem como categoria nesse catálogo não podem vir do `read_model` sem mudar o SDK: por exemplo estresse, frequência cardíaca em repouso, variabilidade da FC, ciclo menstrual, índice de antioxidantes e metas de peso. Esta pesquisa não verificou se versões mais novas do SDK as oferecem.

### Escalas não documentadas

- `sleep_score` e `current_energy_score.score` não têm escala documentada pelo Samsung Health (comentários em V14 e `NormalizedPayloads.kt`); limites de eixo e cores por faixa ficam por conta do painel.
- `pulse_rate`, `floors`, `insulin_injected`, `body_mass_index` não têm unidade documentada.

## Questões em aberto

- Granularidade real dos bins de oxigênio, temperatura da pele e (se exposta) frequência cardíaca: o repositório não a documenta; só os dados reais dirão.
- Atribuição de uma noite de sono a um dia (início ou fim do período): o Samsung Health parece usar o dia do despertar, mas isto não foi verificado nas fontes; é decisão do painel de sono.
- Comportamento exato do plugin PostgreSQL do Grafana com `timestamp` sem fuso e `$__timeFilter`: inferência a confirmar no ticket dos painéis.
