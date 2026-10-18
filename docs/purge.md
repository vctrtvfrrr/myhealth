# Purga integral

Este é o procedimento administrativo pelo qual o Data Owner exerce o controle final sobre a cópia remota: ele apaga integralmente o que o MyHealth Bridge guarda no PostgreSQL da VPS e recomeça a importação do zero. Ver ADR [`0021`](adr/0021-purge-the-whole-schema-rather-than-delete-selectively.md).

O roteiro inteiro é verificado contra um banco descartável por [`DatabasePurgeTest`](../ingestion-api/src/test/kotlin/br/etc/victor/myhealthbridge/api/DatabasePurgeTest.kt), que roda em `./gradlew integrationTest`: purga com a API parada, purga de novo sobre o banco vazio, reinício com migrations desde o banco vazio, novo aparelho, novas permissões de leitura pelo mesmo `docs/sql/read-access.sql` e a entrega do histórico duas vezes, como faz uma reconciliação integral, sem nenhuma Observed Record Version a mais do que antes da purga.

## O que é apagado

Tudo o que as migrations criaram, sem exceção:

- os Health Records, todas as Observed Record Versions e as Source Removals já observadas;
- o Current Health Record e o schema `read_model` com todas as views;
- as Ingestions e o resultado de cada posição;
- os Ingestion Devices, ou seja, todo token emitido deixa de valer;
- o histórico de migrations do Flyway, de modo que a próxima partida migra desde o banco vazio.

A conta somente leitura é um papel do PostgreSQL, não um objeto do schema, então ela sobrevive, mas perde as permissões que recebeu sobre o `read_model`.

Nada fica guardado fora do banco: não existe backup implícito, lixeira nem desfazer. Se a cópia atual precisa ser mantida, faça um `pg_dump` antes, sabendo que ele é um segundo lugar com dados de saúde e precisa do mesmo cuidado.

## O que a reimportação recupera e o que não recupera

A reconciliação integral relê todo o histórico **acessível hoje** no Samsung Health. Como o `observedAt` é o horário em que a origem alterou o registro pela última vez, a releitura produz as mesmas Observed Record Versions do estado atual de cada registro, e nada é duplicado. Ver ADRs `0014` e `0017`.

O que deixou de existir na origem não volta:

- versões anteriores de um registro editado no Samsung Health, porque a origem só devolve o estado atual;
- Source Removals, porque um registro removido não aparece em leitura alguma. Ele simplesmente não é reimportado;
- registros além do que o Samsung Health ainda disponibiliza ou de categorias cuja permissão foi retirada.

## Exclusão seletiva não é suportada

Não existe, nem por este procedimento nem por outro, exclusão permanente de um Health Record, de um período ou de uma Health Category com prevenção de reimportação. Apagar só uma parte não impediria a próxima sincronização, a releitura diária ou uma reconciliação de trazê-la de volta, e as Observed Record Versions são recusadas pelo próprio PostgreSQL a `UPDATE` e `DELETE`. A única exclusão oferecida é a purga integral, e ela existe justamente para a importação recomeçar.

## Procedimento

Os comandos abaixo rodam na VPS, onde o stack `myhealth` tem o container `myhealth-api` e a configuração de runtime em `/opt/codelab/apps/myhealth/.env`. Eles agem sobre a database configurada nesse arquivo, a de produção.

### 1. Parar a API

```sh
docker stop myhealth-api
```

Com a API parada, nenhuma ingestão disputa a purga. As entregas do aparelho falham até o novo token ser salvo no passo 6 e continuam na outbox, que é a sua função.

### 2. Purgar

O subcomando roda na mesma imagem da API parada, com a mesma configuração. Ele exige o nome da database como confirmação e recusa qualquer outro, para que um comando copiado de outro ambiente não esvazie este.

```sh
docker run --rm \
  --env-file /opt/codelab/apps/myhealth/.env \
  --network postgres \
  "$(docker inspect --format '{{.Config.Image}}' myhealth-api)" \
  database purge myhealth
```

**Esperado:** a saída termina em `purged`. O subcomando não aplica migrations, nem antes nem depois, então também purga um schema deixado em estado de falha. Repeti-lo sobre o banco já vazio não falha.

### 3. Reiniciar a API

```sh
docker start myhealth-api
docker inspect --format '{{.State.Health.Status}}' myhealth-api
```

A API aplica as migrations desde o banco vazio antes de abrir a porta, como em qualquer partida (ADR `0006`). Repita o `inspect` até ele responder `healthy`.

**Esperado:** `GET https://myhealth.victor.etc.br/ready` responde `200`.

### 4. Provisionar o aparelho de novo

```sh
docker exec myhealth-api /app/bin/ingestion-api device create phone
```

**Esperado:** a saída mostra o novo token, prefixado por `token=`, uma única vez. O token antigo recebe `401 invalid_device_token`.

### 5. Devolver o acesso de leitura

Execute novamente [`docs/sql/read-access.sql`](sql/read-access.sql) como o papel de runtime, como na primeira vez (ver [Modelo de leitura](../README.md#modelo-de-leitura)). O papel `myhealth_read` continua existindo e não precisa ser recriado.

### 6. Reconciliar no aplicativo

1. Na aba **Sincronização**, salve o novo token.
2. Toque em **Reconciliar todo o histórico**.

A reconciliação não depende dos cursores do aparelho: ela relê todo o histórico acessível de cada categoria com permissão. O que já estava na outbox é entregue como qualquer outro item.

**Esperado:** a execução termina sem Maintenance Incident. Pela conta somente leitura, as views do `read_model` voltam a mostrar o histórico. Uma segunda reconciliação não altera as contagens, porque a API responde `already_present`.
