# Samsung Health Data SDK: modo desenvolvedor, chave de release e códigos de erro

Data: 2026-10-17

## Pergunta

O app pessoal `br.etc.victor.myhealthbridge` usa o Samsung Health Data SDK v1.1.0 (`com.samsung.android.sdk.health.data`) em modo desenvolvedor. O build de debug (chave de debug) funcionava; depois de trocar para um APK assinado com uma nova chave de release estável, o app mostra uma mensagem de "modo desenvolvedor" e nenhuma checagem de disponibilidade passa num Galaxy S25 Ultra com Android 16. Hipóteses: (a) o reconhecimento depende de (nome do pacote, certificado de assinatura) e o novo certificado precisa ser registrado; (b) o modo desenvolvedor do Samsung Health simplesmente foi desligado no aparelho.

## Resumo e conclusão

- **O modo desenvolvedor aceita qualquer chave de assinatura.** A documentação diz que ele serve justamente para "temporarily bypass the signature check" e para testar "with a different app key". Portanto a hipótese (a) não se sustenta para leitura em modo desenvolvedor: não há registro de certificado para uso pessoal em modo desenvolvedor. Confiança: alta (documentação explícita).
- **Leitura vs. escrita:** em modo desenvolvedor, leitura exige apenas ligar "Developer Mode for Data Read"; escrita (insert/update/delete) exige um access code obtido via parceria, mesmo em modo desenvolvedor. Confiança: alta.
- **Persistência:** a documentação não diz nada sobre o modo desenvolvedor sobreviver a reinstalações do app ou atualizações do Samsung Health. Confiança: n/a (lacuna).
- **Hipótese mais provável (inferência):** (b) ou uma variante dela, ou seja, o toggle "Developer Mode for Data Read" está desligado, ou algo no lado do app mudou no build de release (por exemplo, permissões perdidas ao desinstalar o APK de debug, ou R8/minificação no build de release). O primeiro passo é capturar a exceção concreta (`HealthDataException` subclasse + `errorCode`) para discriminar os casos. Confiança: média (inferência a partir da documentação).
- **Códigos de erro:** a referência da API lista 21 constantes em `ErrorCode`. App sem assinatura registrada fora do modo desenvolvedor resulta em `AuthorizationException`; o código mais provável para "app não permitido" é `ERR_ACCESS_CONTROL = 2003`. **Não existe** código específico do tipo `ERR_DEVELOPER_MODE_*` ou `ERR_USER_NOT_AGREED` na referência. Confiança: alta para a lista; média para o mapeamento 2003 ↔ "não registrado" (a documentação de verificação não cita o número).

## 1. O modo desenvolvedor aceita qualquer certificado? Serve só para leitura?

**O que a documentação diz:**

- Partner apps são reconhecidos por pacote + assinatura: o Samsung Health libera o acesso "only if a running app's package name and signature are both matched" com o registro, e a assinatura registrada "should come from the app release key" ([Samsung Health's developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).
- Para testar com outra chave: "If a partner app developer would like to test the app with a different app key, activate Samsung Health's developer mode" ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).
- A página de verificação diz que o modo desenvolvedor permite "temporarily bypass the signature check for your application" e que, sem assinatura registrada, o SDK bloqueia "the connection for security reasons" com `AuthorizationException` ([App verification](https://developer.samsung.com/health/data/guide/app-verification.html)).
- Leitura: "For reading data ... turn Developer Mode for Data Read on" ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).
- Escrita: é preciso "Get the access code through requesting partnership" e informar "Client ID" (nome do pacote) e "Access Code" na tela do modo desenvolvedor ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)). O release note do 1.0.0 beta2 confirma: "the access code is required on the developer mode page" para insert/update/delete ([Release note](https://developer.samsung.com/health/data/release-note.html)).

**Inferência:** como o modo desenvolvedor contorna a checagem de assinatura, debug e release são tratados igualmente. Trocar a chave não deveria, por si só, quebrar a leitura enquanto o modo desenvolvedor (leitura) estiver ligado.

## 2. O modo desenvolvedor persiste entre reinstalações e atualizações?

**O que a documentação diz:** nada. Nem a página de [developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html) nem o [Release note](https://developer.samsung.com/health/data/release-note.html) descrevem persistência, expiração ou reset. A página de [developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html) também não descreve como desligá-lo.

**Histórico relacionado (documentado):** o release note do 1.0.0 registra que, "From version 6.30 of the Samsung Health app", o menu foi renomeado para "Developer Mode (Samsung Health Data SDK)" ([Release note](https://developer.samsung.com/health/data/release-note.html)), ou seja, atualizações do Samsung Health já mudaram o menu.

**Fórum (não verificado diretamente):** os resultados de busca mostram uma thread em que um usuário relata que o modo desenvolvedor parou de funcionar após atualizar o Samsung Health para 6.12.3 ([thread](https://forum.developer.samsung.com/t/samsung-privileged-health-sdk-not-working-without-developer-mode/27716)), mas isso é da era do SDK antigo (Samsung Health SDK for Android / Privileged SDK), não do Data SDK. Não consegui abrir as threads do fórum (a página carrega só via JavaScript), então não confirmo autoria nem conteúdo.

**Inferência:** a configuração do modo desenvolvedor fica no app Samsung Health (não no seu app), então reinstalar o seu app não deveria desligá-la. Já limpar dados do Samsung Health, reinstalá-lo ou uma atualização maior pode, plausivelmente, resetá-la. Por outro lado, desinstalar o APK de debug para instalar o de release (assinaturas diferentes exigem isso) apaga as permissões concedidas ao seu app, que precisam ser pedidas de novo.

## 3. Como ligar e desligar o modo desenvolvedor; versão exigida

**O que a documentação diz** ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)):

1. No Samsung Health, tocar no menu ⋮ (canto superior direito).
2. Ir em **Settings > About Samsung Health**.
3. Tocar rapidamente na linha da versão 10 vezes ou mais; aparece o botão **Developer mode (Samsung Health Data SDK)**.
4. Abrir, aceitar o **Notice** e ligar **Developer Mode for Data Read** (para leitura). Para escrita, informar Client ID e Access Code.

Avisos: o modo é "ONLY intended for testing or debugging your app" e "It is NOT for app users" ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).

Desligar: não documentado. **Inferência:** é o mesmo toggle "Developer Mode for Data Read" na mesma tela.

Versão exigida: "Samsung Health Data SDK requires Samsung Health version 6.30.2 or later" e Android 10 (API 29) ou superior, segundo o [Release note](https://developer.samsung.com/health/data/release-note.html) (SDK 1.1.0, 12/03/2026).

Atenção: a página antiga [dev mode](https://developer.samsung.com/health/android/data/guide/dev-mode.html) (Samsung Health SDK for Android, descontinuado) fala em "access key" a partir do Samsung Health 6.11 para ativar o modo desenvolvedor. Isso **não** se aplica ao Data SDK, cuja documentação só exige access code para escrita.

## 4. Registro de certificado e formulário de parceria

**O que a documentação diz:**

- Uso pessoal em modo desenvolvedor: não há registro. O app "works only with the developer mode turned on" se não for aprovado ([App creation process](https://developer.samsung.com/health/data/process.html)); durante o desenvolvimento é possível testar sem registrar a assinatura ([App verification](https://developer.samsung.com/health/data/guide/app-verification.html)).
- Parceria: "Please submit partner request before your app distribution"; após aprovação, "app package name and signature (SHA-256)" são registrados no sistema do Samsung Health, e o desenvolvedor recebe o access code para escrita ([App creation process](https://developer.samsung.com/health/data/process.html)). O time do Samsung Health registra também o escopo de tipos de dados permitidos ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).
- Formulário: [partner request](https://developer.samsung.com/SHealth/business-partner/m48wvqi1mt9w2w4c). Não abri o formulário (exige login); campos exatos não verificados além de pacote e SHA-256.
- Para distribuição, registrar o SHA-256 da **app signing key** junto com o pacote ([App verification](https://developer.samsung.com/health/data/guide/app-verification.html)).

**O que a parceria dá:** uso sem modo desenvolvedor (distribuição a usuários finais) e o access code para escrita.

**Fórum (não verificado):** snippets de busca indicam que a equipe do Samsung Health mudou a política de parceria e chegou a fechar candidaturas em algum momento ([thread 8281](https://forum.developer.samsung.com/t/samsung-health-access-code/8281), [thread 11820](https://forum.developer.samsung.com/t/how-do-i-get-data-without-developer-mode-i-dont-understand/11820)); provavelmente refere-se ao SDK antigo. Elegibilidade de pessoa física não é declarada na documentação.

## 5. Exceções e códigos de erro

**Hierarquia** ([pacote error](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.error/index.html)): `HealthDataException` (open, estende `RuntimeException`, com `errorCode: Int?` e `errorMessage: String`) e as subclasses:

- `AuthorizationException`: "the app is not authorized to perform the specified operation".
- `InvalidRequestException`: requisição com condições inválidas.
- `PlatformInternalException`: erro interno do Samsung Health que o app não resolve.
- `ResolvablePlatformException`: "the Samsung Health app is not ready to serve the specified operation"; tem `hasResolution: Boolean` e `fun resolve(activity: Activity)` ([ResolvablePlatformException](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.error/-resolvable-platform-exception/index.html)). Carrega `ERR_PLATFORM_NOT_INSTALLED`, `ERR_OLD_VERSION_PLATFORM`, `ERR_PLATFORM_DISABLED`, `ERR_PLATFORM_NOT_INITIALIZED`.

**`ErrorCode`** ([ErrorCode](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.error/-error-code/index.html)), todas as 21 constantes:

| Constante | Valor | Descrição (resumida) |
|---|---|---|
| `ERR_INVALID_UID` | 1000 | UID do dado solicitado inválido |
| `ERR_INVALID_INPUT` | 1001 | Entrada inválida |
| `ERR_INVALID_CALLER` | 1002 | UID do app chamador inválido |
| `ERR_NO_USER_PERMISSION` | 2000 | Usuário não concedeu permissão do dado |
| `ERR_UNSUPPORTED_OPERATION` | 2001 | Operação não suportada |
| `ERR_NO_OWNERSHIP_TO_WRITE` | 2002 | Alterar/apagar dado que o app não inseriu |
| `ERR_ACCESS_CONTROL` | 2003 | "The calling app is not allowed to use the feature." |
| `ERR_INVALID_PLATFORM_SIGNATURE` | 2004 | Assinatura do **Samsung Health** inválida |
| `ERR_CHILD_ACCOUNT_ACCESS` | 2005 | Conta Samsung infantil |
| `ERR_PLATFORM_NOT_INSTALLED` | 3000 | Samsung Health não instalado |
| `ERR_OLD_VERSION_PLATFORM` | 3001 | Samsung Health abaixo da versão exigida |
| `ERR_PLATFORM_DISABLED` | 3002 | Samsung Health instalado mas desativado |
| `ERR_PLATFORM_NOT_INITIALIZED` | 3003 | Usuário não concluiu a inicialização (ex.: termos) |
| `ERR_DB_ERROR` | 9000 | Erro de banco interno |
| `ERR_INVALID_ENCRYPTION_KEY` | 9001 | Problema na chave de criptografia |
| `ERR_OUT_OF_SPACE` | 9002 | Sem espaço |
| `ERR_INTERNAL_ERROR` | 9003 | Erro interno não recuperável |
| `ERR_CONNECTION_FAIL` | 9004 | Conexão continua falhando |
| `ERR_INTERRUPTED` | 9005 | Operação interrompida |
| `ERR_CONNECTION_TIMEOUT` | 9006 | Timeout de conexão |
| `ERR_PLATFORM_DISCONNECTED` | 9007 | Samsung Health desconectado |

**Não existem** na referência: `ERR_INVALID_INPUT_VALUE` (o nome correto é `ERR_INVALID_INPUT`), qualquer `ERR_DEVELOPER_MODE_*`, `ERR_USER_NOT_AGREED*` (o caso de termos não aceitos é `ERR_PLATFORM_NOT_INITIALIZED = 3003`).

**"Modo desenvolvedor desligado" vs. "app não registrado":**

- Documentado: app sem assinatura registrada (e sem modo desenvolvedor) recebe `AuthorizationException` porque o SDK bloqueia a conexão ([App verification](https://developer.samsung.com/health/data/guide/app-verification.html)).
- Inferência: o código provável é `ERR_ACCESS_CONTROL = 2003` ("not allowed to use the feature"). Snippets de busca de uma thread do fórum ([AuthorizationException: 2003](https://forum.developer.samsung.com/t/com-samsung-android-sdk-health-data-error-authorizationexception-2003/40438) e [SHA-256 partner registration](https://forum.developer.samsung.com/t/inquiry-regarding-sha-256-used-for-partner-registration/37036)) associam 2003 a pacote/assinatura que não batem com o registro, com a equipe do fórum encaminhando para o Developer Support; não consegui abrir as threads para confirmar.
- A documentação **não** distingue "modo desenvolvedor desligado" de "app não registrado": do ponto de vista do SDK, ambos parecem ser o mesmo caso (assinatura não aceita), logo `AuthorizationException`/2003. Já `ResolvablePlatformException` (3000–3003) indica problema no próprio Samsung Health (ausente, antigo, desativado, não inicializado), não no seu app.
- Snippet de busca de outra thread ([Beta 2 connection issue](https://forum.developer.samsung.com/t/samsung-health-data-sdk-beta-2-connection-issue/39373)) sugere `ERR_CONNECTION_FAIL = 9004` num cenário sem modo desenvolvedor; não verificado.

## Lacunas e incertezas

- Persistência/reset do modo desenvolvedor e o procedimento de desligar não são documentados.
- O número exato do erro para "modo desenvolvedor desligado" não é documentado; 2003 é inferência apoiada em snippets do fórum que não pude abrir.
- O fórum do Samsung Developers não pôde ser lido diretamente (renderização via JavaScript); tudo que vem dele aqui é de snippets de busca e deve ser tratado como não verificado.
- Campos exatos do formulário de parceria e elegibilidade de pessoa física não verificados.
- Sugestão prática (inferência): registrar a classe da exceção, o `errorCode` e o `errorMessage`; conferir no aparelho se "Developer Mode for Data Read" está ligado e se o Samsung Health é 6.30.2 ou superior; reconceder permissões após a troca de assinatura.

## Confrontação com o código do repositório

Verificado no próprio repositório e no AAR fixado (`samsung-health-data-api-1.1.0.aar`), inspecionado com `javap`:

- A mensagem vista na tela é texto do próprio aplicativo, não do SDK: `health_availability_not_recognized` em `health-permissions/src/main/res/values/strings.xml`, exibida para `Remediation.APPLICATION_NOT_RECOGNIZED`. `SamsungOutcomes.availabilityOf` (`android-app/src/main/kotlin/br/etc/victor/myhealthbridge/samsung/SamsungOutcomes.kt`) agrupa nesse único caso os códigos 1000, 1001, 1002, 2003 e 2004. O mapeamento é o mesmo no commit `07cfabd` do artefato observado.
- O `diagnosticCode` (`samsung_error_<código>`) é calculado, mas nenhuma tela, log ou incidente o expõe, e o `errorMessage` do SDK é descartado. Por isso o código exato da falha observada em 2026-08-13 é desconhecido.
- A checagem de assinatura feita dentro do SDK (classe ofuscada `a2`) confere a assinatura do pacote `com.sec.android.app.shealth`, ou seja, do Samsung Health, e lança `AuthorizationException` com a mensagem "Samsung Health signature is not matched". Ela não olha a assinatura do aplicativo chamador. O reconhecimento do chamador acontece dentro do Samsung Health, que devolve um `ErrorStatus` (código e mensagem) pelo binder.
- `ResolvablePlatformException.resolve` só age nos códigos 3000 e 3001 (abre a Galaxy Store ou a Play Store), 3002 (abre os detalhes do Samsung Health nas configurações do Android) e 3003 (abre o Samsung Health). Não há resolução oferecida pelo SDK para modo desenvolvedor desligado nem para aplicativo não registrado.
- O build de release não usa R8 (`isMinifyEnabled = false` em `android-app/build.gradle.kts`), então minificação não explica a diferença entre debug e release.
- O artefato `07cfabd` é anterior ao commit `c5a8513`, que acrescentou `kotlinx-parcelize-runtime` e Gson ao APK porque o SDK falhava com `ClassNotFoundException` na primeira leitura. Essa falha derrubaria a leitura de registros, mas é um `Error`, não um `HealthDataException`, então não produziria a mensagem de "não reconhecido". Mesmo assim, o teste no aparelho deve usar um artefato posterior a `c5a8513`.
- Debug e release usam o mesmo `applicationId` (`br.etc.victor.myhealthbridge`). A primeira instalação de release exigiu desinstalar a de debug, o que apaga as permissões concedidas no Samsung Health; isso causaria `ERR_NO_USER_PERMISSION` (2000) ou conjunto vazio de permissões, não a mensagem de "não reconhecido".

**Conclusão combinada (confiança média-alta):** pela documentação, o modo desenvolvedor de leitura dispensa a checagem de assinatura do chamador para qualquer chave, então a troca para a chave de release não exige registro de certificado no uso pessoal. A causa mais provável é o "Developer Mode for Data Read" desligado (ou resetado por atualização ou limpeza de dados do Samsung Health) no momento do teste. A confirmação depende do código de erro, que hoje o aplicativo não mostra.
