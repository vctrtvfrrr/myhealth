# Observabilidade do stack

Este diretório é o estado desejado completo dos dashboards e das regras de alerta do stack `myhealth` no Grafana da plataforma, conforme o ADR-0008 de `codelab/infra`.

Ele está **deliberadamente vazio**. Não existem `dashboards/*.json` nem `alerts/*.json`, e cada deploy publica uma geração vazia: a plataforma converge o stack para zero recursos próprios no Grafana. Isso é uma declaração, não uma pendência esquecida — a primeira versão da entrega decide que dashboards e regras específicas desta aplicação estão fora do escopo, e o ADR `0005` registra a decisão.

A primeira regra ou dashboard nasce quando existir uma pergunta operacional que a plataforma ainda não responde. Até lá, o stack é observado pelo que a plataforma já oferece a todo Application Stack: containers esperados, reinícios e logs.

Ao acrescentar um arquivo aqui, use JSON nativo do Grafana. Cada regra declara `severity` (`critical` ou `warning`) e `noDataState`, e consulta apenas `prometheus`, `loki` e `__expr__`; o restante é imposto pela plataforma. Remover um arquivo remove o recurso correspondente do Grafana no deploy seguinte.
