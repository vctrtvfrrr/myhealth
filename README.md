# MyHealth Bridge

MyHealth Bridge é um sistema pessoal para copiar os dados de saúde e atividade disponíveis no Samsung Health para um PostgreSQL sob controle do próprio usuário. O objetivo é formar um histórico durável e consultável por SQL, Grafana ou outras ferramentas externas, sem depender do Samsung Health como única interface de acesso.

> Este projeto é destinado exclusivamente ao acompanhamento pessoal de fitness e bem-estar. Ele não realiza diagnóstico, tratamento, recomendações clínicas ou alertas médicos.

## Estado do projeto

O projeto está em fase inicial. O monorepo Kotlin/Gradle já existe e é construível, com os três módulos previstos criados e sem comportamento de domínio implementado. A API de ingestão sobe contra um PostgreSQL real e executa suas migrations Flyway na inicialização, mas ainda não existe nenhuma migration versionada nem endpoint de ingestão.

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

As migrations Flyway rodam na inicialização. Se qualquer uma falhar, a API registra a causa em log e encerra com código diferente de zero, sem abrir a porta HTTP, para nunca atender sobre schema incompatível.

Para o ambiente local, copie `.env.example` para `.env` e ajuste os valores. O arquivo `.env` não é versionado.

O desenvolvimento da integração requer um aparelho Android físico com Samsung Health e o Samsung Health Data SDK em modo de desenvolvedor; emuladores não suportam a integração real.

### Integração contínua

O workflow `.gitea/workflows/build.yml` executa `./gradlew build` a cada push e a cada pull request, e falha quando qualquer módulo quebra.

Dados pessoais, credenciais da VPS, tokens, chaves de assinatura e fixtures derivadas de medições reais não devem ser adicionados ao repositório.
