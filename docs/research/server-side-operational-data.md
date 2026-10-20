# O que o servidor registra que serve ao dashboard operacional

Data: 2026-10-20

Ticket: [#73](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/73), filho do mapa [#69](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/69).

## Pergunta

Do lado do servidor, o que existe para um dashboard operacional da sincronização? Em particular: o que as tabelas de ingestão registram e com quais carimbos de tempo; se dá para derivar Ingestions por hora, itens aceitos e recusados (com códigos), atraso desde a última entrega por Health Category, inventários e remoções concluídas; o formato dos logs da API e o que chega ao Loki da plataforma; se a API expõe métricas Prometheus e, se não, o que a plataforma coleta do container; o que nenhuma fonte do servidor responde, já que Maintenance Incidents, Mapping Pendencies e Sync Cursors vivem só no aparelho (ADR 0018).

Fontes primárias: as migrations e o código de `ingestion-api` neste repositório (no commit `2e9efe4`), e o repositório `codelab/infra` (branch `master`, lido pela API do Gitea em 2026-10-20) mais `codelab/deploy-stack`.

## Resumo e conclusão

- **O banco responde quase tudo o que o ticket pergunta**, por SQL sobre as tabelas de ingestão: Ingestions por hora (`ingestion.received_at`), itens `accepted` / `already_present` / `rejected` com os códigos de recusa (`ingestion_item`), a última entrega por Health Category (derivada por junção, porque a Ingestion não guarda a categoria), inventários por status e remoções concluídas (`declared_inventory`, `concluded_removal`). Confiança: alta (esquema e código).
- **O banco não vê as recusas de lote nem as falhas.** Um lote recusado com 401, 413, 415, 400 ou 422 e um 503 não deixam linha nenhuma: a Ingestion só é gravada na transação que dá certo. Esses casos só aparecem no log e nas métricas do Traefik. Confiança: alta.
- **O `myhealth_read` não alcança nada disso.** Ele só recebe `read_model`, e nenhuma view do `read_model` expõe tabela de ingestão, `received_at` ou `first_received_at`. Confiança: alta.
- **E mais fundo: hoje nenhum dashboard da dropzone pode consultar o PostgreSQL, com qualquer conta.** O Grafana da plataforma só tem os datasources Prometheus e Loki, e o contrato da dropzone permite apenas `prometheus`, `loki` e `__expr__` (ADR-0008 de `codelab/infra`). Isso vale para o mapa inteiro, não só para o operacional. Confiança: alta.
- **Os logs da API chegam ao Loki e carregam contagens por Ingestion**, numa linha `chave=valor` em texto puro: id, versão do contrato, itens, duração, aceitos, já presentes, recusados e códigos. Não trazem a Health Category nem o aparelho, e ficam só 30 dias no Loki. Hoje são a única fonte de estatística de ingestão que um dashboard da dropzone pode consultar. Confiança: alta.
- **A API não expõe métricas Prometheus.** Não há endpoint, dependência nem os labels de opt-in `codelab.metrics.*`. A plataforma coleta de todo container CPU, memória, rede (cAdvisor), reinícios (`codelab_container_restarts_total`), presença como Expected Service e os logs, e do Traefik as requisições e a latência por router e código HTTP. Confiança: alta.
- **Ficam sem resposta no servidor:** Maintenance Incidents, Mapping Pendencies, Sync Cursors, a profundidade do Outbox, Permission State, Samsung Health Availability e qualquer tentativa do aparelho que não chegou à API. O servidor só vê a ausência de entregas. Confiança: alta.

## Fatos

### 1. Tabelas de ingestão: o que cada uma registra e quando

Esquema em `ingestion-api/src/main/resources/db/migration/V1__ingestion.sql`, `V2__current_health_record.sql`, `V5__declared_inventory.sql` e `V6__observed_snapshot.sql`; escrita em `ingestion-api/src/main/kotlin/br/etc/victor/myhealthbridge/api/IngestionStore.kt`.

| Tabela | O que registra | Carimbos de tempo | Observações |
|---|---|---|---|
| `ingestion_device` | `device_label`, `token_digest`, `created_at`, `revoked_at` | `created_at`, `revoked_at` | Rotacionar o token sobrescreve o digest e zera `revoked_at` (`IngestionDevices.kt`, `update ingestion_device set token_digest = ?, revoked_at = null`): não há histórico de tokens nem "visto por último". |
| `ingestion` | `id` (UUID), `ingestion_device_id`, `contract_version`, `item_count`, `received_at` | `received_at` = `Instant.now()` no início da escrita (`IngestionRoutes.kt`, `persist`) | **Não guarda** a Health Category (`recordType` / `snapshotType` do lote), a duração nem o status HTTP. Só existe para um lote que passou por autenticação, tipo de mídia, tamanho, parse, faixa do contrato e limite de itens, **e** cuja transação fez commit. |
| `ingestion_item` | `(ingestion_id, position)`, `status` (`accepted`, `already_present`, `rejected`), `observed_record_version_id` ou `snapshot_observation_id`, `rejection_codes text[]` | nenhum próprio; herda `ingestion.received_at` | Um item recusado guarda só os códigos, sem JSON, fragmento nem Samsung UID (comentário em `V1__ingestion.sql`). Um item não recusado aponta exatamente para uma versão ou observação (`ingestion_item_result_shape`, `V6`). |
| `observed_record_version` | `record_type`, `state` (`present` / `removed`), `observed_at`, `period_start` / `period_end` com offsets, `mapper_version`, `envelope`, `first_received_at` | `observed_at` é a hora em que a origem alterou o registro pela última vez (ADR 0014); `first_received_at` é o `received_at` da Ingestion que gravou a versão primeiro | Imutável (trigger `observed_record_version_is_immutable`). Uma versão `already_present` não muda `first_received_at`. |
| `health_record_identity` | `record_type`, `samsung_uid` | nenhum | Identidade do Health Record. |
| `current_health_record` | ponteiro identidade → versão corrente | nenhum | Projeção. |
| `declared_inventory` | `ingestion_id` (PK), `record_type`, `interval_start` / `interval_end` (hora local, `timestamp` sem fuso), `read_at`, `declared_count`, `status` (`concluded`, `incomplete`, `invalid`), `removal_count` | `read_at`; a hora de recebimento vem da `ingestion` por junção | Um inventário `invalid` é gravado sem intervalo e com `removal_count = 0` (`declared_inventory_shape`). |
| `concluded_removal` | `(ingestion_id, observed_record_version_id)` | nenhum próprio | Liga cada Source Removal concluída ao inventário que a concluiu. |
| `observed_snapshot` | `snapshot_type`, `window_start` / `window_end` | a janela, em hora local | Identidade do Observed Snapshot. |
| `snapshot_observation` | `snapshot_type`, `observed_at`, `observed_at_offset`, `source_scope`, `mapper_version`, `empty`, `observation`, `first_received_at` | `observed_at` é a hora da leitura pelo app (ADR 0025); `first_received_at` como acima | Só é gravada quando o conteúdo difere da observação anterior; o reenvio igual responde `already_present` e aponta para a anterior. |
| `current_observed_snapshot` | ponteiro snapshot → observação corrente | nenhum | Projeção. |

Os valores de `record_type` e `snapshot_type` são os nomes das Health Categories do catálogo (`docs/capability-catalog.md`), com a exceção de `exercise_location`, que viaja dentro de `exercise`.

Índices: além das chaves primárias e únicas, só existem `observed_record_version_by_identity_observation` e `snapshot_observation_by_snapshot_observation`. Não há índice em `ingestion.received_at` nem em `ingestion_item.status`.

### 2. O que dá para derivar do banco

Tudo abaixo é SQL sobre as tabelas de ingestão, que só o papel de runtime da API alcança hoje (ver a seção 5).

**Ingestions por hora**: sim, direto. Também por aparelho (`ingestion_device_id`) e por versão do contrato (`contract_version`).

```sql
select date_trunc('hour', received_at) as hora, count(*) as ingestions, sum(item_count) as itens
from ingestion group by 1 order by 1;
```

**Itens aceitos, já presentes e recusados, com códigos**: sim.

```sql
select date_trunc('hour', i.received_at) as hora, it.status, count(*)
from ingestion i join ingestion_item it on it.ingestion_id = i.id
group by 1, 2;

select code, count(*)
from ingestion_item it, unnest(it.rejection_codes) as code
group by code;
```

**Health Category de uma Ingestion**: só por derivação. Cada lote carrega um único `recordType` ou `snapshotType` (`IngestionRoutes.kt`), mas a `ingestion` não o guarda. Ele se recupera pelo item aceito ou já presente (`observed_record_version.record_type` ou `snapshot_observation.snapshot_type`) ou pelo inventário (`declared_inventory.record_type`). **Um lote em que todos os itens foram recusados e que não declarou inventário perde a categoria**: a recusa por item fica contada, mas não atribuível.

```sql
create temporary view ingestion_category as
select i.id, i.received_at,
       coalesce(max(v.record_type), max(o.snapshot_type), max(d.record_type)) as category
from ingestion i
left join ingestion_item it on it.ingestion_id = i.id
left join observed_record_version v on v.id = it.observed_record_version_id
left join snapshot_observation o on o.id = it.snapshot_observation_id
left join declared_inventory d on d.ingestion_id = i.id
group by i.id, i.received_at;
```

**Atraso desde a última entrega por Health Category**: sim, com dois sentidos diferentes de "entrega".

- *Última Ingestion que carregou a categoria*: `max(received_at)` sobre a derivação acima. Conta reenvios `already_present`. O app reencaminha registros já guardados na Overlap Re-read diária (`health-sync/.../SyncCursor.kt`, comentário de `importedRecords`; `SyncService.kt`, "the ingestion answers `already_present` for every one it already holds"), e um Observed Snapshot é relido a cada execução (ADR 0025). Por isso esse sinal funciona como um batimento por categoria, exceto para categoria sem nenhum registro nos últimos sete dias.
- *Último dado novo*: `max(first_received_at)` por `record_type` em `observed_record_version`, ou por `snapshot_type` em `snapshot_observation`. Mede quando chegou conteúdo novo, não quando o aparelho sincronizou.

**Inventários e remoções concluídas**: sim. `declared_inventory` por `record_type` e `status`, com `declared_count` e `removal_count`, datado pela junção com `ingestion.received_at`; `concluded_removal` liga cada remoção ao inventário.

**O que o banco não tem**:

- Lotes recusados antes da persistência (`invalid_device_token` 401, `unsupported_media_type` 415, `batch_too_large` 413, `invalid_request` 400, `too_many_items` / `invalid_batch` / `contract_version_too_old` / `contract_version_too_new` 422; `contract/.../ProblemDetails.kt`). Retornam em `IngestionRoutes.kt` antes de `store.persist`.
- `ingestion_temporarily_unavailable` (503): a transação faz rollback (`IngestionStore.kt`, `catch` → `connection.rollback()`), inclusive quando o orçamento de tempo expira antes do commit.
- Duração da Ingestion e latência HTTP.

### 3. Logs da API

**Formato**: `ingestion-api/src/main/resources/logback.xml` tem um único `ConsoleAppender` em stdout, nível raiz `INFO`, padrão `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`. É texto puro, não JSON. A hora não traz data nem fuso; o Loki usa o carimbo do Docker. O logger de ingestão (`br.etc.victor.myhealthbridge.api.Ingestion`) tem 42 caracteres e sai abreviado pelo `{36}` (inferência sobre o abreviador do Logback: algo como `b.e.v.myhealthbridge.api.Ingestion`).

**Não há log de acesso.** O módulo Ktor instala só o roteamento (`Application.kt`, `module`), e `ingestion-api/build.gradle.kts` não tem `call-logging`.

**Linhas que a API escreve sobre ingestão**, todas em `IngestionLog.kt`, que trabalha por allowlist:

| Evento | Nível | Mensagem |
|---|---|---|
| Ingestion persistida | INFO | `ingestion={uuid} contractVersion={n} items={n} durationMs={n} accepted={n} alreadyPresent={n} rejected={n} codes={lista}` |
| Inventário da mesma Ingestion | INFO | `ingestion={uuid} inventory={concluded\|incomplete\|invalid} removals={n}` |
| Lote recusado (4xx) ou 503 respondido | INFO | `ingestion refused code={código} status={http}` |
| Falha de persistência ou leitura | WARN | `ingestion unavailable code=ingestion_temporarily_unavailable failure={classe} sqlState={estado\|none}` |

Uma resposta 503 gera duas linhas: o `unavailable` (WARN) e o `refused ... status=503` (INFO). A falha de startup sai como ERROR `Startup aborted before serving any request` com a exceção (`Application.kt`).

**Limites dos logs**:

- Nenhuma linha traz a Health Category, o aparelho ou o caminho HTTP.
- A linha `refused` não traz id de Ingestion.
- `codes` é uma `List` do Kotlin impressa com `toString`, ou seja, `[invalid_payload, invalid_unit]`, com espaços. O parser `logfmt` do LogQL não a lê inteira; o caminho é `regexp` ou `pattern`.
- Payloads, valores biométricos, tokens, Samsung UIDs, rótulo do aparelho e mensagens de exceção ficam de fora por design (KDoc de `IngestionLog`; `IngestionLogRedactionTest`).

**O que chega ao Loki** (`codelab/infra`, [`platform/alloy/config.alloy`](https://git.codelab.tec.br/codelab/infra/src/branch/master/platform/alloy/config.alloy)): `loki.source.docker` lê stdout e stderr de todo container, sem redaction, com rótulos indexados fechados derivados dos labels do Compose:

- `app` = projeto Compose. O `deploy.sh` de `codelab/deploy-stack` sobe o stack com `docker compose up -d` dentro de `/opt/codelab/apps/<app>`, sem `-p`, então o projeto é `myhealth` (inferência a confirmar no Explore).
- `service` = serviço Compose: `api`.
- `container` = `<projeto>-<serviço>-<número>`, isto é `myhealth-api-1`. O `container_name: myhealth-api` do `compose.yml` só vale como reserva quando faltam os labels do Compose.
- `kind` = `application`, porque o diretório de trabalho fica sob `/opt/codelab/apps/`.

Retenção de 30 dias (`retention_period: 720h` em [`platform/loki/loki.yml`](https://git.codelab.tec.br/codelab/infra/src/branch/master/platform/loki/loki.yml)), com limite de 512 KiB/s por stream. Os campos semânticos ficam no corpo e são extraídos na consulta (ADR-0006 de `codelab/infra`).

**O que dá para tirar dos logs com LogQL** (inferência sobre o formato acima): Ingestions por hora (`count_over_time` sobre `|= "accepted="`), soma de aceitos, já presentes e recusados (`regexp` + `unwrap`), distribuição de `durationMs`, recusas por código e status, e 503s por `sqlState`. Tudo restrito aos últimos 30 dias e sem quebra por Health Category.

### 4. Métricas

**A API não expõe métricas.** As rotas são `/health`, `/ready`, `/ingestion-contract` e `POST /ingestions` (`Application.kt`, `IngestionRoutes.kt`). Não há Micrometer nem cliente Prometheus em `ingestion-api/build.gradle.kts`, e o `compose.yml` não tem os labels de opt-in. O opt-in da plataforma exige `codelab.metrics.enabled=true` e `codelab.metrics.port`, com `codelab.metrics.path` opcional, num endpoint sem autenticação e sem dado pessoal, alcançado pela rede `web` ([ADR-0012 de `codelab/infra`](https://git.codelab.tec.br/codelab/infra/src/branch/master/docs/adr/0012-application-stack-metrics-opt-in.md)).

**O que a plataforma coleta de todo Application Stack**, com os rótulos `app`, `service`, `container` e `kind` (`config.alloy`; [`docs/runbook/observability.md`](https://git.codelab.tec.br/codelab/infra/src/branch/master/docs/runbook/observability.md)):

| Sinal | Série | Fonte |
|---|---|---|
| CPU, memória (working set e limite), rede | `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`, `container_spec_memory_limit_bytes`, `container_network_*_bytes_total` | cAdvisor embutido no Alloy, a cada 30 s |
| Reinícios decididos pela política do Docker | `codelab_container_restarts_total` | Host Service textfile ([ADR-0013 de `codelab/infra`](https://git.codelab.tec.br/codelab/infra/src/branch/master/docs/adr/0013-container-restart-counter.md)); um `docker restart` manual não conta |
| Serviço esperado | `codelab_expected_service{app,service,kind}` | `deploy-stack` publica `docker compose config --services`; a regra da plataforma *Serviço esperado ausente* compara com o cAdvisor |
| Requisições HTTP e latência por router | `traefik_router_requests_total{router,code,...}`, `traefik_router_request_duration_seconds_bucket` | Traefik com `addRoutersLabels: true` ([`platform/traefik/traefik.yml`](https://git.codelab.tec.br/codelab/infra/src/branch/master/platform/traefik/traefik.yml)); o router do stack é `myhealth` (`compose.yml`), que o Traefik nomeia `myhealth@docker` (inferência) |
| Banco | `pg_stat_database_*{datname}`, `pg_database_size_bytes` | exporter Postgres do Alloy com os coletores `database`, `stat_database` e `long_running_transactions`; o banco do stack é `myhealth` por padrão (`.gitea/workflows/ci.yml`) |

O router do Traefik é um só para todo o host `myhealth.victor.etc.br`, então os códigos HTTP (401, 413, 415, 422, 503...) vêm agregados de todas as rotas. Como quase todo o tráfego externo é `POST /ingestions`, isso aproxima as recusas de lote (inferência). O healthcheck do Docker em `/ready` roda dentro do container e não passa pelo Traefik.

O Prometheus guarda 13 meses. O dashboard curado **Serviço** da plataforma já mostra CPU, memória, rede, reinícios, `up{job="app_metrics"}`, conflito de identidade e logs de qualquer container ([`platform/grafana/dashboards/service.json`](https://git.codelab.tec.br/codelab/infra/src/branch/master/platform/grafana/dashboards/service.json)). Um operacional do MyHealth que repita isso duplicaria a plataforma.

### 5. O que o `myhealth_read` alcança

- `docs/sql/read-access.sql` concede `usage` em `read_model` e `select` nas views dele, mais nada. O ADR 0013 deixa isso explícito: a conta de leitura não tem privilégio nas tabelas de ingestão.
- As views do `read_model` (V2 a V14) são todas de estado corrente de saúde: `current_*`, `user_profile`, `current_observed_snapshot`, `current_hourly_steps`. **Nenhuma** expõe `ingestion`, `ingestion_item`, `ingestion_device`, `declared_inventory`, `concluded_removal`, `received_at` ou `first_received_at`.
- O que o `read_model` oferece de mais próximo é `observed_at` e `mapper_version` por registro ou snapshot corrente. Isso mede o dado, não a sincronização. Num Health Record, `observed_at` é a hora em que a origem alterou o registro (ADR 0014). Num snapshot, é a primeira vez que aquele conteúdo foi visto (ADR 0025), e um perfil que não muda mantém o `observed_at` antigo mesmo sendo relido a cada execução.
- Logo, qualquer painel operacional por SQL exige uma superfície nova: views operacionais no `read_model` (o ADR 0013 deixa a conta enxergar o que uma view expuser), outro schema ou outra conta.

**Restrição da plataforma que vale para o mapa inteiro**: o Grafana da CodeLab só provisiona os datasources `Prometheus` e `Loki` ([`platform/grafana/provisioning/datasources/datasources.yml`](https://git.codelab.tec.br/codelab/infra/src/branch/master/platform/grafana/provisioning/datasources/datasources.yml)). O contrato da dropzone diz que os artefatos de app "poderão consultar somente `prometheus`, `loki` e `__expr__`" ([ADR-0008 de `codelab/infra`](https://git.codelab.tec.br/codelab/infra/src/branch/master/docs/adr/0008-application-observability-reconciliation.md); [`docs/runbook/applications.md`](https://git.codelab.tec.br/codelab/infra/src/branch/master/docs/runbook/applications.md)). O `observability/README.md` deste repositório repete a regra. Um dashboard entregue pela dropzone não pode, hoje, consultar o PostgreSQL com conta nenhuma: isso exige mudar a plataforma (datasource novo e emenda ao ADR-0008 dela) ou entregar o dashboard por outro caminho.

### 6. O que nenhuma fonte do servidor responde

- **Maintenance Incidents, Mapping Pendencies e Sync Cursors**: vivem em Room no aparelho (ADR 0018; `CONTEXT.md`). O servidor vê só o efeito: uma Mapping Pendency nasce de um item `rejected`, que aparece em `ingestion_item`, mas o servidor não sabe se ela ainda está aberta.
- **Profundidade do Outbox**, backpressure, Permission State, Samsung Health Availability, categorias nunca concedidas e o último resultado de cada Sync Cursor (`lastAttemptAt`, `lastSuccessAt`, `lastOutcome`).
- **Tentativas que não chegaram**: aparelho sem rede, endpoint não configurado, Samsung Health indisponível. Para o servidor, uma interrupção é só a ausência de Ingestions, sem causa.
- **O tipo de leitura** que gerou um lote (leitura comum, Overlap Re-read, Full Reconciliation, Source Change Feed): o lote não diz.
- **A categoria de um lote recusado inteiro** (4xx, 503 ou todos os itens `rejected` sem inventário).

## Questões em aberto

1. **Por onde o Grafana lê o banco.** Há três caminhos e nenhum está decidido: (a) datasource PostgreSQL na plataforma, o que muda o ADR-0008 de `codelab/infra`; (b) a API expor contadores pelo opt-in do ADR-0012, por exemplo Ingestions, itens por status e categoria, códigos de recusa e última entrega por categoria, sem dado pessoal; (c) só LogQL sobre os logs atuais, aceitando 30 dias e nenhuma quebra por categoria. Para o mapa #69 é decisão de grilling.
2. **Rótulos exatos no Loki e no Prometheus**: confirmar no Explore que `app="myhealth"`, `service="api"`, `container="myhealth-api-1"` e `router="myhealth@docker"`.
3. **Estado de saúde do Docker** (`healthy` / `unhealthy` do healthcheck em `/ready`): não achei a série na configuração do Alloy, e o cAdvisor não costuma exportá-la. Hoje a prontidão só se vê indiretamente.
4. **Pôr a categoria no log ou na `ingestion`**: um campo `recordType` / `snapshotType` na linha `ingestion=` e na tabela resolveria a atribuição dos lotes recusados inteiros e a quebra por categoria nos logs. É mudança de código, fora desta pesquisa.
5. **Custo das consultas**: sem índice em `ingestion.received_at`, consultas por janela de tempo varrem a tabela. Com cerca de 24 execuções por dia e um lote por categoria, o volume deve ser pequeno, mas não foi medido.
6. **Escopo do operacional diante do dashboard Serviço**: CPU, memória, reinícios e logs do container já existem na pasta Plataforma; falta decidir se o operacional do MyHealth só aponta para lá ou repete esses painéis.
