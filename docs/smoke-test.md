# Smoke test em aparelho físico

Este roteiro valida o que o CI não reproduz: a integração real com o Samsung Health em developer mode, num aparelho compatível, ponta a ponta até a API na VPS. Cada passo diz o que fazer e o que deve acontecer; um resultado diferente é um desvio, anotado na seção [Execuções](#execuções).

É também o roteiro de aceitação da 1.0: a execução roda sobre o APK de release 1.0.x assinado, e uma linha aprovada na tabela [Execuções](#execuções) é o que declara a entrega (ver [Critério de aprovação](#critério-de-aprovação)). A exceção inesperada da sincronização (Maintenance Incident `unexpected_failure`) não tem passo aqui: ela fica coberta só pelos testes automatizados, porque não há como provocá-la no aparelho sem alterar o código.

Nada lido durante o smoke test entra no repositório. O registro de uma execução guarda só estados da tela, códigos, contagens e datas da execução; nenhum valor medido, coordenada, Samsung UID, identificador de aparelho ou token. Por isso todas as consultas abaixo devolvem apenas contagens, e os comandos de log filtram apenas códigos e contagens.

## Pré-requisitos

- Aparelho Galaxy com Samsung Health 6.30.2 ou superior e **Developer Mode for Data Read** ligado (ver [Reconhecimento pelo Samsung Health](../README.md#reconhecimento-pelo-samsung-health)).
- Depuração USB ativa e o aparelho autorizado: `adb devices` lista o aparelho como `device`.
- O APK `myhealth-bridge-1.0.x.apk` anexado à release `v1.0.x` do Gitea, a que está sendo aceita. Ele precisa ser assinado pela chave de release: `apksigner verify --print-certs myhealth-bridge-1.0.x.apk` mostra o mesmo SHA-256 da instalação existente.
- A API no ar: `GET https://myhealth.victor.etc.br/ready` responde `200`.
- O Ingestion Device do aparelho provisionado na VPS (na execução de 2026-10-17, o label `galaxy-s25-ultra`) e o token dele salvo no aplicativo. O passo 8 revoga e rotaciona esse token; o novo é mostrado uma única vez e vai direto para o aplicativo, sem ser guardado em outro lugar.
- Um shell na VPS com acesso ao Docker, para os logs da API (`myhealth-api`) e os subcomandos `device`.
- A senha da conta somente leitura `myhealth_read`, no armazenamento de senhas do Data Owner (ver [Provisionar a conta de leitura na VPS CodeLab](../README.md#provisionar-a-conta-de-leitura-na-vps-codelab)). Todas as consultas desta execução rodam por ela. Não há fallback de administrador: se a conta não autenticar ou não alcançar uma view, a execução é reprovada.

## Roteiro

Os passos rodam em ordem, numa mesma sessão. A sessão `psql` aberta no passo 6 fica aberta até o passo 10, porque guarda as variáveis `corte`, `inicio_fc` e `inicio_teste` usadas nas consultas seguintes; se ela cair, recrie a variável com `\set <nome> '<valor>'` antes de repetir a consulta.

### 1. Instalação

1. `adb install -r myhealth-bridge-1.0.x.apk`.
2. `adb shell dumpsys package br.etc.victor.myhealthbridge | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"`.

**Esperado:**

- A instalação é uma atualização: `firstInstallTime` anterior e `lastUpdateTime` de agora. Outbox, cursores, endpoint e permissões continuam como estavam.
- `versionName=1.0.x` e `versionCode=100000x`, com o mesmo `x` da release (`1000000 + patch`).

### 2. Conexão com o Samsung Health

1. Abra o MyHealth Bridge, na aba **Permissões**, e toque em **Atualizar**.
2. No Samsung Health, desligue o **Developer Mode for Data Read**. Volte ao aplicativo e toque em **Atualizar**.
3. Religue o modo e toque em **Atualizar** de novo.

**Esperado:**

- Passo 1: o card diz que o Samsung Health está respondendo, e a última verificação bem-sucedida é a de agora.
- Passo 2: o card diz que o Samsung Health não reconhece o aplicativo e mostra `Código de diagnóstico: samsung_error_2003`. As permissões aparecem como da última verificação bem-sucedida.
- Passo 3: volta a responder, sem reinstalar nada.

### 3. Permissões por categoria

1. Na aba **Permissões**, toque em **Solicitar** numa categoria sincronizada (Frequência cardíaca ou Exercício) que não esteja concedida e conceda no diálogo do Samsung Health. Se as duas já estiverem concedidas, faça antes o passo 2 e volte a este.
2. No Samsung Health, em **⋮ > Configurações > Apps e serviços conectados > Apps > MyHealth Bridge** (em inglês: **More options > Settings > Connected apps and services > Apps**), desligue **Exercício** em **Permitido ler** (**Allowed to read**).
3. Volte ao aplicativo e toque em **Atualizar**.
4. Na aba **Sincronização**, toque em **Sincronizar agora**.
5. Toque na notificação **Manutenção necessária**.
6. Conceda de novo a leitura de Exercício pela aba **Permissões** e toque em **Sincronizar agora**.
7. Toque em **Solicitar** em toda categoria que ainda não esteja **Concedida** e conceda as 25.

**Esperado:**

- Passo 1: a categoria passa a **Concedida**.
- Passo 3: Exercício aparece como **Revogada**, com a orientação de conceder de novo; as demais categorias não mudam.
- Passo 4: Exercício termina como aguardando a permissão de leitura, enquanto Frequência cardíaca conclui normalmente. Uma notificação **Manutenção necessária** aparece com **Permissão de leitura revogada**.
- Passo 5: a notificação abre a aba **Diagnóstico**, com a categoria Exercício, o código, a primeira e a última ocorrência e a ação sugerida. Nenhum valor medido aparece.
- Passo 6: a categoria volta a **Concedida**, a sincronização conclui e o incidente sai da aba **Diagnóstico** e da área de notificações.
- Passo 7: as 25 categorias aparecem como **Concedida**. Uma categoria que o Samsung Health não deixa conceder é um desvio.

### 4. Reconciliação integral e carga das 25 categorias

A Full Reconciliation depois de instalar a 1.0 é obrigatória, mesmo que todas as categorias já mostrem a carga inicial concluída.

1. Na aba **Sincronização**, confira que o endereço da API aparece como **Entregando em** `https://myhealth.victor.etc.br`. Se não aparecer, informe o endereço e o token e toque em **Salvar**.
2. No shell da VPS, marque o início:

   ```sh
   INICIO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
   ```

3. Se alguma categoria mostrar **Carga inicial não iniciada**, toque em **Iniciar carga inicial**.
4. Toque em **Reconciliar todo o histórico** e depois em **Sincronizar agora**, e acompanhe a tela até a execução terminar.
5. Confira cada linha da tabela abaixo na seção **Categorias** da tela.
6. No shell da VPS, resuma o que a API respondeu desde o início, só por códigos e contagens:

   ```sh
   docker logs --since "$INICIO" myhealth-api 2>&1 | grep -oE 'inventory=[a-z_]+|rejected=[0-9]+|removals=[0-9]+' | sort | uniq -c
   ```

| Categoria na tela | Id | Classe | View |
| --- | --- | --- | --- |
| Resumo de atividade | `activity_summary` | Observed Snapshot | `current_hourly_activity_summary` |
| Exercício | `exercise` | Health Record | `current_exercise`, `current_exercise_session`, `current_exercise_log`, `current_exercise_swimming_interval` |
| Localização de exercício | `exercise_location` | só permissão | `current_exercise_location` |
| Andares subidos | `floors_climbed` | Health Record | `current_floors_climbed` |
| Passos | `steps` | Observed Snapshot | `current_hourly_steps` |
| Nutrição | `nutrition` | Health Record | `current_nutrition` |
| Meta de nutrição | `nutrition_goal` | Observed Snapshot | `current_daily_goal` |
| Ingestão de água | `water_intake` | Health Record | `current_water_intake` |
| Meta de ingestão de água | `water_intake_goal` | Observed Snapshot | `current_daily_goal` |
| Sono | `sleep` | Health Record | `current_sleep`, `current_sleep_session`, `current_sleep_stage` |
| Apneia do sono | `sleep_apnea` | Health Record | `current_sleep_apnea` |
| Meta de sono | `sleep_goal` | Observed Snapshot | `current_daily_goal` |
| Temperatura da pele | `skin_temperature` | Health Record | `current_skin_temperature`, `current_skin_temperature_series` |
| Glicemia | `blood_glucose` | Health Record | `current_blood_glucose`, `current_blood_glucose_series` |
| Oxigênio no sangue | `blood_oxygen` | Health Record | `current_blood_oxygen`, `current_blood_oxygen_series` |
| Pressão arterial | `blood_pressure` | Health Record | `current_blood_pressure` |
| Composição corporal | `body_composition` | Health Record | `current_body_composition` |
| Temperatura corporal | `body_temperature` | Health Record | `current_body_temperature` |
| Frequência cardíaca | `heart_rate` | Health Record | `current_heart_rate`, `current_heart_rate_series` |
| Notificação de ritmo cardíaco irregular | `irregular_heart_rhythm` | Health Record | `current_irregular_heart_rhythm` |
| Meta de calorias ativas | `active_calories_burned_goal` | Observed Snapshot | `current_daily_goal` |
| Meta de tempo ativo | `active_time_goal` | Observed Snapshot | `current_daily_goal` |
| Pontuação de energia | `energy_score` | Health Record | `current_energy_score` |
| Meta de passos | `steps_goal` | Observed Snapshot | `current_daily_goal` |
| Perfil do usuário | `user_profile` | Observed Snapshot | `user_profile` |

**Esperado:**

- Durante a execução, as categorias mostram **Carga inicial em andamento** e o contador de **Observações lidas** sobe. A outbox sobe e volta a zero à medida que a API confirma.
- Ao fim, nenhuma categoria aparece como **Não implementada nesta versão**. Localização de exercício aparece como **Sincronizada junto com outra categoria**; cada uma das outras 24 mostra **Carga inicial concluída**, **Última sincronização concluída**, e **Último sucesso** igual a **Última tentativa**. **Observações lidas** pode ficar em 0 numa categoria sem dados no aparelho.
- **Pendências de mapeamento** fica em 0. Um valor maior é um desvio: o registro foi recusado pela API, e a aba **Diagnóstico** mostra **Registro não mapeável**.
- A aba **Diagnóstico** não mostra incidente algum.
- No log, aparece `inventory=concluded` e nenhum `inventory=incomplete` ou `inventory=invalid`: cada Record Inventory declarado foi aceito e concluído. Só aparece `rejected=0`. `removals` maior que zero não é desvio aqui: é uma Source Removal concluída de um registro apagado no Samsung Health enquanto o aplicativo não observava; anote só a soma.

### 5. Sincronização em background

1. Feche o aplicativo indo para a tela inicial. Não use force-stop: ele cancela o agendamento.
2. Confira quando o trabalho periódico pode rodar:

   ```sh
   adb shell dumpsys jobscheduler | grep -A40 "androidx.work.systemjobscheduler:.*myhealthbridge" | grep -E "JOB |Run time|Unsatisfied"
   ```

3. Espere passar o `earliest` do `Run time`, sem abrir o aplicativo, até o id do job mudar. O WorkManager reagenda o trabalho periódico com outro id a cada execução.
4. Abra a aba **Sincronização**.

**Esperado:**

- Passo 2: existe um único job `SyncWorker` do aplicativo, cujo `Run time` é cerca de uma hora depois da última execução.
- Passo 3: o job roda depois do `earliest`, mas não necessariamente nele. Enquanto o aplicativo está em cache, o Android pode bloquear a rede dele (`Unsatisfied constraints: CONNECTIVITY`) até o sistema liberar o job.
- Passo 4: **Última tentativa** e **Último sucesso** coincidem com a execução do passo 3.

Forçar o job com `cmd jobscheduler run -f` não serve: o WorkManager recusa executar um trabalho periódico antes da janela e só o reagenda.

### 6. Conta somente leitura

Na VPS, abra a sessão pela conta de leitura e informe a senha no prompt, sem colocá-la na linha de comando:

```sh
docker exec -it postgres-postgres-1 psql -X -W -h 127.0.0.1 -U myhealth_read -d myhealth
```

Na sessão, confira quem está conectado, os atributos e associações do papel, o que ele alcança no `read_model` e o que ele alcança nas tabelas de ingestão:

```sql
select current_user, session_user;
select rolsuper, rolcreaterole, rolcreatedb, rolinherit, rolreplication, rolbypassrls from pg_roles where rolname = current_user;
select count(*) as associacoes from pg_auth_members where member = (select oid from pg_roles where rolname = current_user);
select count(*) as views,
       count(*) filter (where has_table_privilege(c.oid, 'select')) as legiveis,
       count(*) filter (where has_table_privilege(c.oid, 'insert') or has_table_privilege(c.oid, 'update') or has_table_privilege(c.oid, 'delete') or has_table_privilege(c.oid, 'truncate')) as gravaveis
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'read_model' and c.relkind = 'v';
select n.nspname, c.relname, has_table_privilege(c.oid, 'select') as legivel
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind = 'r' and n.nspname not in ('pg_catalog', 'information_schema', 'read_model')
order by 1, 2;
```

**Esperado:**

- `current_user` e `session_user` são `myhealth_read`.
- Todos os atributos são `f`, e `associacoes` é 0.
- `views` e `legiveis` são 26, as views que as migrations criam no `read_model`, e `gravaveis` é 0.
- A última consulta lista as tabelas da API (entre elas `observed_record_version`, `snapshot_observation`, `declared_inventory`, `ingestion_device` e `flyway_schema_history`) e nenhuma é `legivel`. Uma linha `t` é um desvio: o isolamento das tabelas de ingestão foi quebrado.

### 7. Consultas agregadas das 25 categorias

Defina o corte, dois dias antes de hoje no relógio do banco. As contagens até o corte não mudam com o que o aparelho mede durante a execução, e são elas que o passo 10 compara:

```sql
select current_date - 2 as corte \gset
```

A consulta de totais tem uma linha por view, nomeada pela categoria (e pelos dados associados, depois do ponto). Ela devolve só contagens:

```sql
select 'activity_summary' as categoria, count(*) as linhas, count(*) filter (where window_start < :'corte') as ate_o_corte from read_model.current_hourly_activity_summary
union all select 'active_calories_burned_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'active_calories_burned_goal'
union all select 'active_time_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'active_time_goal'
union all select 'blood_glucose', count(*), count(*) filter (where period_start < :'corte') from read_model.current_blood_glucose
union all select 'blood_glucose.series', count(*), count(*) filter (where "at" < :'corte') from read_model.current_blood_glucose_series
union all select 'blood_oxygen', count(*), count(*) filter (where period_start < :'corte') from read_model.current_blood_oxygen
union all select 'blood_oxygen.series', count(*), count(*) filter (where start_at < :'corte') from read_model.current_blood_oxygen_series
union all select 'blood_pressure', count(*), count(*) filter (where period_start < :'corte') from read_model.current_blood_pressure
union all select 'body_composition', count(*), count(*) filter (where period_start < :'corte') from read_model.current_body_composition
union all select 'body_temperature', count(*), count(*) filter (where period_start < :'corte') from read_model.current_body_temperature
union all select 'energy_score', count(*), count(*) filter (where local_date < :'corte') from read_model.current_energy_score
union all select 'exercise', count(*), count(*) filter (where period_start < :'corte') from read_model.current_exercise
union all select 'exercise.session', count(*), count(*) filter (where start_at < :'corte') from read_model.current_exercise_session
union all select 'exercise.log', count(*), count(*) filter (where "at" < :'corte') from read_model.current_exercise_log
union all select 'exercise_location', count(*), count(*) filter (where "at" < :'corte') from read_model.current_exercise_location
union all select 'floors_climbed', count(*), count(*) filter (where period_start < :'corte') from read_model.current_floors_climbed
union all select 'heart_rate', count(*), count(*) filter (where period_start < :'corte') from read_model.current_heart_rate
union all select 'heart_rate.series', count(*), count(*) filter (where start_at < :'corte') from read_model.current_heart_rate_series
union all select 'irregular_heart_rhythm', count(*), count(*) filter (where detected_at < :'corte') from read_model.current_irregular_heart_rhythm
union all select 'nutrition', count(*), count(*) filter (where period_start < :'corte') from read_model.current_nutrition
union all select 'nutrition_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'nutrition_goal'
union all select 'observed_snapshot', count(*), count(*) filter (where window_start < :'corte') from read_model.current_observed_snapshot
union all select 'skin_temperature', count(*), count(*) filter (where period_start < :'corte') from read_model.current_skin_temperature
union all select 'skin_temperature.series', count(*), count(*) filter (where start_at < :'corte') from read_model.current_skin_temperature_series
union all select 'sleep', count(*), count(*) filter (where period_start < :'corte') from read_model.current_sleep
union all select 'sleep.sessions', count(*), count(*) filter (where start_at < :'corte') from read_model.current_sleep_session
union all select 'sleep.stages', count(*), count(*) filter (where start_at < :'corte') from read_model.current_sleep_stage
union all select 'sleep_apnea', count(*), count(*) filter (where period_start < :'corte') from read_model.current_sleep_apnea
union all select 'sleep_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'sleep_goal'
union all select 'steps', count(*), count(*) filter (where window_start < :'corte') from read_model.current_hourly_steps
union all select 'steps_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'steps_goal'
union all select 'user_profile', count(*), count(*) filter (where observed_at < :'corte') from read_model.user_profile
union all select 'water_intake', count(*), count(*) filter (where period_start < :'corte') from read_model.current_water_intake
union all select 'water_intake_goal', count(*), count(*) filter (where day < :'corte') from read_model.current_daily_goal where goal = 'water_intake_goal'
order by categoria;
```

1. Execute a consulta de totais.
2. Marque o instante e registre uma frequência cardíaca nova no Samsung Health (medição manual no aparelho ou no relógio):

   ```sql
   select now() as inicio_fc \gset
   ```

3. Toque em **Sincronizar agora** e, quando a execução terminar, conte as medições desde o instante marcado:

   ```sql
   select count(*) as novas from read_model.current_heart_rate where period_start >= :'inicio_fc';
   ```

**Esperado:**

- A consulta de totais responde com as 31 linhas, sem erro de permissão. Uma categoria com `linhas` em 0 passa se cumprir o [Critério de aprovação](#critério-de-aprovação).
- Depois do passo 3, `novas` é ao menos 1.
- A contagem de `exercise_location` é maior que zero quando há um exercício ao ar livre com localização no histórico. Sem nenhum, zero também está correto.

### 8. Token recusado

1. No shell da VPS, marque o início e revogue o Ingestion Device do aparelho:

   ```sh
   INICIO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
   docker exec myhealth-api /app/bin/ingestion-api device revoke <label>
   ```

2. Registre uma frequência cardíaca nova no Samsung Health, para que haja algo a entregar: uma categoria sem nada a entregar conclui sem consultar a API.
3. No aplicativo, toque em **Sincronizar agora**.
4. Toque na notificação **Manutenção necessária**.
5. No shell da VPS, conte as recusas desde o início:

   ```sh
   docker logs --since "$INICIO" myhealth-api 2>&1 | grep -c 'ingestion refused code=invalid_device_token'
   ```

6. Rotacione o token. A saída mostra o token novo uma única vez, prefixado por `token=`:

   ```sh
   docker exec myhealth-api /app/bin/ingestion-api device rotate <label>
   ```

7. Na aba **Sincronização**, informe o token novo em **Token do aparelho**, toque em **Salvar** e depois em **Sincronizar agora**.

**Esperado:**

- Passo 3: Frequência cardíaca mostra **A API de ingestão recusou o token deste aparelho. Veja a aba Diagnóstico.**, e **Aguardando confirmação** fica maior que zero: nada é descartado. Uma notificação **Manutenção necessária** aparece com **Token do aparelho recusado**.
- Passo 4: a notificação abre a aba **Diagnóstico**, com **Token do aparelho recusado**, `Código: device_token_refused`, `Categoria: todas` e a ação sugerida. Nenhum token aparece.
- Passo 5: a contagem é ao menos 1.
- Passo 7: a entrega é aceita, **Aguardando confirmação** volta a 0, Frequência cardíaca mostra **Última sincronização concluída**, e o incidente sai da aba **Diagnóstico** e da área de notificações.

### 9. Source Removal de um registro de teste

1. Marque o instante:

   ```sql
   select now() as inicio_teste \gset
   ```

2. No Samsung Health, registre um copo em **Ingestão de água**. Toque em **Sincronizar agora** e, ao fim, conte os registros de teste:

   ```sql
   select (select count(*) from read_model.current_water_intake where period_start >= :'inicio_teste') as agua,
          (select count(*) from read_model.current_body_composition where period_start >= :'inicio_teste') as composicao;
   ```

3. No Samsung Health, apague esse registro. Toque em **Sincronizar agora** e repita a consulta.

Se o Samsung Health não criar um registro de água próprio (`agua` não sobe para 1 no passo 2), use no lugar um peso manual em **Composição corporal**, acompanhado pela coluna `composicao`.

**Esperado:**

- Passo 2: a coluna do registro de teste passa de 0 a 1.
- Passo 3: ela volta a 0. A remoção chegou à VPS como Source Removal: o Health Record removido sai da view, mas continua preservado e projetado.
- **Pendências de mapeamento** continua em 0.

### 10. Segunda reconciliação integral

1. Execute a consulta de totais do passo 7 e guarde a coluna `ate_o_corte` fora do repositório.
2. No shell da VPS, marque o início com `INICIO=$(date -u +%Y-%m-%dT%H:%M:%SZ)`.
3. Toque em **Reconciliar todo o histórico** e depois em **Sincronizar agora**, e espere a execução terminar.
4. Repita o resumo de log do passo 4.
5. Execute de novo a consulta de totais e a consulta do registro de teste do passo 9.

**Esperado:**

- A coluna `ate_o_corte` é idêntica, linha a linha, à do item 1: a reconciliação não duplicou nada, porque a API responde `already_present` para o que já guardou. Qualquer diferença é um desvio; anote só a categoria e o sinal da diferença.
- No log, aparece `inventory=concluded`, nenhum `inventory=incomplete` ou `inventory=invalid`, só `rejected=0` e só `removals=0`: nada removido desde a primeira reconciliação, e a remoção do passo 9 não é concluída de novo.
- O registro de teste do passo 9 continua em 0: a reconciliação não o traz de volta.
- **Pendências de mapeamento** continua em 0 e a aba **Diagnóstico** não mostra incidente algum.

## Critério de aprovação

Uma execução é aprovada quando todos os passos terminam como esperado, e em particular:

- O APK é o de release 1.0.x assinado, instalado como atualização (passo 1).
- Cada uma das 25 categorias passa com permissão concedida, carga inicial concluída (ou, em Localização de exercício, sincronizada junto com Exercício), **Pendências de mapeamento** em 0 e as consultas da view dela respondendo. Contagem zero é aceita: apneia do sono, ritmo cardíaco irregular, glicemia, pressão arterial, temperatura corporal, nutrição e qualquer outra categoria que o Data Owner não registra podem vir vazias sem reprovar a execução. Se o mapper interpreta corretamente um registro dessas categorias é coberto pelos testes automatizados, não por este roteiro.
- A Full Reconciliation depois da instalação foi feita (passo 4), e a segunda deixou `ate_o_corte` inalterado (passo 10).
- Os Record Inventories foram todos concluídos, e a remoção do registro de teste chegou à VPS como removida e assim ficou depois da segunda reconciliação (passos 4, 9 e 10).
- O token recusado abriu o Maintenance Incident e a notificação, e a rotação os encerrou sem perder nada da outbox (passo 8).
- Todas as consultas rodaram pela conta `myhealth_read`, sem fallback de administrador, e ela não alcança as tabelas de ingestão (passos 6 a 10).

Uma execução que falha em qualquer um desses pontos é reprovada. A correção sai numa nova release 1.0.x (a tag nunca se move e o `versionCode` nunca regride), e o roteiro inteiro roda de novo sobre ela, numa nova linha da tabela. A entrega é a primeira 1.0.x aprovada.

## Execuções

Uma linha por execução completa, acrescentada ao fim da tabela; linhas anteriores não são editadas. Um desvio que gerou issue a cita pelo nome. As colunas da linha são:

- **Data:** o dia da execução, `AAAA-MM-DD`.
- **Commit ou tag do APK:** a tag da release instalada e o commit curto dela, como `v1.0.0` (`abc1234`). Execuções anteriores à 1.0 citam só o commit do artefato.
- **Aparelho:** modelo e versão do Android, como `SM-S938B, Android 16`.
- **Samsung Health:** a versão instalada, em **Configurações > Sobre o Samsung Health**.
- **Resultado:** `Aprovada` ou `Reprovada`, seguido de uma frase sobre o que decidiu o resultado.
- **Desvios:** `Nenhum`, ou `Ver abaixo` com uma subseção da data listando cada desvio por passo, a issue que ele gerou e se ela está aberta ou fechada.

Uma linha `Aprovada` sem desvios abertos, com todas as issues de implementação da spec fechadas, fecha a spec [Sincronizar Consolidated Health Data com PostgreSQL pessoal](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/1) (#1) e, em seguida, o mapa [Entregar a MyHealth Bridge 1.0](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/27) (#27). Uma linha aprovada com desvio aberto só fecha a spec quando esse desvio for resolvido.

| Data | Commit ou tag do APK | Aparelho | Samsung Health | Resultado | Desvios |
| --- | --- | --- | --- | --- | --- |
| 2026-10-17 | `7ad5850` | SM-S938B, Android 16 | 7.1.0.053 | Todos os passos passaram depois de provisionar o aparelho na VPS. | Ver abaixo. |
| 2026-10-19 | `v1.0.0` (`5f626fc`) | SM-S938B, Android 16 | 7.1.0.053 | Reprovada: no passo 7 a leitura incremental falhou com a janela de leitura invertida; os passos 1 a 4 e 6 passaram. | Ver abaixo. |
| 2026-10-19 | `v1.0.1` (`14be1f0`) | SM-S938B, Android 16 | 7.1.0.053 | Reprovada: no passo 4 a Pontuação de energia não concluiu a sincronização; os passos 1 a 3 passaram. | Ver abaixo. |
| 2026-10-19 | `v1.0.2` (`01fcf40`) | SM-S938B, Android 16 | 7.1.0.053 | Aprovada: os dez passos terminaram como esperado, o passo 5 na segunda tentativa; é a primeira 1.0.x aprovada e a entrega da 1.0. | Ver abaixo. |

### 2026-10-17

- **Passo 4:** a sincronização estava parada desde 14/08. A API respondia `401` porque nenhum Ingestion Device tinha sido provisionado em produção, e o token salvo vinha de outra instância. O aplicativo mostrou só que a API "não está configurada ou não aceitou o token", sem incidente nem notificação. Corrigido ao criar o aparelho `galaxy-s25-ultra`; a ausência de incidente para token recusado segue em "Relatar token de aparelho recusado como Maintenance Incident" (#24).
- **Passo 6:** a conta somente leitura não existe em produção. As consultas rodaram como administrador do PostgreSQL. Segue em "Criar a conta somente leitura do read model em produção" (#25).
- **Passo 3:** o texto da notificação termina com ponto duplicado ("p.m..").
- **Passo 5:** o trabalho horário rodou sete minutos depois da janela, com a rede do aplicativo em background bloqueada até o sistema liberar o job.
- A especificação previa o token do aparelho no Android Keystore; ele fica no Room. Decidido em "Decidir o armazenamento do token do aparelho: Keystore ou Room" (#26): o Room basta, ver ADR `0020`.

### 2026-10-19

- **v1.0.0, passo 7:** depois da carga inicial, a leitura incremental de várias categorias falhou com `unexpected_failure` e `InvalidRequestException`, e uma frequência cardíaca nova não chegou à API. O `SyncService` calculava o fim da carga inicial na hora UTC e os importadores liam na hora local do aparelho, então a janela ia de 20:35 a 17:35. Corrigido em "Janela de leitura invertida depois da carga inicial por descompasso de relógios" (#64, fechada), na v1.0.1. Um cursor já gravado pela 1.0.0 só se recupera com uma Full Reconciliation, o que o passo 4 já exige.
- **v1.0.1, passo 4:** a Pontuação de energia terminava a sincronização com "O Samsung Health não respondeu na última tentativa", porque a leitura por data pedia um intervalo de tamanho zero quando a janela cabia num único dia. Corrigido em "Leitura da pontuação de energia recusada quando a janela cabe num único dia" (#66, fechada), na v1.0.2.
- **v1.0.2, passo 5:** o trabalho horário rodou mais de uma hora depois da janela, com o aplicativo em background, e a rede do aplicativo caiu no meio da rodada (filtro de rede em background da Samsung). A API recebeu parte dos lotes, a outbox ficou com 10 itens e a aba Diagnóstico mostrou `ingestion_unreachable`. Depois de uma sincronização manual, o trabalho seguinte rodou inteiro: Última tentativa e Último sucesso coincidiram nas 24 categorias lidas, sem incidente e com a outbox em 0. Não gerou issue, porque o aplicativo se comportou como projetado: parou a rodada, manteve a outbox e retomou na seguinte.
- **v1.0.2, passo 3:** o texto da notificação continua terminando com ponto duplicado ("p.m..").
- **v1.0.2, passo 5:** o título do incidente `ingestion_unreachable` diz "sem resposta há 24 horas" mesmo quando a falha foi de uma só rodada.
