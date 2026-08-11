# MyHealth Bridge

MyHealth Bridge é um sistema pessoal para copiar os dados de saúde e atividade disponíveis no Samsung Health para um PostgreSQL sob controle do próprio usuário. O objetivo é formar um histórico durável e consultável por SQL, Grafana ou outras ferramentas externas, sem depender do Samsung Health como única interface de acesso.

> Este projeto é destinado exclusivamente ao acompanhamento pessoal de fitness e bem-estar. Ele não realiza diagnóstico, tratamento, recomendações clínicas ou alertas médicos.

## Estado do projeto

O projeto está em fase inicial. A API de ingestão já recebe lotes homogêneos e idempotentes de envelopes canônicos em `POST /ingestions`, autentica o aparelho por token, preserva as Observed Record Versions de forma imutável no PostgreSQL e devolve um resultado por posição enviada. A única vertical de contrato implementada é `heart_rate`, exercitada com envelopes sintéticos. O aplicativo Android ainda não lê o Samsung Health e não existe projeção do Current Health Record.

## Arquitetura planejada

- **Aplicativo Android:** lê todas as categorias disponíveis no Samsung Health Data SDK, realiza a carga histórica e mantém sincronizações periódicas e manuais.
- **API de ingestão:** recebe lotes idempotentes do aplicativo e controla a compatibilidade do contrato.
- **PostgreSQL:** preserva as versões observadas dos registros, mantém o estado corrente e oferece views SQL para consulta.
- **VPS CodeLab:** executa a API pelo contrato existente de Application Stack e `deploy-stack`.

O aplicativo Android funciona como buffer: registros permanecem em uma outbox local somente até a confirmação durável da API. O histórico canônico fica no PostgreSQL remoto.

## Escopo da versão 1.0

- Importação de todo o histórico acessível pelo Samsung Health Data SDK.
- Sincronização incremental aproximadamente horária e sincronização manual.
- Cobertura de todas as categorias legíveis do SDK adotado.
- Preservação de identidade, proveniência, versões observadas e remoções na origem.
- Recuperação idempotente após falhas, interrupções ou reinstalação do aplicativo.
- Notificações Android para incompatibilidades e situações que exijam manutenção.
- Views SQL básicas para consultas manuais e futuros consumidores.

Não fazem parte da primeira versão: escrita no Samsung Health, captura de sensores em tempo real, dashboards, análise clínica, suporte a múltiplos usuários, API de consulta ou distribuição pela Play Store.

## Documentação

- [`CONTEXT.md`](CONTEXT.md) define o vocabulário canônico do domínio.
- [`docs/adr/`](docs/adr/) registra as decisões arquiteturais.
- [Issue #1](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/1) contém a especificação completa e está marcada como pronta para implementação.

## Módulos

| Módulo          | Descrição                                                                                                             |
| --------------- | --------------------------------------------------------------------------------------------------------------------- |
| `contract`      | Contrato de transporte versionado compartilhado pelos dois lados. Não depende do módulo Android nem do módulo da API. |
| `ingestion-api` | API de ingestão Ktor. Depende de `contract`.                                                                          |
| `android-app`   | Aplicativo Android (Jetpack Compose). Depende de `contract`.                                                          |

## Desenvolvimento

### Pré-requisitos

- JDK 21 ou superior para executar o Gradle. O código é compilado para Java 17 por meio de um toolchain, baixado automaticamente quando ausente.
- Android SDK instalado, com o caminho declarado em `local.properties` (`sdk.dir=...`) ou na variável `ANDROID_HOME`. O arquivo `local.properties` não é versionado.
- Docker, para os testes de integração da API e para o ambiente local em containers.

### Comandos

| Comando                                | Efeito                                                                                                                |
| -------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `./gradlew build`                      | Compila e testa os três módulos, incluindo os testes de integração.                                                   |
| `./gradlew test`                       | Executa apenas os testes que não exigem Docker.                                                                       |
| `./gradlew integrationTest`            | Executa apenas os testes de integração, que sobem containers.                                                         |
| `./gradlew :ingestion-api:buildImage`  | Constrói a imagem `myhealthbridge-api:local` a partir da distribuição do Gradle.                                      |
| `./gradlew devUp`                      | Reconstrói a imagem e sobe PostgreSQL e a API em containers para desenvolvimento local.                               |
| `./gradlew :ingestion-api:run`         | Sobe a API de ingestão em `http://localhost:8080` (porta configurável por `PORT`), exigindo um PostgreSQL alcançável. |
| `./gradlew :android-app:assembleDebug` | Gera o APK de depuração em `android-app/build/outputs/apk/debug/`.                                                    |
| `./gradlew :android-app:installDebug`  | Instala o APK no aparelho ou emulador conectado.                                                                      |

A imagem da API é construída somente pela tarefa `buildImage`, que garante a distribuição atualizada antes do `docker build`. O ambiente local sobe por `devUp`, que depende dela: `docker build` e `docker compose up` invocados diretamente produzem imagem com o jar defasado e não são caminhos suportados.

`devUp` é o caminho documentado para exploração E2E manual, porque seu volume nomeado preserva o estado entre execuções. Por isso mesmo ele não é fixture de CI: os testes de integração usam containers descartáveis por classe.

### Configuração da API

A API lê toda a configuração de banco do ambiente e encerra a inicialização, nomeando a variável ausente, quando falta alguma das obrigatórias.

| Variável        | Obrigatória | Efeito                                                                      |
| --------------- | ----------- | --------------------------------------------------------------------------- |
| `DATABASE_HOST` | Sim         | Host do PostgreSQL.                                                         |
| `DATABASE_PORT` | Não         | Porta do PostgreSQL. O padrão é `5432`.                                     |
| `DATABASE_NAME` | Sim         | Nome da database.                                                           |
| `DATABASE_USER` | Sim         | Papel de runtime, que precisa das permissões DDL exigidas pelas migrations. |
| `DATABASE_PASS` | Sim         | Senha do papel de runtime.                                                  |
| `PORT`          | Não         | Porta HTTP da API. O padrão é `8080`.                                       |

Os limites de ingestão também vêm do ambiente. Um valor fora da faixa aceita impede a inicialização, em vez de ser ajustado silenciosamente.

| Variável                           | Padrão    | Faixa aceita     | Efeito                                              |
| ---------------------------------- | --------- | ---------------- | --------------------------------------------------- |
| `INGESTION_MAX_ITEMS`              | `500`     | 1 a 10000        | Itens por lote.                                     |
| `INGESTION_MAX_BYTES`              | `2097152` | 1024 a 67108864  | Bytes do corpo da requisição.                       |
| `INGESTION_TIMEOUT_SECONDS`        | `30`      | 1 a 600          | Tempo até a ingestão ser revertida e devolver 503.  |
| `DATABASE_POOL_MAX_SIZE`           | `5`       | 1 a 50           | Conexões simultâneas no pool.                       |
| `DATABASE_POOL_ACQUIRE_TIMEOUT_MS` | `5000`    | 250 a 60000      | Espera máxima por uma conexão do pool.              |

As migrations Flyway rodam na inicialização. Se qualquer uma falhar, a API registra a causa em log e encerra com código diferente de zero, sem abrir a porta HTTP, para nunca atender sobre schema incompatível. O pool de conexões só é aberto depois das migrations e antes da porta HTTP.

### Endpoints

| Endpoint                  | Autenticação     | Efeito                                                                                       |
| ------------------------- | ---------------- | -------------------------------------------------------------------------------------------- |
| `GET /health`             | Não              | Liveness. Não acessa o banco: responde `200` enquanto o processo estiver vivo.               |
| `GET /ready`              | Não              | Readiness. Responde `200` com banco alcançável e migrations aplicadas; `503` caso contrário.  |
| `GET /ingestion-contract` | Não              | Publica o Supported Contract Range: `minimumVersion`, `maximumVersion` e `recommendedVersion`. Não acessa o banco. |
| `POST /ingestions`        | `Bearer <token>` | Recebe um lote homogêneo de envelopes canônicos e devolve um resultado por posição enviada.   |

Não existe endpoint de consulta, alteração ou exclusão de Health Records: a superfície HTTP é só ingestão e saúde.

### Compatibilidade do contrato

O lote declara a sua Ingestion Contract Version e a API declara o Supported Contract Range, de modo que aplicativo e servidor possam ser atualizados em momentos diferentes. Hoje o range é unitário: mínima, recomendada e atual valem `1`. Ver ADR `0007`.

Toda resposta de `POST /ingestions`, inclusive os problem documents, carrega `Ingestion-Contract-Minimum`, `Ingestion-Contract-Maximum` e `Ingestion-Contract-Recommended`, para que a divergência apareça antes de algo quebrar. `GET /ingestion-contract` responde os mesmos números sem autenticação e sem depender do banco, porque é justamente com o serviço degradado que o cliente precisa distinguir uma queda de uma incompatibilidade. Os dois limites são publicados: sem o superior, um cliente mais novo não consegue distinguir "a API não aceita a minha versão" de "aceita, mas recomenda outra".

Uma versão fora do range recebe `422` com código estável: `contract_version_too_old` quando o aplicativo precisa ser atualizado, `contract_version_too_new` quando o servidor é que está atrasado. Não é `426`: o RFC 9110 exige o header `Upgrade` nesse status e o define sobre o protocolo HTTP, não sobre a versão de um documento. Os dois códigos são publicados desde já, mesmo com o range unitário tornando o primeiro inalcançável, para que a chegada de uma segunda versão não exija release coordenada dos dois lados. Um lote sem `contractVersion`, ou com valor não inteiro, continua sendo `invalid_batch`: é documento malformado, não cliente antigo.

Campo desconhecido na raiz do lote ou no envelope é descartado: fica fora do digest e fora do envelope armazenado, então não gera uma nova Observed Record Version — mas também não é preservado. Campo novo dentro de `sourcePayload` entra no digest e gera outra versão observada, porque um `sourcePayload` diferente é uma observação diferente.

O lote declara `contractVersion`, `recordType` e `items`; o tipo de registro aparece somente na raiz. Cada resultado traz `index` e `status` — `accepted`, `already_present` ou `rejected` —, e um item rejeitado traz códigos estáveis e ordenados. `accepted` e `already_present` significam igualmente que a observação está durável, o que permite ao aplicativo remover o item da outbox. Erros de lote usam RFC 9457 Problem Details com um `code` estável e não ecoam nada do que foi recebido.

Uma observação é a mesma Observed Record Version quando a representação canônica validada tem o mesmo digest SHA-256. Mudança em conteúdo, Source Provenance, offset original ou mapper version cria outra versão da mesma Health Record Identity. Versões observadas são imutáveis: `UPDATE` e `DELETE` são recusados pelo próprio PostgreSQL.

Os logs seguem uma allowlist. Eles podem conter apenas o `ingestionId`, a versão do contrato, tamanhos, duração, contagens por resultado e códigos seguros; payloads, valores biométricos, coordenadas, tokens, digests de token, Samsung UIDs, identificadores de Source Provenance e device labels nunca são registrados, nem através de mensagens de exceção.

### Aparelhos de ingestão

Somente aparelhos provisionados podem ingerir. O provisionamento é feito por subcomandos do mesmo artefato da API, que não abrem a porta HTTP:

| Comando                        | Efeito                                                                                          |
| ------------------------------ | ----------------------------------------------------------------------------------------------- |
| `device create <label>`        | Cria o aparelho e mostra o token uma única vez, prefixado por `token=`.                          |
| `device rotate <label>`        | Gera outro token, invalida o anterior imediatamente e reativa um aparelho revogado.              |
| `device revoke <label>`        | Revoga o aparelho.                                                                              |

Somente o SHA-256 do token é persistido, então um vazamento do banco não revela credencial utilizável. Token ausente, inválido ou revogado recebe o mesmo `401 invalid_device_token`. Contra o stack local:

```sh
docker compose -f compose.dev.yml run --rm api device create phone
```

Para o ambiente local, copie `.env.example` para `.env` e ajuste os valores. O arquivo `.env` não é versionado.

O desenvolvimento da integração requer um aparelho Android físico com Samsung Health e o Samsung Health Data SDK em modo de desenvolvedor; emuladores não suportam a integração real.

### Integração contínua

O workflow `.gitea/workflows/build.yml` executa `./gradlew build` a cada push e a cada pull request, e falha quando qualquer módulo quebra.

Dados pessoais, credenciais da VPS, tokens, chaves de assinatura e fixtures derivadas de medições reais não devem ser adicionados ao repositório.
