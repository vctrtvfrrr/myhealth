# Smoke test em aparelho físico

Este roteiro valida o que o CI não reproduz: a integração real com o Samsung Health em developer mode, num aparelho compatível, ponta a ponta até a API na VPS. Cada passo diz o que fazer e o que deve acontecer; um resultado diferente é um desvio, anotado na seção [Execuções](#execuções).

Nada lido durante o smoke test entra no repositório. O registro de uma execução guarda só estados da tela, códigos, contagens e datas da execução; nenhum valor medido, coordenada, Samsung UID, identificador de aparelho ou token.

## Pré-requisitos

- Aparelho Galaxy com Samsung Health 6.30.2 ou superior e **Developer Mode for Data Read** ligado (ver [Reconhecimento pelo Samsung Health](../README.md#reconhecimento-pelo-samsung-health)).
- Depuração USB ativa e o aparelho autorizado: `adb devices` lista o aparelho como `device`.
- O APK de release do artefato `myhealth-bridge-<sha>` do commit a validar, descompactado. Ele precisa ser assinado pela chave de release: `apksigner verify --print-certs android-app-release.apk` mostra o mesmo SHA-256 da instalação existente.
- A API no ar: `GET https://myhealth.victor.etc.br/ready` responde `200`.
- Um token de Ingestion Device, criado com `device create <label>` na VPS e guardado fora do repositório, ou já salvo no aplicativo.
- Acesso de leitura ao PostgreSQL pela conta somente leitura do `read_model` (ver [Modelo de leitura](../README.md#modelo-de-leitura)).

## Roteiro

### 1. Instalação

1. `adb install -r android-app-release.apk`.
2. `adb shell dumpsys package br.etc.victor.myhealthbridge` mostra `firstInstallTime` anterior e `lastUpdateTime` de agora.

**Esperado:** a instalação é uma atualização. Outbox, cursores, endpoint e permissões continuam como estavam.

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

**Esperado:**

- Passo 1: a categoria passa a **Concedida**.
- Passo 3: Exercício aparece como **Revogada**, com a orientação de conceder de novo; as demais categorias não mudam.
- Passo 4: Exercício termina como aguardando a permissão de leitura, enquanto Frequência cardíaca conclui normalmente. Uma notificação **Manutenção necessária** aparece com **Permissão de leitura revogada**.
- Passo 5: a notificação abre a aba **Diagnóstico**, com a categoria Exercício, o código, a primeira e a última ocorrência e a ação sugerida. Nenhum valor medido aparece.
- Passo 6: a categoria volta a **Concedida**, a sincronização conclui e o incidente sai da aba **Diagnóstico** e da área de notificações.

### 4. Carga inicial e leitura de registros reais

1. Na aba **Sincronização**, confira que o endereço da API aparece como **Entregando em** `https://myhealth.victor.etc.br`. Se não aparecer, informe o endereço e o token e toque em **Salvar**.
2. Se alguma categoria mostrar **Carga inicial não iniciada**, toque em **Iniciar carga inicial**. Senão, toque em **Reconciliar todo o histórico**.
3. Toque em **Sincronizar agora** e acompanhe a tela até a execução terminar.

**Esperado:**

- Durante a execução, as categorias mostram **Carga inicial em andamento** e o contador de **Observações lidas** sobe. A outbox sobe e volta a zero à medida que a API confirma.
- Ao fim, cada categoria concedida mostra **Carga inicial concluída**, **Última sincronização concluída**, e **Último sucesso** igual a **Última tentativa**.
- **Pendências de mapeamento** fica em 0. Um valor maior é um desvio: o registro foi recusado pela API, e a aba **Diagnóstico** mostra **Registro não mapeável**.
- Numa reconciliação, nada é duplicado na VPS (passo 6).

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

### 6. Envio à VPS e verificação pelas views SQL

Com a conta somente leitura, ou como administrador do PostgreSQL enquanto ela não existir, execute só consultas agregadas. Os valores retornados não são copiados para o repositório; o registro da execução anota apenas se cada consulta respondeu como esperado.

```sql
select count(*), max(period_start) from read_model.current_heart_rate;
select count(*), max(period_start) from read_model.current_exercise;
select count(distinct samsung_uid), count(*) from read_model.current_exercise_location;
```

1. Execute as consultas antes e depois do passo 4.
2. Registre uma frequência cardíaca nova no Samsung Health (medição manual no aparelho ou no relógio), toque em **Sincronizar agora** e repita a primeira consulta.

**Esperado:**

- As três consultas respondem. Os totais de frequência cardíaca e de exercício são maiores que zero, e `max(period_start)` é recente.
- Uma reconciliação não altera os totais, porque a API responde `already_present`.
- Depois do passo 2, o total de frequência cardíaca cresce e `max(period_start)` corresponde à medição nova.
- A contagem de rotas é maior que zero quando há um exercício ao ar livre com localização no período. Sem nenhum, zero também está correto.

## Execuções

Uma linha por execução completa. Um desvio que gerou issue a cita pelo nome.

| Data | Commit do APK | Aparelho | Samsung Health | Resultado | Desvios |
| --- | --- | --- | --- | --- | --- |
| 2026-10-17 | `7ad5850` | SM-S938B, Android 16 | 7.1.0.053 | Todos os passos passaram depois de provisionar o aparelho na VPS. | Ver abaixo. |

### 2026-10-17

- **Passo 4:** a sincronização estava parada desde 14/08. A API respondia `401` porque nenhum Ingestion Device tinha sido provisionado em produção, e o token salvo vinha de outra instância. O aplicativo mostrou só que a API "não está configurada ou não aceitou o token", sem incidente nem notificação. Corrigido ao criar o aparelho `galaxy-s25-ultra`; a ausência de incidente para token recusado segue em "Relatar token de aparelho recusado como Maintenance Incident" (#24).
- **Passo 6:** a conta somente leitura não existe em produção. As consultas rodaram como administrador do PostgreSQL. Segue em "Criar a conta somente leitura do read model em produção" (#25).
- **Passo 3:** o texto da notificação termina com ponto duplicado ("p.m..").
- **Passo 5:** o trabalho horário rodou sete minutos depois da janela, com a rede do aplicativo em background bloqueada até o sistema liberar o job.
- A especificação previa o token do aparelho no Android Keystore; ele fica no Room. Decidido em "Decidir o armazenamento do token do aparelho: Keystore ou Room" (#26): o Room basta, ver ADR `0020`.
