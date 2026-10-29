# MyHealth Bridge

MyHealth Bridge é um sistema pessoal para copiar os dados de saúde e atividade disponíveis no Samsung Health para um PostgreSQL sob controle do próprio usuário. O objetivo é formar um histórico durável e consultável por SQL, Grafana ou outras ferramentas externas, sem depender do Samsung Health como única interface de acesso.

> Este projeto é destinado exclusivamente ao acompanhamento pessoal de fitness e bem-estar. Ele não realiza diagnóstico, tratamento, recomendações clínicas ou alertas médicos.

## Estado do projeto

O projeto está em fase inicial. A API de ingestão já recebe lotes homogêneos e idempotentes de envelopes canônicos em `POST /ingestions`, autentica o aparelho por token, preserva as Observed Record Versions de forma imutável no PostgreSQL e devolve um resultado por posição enviada. As verticais de contrato implementadas são `heart_rate`, `exercise`, `sleep`, `blood_oxygen`, `skin_temperature`, `blood_glucose`, `blood_pressure`, `body_composition`, `body_temperature`, `nutrition`, `water_intake`, `floors_climbed`, `irregular_heart_rhythm`, `sleep_apnea` e `energy_score`, exercitadas com envelopes sintéticos.

Cada ingestão também projeta o Current Health Record e o Current Observed Snapshot, e o schema de leitura `read_model` oferece as views `current_heart_rate`, `current_exercise`, `current_exercise_location`, `current_sleep`, `current_sleep_session`, `current_sleep_stage`, `current_blood_oxygen`, `current_blood_oxygen_series`, `current_skin_temperature`, `current_skin_temperature_series`, `current_blood_glucose`, `current_blood_glucose_series`, `current_blood_pressure`, `current_body_composition`, `current_body_temperature`, `current_nutrition`, `current_water_intake`, `current_floors_climbed`, `current_irregular_heart_rhythm`, `current_sleep_apnea`, `current_energy_score`, `current_observed_snapshot`, `current_hourly_steps`, `current_hourly_activity_summary`, `user_profile` e `current_daily_goal` para consulta por uma conta somente leitura.

O aplicativo Android já conecta ao Samsung Health Data SDK somente para leitura: verifica a Samsung Health Availability, solicita permissões de leitura e exibe o Permission State de cada uma das 25 Health Categories catalogadas.

A primeira fatia vertical está fechada para frequência cardíaca: o aplicativo importa o histórico acessível de forma paginada e retomável, guarda cada página numa outbox Room antes de avançar o cursor, entrega lotes idempotentes à API e remove o payload só depois da confirmação. A sincronização é solicitada de hora em hora pelo WorkManager e também pode ser iniciada manualmente.

Os exercícios seguem o mesmo critério, e trazem o que só existe dentro do registro que os carrega: a rota, que o Samsung Health não expõe como leitura própria e cuja Health Category existe apenas para que a permissão dela seja pedida. Ela é preservada no envelope do exercício e projetada como linhas próprias. Ver ADR `0019`.

O sono segue o mesmo critério: as sessões de cada noite e os estágios de cada sessão, que não têm identidade própria, viajam no envelope do sono e são projetados como linhas próprias. Os pontos de oxigenação do sangue e de temperatura da pele que o Samsung Health associa a uma noite não entram no sono: têm UID próprio e chegam pelas próprias Health Categories. Ver ADR `0023`.

A oxigenação do sangue, a temperatura da pele e a glicose seguem o mesmo critério, e trazem a série `binning_data` de que cada medição foi agregada. Um ponto da série não tem identidade própria: viaja no envelope da medição e é projetado como linha própria, como os estágios do sono. As medições que o Samsung Health associa a uma noite chegam por aqui como quaisquer outras; a noite a que pertencem é o sono cujo período as contém.

A pressão arterial, a composição corporal e a temperatura corporal seguem o mesmo critério e são registros de campos simples, sem nada aninhado. Cada medida vai na unidade que o Samsung Health documenta para ela; o pulso da pressão arterial e o índice de massa corporal não têm unidade documentada, e nenhuma é atribuída a eles.

A nutrição, a água e os andares subidos seguem o mesmo critério da frequência cardíaca: são registros de campos simples, sem nada que viaje dentro deles. Uma refeição traz o tipo, o título, as calorias e cada nutriente registrado na unidade que o Samsung Health documenta; a água traz a quantidade em mL; os andares trazem a contagem sem unidade, porque o Samsung Health não documenta uma.

Os monitores do Galaxy Watch, notificações de ritmo cardíaco irregular e avaliações de apneia do sono, e o Energy Score diário seguem o mesmo critério. Os dois monitores dependem do Samsung Health Monitor no relógio, e sem ele o histórico deles fica simplesmente vazio, sem incidente. O Energy Score é o único Health Record que o Samsung Health só filtra por data local: a leitura cobre os dias locais que a janela toca, e o score, que não tem fim, é datado no início do seu dia.

Alterações feitas depois no Samsung Health se refletem no Current Health Record, e um Source Removal muda o estado corrente sem apagar Observed Record Version alguma. Além do cursor, cada execução lê o Source Change Feed da categoria, uma vez por dia relê os sete dias anteriores, e o Data Owner pode acionar uma reconciliação integral que relê todo o histórico acessível.

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

A superfície com que a linha 1.x se compromete são as 25 de 25 Health Capabilities do [catálogo de capacidades](docs/capability-catalog.md). O Supported Contract Range da 1.x continua de `1` a `3`, com `3` recomendada: declarar a 1.0 não muda o contrato. Ver ADR `0026`.

Não fazem parte da primeira versão: escrita no Samsung Health, captura de sensores em tempo real, dashboards, análise clínica, suporte a múltiplos usuários, API de consulta ou distribuição pela Play Store.

## Documentação

- [`CONTEXT.md`](CONTEXT.md) define o vocabulário canônico do domínio.
- [`docs/adr/`](docs/adr/) registra as decisões arquiteturais.
- [Issue #1](https://git.codelab.tec.br/vctrtvfrrr/myhealth/issues/1) contém a especificação completa e está marcada como pronta para implementação.

## Módulos

| Módulo | Descrição |
| --- | --- |
| `contract` | Contrato de transporte versionado compartilhado pelos dois lados. Não depende do módulo Android nem do módulo da API. |
| `ingestion-api` | API de ingestão Ktor. Depende de `contract`. |
| `health-permissions` | Catálogo de Health Categories, Permission States, histórico em Room e tela de permissões. Não depende de tipos do Samsung SDK. |
| `maintenance` | Maintenance Incidents, deduplicação por identidade, política de espera das falhas transitórias e tela de diagnóstico. Depende de `health-permissions`. |
| `health-sync` | Catálogo de capacidades, mappers, outbox, cursores por categoria, cliente de ingestão e tela de sincronização. Não depende de tipos do Samsung SDK. |
| `android-app` | Aplicativo Android (Jetpack Compose), o adaptador do Samsung Health Data SDK, o canal de notificação e o agendamento por WorkManager. Depende dos quatro anteriores. |

## Desenvolvimento

### Pré-requisitos

- JDK 21 ou superior para executar o Gradle. O código é compilado para Java 17 por meio de um toolchain, baixado automaticamente quando ausente.
- Android SDK instalado, com o caminho declarado em `local.properties` (`sdk.dir=...`) ou na variável `ANDROID_HOME`. O arquivo `local.properties` não é versionado.
- Docker, para os testes de integração da API e para o ambiente local em containers.
- Samsung Health Data SDK v1.1.0 instalado localmente, conforme a seção abaixo. Sem ele o módulo Android não compila.

### Comandos

| Comando | Efeito |
| --- | --- |
| `./gradlew build` | Compila e testa os cinco módulos, incluindo os testes de integração. |
| `./gradlew test` | Executa apenas os testes que não exigem Docker. |
| `./gradlew integrationTest` | Executa apenas os testes de integração, que sobem containers. |
| `./gradlew :ingestion-api:buildImage` | Constrói a imagem `myhealth-api:local` a partir da distribuição do Gradle; `-Pmyhealth.image=<nome:tag>` troca o nome. |
| `./gradlew devUp` | Reconstrói a imagem e sobe PostgreSQL e a API em containers para desenvolvimento local. |
| `./gradlew :ingestion-api:run` | Sobe a API de ingestão em `http://localhost:8039` (porta configurável por `PORT`), exigindo um PostgreSQL alcançável. |
| `./gradlew :android-app:assembleDebug` | Gera o APK de depuração em `android-app/build/outputs/apk/debug/`. |
| `./gradlew :android-app:installDebug` | Instala o APK no aparelho ou emulador conectado. |
| `./gradlew :android-app:assembleRelease` | Gera o APK de release assinado em `android-app/build/outputs/apk/release/`. Exige as variáveis de assinatura; sem `-Pmyhealth.version=X.Y.Z`, sai como versão de desenvolvimento. |
| `./gradlew :android-app:verifyReleaseSigning` | Confere se a chave de assinatura está configurada no ambiente. Roda antes de qualquer build de release. |
| `./gradlew :android-app:installSamsungHealthSdk -Psamsung.health.sdk=<caminho>` | Copia o AAR baixado para `android-app/libs/`. |
| `./gradlew :android-app:verifySamsungHealthSdk` | Confere presença e checksum do AAR. Roda antes de qualquer compilação do módulo Android. |

A imagem da API é construída somente pela tarefa `buildImage`, que garante a distribuição atualizada antes do `docker build`. O ambiente local sobe por `devUp`, que depende dela: `docker build` e `docker compose up` invocados diretamente produzem imagem com o jar defasado e não são caminhos suportados.

`devUp` é o caminho documentado para exploração E2E manual, porque seu volume nomeado preserva o estado entre execuções. Por isso mesmo ele não é fixture de CI: os testes de integração usam containers descartáveis por classe.

### Samsung Health Data SDK

O aplicativo fixa o Samsung Health Data SDK v1.1.0. O AAR **não é versionado**, porque não há confirmação explícita de que a licença permite redistribuição. Baixe o artefato na [página oficial](https://developer.samsung.com/health/data/overview.html) e instale-o com `./gradlew :android-app:installSamsungHealthSdk -Psamsung.health.sdk=<caminho do AAR>`, que o copia para `android-app/libs/`, caminho ignorado pelo Git.

Toda compilação do módulo Android confere presença e SHA-256 do artefato antes de compilar e falha com a instrução de download quando ele está ausente ou inesperado. Não existem stubs imitando classes da Samsung: sem o AAR, o build para. O runner do Gitea provisiona o mesmo artefato a partir de armazenamento privado, pelos secrets `SAMSUNG_HEALTH_SDK_URL` e `SAMSUNG_HEALTH_SDK_TOKEN`. Ver ADR `0010`.

O developer mode do Samsung Health é pré-requisito externo, habilitado nas configurações do próprio Samsung Health. O aplicativo detecta a falha correspondente e explica a necessidade, mas não oferece um controle falso para ativá-lo.

### Permissões do Samsung Health

A tela de permissões usa o SDK exclusivamente para leitura: ela obtém o conjunto de permissões concedidas e solicita permissões READ. Nenhuma operação de escrita é exposta e nenhum Health Record é lido nesta fatia.

A Samsung Health Availability é derivada do resultado de operações reais, nunca de um booleano de conexão. Responder à consulta é o que torna a plataforma disponível; falhas viram remediação necessária, indisponibilidade temporária ou incompatibilidade definitiva, e um erro desconhecido permanece temporário em vez de virar incompatibilidade.

O SDK informa apenas o conjunto atualmente concedido, então `denied` e `revoked` são inferências locais. O Room guarda, por Health Category, se houve solicitação observada, se houve concessão observada, se a última observação a mostrou concedida e quando essa observação ocorreu — nenhum conteúdo de saúde. Cada verificação é atômica: só depois de uma consulta bem-sucedida todos os estados são gravados em uma única transação. Se a consulta falhar, nada muda e a observação anterior continua visível como desatualizada. Antes da primeira verificação bem-sucedida existe um estado de consulta desconhecido, que não é um quinto Permission State. Ver ADR `0009`.

As 25 Health Categories catalogadas cobrem exatamente os data types legíveis do SDK fixado, verificado por teste. Atualizar o AAR não adiciona categoria alguma sozinho.

### Sincronização

A tela de sincronização é a segunda aba do aplicativo. Nela o Data Owner informa o endereço da API e o token deste Ingestion Device, inicia a carga inicial, pede uma sincronização manual e aciona a reconciliação integral; ela também mostra o tamanho da outbox, o progresso da carga inicial, o horário da última tentativa, o horário do último sucesso, o horário da última releitura sobreposta e o estado de cada Health Category do catálogo de capacidades.

O endereço e o token ficam no Room do aparelho e não são embutidos no APK, porque o token é criado por `device create <label>` e mostrado uma única vez. O campo do token começa vazio a cada abertura e salvar substitui o que estiver guardado; ele nunca é lido de volta para a tela. O token fica em claro, sem cifragem própria: ele divide o banco com a outbox, que já guarda dados de saúde em claro, e só autoriza entregas que `device revoke` ou `device rotate` cortam na hora. Ver ADR `0020`.

O catálogo de capacidades é a fonte de verdade da cobertura: ele tem uma entrada para cada uma das 25 Health Categories, marcada como implementada ou não implementada e classificada como Health Record, Observed Snapshot ou só permissão. Cada entrada declara permissão, operações de leitura, paginação, suporte a mudanças, dados associados, identidade, mapper e projeção; a versão legível dele, gerada e verificada por teste, está em [`docs/capability-catalog.md`](docs/capability-catalog.md). A tela de sincronização lista todas as entradas, mostrando as não implementadas como lacunas, e a consulta de permissões pergunta pela permissão de cada entrada. Só as entradas implementadas são sincronizadas, e um teste exige de cada uma delas um mapper, uma fixture sintética e uma view criada pelas migrations da API. Cobrir uma categoria é trocar a entrada não implementada por uma implementada — cobertura cresce por vertical, nunca por atualização do SDK. Se um build expuser uma categoria do SDK ausente do catálogo, ela não é sincronizada e cada execução a relata como Maintenance Incident `uncataloged_category`, até que um build a catalogue. Ver ADR `0010`.

A carga inicial é explícita: ela começa pelo botão da tela, para as categorias com permissão concedida, e fixa a sua janela ao começar. O cursor de cada categoria guarda o horário local em que a próxima leitura começa, e só avança sobre uma página já gravada na outbox — as duas escritas são uma transação só. Uma interrupção, um encerramento do aplicativo ou um reinício do aparelho retomam dali, relendo no máximo os registros do horário de fronteira, que a API responde como `already_present`. O page token do SDK serve apenas para percorrer as páginas seguintes da mesma execução. Ver ADRs `0014` e `0015`.

A outbox é a fila durável entre o Samsung Health e a API, e o seu limite vale para o aparelho inteiro, não por categoria. A leitura pausa quando ela atinge o limite configurado e retoma pelo page token depois da entrega — sem isso, um grupo de registros com o mesmo horário local maior que a outbox nunca seria atravessado. Uma pausa que não conseguiu enfileirar nada encerra a execução como `OUTBOX_FULL`, porque a outbox está cheia de outro record type e insistir só repetiria a leitura. Um item só sai depois de `accepted` ou `already_present`. Um item `rejected` permanece como pendência de mapeamento: ele fica guardado, sai de todos os lotes seguintes e não bloqueia o progresso dos demais registros.

A sincronização incremental é solicitada de hora em hora pelo WorkManager, em qualquer tipo de rede e sem promessa de horário exato. A execução manual usa exatamente o mesmo pipeline idempotente. Cada categoria registra por conta própria como a sua execução terminou, então uma falha de categoria não segura as outras.

O `observedAt` do envelope é o horário em que a origem alterou o registro pela última vez, nunca o relógio da importação: fosse o relógio, cada execução horária gravaria outra Observed Record Version de um registro que ninguém mudou. Ver ADR `0014`.

Depois de percorrer o histórico acessível, cada execução também lê o Source Change Feed da categoria. É por ele que uma alteração posterior chega ao Current Health Record — a leitura por intervalo é filtrada pelo horário do próprio registro e nunca volta a um registro já percorrido — e é a única leitura capaz de relatar um Source Removal. Um upsert passa pelo mesmo mapper da leitura comum, então o registro novo costuma ser entregue duas vezes por execução e a segunda termina em `already_present`. O removal não carrega conteúdo biométrico, é datado pelo horário da alteração na origem e tem Source Provenance desconhecida, porque a origem só diz que o registro se foi. Ver ADR `0016`.

A leitura de mudanças é retomada pelo intervalo que ela cobre, nunca pelos horários dentro dele: o feed não oferece ordenação para pedir, então o maior horário de uma página não limita o que uma página seguinte ainda guarda. O intervalo é fixado antes de qualquer leitura, fica no cursor para sobreviver ao processo e só é fechado quando tudo dentro dele está na outbox. Uma interrupção repete o intervalo inteiro, que é idempotente.

Nada disso depende do cursor estar certo. Uma vez por dia a execução puxa o cursor da categoria de volta sobre os sete dias anteriores e relê a janela por conta própria; o horário da última releitura fica no cursor e aparece na tela. O Data Owner também pode acionar uma reconciliação integral, que reinicia a leitura de todo o histórico acessível de cada categoria com permissão e não confia em cursor algum — é a recuperação depois de perda de estado local ou suspeita de inconsistência, e é exatamente a situação de uma reinstalação, que não duplica o Personal Health History porque a releitura produz as mesmas Observed Record Versions. Um cursor guardado que este build não consegue interpretar recebe o mesmo tratamento e nunca uma posição padrão: a execução grava a releitura completa daquela categoria e termina como `CURSOR_UNRECOVERABLE`, para que a perda de posição seja relatada em vez de virar lacuna silenciosa. Ver ADR `0017`.

**Remoção que ninguém observou:** um registro removido no Samsung Health enquanto o aplicativo não estava olhando — entre uma reinstalação e a caminhada seguinte, antes da atualização que passou a ler o feed, ou além do que o feed retém — não aparece em leitura alguma. Por isso cada página da leitura por intervalo declara à API um Record Inventory: o trecho de horário local que ela fechou e todas as Health Record Identities lidas ali. Depois que a outbox esvazia, o inventário segue num lote próprio; a API conclui o Source Removal de toda identidade que ela projeta como presente, cujo período começa dentro do trecho, que ela já conhecia antes da leitura começar e que o inventário omitiu. A remoção concluída é uma Observed Record Version sem conteúdo biométrico, datada como a versão que ela encerra, e nada é apagado; um inventário que cita identidade nunca entregue é respondido como `incomplete` e não conclui nada. Ver ADRs `0017` e `0022`.

Como cada releitura reentrega observações que a API já guarda, o contador da tela conta observações lidas e não Health Records distintos.

### Manutenção necessária

O canal de notificação Android "Manutenção necessária" avisa o Data Owner quando o sistema encontra algo que só uma alteração no código resolve: registro recusado pela API, valor de enumeração que nenhum mapper interpreta, contrato de ingestão incompatível, permissão de leitura revogada, cursor irrecuperável e Samsung Health não suportado. A notificação abre a terceira aba do aplicativo, a tela de diagnóstico, que mostra categoria, código, primeira ocorrência, última ocorrência, número de ocorrências e ação sugerida de cada incidente aberto.

Um incidente é identificado pelo que precisa ser corrigido, nunca pelo momento em que foi encontrado. Encontrar a mesma condição na execução seguinte atualiza a mesma notificação, com contagem e última ocorrência novas, em vez de acrescentar outra ao lado — a notificação é publicada sob a identidade do incidente como tag. A Health Category só entra na identidade quando corrigir uma categoria deixa as outras em paz; contrato incompatível e plataforma não suportada são relatados sem categoria, para que uma única correção continue sendo uma única notificação.

Um incidente é encerrado pela execução que observa a sua condição desaparecer: o Samsung Health responder encerra a plataforma não suportada, a permissão voltar encerra a revogação, e uma categoria que leu e entregou encerra a posição perdida e o contrato incompatível. Nada fica guardado como resolvido — a condição voltar é um incidente novo, datado pela sua própria primeira ocorrência. Registro recusado e enum desconhecido são a exceção e continuam abertos até o mapper mudar, porque a observação por trás deles continua na outbox, fora de todos os lotes.

Falha transitória — Samsung Health ou API de ingestão sem resposta — é registrada desde a primeira ocorrência, porque é isso que data o início da indisponibilidade, mas só vira notificação depois de 24 horas sem nenhuma sincronização bem-sucedida. Uma execução em que qualquer categoria concluiu encerra e esquece os incidentes transitórios, então a contagem recomeça a partir do último sucesso. Endpoint não configurado, outbox no limite e permissão nunca concedida não são incidentes: são o sistema funcionando como projetado, esperando pelo Data Owner ou por si mesmo.

Nenhum incidente carrega o que foi observado. O detalhe vem de vocabulários que o próprio código define — códigos de recusa, campo e constante de enumeração — e a auditoria de enumerações só lê campos que o mapper declarou como tal. Declarar o campo diz qual é lido, não o que a origem coloca nele, então o valor é conferido contra o formato de uma constante do SDK: fora dele, o incidente cita só o nome do campo. Diagnóstico, notificação e log não exibem valor medido, coordenada nem token. Ver ADR `0018`.

### Configuração da API

A API lê toda a configuração de banco do ambiente e encerra a inicialização, nomeando a variável ausente, quando falta alguma das obrigatórias. Valor em branco conta como ausente, porque é assim que uma variável não configurada chega pela entrega.

| Variável | Obrigatória | Efeito |
| --- | --- | --- |
| `DATABASE_HOST` | Sim | Host do PostgreSQL. |
| `DATABASE_PORT` | Não | Porta do PostgreSQL. O padrão é `5432`. |
| `DATABASE_NAME` | Sim | Nome da database. |
| `DATABASE_USER` | Sim | Papel de runtime, que precisa das permissões DDL exigidas pelas migrations. |
| `DATABASE_PASS` | Sim | Senha do papel de runtime. |
| `PORT` | Não | Porta HTTP da API. O padrão é `8039`. |

Os limites de ingestão também vêm do ambiente. Um valor fora da faixa aceita impede a inicialização, em vez de ser ajustado silenciosamente.

| Variável | Padrão | Faixa aceita | Efeito |
| --- | --- | --- | --- |
| `INGESTION_MAX_ITEMS` | `500` | 1 a 10000 | Itens por lote. |
| `INGESTION_MAX_BYTES` | `2097152` | 1024 a 67108864 | Bytes do corpo da requisição. |
| `INGESTION_TIMEOUT_SECONDS` | `30` | 1 a 600 | Tempo até a ingestão ser revertida e devolver 503. |
| `DATABASE_POOL_MAX_SIZE` | `5` | 1 a 50 | Conexões simultâneas no pool. |
| `DATABASE_POOL_ACQUIRE_TIMEOUT_MS` | `5000` | 250 a 60000 | Espera máxima por uma conexão do pool. |

As migrations Flyway rodam na inicialização. Se qualquer uma falhar, a API registra a causa em log e encerra com código diferente de zero, sem abrir a porta HTTP, para nunca atender sobre schema incompatível. O pool de conexões só é aberto depois das migrations e antes da porta HTTP.

### Endpoints

| Endpoint | Autenticação | Efeito |
| --- | --- | --- |
| `GET /health` | Não | Liveness. Não acessa o banco: responde `200` enquanto o processo estiver vivo. |
| `GET /ready` | Não | Readiness. Responde `200` com banco alcançável e migrations aplicadas; `503` caso contrário. |
| `GET /ingestion-contract` | Não | Publica o Supported Contract Range: `minimumVersion`, `maximumVersion` e `recommendedVersion`. Não acessa o banco. |
| `POST /ingestions` | `Bearer <token>` | Recebe um lote homogêneo de envelopes canônicos e devolve um resultado por posição enviada. |

Não existe endpoint de consulta, alteração ou exclusão de Health Records: a superfície HTTP é só ingestão e saúde. A única exclusão é a [purga integral](#purga-integral), feita fora da superfície HTTP.

### Compatibilidade do contrato

O lote declara a sua Ingestion Contract Version e a API declara o Supported Contract Range, de modo que aplicativo e servidor possam ser atualizados em momentos diferentes. Hoje a mínima é `1` e a recomendada e a atual valem `3`; a versão `2` acrescenta o Record Inventory, e um aplicativo na versão `1` continua aceito, sem inventário e sem concluir remoção; a versão `3` acrescenta o lote de Observed Snapshots e deixa o lote de Health Records como era. Ver ADRs `0007`, `0022` e `0025`.

Toda resposta de `POST /ingestions`, inclusive os problem documents, carrega `Ingestion-Contract-Minimum`, `Ingestion-Contract-Maximum` e `Ingestion-Contract-Recommended`, para que a divergência apareça antes de algo quebrar. `GET /ingestion-contract` responde os mesmos números sem autenticação e sem depender do banco, porque é justamente com o serviço degradado que o cliente precisa distinguir uma queda de uma incompatibilidade. Os dois limites são publicados: sem o superior, um cliente mais novo não consegue distinguir "a API não aceita a minha versão" de "aceita, mas recomenda outra". O aplicativo lê `Ingestion-Contract-Recommended` de cada resposta e, quando a versão que envia está abaixo dela, registra no logcat (tag `IngestionContract`) só as duas versões; é um aviso, não uma Contract Incompatibility, então a sincronização segue, a outbox não é tocada e nenhum Maintenance Incident é aberto.

Uma versão fora do range recebe `422` com código estável: `contract_version_too_old` quando o aplicativo precisa ser atualizado, `contract_version_too_new` quando o servidor é que está atrasado. Não é `426`: o RFC 9110 exige o header `Upgrade` nesse status e o define sobre o protocolo HTTP, não sobre a versão de um documento. Os dois códigos foram publicados quando o range ainda era unitário, para que a chegada da segunda versão não exigisse release coordenada dos dois lados. Um lote sem `contractVersion`, ou com valor não inteiro, continua sendo `invalid_batch`: é documento malformado, não cliente antigo.

Campo desconhecido no envelope é preservado, em qualquer nível de aninhamento, e entra no digest — logo gera outra Observed Record Version, a mesma regra que `sourcePayload` já seguia. Aceitar o item e descartar parte dele responderia `200`, deixaria o aplicativo limpar a outbox e perderia conteúdo observado. Preservar fora do digest não resolveria: duas observações que diferem só pelo campo desconhecido colidiriam na restrição de unicidade e a mais rica seria descartada assim mesmo. Ver ADR `0008`.

Campo desconhecido na **raiz do lote** é a exceção e continua ignorado: é enquadramento de transporte, não algo que a fonte observou, então não pertence a nenhuma observação. Um item que não carrega nada desconhecido é renderizado exatamente como antes, então nenhum digest armazenado muda.

O lote declara `contractVersion`, `recordType` e `items`; o tipo de registro aparece somente na raiz. A partir da versão `2` o lote pode trazer `inventory` (`interval` com `start` incluído e `end` excluído em horário local, `readAt` e `samsungUids`), caso em que `items` pode vir vazio, e a resposta traz `inventory` com `status` — `concluded`, `incomplete` ou `invalid` — e `removals`. Cada resultado traz `index` e `status` — `accepted`, `already_present` ou `rejected` —, e um item rejeitado traz códigos estáveis e ordenados. `accepted` e `already_present` significam igualmente que a observação está durável, o que permite ao aplicativo remover o item da outbox.

A partir da versão `3` o mesmo endpoint aceita um lote de Observed Snapshots, que nomeia `snapshotType` na raiz em vez de `recordType` (um lote com os dois é `invalid_batch`). Cada item traz `window` (a Snapshot Window, `start` incluído e `end` excluído em horário local), `observedAt` (o instante em que o aplicativo leu, com offset), `sourceScope` (`platform` ou `all_sources`), `mapperVersion` e `content`, ausente ou nulo para uma janela vazia. O digest de um snapshot exclui o `observedAt`, e a API só grava a observação que difere da imediatamente anterior em `observedAt`; igual a ela, responde `already_present`. Um valor que volta a um já visto (A, B, A) é gravado de novo. Ver ADR `0025`. Erros de lote usam RFC 9457 Problem Details com um `code` estável e não ecoam nada do que foi recebido.

Uma observação é a mesma Observed Record Version quando a representação canônica validada tem o mesmo digest SHA-256. Mudança em conteúdo, Source Provenance, offset original ou mapper version cria outra versão da mesma Health Record Identity. Versões observadas são imutáveis: `UPDATE` e `DELETE` são recusados pelo próprio PostgreSQL.

Os logs seguem uma allowlist. Eles podem conter apenas o `ingestionId`, a versão do contrato, tamanhos, duração, contagens por resultado e códigos seguros; payloads, valores biométricos, coordenadas, tokens, digests de token, Samsung UIDs, identificadores de Source Provenance e device labels nunca são registrados, nem através de mensagens de exceção.

### Aparelhos de ingestão

Somente aparelhos provisionados podem ingerir. O provisionamento é feito por subcomandos do mesmo artefato da API, que não abrem a porta HTTP:

| Comando | Efeito |
| --- | --- |
| `device create <label>` | Cria o aparelho e mostra o token uma única vez, prefixado por `token=`. |
| `device rotate <label>` | Gera outro token, invalida o anterior imediatamente e reativa um aparelho revogado. |
| `device revoke <label>` | Revoga o aparelho. |

Somente o SHA-256 do token é persistido, então um vazamento do banco não revela credencial utilizável. Token ausente, inválido ou revogado recebe o mesmo `401 invalid_device_token`. Contra o stack local:

```sh
docker compose -f compose.dev.yml run --rm api device create phone
```

Para o ambiente local, copie `.env.example` para `.env` e ajuste os valores. O arquivo `.env` não é versionado.

O desenvolvimento da integração requer um aparelho Android físico com Samsung Health e o Samsung Health Data SDK em modo de desenvolvedor; emuladores não suportam a integração real.

### Modelo de leitura

O Current Health Record é a linha que diz qual Observed Record Version é o estado corrente de uma Health Record. Ele guarda só esse ponteiro: todo campo que um leitor quer já existe na versão imutável apontada, e copiá-lo aqui criaria uma segunda representação capaz de divergir.

Cada ingestão projeta, dentro da própria transação, as identidades que observou. A projeção é recalculada a partir das versões preservadas, não comparada com o que acabou de chegar: uma observação que chega atrasada não vira corrente só por ter chegado por último. A versão corrente é a de maior `observed_at`, com `first_received_at` e o id como desempate determinístico. Ver ADR `0012`.

A reconstrução é o mesmo enunciado SQL sobre todas as identidades, exposto como subcomando do mesmo artefato:

| Comando | Efeito |
| --- | --- |
| `projection rebuild` | Descarta as projeções e as regenera a partir dos envelopes e observações preservados. Mostra a contagem de Current Health Records, prefixada por `projected=`, e a de Current Observed Snapshots, prefixada por `projected_snapshots=`. |

O schema `read_model` é a superfície de consulta. Hoje ele tem vinte e seis views:

| View | Conteúdo |
| --- | --- |
| `read_model.current_heart_rate` | Health Records de frequência cardíaca correntes: `record_type` e `samsung_uid` da identidade, `beats_per_minute` e `unit` normalizados, período e observação em UTC com o zone offset original, `source_app`, `source_device` e `mapper_version`. |
| `read_model.current_exercise` | Exercícios correntes: identidade, `exercise_type`, `duration_seconds`, `distance_meters` e `calories_kilocalories`, período e observação em UTC com o zone offset original, proveniência e `mapper_version`. Um total só aparece quando toda sessão do registro o reportou, porque a soma de parte de um exercício seria indistinguível do total dele. |
| `read_model.current_exercise_location` | A rota dos exercícios correntes, uma linha por ponto: `samsung_uid` do exercício a que pertence, `position` na ordem em que a fonte reportou, `at`, `latitude_degrees`, `longitude_degrees`, `altitude_meters` e `accuracy_meters`. Um exercício cuja rota o Samsung Health não revelou não contribui linha alguma. |
| `read_model.current_sleep` | Noites de sono correntes: identidade, `duration_seconds`, `sleep_score` (escala não documentada pelo Samsung Health), período e observação em UTC com o zone offset original, proveniência e `mapper_version`. |
| `read_model.current_sleep_session` | As sessões das noites correntes, uma linha por sessão: `samsung_uid` do sono a que pertence, `position` na ordem em que a fonte reportou, `start_at`, `end_at` e `duration_seconds`. |
| `read_model.current_observed_snapshot` | Todo Current Observed Snapshot, de qualquer tipo: `snapshot_type`, `window_start` e `window_end` em horário local, `observed_at` em UTC com o offset original, `partial` (a observação foi feita antes do fim da janela, em horário local), `empty`, `content` (nulo numa janela vazia), `source_scope` e `mapper_version`. Nada aqui é Health Record. |
| `read_model.current_hourly_steps` | Os passos de cada hora local em que o Samsung Health contou algum: `window_start`, `window_end`, `steps`, `partial`, observação e `source_scope`. Uma hora observada como vazia sai da view, então o total de um dia é uma soma simples sobre ela. |
| `read_model.current_hourly_activity_summary` | O resumo de atividade de cada hora local em que o Samsung Health mediu alguma, somando celular e wearables: `window_start`, `window_end`, `active_calories_burned_kilocalories`, `calories_burned_kilocalories`, `active_time_seconds`, `distance_meters`, `partial`, observação e `source_scope`. Um total que a fonte não reportou fica nulo, e uma hora observada como vazia sai da view, então o total de um dia é uma soma simples sobre ela. As unidades não são documentadas pelo Samsung Health; são as que ele usa nas demais categorias. |
| `read_model.user_profile` | O perfil do Data Owner e o histórico das suas mudanças, uma linha por observação guardada (só uma mudança é guardada): `observed_at` em UTC com o offset original, `current` (a observação de maior `observed_at`), `empty` (o Samsung Health não tinha perfil), `date_of_birth` como texto (formato não documentado pelo Samsung Health), `height_cm`, `weight_kg`, `gender` com a constante da fonte, `nickname`, `source_scope` e `mapper_version`. Um campo nunca preenchido é nulo. São dados pessoais. |
| `read_model.current_daily_goal` | O objetivo vigente em cada dia local, uma linha por objetivo e dia: `goal` (`steps_goal`, `active_calories_burned_goal`, `active_time_goal`, `nutrition_goal`, `water_intake_goal` ou `sleep_goal`), `day`, a coluna tipada do objetivo, na unidade que o conteúdo declara (`steps`, `active_calories_burned_kilocalories`, `active_time_seconds`, `calories_kilocalories` da meta de nutrição, `amount_milliliters`, ou `bed_time` e `wake_up_time` como hora local do dia), `changed` (o valor difere do dia observado antes), `partial`, observação e `source_scope`. O Samsung Health repete o objetivo em todo dia em que ele vale, então `where changed` mostra só os dias em que ele mudou; um dia observado sem objetivo sai da view. |
| `read_model.current_sleep_stage` | Os estágios das sessões correntes, uma linha por estágio: `samsung_uid` do sono, `session_position` da sessão a que pertence, `position` dentro dela, `start_at`, `end_at` e `stage` com a constante que a fonte reportou (`AWAKE`, `LIGHT`, `DEEP`, `REM` ou `UNDEFINED` no SDK fixado). Uma sessão sem estágios não contribui linha alguma. |
| `read_model.current_blood_oxygen` | Medições de oxigenação do sangue correntes: identidade, `oxygen_saturation_percent` e a faixa `min_oxygen_saturation_percent`/`max_oxygen_saturation_percent` em %, período e observação em UTC com o zone offset original, proveniência e `mapper_version`. |
| `read_model.current_blood_oxygen_series` | A série das medições correntes, uma linha por ponto: `samsung_uid` da medição, `position` na ordem em que a fonte reportou, `start_at`, `end_at`, `oxygen_saturation_percent`, `min_oxygen_saturation_percent` e `max_oxygen_saturation_percent`. |
| `read_model.current_skin_temperature` | Medições de temperatura da pele correntes, que o Samsung Health só faz durante o sono: identidade, `temperature_celsius` e a faixa `min_temperature_celsius`/`max_temperature_celsius` em °C, período, observação, proveniência e `mapper_version`. |
| `read_model.current_skin_temperature_series` | A série das medições correntes, uma linha por ponto: `samsung_uid`, `position`, `start_at`, `end_at`, `temperature_celsius`, `min_temperature_celsius` e `max_temperature_celsius`. |
| `read_model.current_blood_glucose` | Leituras de glicose correntes: identidade, `glucose_mmol_per_liter`, `measurement_type`, `sample_source_type` e `meal_status` com a constante que a fonte reportou (nulos quando ela reportou `UNDEFINED`), `meal_at`, `insulin_injected` (sem unidade, porque o Samsung Health não documenta uma), `medication_taken`, período, observação, proveniência e `mapper_version`. |
| `read_model.current_blood_glucose_series` | A série das leituras correntes, uma linha por ponto: `samsung_uid`, `position`, `at` e `glucose_mmol_per_liter`. |
| `read_model.current_blood_pressure` | Leituras de pressão arterial correntes: identidade, `systolic_mmhg`, `diastolic_mmhg` e `mean_mmhg` em mmHg, `pulse_rate` (sem unidade, porque o Samsung Health não documenta uma), `medication_taken`, período e observação em UTC com o zone offset original, proveniência e `mapper_version`. |
| `read_model.current_body_composition` | Medições de composição corporal correntes: identidade, `weight_kg` (o único campo obrigatório), `height_cm`, `body_fat_percent`, `skeletal_muscle_percent`, `muscle_mass_percent` (percentual, como o Samsung Health documenta), `basal_metabolic_rate_kcal_per_day`, `body_fat_mass_kg`, `fat_free_mass_kg`, `fat_free_percent`, `skeletal_muscle_mass_kg`, `total_body_water_liters` e `body_mass_index` (calculado pelo Samsung Health, sem unidade), período, observação, proveniência e `mapper_version`. Uma medida não reportada é nula. |
| `read_model.current_body_temperature` | Leituras de temperatura corporal correntes: identidade, `temperature_celsius` em °C, período, observação, proveniência e `mapper_version`. |
| `read_model.current_nutrition` | Refeições registradas correntes: identidade, `meal_type` com a constante que a fonte reportou (nulo quando ela reportou `UNDEFINED`), `title`, `calories_kcal` e cada nutriente na unidade que o Samsung Health documenta, indicada no nome da coluna (`total_fat_g`, `saturated_fat_g`, `polysaturated_fat_g`, `monosaturated_fat_g`, `trans_fat_g`, `carbohydrate_g`, `dietary_fiber_g`, `sugar_g`, `protein_g`, `cholesterol_mg`, `sodium_mg`, `potassium_mg`, `vitamin_a_ug`, `vitamin_c_mg`, `calcium_mg`, `iron_mg`), período, observação, proveniência e `mapper_version`. Um nutriente não registrado é nulo, nunca zero. |
| `read_model.current_water_intake` | Registros de água correntes: identidade, `amount_ml`, período, observação, proveniência e `mapper_version`. |
| `read_model.current_floors_climbed` | Andares subidos correntes: identidade, `floors` (sem unidade, porque o Samsung Health não documenta uma), período, observação, proveniência e `mapper_version`. |
| `read_model.current_irregular_heart_rhythm` | Notificações de ritmo cardíaco irregular correntes: identidade, `status` com a constante que a fonte reportou (nulo quando ela reportou `UNDEFINED`), `detected_at` em UTC com o zone offset original, observação, proveniência e `mapper_version`. |
| `read_model.current_sleep_apnea` | Avaliações de apneia do sono correntes: identidade, `result` com a constante que a fonte reportou (`DETECTED` ou `NOT_DETECTED`; nulo quando ela reportou `UNDEFINED`), período, observação, proveniência e `mapper_version`. |
| `read_model.current_energy_score` | O Energy Score corrente de cada dia: identidade, `local_date` (o dia local que ele pontua), `score` (sem unidade, porque o Samsung Health não documenta a escala), início do dia em UTC com o zone offset original, observação, proveniência e `mapper_version`. |

Uma Health Record removida na origem sai da view: a remoção é estado corrente, não medição corrente, e continua projetada e preservada. Um exercício removido leva a rota dele junto, um sono removido leva as sessões e os estágios dele, e uma medição removida leva a série dela, pela mesma razão. Uma medição sem série não contribui linha alguma para a view da série. `source_app` ou `source_device` nulo é o unknown explícito da Source Provenance, que é o que a fonte reportou.

Cada instante de um Health Record também aparece na Record Local Time, o relógio de parede do offset do próprio registro, numa coluna `timestamp` sem fuso no fim da view: `period_start_local`, `period_end_local` e, nas notificações de ritmo irregular, `detected_at_local`. As views filhas (sessões e estágios de sono, séries, leituras de glicose e pontos de rota) expõem `start_at_local`, `end_at_local` ou `at_local` pelo offset do início do registro a que pertencem. As views de Observed Snapshot não ganham coluna, porque a Snapshot Window já está em horário local. Nenhuma view agrega: quem consulta agrupa com `read_model.local_bucket(granularidade, hora_local)`, que devolve o início da hora, do dia, da semana (começando no domingo) ou do mês local, com `granularidade` igual a `hour`, `day`, `week` ou `month`. Um registro sem offset na fonte foi gravado como `+00:00` e cai no dia UTC. Ver ADR `0029`.

A conta somente leitura é criada por quem administra o PostgreSQL, porque o papel de runtime da API não tem esse privilégio. Depois disso, as permissões vêm de [`docs/sql/read-access.sql`](docs/sql/read-access.sql), executado como o papel de runtime — uma vez, e novamente a cada deploy que acrescente uma view ao `read_model`. Nada é concedido sobre as tabelas de ingestão: a view roda com os privilégios de quem a possui, então a conta alcança os envelopes preservados apenas pelo formato que a view expõe. Ver ADR `0013`.

#### Provisionar a conta de leitura na VPS CodeLab

O papel é `myhealth_read`, na database `myhealth` do container `postgres-postgres-1`. Como administrador do PostgreSQL, crie-o com `CREATE ROLE myhealth_read LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS` e defina uma senha aleatória exclusiva por `\password myhealth_read` no `psql`. Não reutilize a senha de runtime nem conceda associação a outro papel. Aplique o conteúdo de `docs/sql/read-access.sql` na database `myhealth`, como `myhealth` (ou usando `SET ROLE myhealth` na sessão administrativa).

Essa conta foi provisionada em produção em 19/10/2026. As sete consultas que o passo 6 do smoke test tinha então passaram com autenticação por senha, sem acesso às tabelas de ingestão, privilégios de escrita no `read_model` ou associação a outros papéis. Essa verificação de acesso não substitui uma execução completa do smoke test no aparelho.

A credencial fica no armazenamento privado de senhas do Data Owner, fora do repositório, do `.env` da API e dos secrets do CI. O arquivo `/home/codelab/.config/myhealth/read.pgpass`, usado no provisionamento, foi removido da VPS em 19/10/2026 depois que o Data Owner confirmou ter salvo a senha. O papel e sua senha continuam válidos no PostgreSQL.

O Grafana da plataforma também lê o `read_model` por essa conta, pela datasource `myhealth-read` que o `codelab/infra` provisiona (ADR `0027`). Quando ela existir, a senha passa a viver também numa chave do Ansible Vault do `codelab/infra`. Para rotacioná-la, altere a senha do papel, atualize o armazenamento privado e o vault e rode de novo o playbook do Grafana; até isso terminar, os dashboards e o `psql` pela conta de leitura falham juntos.

Para consultar pela conta de leitura, execute na VPS o comando abaixo e informe a senha no prompt do `psql`, sem colocá-la na linha de comando:

```sh
docker exec -it postgres-postgres-1 psql -X -W -h 127.0.0.1 -U myhealth_read -d myhealth
```

Execute nessa sessão as consultas dos passos 6 a 10 de [`docs/smoke-test.md`](docs/smoke-test.md): o passo 6 verifica que o papel não tem acesso às tabelas de ingestão, privilégios de escrita nas views nem associação a outros papéis. Registre apenas o resultado das verificações; não publique os valores de saúde ou a credencial. Depois de uma Full Purge, reaplique os grants: o papel e sua senha permanecem, mas o schema de leitura é recriado.

### Purga integral

O Data Owner mantém o controle final sobre a cópia remota por uma purga integral: ela apaga tudo o que a API guarda no PostgreSQL, inclusive o `read_model`, os Ingestion Devices e o histórico do Flyway, e a importação recomeça do zero. O procedimento completo, verificado contra banco descartável pelo teste de integração, está em [`docs/purge.md`](docs/purge.md). Ver ADR `0021`.

| Comando | Efeito |
| --- | --- |
| `database purge <database>` | Apaga o schema da API e o `read_model`. Exige o nome da database configurada como confirmação e mostra `purged`. Não aplica migrations: elas rodam na próxima partida da API. |

Depois da purga, a API reinicia, migra desde o banco vazio e volta a atender; o aparelho é provisionado de novo, o acesso de leitura é concedido de novo e uma reconciliação integral no aplicativo reimporta o histórico acessível sem duplicações.

**Exclusão seletiva permanente não é suportada.** Não há como apagar um Health Record, um período ou uma Health Category impedindo que eles sejam reimportados: a próxima sincronização, a releitura diária ou uma reconciliação os trariam de volta. A purga é integral ou não acontece.

### Integração contínua

O workflow `.gitea/workflows/ci.yml` roda em todo pull request e em todo push na `master`; um branch é validado pelo seu pull request, e não também pelo push. Num mesmo ref de branch, uma revisão nova cancela a execução anterior; a `master` nunca cancela, porque cada push ali calcula a sua versão a partir da release anterior. São quatro jobs:

| Job | Quando | O que faz |
| --- | --- | --- |
| `api` | Sempre | Testes do `contract` e da API, inclusive os de integração, e a imagem, construída direto com o nome do commit: as execuções dividem o mesmo Docker, e uma tag compartilhada entre elas fica com a imagem de quem construiu por último. |
| `android` | Sempre, em paralelo ao `api` | Testes unitários e lint do variant debug dos módulos Android e o APK de depuração; numa release, também o APK assinado. |
| `deploy` | Push na `master`, depois de `api` e `android` | Entrega a API. Espera a suíte inteira, inclusive a do aplicativo. |
| `release` | Push na `master` que gera release, depois do `deploy` | Publica a versão. |

Os testes rodam só sobre o variant debug, porque o release executaria os mesmos testes sobre o mesmo código. Os testes de integração rodam duas classes por vez: cada uma já tem o próprio PostgreSQL, processo da API, porta e diretório, e o limite em dois poupa o host, que é compartilhado com os stacks publicados. As dependências do Gradle e o Android SDK ficam em cache entre execuções.

### Versões e releases

Todo push na `master` que entrega uma mudança vira release, sem número editado à mão. A versão é calculada por `.gitea/scripts/next-version.sh` a partir dos Conventional Commits desde a última tag `vMAJOR.MINOR.PATCH`:

| Commits desde a última release | Versão seguinte |
| --- | --- |
| Algum breaking (`!` no tipo ou rodapé `BREAKING CHANGE:`) | Sobe o major, também na linha 0.x: de 0.6.0 vai para 1.0.0, com `versionCode` `1000000`. |
| Algum `feat` | Sobe o minor. |
| Algum `fix`, `perf`, `refactor` ou `revert` | Sobe o patch. |
| Só `docs`, `ci`, `test`, `chore`, `style` ou `build` | Nenhuma: build e deploy rodam, mas nada é lançado. |

A versão existe só na tag, criada pelo CI; tag empurrada à mão não lança nada. O Gradle a recebe por `-Pmyhealth.version=X.Y.Z`, e sem ela o build é de desenvolvimento: `versionName` `0.0.0-dev` e `versionCode` `1`, que nenhuma release instalada aceita como atualização. O `versionCode` é `major * 1.000.000 + minor * 1.000 + patch`, então cresce exatamente quando a versão cresce, e minor e patch precisam ficar abaixo de 1000. Ver ADRs `0024` e `0026`.

O `.gitea/scripts/next-version.test.sh` exercita o cálculo em repositórios descartáveis, com breaking em 0.x e em 1.x, `feat`, `fix` e commits que não entregam mudança, e roda no job `api`: `bash .gitea/scripts/next-version.test.sh`.

O job `release` cria a tag no commit lançado e a release no Gitea, com notas agrupadas em mudanças incompatíveis, funcionalidades, correções e outras mudanças, anexa o APK como `myhealth-bridge-X.Y.Z.apk` e publica a imagem também como `myhealth-api:X.Y.Z`, com os labels `org.opencontainers.image.version` e `org.opencontainers.image.revision`. Um deploy que falha não lança nada, e o push seguinte calcula a mesma versão e leva esses commits nas notas. Se dois pushes seguidos calcularem a mesma versão, o segundo falha em vez de mover a tag, e o push depois dele parte da tag nova. Reexecutar o job para o mesmo commit só completa o que faltou.

A release usa o `secrets.GITEA_TOKEN` automático da execução, com permissão de escrita em releases somente nesse job.

Dados pessoais, credenciais da VPS, tokens, chaves de assinatura e fixtures derivadas de medições reais não devem ser adicionados ao repositório.

### Entrega na VPS

A API é entregue como o Application Stack `myhealth` na VPS CodeLab, pelo contrato existente da action `codelab/deploy-stack`. Não existe mecanismo de entrega paralelo. Ver ADR `0005`.

O job `deploy` publica a imagem no registro do Gitea como `git.codelab.tec.br/vctrtvfrrr/myhealth-api`, com a tag do SHA do commit, e invoca a action. Uma release acrescenta a tag da versão, que é fixa como a do commit. Como build e run compartilham o mesmo daemon Docker da VPS, o deploy reaproveita a imagem local; a publicação no registro vale por durabilidade e rollback. O `compose.yml` na raiz do repositório é o do stack e consome `${IMAGE_TAG}`, exportado pela action.

Nenhuma tag móvel é publicada. Sem `IMAGE_TAG` o `compose.yml` cai em `latest`, que o CI nunca publica: comandos manuais na VPS — como o `docker compose down` do descomissionamento — continuam renderizando, mas um `up` sem a tag falha por imagem inexistente em vez de subir um commit qualquer.

O host público do stack é versionado no `compose.yml`, em `myhealth.victor.etc.br`, atrás do Cloudflare (`cf-only@file`, `tls=true`). Ele é o endereço da aplicação, não um endpoint da VPS.

O deploy é autocontido: subir o stack sobe a API, que aplica as migrations Flyway antes de abrir a porta HTTP e encerra se alguma falhar. Como a política é `restart: unless-stopped`, uma partida antes do PostgreSQL estar alcançável é apenas mais uma tentativa. A action devolve o controle assim que o Compose aceita o container, o que ainda não diz que as migrations passaram; por isso o job só termina depois que o healthcheck do container responde `healthy`.

O container roda com 512 MiB e `-XX:MaxRAMPercentage=75`, o que dá cerca de 370 MiB de heap. Sem limite, a JVM dimensionaria o heap pela memória inteira da VPS, que é compartilhada com o PostgreSQL, o Traefik e os demais stacks.

A configuração de runtime chega por variáveis `APPENV_*` declaradas no `env:` da etapa de deploy. A action remove o prefixo e escreve `/opt/codelab/apps/myhealth/.env`, que o container consome por `env_file`.

| Variável no workflow | Origem | Nome consumido | Efeito |
| --- | --- | --- | --- |
| `APPENV_DATABASE_HOST` | literal `postgres` | `DATABASE_HOST` | PostgreSQL compartilhado da plataforma, alcançado pela rede `postgres`. |
| `APPENV_DATABASE_PORT` | literal `5432` | `DATABASE_PORT` | Porta do PostgreSQL compartilhado. |
| `APPENV_DATABASE_NAME` | `vars.DATABASE_NAME`, default `myhealth` | `DATABASE_NAME` | Database provisionada pela plataforma, que nomeia role e database como o próprio app. |
| `APPENV_DATABASE_USER` | `vars.DATABASE_USER`, default `myhealth` | `DATABASE_USER` | Papel de runtime, com as permissões DDL das migrations. |
| `APPENV_DATABASE_PASS` | `secrets.DATABASE_PASS` | `DATABASE_PASS` | Senha do papel de runtime, nascida no vault da plataforma. |

Os limites de ingestão ficam nos padrões documentados em [Configuração da API](#configuração-da-api): declará-los só faz sentido quando um deles precisar mudar. O `secrets.REGISTRY_TOKEN` autentica no registro e não chega ao runtime.

### Distribuição do aplicativo

O aplicativo não é publicado na Play Store: o Data Owner instala e atualiza o APK manualmente. Cada release gera o APK assinado e o anexa à release do Gitea como `myhealth-bridge-X.Y.Z.apk`. Ver ADRs `0011` e `0024`.

A chave só é entregue ao build de uma revisão que já está na `master`. O build é o `build.gradle.kts` daquela revisão: um branch ou pull request que chegasse a esse passo poderia copiar a chave para onde quisesse, e ela é justamente a autoridade que decide o que o aparelho aceita como atualização. Por isso os demais eventos continuam validando o variant de depuração, sem chave alguma.

A assinatura é estável, e é ela que preserva a identidade do aplicativo: o Android recusa uma atualização assinada por outra chave, e a única saída seria desinstalar, o que levaria junto a outbox e o estado operacional local. O keystore **não é versionado** e chega ao build por variáveis de ambiente:

| Variável | Efeito |
| --- | --- |
| `MYHEALTH_RELEASE_KEYSTORE` | Caminho do keystore. |
| `MYHEALTH_RELEASE_KEYSTORE_PASSWORD` | Senha do keystore. |
| `MYHEALTH_RELEASE_KEY_ALIAS` | Alias da chave. |
| `MYHEALTH_RELEASE_KEY_PASSWORD` | Senha da chave. |

Faltando qualquer uma delas, ou com valor em branco, o build de release falha nomeando as ausentes, em vez de cair na chave de depuração ou produzir um APK sem assinatura: os dois instalariam hoje e recusariam a próxima atualização.

O keystore é gerado uma única vez e guardado fora do Git:

```sh
keytool -genkeypair -keystore myhealth-release.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias myhealth
base64 -w0 myhealth-release.jks
```

No runner, o arquivo vem do secret `RELEASE_KEYSTORE_BASE64` — a saída do `base64` acima —, é escrito fora do workspace com `umask 077` e removido por `trap`, inclusive quando o build falha; as credenciais vêm dos secrets `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`. Perder o keystore custa a identidade do aplicativo: não existe recuperação, e daí em diante toda instalação exigiria desinstalar a anterior.

Para instalar, baixe o APK da release desejada, na página de releases do repositório no Gitea. Com o aparelho conectado por USB e a depuração USB ativa:

```sh
adb install -r myhealth-bridge-X.Y.Z.apk
```

`-r` reinstala por cima da versão anterior preservando os dados. Sem cabo, copie o APK para o aparelho e abra-o pelo gerenciador de arquivos, autorizando a instalação de fontes desconhecidas.

O APK de depuração é assinado por outra chave, então a primeira instalação de release por cima de uma instalação de depuração é recusada e exige desinstalar antes. Isso vale uma única vez: entre releases a chave é a mesma.

A atualização preserva outbox e estado operacional local porque nem o `applicationId` nem a assinatura mudam. `versionCode` e `versionName` vêm da versão da release, calculada pelo CI, e nunca são editados à mão; o Android aceita reinstalar o mesmo `versionCode`, mas recusa um menor. Ver [Versões e releases](#versões-e-releases).

#### Reconhecimento pelo Samsung Health

O aplicativo lê o Samsung Health em developer mode, não como partner app aprovado. Nesse modo o Samsung Health deixa de conferir a assinatura do aplicativo chamador, então o APK de release é reconhecido do mesmo jeito que o de depuração e **nenhum certificado precisa ser registrado** na Samsung. O registro de pacote e SHA-256 só existe no programa de parceria, exigido para distribuir a terceiros sem developer mode ou para escrever dados, e nenhum dos dois está no escopo. Ver [`docs/research/samsung-health-release-key.md`](docs/research/samsung-health-release-key.md).

O developer mode é uma configuração do Samsung Health no aparelho, não do aplicativo. Ele exige Samsung Health 6.30.2 ou superior e é ligado assim:

1. No Samsung Health, abra o menu ⋮ e vá em **Configurações > Sobre o Samsung Health**.
2. Toque rapidamente na linha da versão dez vezes ou mais, até aparecer **Developer mode (Samsung Health Data SDK)**.
3. Abra essa opção, aceite o aviso e ligue **Developer Mode for Data Read**.

Desligar é o mesmo interruptor. Reinstalar este aplicativo não mexe nele, mas limpar os dados do Samsung Health, reinstalá-lo ou uma atualização dele pode desligá-lo; por isso, diante da mensagem de que o Samsung Health não reconhece o aplicativo, a primeira coisa a conferir é esse interruptor.

A primeira instalação de release por cima de uma de depuração exige desinstalar, e a desinstalação apaga as permissões concedidas no Samsung Health. Depois dela, solicite as permissões de novo pela tela de permissões.

Para conferir qual certificado assina um APK sem acesso ao keystore, use o `apksigner` das build-tools do Android SDK sobre o APK descompactado do artefato:

```sh
apksigner verify --print-certs android-app-release.apk
```

A linha `Signer #1 certificate SHA-256 digest` é a impressão digital. `keytool -printcert -jarfile` não serve aqui: com `minSdk` 29 o APK é assinado só pelos esquemas v2 e v3, que o `keytool` não lê.

Se a chave de assinatura mudar, o developer mode continua valendo e nada precisa ser registrado na Samsung. O que muda é o Android: a nova chave não atualiza a instalação existente, então é preciso desinstalar, instalar o APK novo, conferir que o developer mode segue ligado e solicitar as permissões outra vez. Só se o aplicativo um dia entrar no programa de parceria a impressão digital da chave passa a ser registrada, e aí trocar a chave exige registrar a nova antes de distribuir.

Na API, só `DATABASE_PORT` e `PORT` têm default; as demais são obrigatórias e valor em branco conta como ausente. O default de name e user vive por isso no workflow, na convenção da plataforma de nomear role e database como o próprio app: definir `vars.DATABASE_NAME` ou `vars.DATABASE_USER` substitui, deixar em branco herda `myhealth`. O único segredo de runtime é `secrets.DATABASE_PASS`, copiado do vault da plataforma, e é o único valor sem default — daí o job conferir que ele existe antes de publicar qualquer coisa.

Antes do primeiro deploy, a plataforma precisa provisionar a database no PostgreSQL compartilhado e o domínio — ver o runbook de Application Stacks em `codelab/infra`.

O diretório [`observability/`](observability/) publica deliberadamente uma geração vazia: dashboards e regras de alerta específicos desta aplicação estão fora do escopo da primeira versão.

`DeploymentContractTest` e `RenderedComposeTest` cobrem esses artefatos: o segundo renderiza o `compose.yml` pelo próprio Compose, a partir do `.env` que a action escreveria, e é por isso que roda em `integrationTest`.
