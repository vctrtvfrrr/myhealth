# Observabilidade do stack

Este diretório é o estado desejado completo dos dashboards do stack `myhealth` no Grafana da plataforma CodeLab, conforme o ADR-0008 de `codelab/infra`. Cada deploy publica como geração os arquivos `dashboards/*.json`, e o reconciliador da plataforma converge a pasta `App: myhealth` para eles: um arquivo novo cria um dashboard, um arquivo alterado o substitui e um arquivo removido o apaga. O stack não declara regras de alerta, e `alerts/` não existe de propósito.

Os dashboards mostram a Personal Health History lida pela datasource `myhealth-read`, que a plataforma provisiona com a conta `myhealth_read` (ADR `0027`). Eles são escritos à mão e verificados num Grafana real antes do deploy (ADR `0028`), e recortam o tempo pela Record Local Time (ADR `0029`).

## Escrever um dashboard

Um dashboard é um arquivo `dashboards/<uid>.json` em JSON nativo do Grafana, no modelo clássico que o reconciliador envia a `/api/dashboards/db`, nunca no schema v2. O arquivo é a fonte: um rascunho feito na interface só conta depois de colado no arquivo e limpo do que o reconciliador recusa.

O reconciliador recusa a geração inteira, e com ela o deploy da API, quando um dashboard quebra uma destas regras, que o `PlatformGenerationTest` restata:

- `uid` local único, só com `[A-Za-z0-9_-]` e até 30 caracteres; na plataforma ele vira `myhealth--<uid>`.
- Só as datasources `myhealth-read`, `prometheus`, `loki` e `__expr__`. Painéis e consultas do histórico citam `{"type": "grafana-postgresql-datasource", "uid": "myhealth-read"}`.
- Nenhuma variável do tipo datasource e nenhum `${DS_*}` de uma exportação "para compartilhar".
- Nenhuma anotação embutida `-- Grafana --`: `annotations.list` fica vazia.
- Links só para dashboards da mesma geração, escritos como `/d/myhealth--<uid>`. Um dashboard ainda não publicado não entra na barra de links de nenhum outro.

A plataforma também impõe a pasta `App: myhealth` e `editable: false`, e descarta `id`, `version` e as tags iniciadas por `codelab-`.

Os painéis só leem views de `read_model`, as únicas que a conta de leitura alcança. Os dashboards de área seguem as convenções do ADR `0029`:

- `timezone: browser` e `weekStart: sunday`.
- A variável `agrupar` ("Agrupar por") é do tipo custom, com `Hora : hour,Dia : day,Semana : week,Mês : month`.
- Cada painel agrega no próprio SQL com `read_model.local_bucket('$agrupar', <coluna>_local)`; para Observed Snapshots, a coluna é `window_start`, que já é local.
- A coluna `time` é o balde convertido para o fuso do navegador, `<balde> at time zone '$__timezone'`.
- O período filtra a coluna local entre `$__timeFrom()::timestamptz at time zone '$__timezone'` e o mesmo com `$__timeTo()`.
- `$__timeGroup` não serve, porque só tem baldes fixos alinhados ao UTC.

Unidades: durações e horários em `dthms`, distância em `lengthkm` com 2 casas, calorias com `suffix: kcal` e frequência cardíaca com `suffix: bpm`. Nenhum painel tem limite, faixa ou cor de referência clínica.

## Testar

```bash
./gradlew :ingestion-api:test --tests '*PlatformGenerationTest' --tests '*PlatformDashboardsTest'
```

```bash
./gradlew :ingestion-api:integrationTest --tests '*DashboardPanelsTest'
```

O primeiro comando confere as regras do reconciliador sobre todos os arquivos deste diretório e a transformação da plataforma, que existe uma vez só, em `PlatformDashboards`. O segundo exige Docker. Ele popula o `read_model` pela API com registros sintéticos, cria a `myhealth_read` por `docs/sql/read-access.sql` e sobe `grafana/grafana:13.1` com a datasource `myhealth-read` logada como ela. Depois publica os dashboards como a plataforma publica e executa cada consulta de cada painel por `/api/ds/query`, para cada valor das variáveis custom, com `$__timezone` igual a `America/Sao_Paulo`. Todo painel precisa responder sem erro e com linhas. Os dois rodam no `:ingestion-api:check` do CI, antes do deploy.

Um dashboard novo pede três coisas em `DashboardPanelsTest`:

1. Os registros sintéticos que dão linhas a cada painel dele no período `SEEDED`.
2. Os valores exatos onde o SQL de um painel carrega regra própria, como o dia local, a meta vigente ou um nulo numa lacuna.
3. Para uma variável que não é custom, como o `samsung_uid` de um detalhe, o valor de teste em `GIVEN_VARIABLES`. Sem ele, o teste falha.

Um painel sem consulta, fora `row` e `text`, falha. Uma consulta ao `loki` ou ao `prometheus` aparece como ignorada, porque o teste não os tem: ela é conferida na prévia. Nenhuma fixture versionada pode conter dado pessoal.

## Pré-visualizar

A prévia é um Grafana 13.1 local com os dashboards exatamente como a plataforma os salva, lendo a produção pela `myhealth_read`. É ferramenta de autoria, não teste: ela julga o layout sobre o histórico real, com o mesmo alcance que a plataforma tem.

1. Abra um túnel SSH para o PostgreSQL da VPS, que escuta só em `127.0.0.1:5432` dela, e deixe-o aberto:

   ```bash
   ssh -N -L 15432:127.0.0.1:5432 <usuário>@<VPS>
   ```

   No Docker Desktop, o container alcança o túnel por `host.docker.internal`. No Docker Engine do Linux, ligue o túnel à ponte do Docker, por exemplo `-L 172.17.0.1:15432:127.0.0.1:5432`.
2. Em outro terminal, ponha a senha da `myhealth_read` numa variável da sessão, sem gravá-la em arquivo nem no histórico do shell, e suba a prévia:

   ```bash
   read -rs MYHEALTH_READ_PASSWORD && export MYHEALTH_READ_PASSWORD && ./gradlew previewUp
   ```

3. Abra `http://localhost:3039` e a pasta `App: myhealth`.

`previewUp` escreve `build/grafana-preview/` pela mesma transformação do teste (a tarefa `:ingestion-api:grafanaPreview`) e sobe o serviço `grafana` do `compose.dev.yml`, no perfil `preview`. O Grafana relê os arquivos a cada 10 segundos, então basta rodar `./gradlew :ingestion-api:grafanaPreview` de novo depois de editar um JSON. A prévia só escuta em `127.0.0.1` e entra sem login, como Editor. Para experimentar na interface, salve uma cópia do dashboard ("Save as"), leve o JSON da cópia para o arquivo e limpe o que as regras acima recusam.
