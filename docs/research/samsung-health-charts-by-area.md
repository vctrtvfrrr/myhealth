# Samsung Health: gráficos e métricas por área

Data: 2026-10-20

## Pergunta

Quais métricas, granularidades e tipos de gráfico o aplicativo Samsung Health (versão atual, com Galaxy Watch) mostra em cada área: passos e atividade diária, exercício, sono, frequência cardíaca, sinais vitais (SpO2, temperatura da pele, pressão, glicose), composição corporal, nutrição e água, andares, Energy Score e monitores (ritmo irregular, apneia)? Para cada tela: métrica principal e secundárias, abas de período, tipo de gráfico, como a meta aparece e os valores-resumo do período. O resultado alimenta a divisão em áreas e os painéis de cada uma no mapa #69.

## Resumo e conclusão

- **As fontes primárias descrevem o que o app mede, não como ele desenha.** As páginas de suporte e de produto da Samsung listam métricas por área, mas quase nunca falam de tipo de gráfico, abas de período ou valores-resumo. A única tela com abas confirmadas é a de passos: Horas, Dias, Semanas e Meses ([ANS10001370][ans-steps]). Nenhuma página primária confirma uma aba Ano em tela de área. O Weekly Report é que compara por semana, mês e ano ([app US][app-us]). Confiança: alta quanto à ausência nas páginas lidas.
- **A camada de dados está bem confirmada.** A referência da API do Samsung Health Data SDK documenta campos, unidades e agregações dos 25 tipos, que batem um a um com as 25 Health Categories do [catálogo](../capability-catalog.md) ([DataTypes][sdk-datatypes]). O SDK agrupa séries por minuto, hora, dia, semana, mês e ano (`LocalTimeGroupUnit`, [referência][sdk-groupunit]). Isso cobre a variável "Agrupar por" já decidida no mapa.
- **Metas: só passos, tempo ativo, calorias ativas, sono, nutrição e água têm meta no SDK.** São seis Health Categories de meta (`steps_goal`, `active_time_goal`, `active_calories_burned_goal`, `sleep_goal`, `nutrition_goal`, `water_intake_goal`). Andares, peso e exercício não têm meta legível, embora o app ofereça meta de andares em relógios antigos e meta de peso ([ANS10001351][ans-trackers], [app US][app-us]).
- **Faixa mín./máx. é nativa nos sinais contínuos.** Frequência cardíaca, SpO2 e temperatura da pele trazem valor, mínimo e máximo por registro e por bloco da série (`binning_data`). Isso sustenta um painel de faixa por período sem inventar estatística ([SDK HR][sdk-hr], [SDK SpO2][sdk-spo2], [SDK pele][sdk-skin]).
- **O sono tem dados para hipnograma e para pontuação.** `SLEEP_SCORE` por registro, sessões com início, fim e duração, e estágios `AWAKE`, `LIGHT`, `DEEP`, `REM` ([SDK sono][sdk-sleep], [estágios][sdk-stage]). O app mostra pontuação em quatro níveis (Excellent, Good, Fair, Needs attention), tempo em cada estágio, SpO2, ronco, temperatura da pele e consistência com a meta ([sono UK][sleep-uk]). A Samsung nomeia os fatores da pontuação, mas não publica os pesos, e o SDK não os entrega.
- **Energy Score é um número por dia, com visão diária e semanal.** O app mostra o score do dia, o semanal, os fatores e dicas ([ANS10003272][ans-ai]). O SDK entrega só `ENERGY_SCORE` (Float), sem os fatores ([SDK Energy][sdk-energy]).
- **Os monitores são eventos, não séries.** Ritmo irregular traz só o instante e `DETECTED` ([SDK IHRN][sdk-ihrn]). Apneia traz o instante e `DETECTED` ou `NOT_DETECTED` ([SDK apneia][sdk-apnea]), depois de duas noites de mais de 4 h em 10 dias ([Newsroom FDA][news-apnea]). Ambos são revistos no Samsung Health Monitor, não no Samsung Health.
- **Várias métricas exibidas pelo app não têm Health Category.** VFC (HRV), frequência respiratória, estresse, ronco, Daily Cardio Load, Fitness Index, Heart Health Score, Vascular Load, Antioxidant Index, AGEs, ECG e métricas avançadas de corrida não estão no SDK. Elas ficam fora dos dashboards.

## Como ler este documento

Cada área tem três partes. **O que o app mostra** cita páginas da Samsung (suporte, produto, Newsroom). **Dados disponíveis** cita a referência do Samsung Health Data SDK, que é o que a Personal Health History guarda. **Implicação para os painéis** é inferência minha, para alimentar os tickets de área; não é fato documentado. **Não confirmado** lista o que a pergunta pede e nenhuma fonte primária confirma.

As páginas de suporte da Samsung são de várias regiões e épocas (Watch5 a Watch8). Menus e telas podem ter mudado na versão atual. Fóruns, Reddit e sites de notícias não foram usados como fonte; quando aparecem, estão marcados como não confirmados.

## Visão geral: área → Health Categories

| Área | Health Categories | Métrica principal no app | Meta no SDK |
|---|---|---|---|
| Passos e atividade diária | `steps`, `activity_summary`, `steps_goal`, `active_time_goal`, `active_calories_burned_goal` | passos; tempo ativo; calorias de atividade | sim (três) |
| Exercício | `exercise`, `exercise_location` | lista de treinos; duração, distância, calorias | não |
| Andares | `floors_climbed` | andares por dia | não |
| Sono | `sleep`, `sleep_goal`, `blood_oxygen`, `skin_temperature` | tempo de sono e sleep score | sim (horários de deitar e acordar) |
| Energy Score | `energy_score` | score diário | não |
| Frequência cardíaca | `heart_rate` | bpm ao longo do dia | não |
| Sinais vitais | `blood_oxygen`, `skin_temperature`, `body_temperature`, `blood_pressure`, `blood_glucose` | valor por medição | não |
| Composição corporal | `body_composition`, `user_profile` | peso e composição | não |
| Nutrição e água | `nutrition`, `nutrition_goal`, `water_intake`, `water_intake_goal` | kcal ingeridas; mL de água | sim (kcal; água) |
| Monitores | `irregular_heart_rhythm`, `sleep_apnea` | eventos detectados | não |

`user_profile` não é uma área: ele dá altura e peso de perfil e não tem tela própria de métrica.

## 1. Passos e atividade diária

**O que o app mostra**

- **Tela Passos (celular):** gráfico de histórico que se percorre por dia. O ícone de gráfico troca o intervalo entre Horas, Dias, Semanas e Meses. Em Mais opções: Set target (meta diária), Share e a escolha da fonte dos passos (todos os dispositivos, ou só celular ou relógio) ([ANS10001370][ans-steps]).
- **Daily activity:** no relógio, passos, tempo ativo e calorias. No celular, tempos de caminhada e corrida, calorias e distância. A meta definida aparece na aba Início para mostrar se foi batida no dia ([ANS10001351][ans-trackers]).
- **Três metas:** passos, tempo ativo e calorias de atividade, cada uma com um gráfico que "enche" até a meta (página do Galaxy Fit3, [Fit3][fit3-activity]).
- **No relógio:** calorias por dia ou semana, incluindo o metabolismo basal do perfil; passos e distância com gráfico semanal e registro de cada dia ([recursos do Watch][ph-watch]).
- **Distância:** estimada a partir de passos e comprimento da passada. O Weekly Report traz os passos diários e compara por semana, mês e ano ([app US][app-us]).

**Dados disponíveis (SDK)**

- `steps`: só agregação, `TOTAL` (Long), agrupável por hora local. Samsung Health combina celular e relógio sem contar em dobro ([SDK passos][sdk-steps]).
- `activity_summary`: só agregação, por hora local: `TOTAL_ACTIVE_TIME` (Duration), `TOTAL_ACTIVE_CALORIES_BURNED` (kcal), `TOTAL_CALORIES_BURNED` (kcal, inclui basal), `TOTAL_DISTANCE` (m, distância em atividade) ([SDK activity][sdk-activity]).
- `steps_goal`, `active_time_goal`, `active_calories_burned_goal`: só `LAST`, a última meta do dia ([SDK meta de passos][sdk-steps-goal]). As três correspondem às três metas do Daily activity.

**Implicação para os painéis (inferência):** barras de passos, tempo ativo e calorias ativas por "Agrupar por", com a meta diária como linha de referência quando o agrupamento é dia. A hora já é a Snapshot Window desses dados, então hora, dia, semana e mês saem de somas.

**Não confirmado:** se os anéis do Daily activity existem na versão atual do app no celular; se o gráfico de passos é de barras e se a meta aparece como linha; aba Ano; valores-resumo do período (total, média diária, melhor dia); meta padrão de 10.000 passos.

## 2. Exercício

**O que o app mostra**

- **Histórico:** lista de treinos recentes; tocar abre o resumo. Corridas podem trazer perda de suor e VO2 máx., cada uma com condições mínimas de velocidade e distância ([ANS10006858][ans-run]).
- **Resumo de qualquer treino:** duração, calorias e distância; treinos com GPS registram velocidade e rota. Dá para definir meta de calorias antes do treino ([monitorar exercícios][latin-exercise]).
- **Detalhes:** seção "Map and chart" com elevação mínima e máxima; lista "Workouts this week" no celular ([ANS10004899][ans-workout]).
- **Zonas de FC:** personalizadas a partir da capacidade cardiorrespiratória, com guia de zona durante o treino ([ANS10003698][ans-hrzone]).
- **Corrida avançada (Watch6):** assimetria, tempo de contato e tempo de voo ([Watch6][ae-watch6]).
- **Outros:** Daily Cardio Load (zona 3 ou mais), Running Coach, natação com braçadas, parciais, ritmo e velocidade, FTP no ciclismo, Trail Run com GPX e Fitness Index com VO2 máx. ([app US][app-us], [Running Coach Watch8][latin-coach]).

**Dados disponíveis (SDK)**

- `exercise`: tipo predefinido (`PredefinedExerciseType`, cerca de 110 valores), título opcional e `SESSIONS`; agregações `TOTAL_CALORIES` e `TOTAL_DURATION` ([SDK exercício][sdk-exercise]).
- `ExerciseSession`: duração, calorias (kcal), distância (m), ganho e perda de altitude e altitudes mín./máx. (m), FC média, mín. e máx. (bpm), velocidade média e máx. (m/s), cadência média e máx., RPM e potência (W), taxa de queima calórica, contagem (passadas, braçadas, repetições), `vo2Max`, `log`, `route` e `swimmingLog` ([sessão][sdk-session]).
- `ExerciseLog`: série com instante, FC, cadência, contagem, potência e velocidade, que é o que sustenta os gráficos dentro de um treino ([log][sdk-exlog]).
- `exercise_location`: rota como latitude, longitude, altitude e precisão por instante ([localização][sdk-exloc]).

**Implicação para os painéis (inferência):** tabela de treinos do período; para um treino escolhido, Geomap da rota e séries de FC, velocidade e cadência do `log`; totais do período por tipo de exercício (duração, distância, calorias). Zonas de FC não estão no SDK; calculá-las seria decisão nossa, não métrica do app.

**Não confirmado:** gráfico de FC por treino e tempo em cada zona como o app desenha; número de zonas; parciais por km em corrida; resumo semanal ou mensal de exercício; métricas avançadas de corrida além das três do Watch6 (e nenhuma delas está no SDK).

## 3. Andares

**O que o app mostra:** o relógio conta andares (cerca de 3 m cada) pelo barômetro e mostra gráfico semanal com o registro de cada dia ([recursos do Watch][ph-watch]). A página de trackers lista meta de andares só para relógios Tizen e Gear antigos ([ANS10001351][ans-trackers]).

**Dados disponíveis (SDK):** `floors_climbed` com `FLOOR` (Float, contagem) por intervalo e agregação `TOTAL`. Não existe tipo de meta de andares ([SDK andares][sdk-floors]).

**Implicação para os painéis (inferência):** barras de andares por "Agrupar por", sem linha de meta.

**Não confirmado:** tela de andares no app atual do celular; meta de andares em relógios Wear OS.

## 4. Sono

**O que o app mostra**

- **Relatório da manhã:** tempo de sono, sleep score em quatro níveis (Excellent, Good, Fair, Needs attention), gráfico e lista dos estágios com o tempo em cada um, SpO2 durante o sono, tempo de ronco, temperatura da pele da noite anterior, consistência do sono (dias da semana em que bateu as metas de deitar e acordar) e o "animal" do sleep coaching ([sono UK][sleep-uk], [ANS10003657][ans-sleep]).
- **Pontuação:** baseada na duração de cada fase e nos movimentos durante o sono, com comparação por idade e sexo ([sono AU][sleep-au]). A Newsroom do Reino Unido cita como fatores o tempo total de sono, o tempo acordado, o ciclo do sono e a recuperação física e mental ([Newsroom UK][news-uk-sleep]), sem pesos nem fórmula. As páginas do Watch9 mostram o score como número com rótulo (por exemplo, 92 "Excellent") ([Watch9 IN][watch9-in]).
- **Medições avançadas:** SpO2, ronco e temperatura da pele durante o sono são ligados em Sleep > Advanced measurement ([ANS10003276][ans-apnea]).
- **Meta:** horário de deitar e de acordar ajustados num controle deslizante ([ANS10003272][ans-ai]). Bedtime Guidance sugere uma janela de horário a partir de cerca de três dias de dados ([Watch8 US][watch8-us]).
- **Outros:** Sleep Insight, Sleep Coaching depois de cerca de uma semana, e vitais durante o sono (FC, VFC, SpO2, frequência respiratória, temperatura da pele) ([app US][app-us]).

**Dados disponíveis (SDK)**

- `sleep`: `DURATION`, `SLEEP_SCORE` (Int) e `SESSIONS`; agregação `TOTAL_DURATION` por data local ([SDK sono][sdk-sleep]).
- Sessão: início (deitar), fim (acordar), duração e estágios ([sessão de sono][sdk-sleep-session]). Estágios: `UNDEFINED`, `AWAKE`, `LIGHT`, `DEEP`, `REM` ([estágios][sdk-stage]).
- `sleep_goal`: `LAST_BED_TIME` e `LAST_WAKE_UP_TIME` (LocalTime) ([SDK meta de sono][sdk-sleep-goal]).
- `blood_oxygen` e `skin_temperature` podem ser lidos como dados associados ao sono (seções 6.1 e 6.2).

**Implicação para os painéis (inferência):** hipnograma por noite (estágios no tempo), barras de duração do sono por noite com a duração-alvo derivada da meta de horários, sleep score por noite e tempo em cada estágio por período. Consistência com a meta pode ser calculada comparando início e fim da sessão com `sleep_goal`.

**Não confirmado:** abas de período e gráficos de semana e mês da tela de sono (só fórum fala em dias, semanas e meses); pesos dos fatores do sleep score e se o app mostra cada fator separadamente; escala numérica do score (1 a 100 só aparece num resumo de busca); eficiência do sono; ronco (não está no SDK); faixas numéricas dos quatro níveis de pontuação.

## 5. Energy Score

**O que o app mostra:** score diário e semanal, os fatores de cada score, dicas e a explicação do cálculo; dá para trocar de data ([ANS10003272][ans-ai]). Exige dados do dia anterior (atividade e sono) e FC durante o sono. A Samsung descreve os fatores como duração e consistência do sono, regularidade e horário de deitar e acordar, atividade do dia anterior, FC e VFC durante o sono ([Watch8 US][watch8-us], [Newsroom Galaxy AI][news-galaxy-ai]). O número vem com um rótulo verbal (o exemplo é 92, "Excellent") ([Watch8 US][watch8-us]). O score chega na manhã seguinte, na tela inicial, com dicas ([Newsroom NZ][news-nz-watch]), e entra no Weekly Report ([app US][app-us]). Com o Galaxy Ring, a Samsung cita sono profundo, nível de atividade e VFC como fatores ([Energy Score no Ring][ae-ring-energy]).

**Dados disponíveis (SDK):** `energy_score` com um registro por dia (início do dia e `ENERGY_SCORE`, Float), lido por intervalo de datas, sem agregação e sem os fatores; unidade e escala não informadas ([SDK Energy][sdk-energy], [tipo Energy Score][sdk-energy-type]).

**Implicação para os painéis (inferência):** linha ou barras do score por dia, com média do período. Os fatores não estão no SDK; parte deles pode ser mostrada ao lado (duração do sono, FC durante o sono, atividade do dia anterior), mas não como "fatores do Energy Score".

**Não confirmado:** escala 0 a 100 e faixas dos rótulos verbais; pesos dos fatores; gráfico da visão semanal.

## 6. Frequência cardíaca

**O que o app mostra:** a tela Heart rate tem um gráfico das medições que se percorre por data; escolher uma data mostra as leituras do dia, cada uma marcada com a atividade (geral, repouso). O relógio mostra tendência semanal. Alertas de FC alta e baixa por 10 minutos em repouso, com limites ajustáveis ([ANS10003311][ans-hr]). A medição pode ser contínua, a cada 10 minutos parado ou manual ([medição da FC][latin-hr]).

**Dados disponíveis (SDK):** `heart_rate` com `HEART_RATE`, `MIN_HEART_RATE`, `MAX_HEART_RATE` (bpm) e série (`binning_data`) com valor, mín. e máx. por bloco; agregações só MIN e MAX ([SDK HR][sdk-hr], [entrada HeartRate][sdk-hr-entry]).

**Implicação para os painéis (inferência):** faixa mín./máx. por "Agrupar por" com a média sobreposta. A FC de repouso não é campo do SDK.

**Não confirmado:** FC de repouso como métrica própria e sua tendência (só fórum); abas de período; resumo do período; limites padrão dos alertas; VFC (o app cita, o SDK não tem).

## 7. Sinais vitais

### 7.1 SpO2

**O que o app mostra:** percentual de SpO2 durante o sono no relatório de sono ([ANS10003657][ans-sleep]), também listado em Vitals ([app US][app-us]).

**Dados disponíveis (SDK):** `blood_oxygen` com `OXYGEN_SATURATION`, mín. e máx. (%) e série com valor, mín. e máx. por bloco; coletado durante o sono quando a medição avançada está ligada; associável ao sono ([SDK SpO2][sdk-spo2]).

**Não confirmado:** gráfico, média e mínimo na interface, abas.

### 7.2 Temperatura da pele e temperatura corporal

**O que o app mostra:** temperatura da pele da noite anterior no relatório de sono ([sono UK][sleep-uk]); um valor representativo por dia, no celular e no relógio ([temperatura da pele UK][uk-skin]); usada em Vitals e no ciclo menstrual ([app US][app-us]).

**Dados disponíveis (SDK):** `skin_temperature` com valor, mín. e máx. (°C) e série por bloco, medida durante o sono ([SDK pele][sdk-skin]). `body_temperature` é medição pontual em °C ([SDK corporal][sdk-bodytemp]).

**Não confirmado:** exibição como variação em relação a uma linha de base; tela de temperatura corporal.

### 7.3 Pressão arterial

**O que o app mostra:** estimativa da pressão pelo relógio depois de calibração com manguito (três leituras em 30 minutos, recalibração a cada 28 dias); resultados no relógio e no Samsung Health Monitor ([ANS10010530][ans-bp], [app US][app-us]).

**Dados disponíveis (SDK):** `blood_pressure` com `SYSTOLIC`, `DIASTOLIC`, `MEAN` (mmHg), `PULSE_RATE` e `MEDICATION_TAKEN`, por instante ([SDK pressão][sdk-bp]).

**Não confirmado:** gráficos e faixas de referência; resumo do período. Um artigo de suporte dos EUA diz que o recurso não está disponível lá, o que contradiz anúncios da Newsroom que não consegui abrir.

### 7.4 Glicemia

**O que o app mostra:** níveis de glicose e o momento da leitura ([ANS10001351][ans-trackers]).

**Dados disponíveis (SDK):** `blood_glucose` com `GLUCOSE_LEVEL` (mmol/L), tipo de medição (sangue total, plasma, soro), estado em relação à refeição (jejum, antes ou depois de café, almoço, jantar, lanche, sono etc.), série para medição contínua, insulina e medicação ([SDK glicemia][sdk-glucose]).

**Não confirmado:** gráfico, faixa-alvo, abas e resumo.

**Implicação para os painéis de 7.x (inferência):** pontos ou linha por medição para pressão (sistólica e diastólica), glicose (com o estado da refeição como rótulo) e temperatura corporal; faixa mín./máx. por noite para SpO2 e temperatura da pele.

## 8. Composição corporal

**O que o app mostra:** a medição por BIA no relógio mostra peso, músculo esquelético, massa de gordura, gordura corporal (%), IMC, água corporal e taxa metabólica basal; sexo, altura e peso são informados antes ([ANS10003647][ans-bia]). Peso e composição são acompanhados ao longo do tempo ([ANS10001351][ans-trackers]), com metas e mensagens motivacionais ([app US][app-us]).

**Dados disponíveis (SDK):** `body_composition` com `WEIGHT` (kg) obrigatório e, opcionais, altura (cm), TMB (kcal/dia), gordura (% e kg), massa livre de gordura (% e kg), massa muscular (%), músculo esquelético (% e kg), água corporal total (L) e IMC ([SDK composição][sdk-body]).

**Implicação para os painéis (inferência):** linhas de tendência de peso, gordura (%) e músculo esquelético (kg), mais o último valor de cada métrica.

**Não confirmado:** gráfico de tendência no app e suas abas; como a meta de peso aparece. A meta de peso não é legível pelo SDK.

## 9. Nutrição e água

**O que o app mostra:** alimentos e calorias consumidas; consumo de água com meta ajustável ([ANS10001351][ans-trackers]). O Food Tracker registra refeições (inclusive por código de barras) e mostra calorias e nutrientes ([app US][app-us]). No Fit3, cada toque soma 250 mL e a meta fica em Set target ([Fit3 água][fit3-water]).

**Dados disponíveis (SDK)**

- `nutrition`: calorias (kcal), título e refeição (café, almoço, jantar e três lanches), macronutrientes e fibras em g, minerais em mg, vitamina A em µg; agregação `TOTAL_CALORIES` ([SDK nutrição][sdk-nutrition]).
- `nutrition_goal`: `LAST_CALORIES` ([SDK meta nutrição][sdk-nutrition-goal]).
- `water_intake`: `AMOUNT` (mL), agregação `TOTAL` ([SDK água][sdk-water]). `water_intake_goal`: `LAST` ([SDK meta água][sdk-water-goal]).

**Implicação para os painéis (inferência):** barras de kcal ingeridas e de água por dia com a meta como linha; divisão de macronutrientes por período; kcal por refeição.

**Não confirmado:** gráfico de consumo contra meta, divisão de macronutrientes na tela, abas.

## 10. Monitores: ritmo irregular e apneia do sono

**Ritmo irregular:** o recurso monitora em segundo plano, sinaliza padrões compatíveis com fibrilação atrial e sugere um ECG ([app US][app-us], [Watch8 US][watch8-us]). O SDK entrega só o instante e o status (`UNDEFINED` ou `DETECTED`), sem duração nem tacograma ([SDK IHRN][sdk-ihrn]).

**Apneia do sono:** adultos sem diagnóstico, duas noites com mais de 4 h de sono em até 10 dias; procura sinais de apneia obstrutiva moderada a grave; resultado no Samsung Health Monitor ([Newsroom FDA][news-apnea], [Watch8 US][watch8-us]). O SDK entrega o instante e `DETECTED`, `NOT_DETECTED` ou `UNDEFINED`, sincronizado do Samsung Health Monitor quando o usuário permite ([SDK apneia][sdk-apnea]).

No Samsung Health Monitor, o resultado mais recente fica no topo da aba Sleep apnea e os anteriores em "View history" ([ANS10003276][ans-apnea], [apneia CA][ca-apnea]).

**Implicação para os painéis (inferência):** tabela de eventos com data e resultado. Pelo limite Personal Wellness Tracking, sem destaque de alerta clínico.

**Não confirmado:** como o histórico de ritmo irregular aparece no Samsung Health Monitor; o texto exato dos resultados de apneia.

## Métricas exibidas pelo app sem Health Category

Estas aparecem nas páginas da Samsung, mas nenhum dos 25 tipos do SDK as entrega, então ficam fora dos dashboards até o SDK expô-las: VFC (HRV) e frequência respiratória ([app US][app-us]); ronco ([sono UK][sleep-uk]); estresse, Daily Cardio Load, Fitness Index, Heart Health Score e Vascular Load ([app US][app-us]); Antioxidant Index e AGEs ([Watch8 US][watch8-us]); ECG ([Watch8 US][watch8-us]); zonas de FC, perda de suor e métricas avançadas de corrida ([ANS10003698][ans-hrzone], [ANS10006858][ans-run], [Watch6][ae-watch6]); fatores do Energy Score e do sleep score.

## Lacunas e incertezas

- **Gráficos do app:** nenhuma fonte primária descreve tipo de gráfico, aba Ano ou valores-resumo por período em qualquer área. A única tela com abas confirmadas é a de passos (Horas, Dias, Semanas, Meses). Capturas oficiais seriam a próxima fonte, mas as páginas de produto mostram telas de marketing, não telas de histórico.
- **Idade das páginas:** várias páginas de suporte são da era Watch5 e Watch6 ou de relógios Tizen; a interface de 2025 e 2026 pode diferir.
- **Páginas inacessíveis:** algumas páginas da Newsroom deram timeout; a página global do app (samsung.com/global/galaxy/apps/samsung-health/) agora redireciona para a home; os PDFs de instruções de uso de IHRN e pressão não puderam ser lidos.
- **Unidades não informadas no SDK:** `vo2Max`, taxa de queima calórica, altitude e precisão da rota, `PULSE_RATE`, escala do `ENERGY_SCORE`.
- **Sono e Energy Score:** os fatores são nomeados pela Samsung, mas sem pesos; a lista longa de fatores do Energy Score (média e consistência do tempo de sono, horário, atividade do dia anterior, FC e VFC no sono) só foi vista em resumos de busca da Newsroom, porque as páginas deram timeout; a mesma lista aparece resumida na página do Watch8. As visões de semana e mês do sono só aparecem em fóruns, não usados como fonte.

## Fontes

Samsung Health Data SDK (developer.samsung.com):

[sdk-datatypes]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-types/-companion/index.html
[sdk-groupunit]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-local-time-group-unit/index.html
[sdk-steps]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-steps-type/index.html
[sdk-activity]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-activity-summary-type/-companion/index.html
[sdk-steps-goal]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-steps-goal-type/-companion/index.html
[sdk-exercise]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-exercise-type/index.html
[sdk-session]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-session/index.html
[sdk-exlog]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-log/index.html
[sdk-exloc]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-location/index.html
[sdk-floors]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-floors-climbed-type/index.html
[sdk-sleep]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-sleep-type/-companion/index.html
[sdk-sleep-session]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-sleep-session/index.html
[sdk-stage]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-sleep-type/-stage-type/index.html
[sdk-sleep-goal]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-sleep-goal-type/-companion/index.html
[sdk-energy]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-energy-score-type/-companion/index.html
[sdk-hr]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-heart-rate-type/index.html
[sdk-hr-entry]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-heart-rate/index.html
[sdk-spo2]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-blood-oxygen-type/index.html
[sdk-skin]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-skin-temperature-type/index.html
[sdk-bodytemp]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-body-temperature-type/index.html
[sdk-bp]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-blood-pressure-type/index.html
[sdk-glucose]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-blood-glucose-type/index.html
[sdk-body]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-body-composition-type/index.html
[sdk-nutrition]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-nutrition-type/index.html
[sdk-nutrition-goal]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-nutrition-goal-type/index.html
[sdk-water]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-water-intake-type/index.html
[sdk-water-goal]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-water-intake-goal-type/index.html
[sdk-ihrn]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-irregular-heart-rhythm-notification-type/index.html
[sdk-apnea]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-sleep-apnea-type/index.html

[ans-steps]: https://www.samsung.com/us/support/answer/ANS10001370/
[ans-trackers]: https://www.samsung.com/us/support/answer/ANS10001351/
[ans-sleep]: https://www.samsung.com/us/support/answer/ANS10003657/
[ans-ai]: https://www.samsung.com/us/support/answer/ANS10003272/
[ans-hr]: https://www.samsung.com/us/support/answer/ANS10003311/
[ans-bia]: https://www.samsung.com/us/support/answer/ANS10003647/
[ans-bp]: https://www.samsung.com/us/support/answer/ANS10010530/
[ans-run]: https://www.samsung.com/us/support/answer/ANS10006858/
[ans-workout]: https://www.samsung.com/us/support/answer/ANS10004899/
[ans-hrzone]: https://www.samsung.com/us/support/answer/ANS10003698/
[app-us]: https://www.samsung.com/us/apps/samsung-health/
[watch8-us]: https://www.samsung.com/us/watches/galaxy-watch8/
[sleep-uk]: https://www.samsung.com/uk/support/mobile-devices/use-samsung-health-sleep-monitoring-on-your-samsung-galaxy-watch-and-fit/
[sleep-au]: https://www.samsung.com/au/support/apps-services/monitor-sleep-samsung-health/
[uk-skin]: https://www.samsung.com/uk/support/apps-services/how-to-use-the-menstrual-skin-temperature-sensor-with-samsung-health/
[ph-watch]: https://www.samsung.com/ph/support/mobile-devices/samsung-health-features-on-galaxy-watch/
[fit3-activity]: https://www.samsung.com/ae/support/mobile-devices/maximizing-your-daily-activity-with-galaxy-fit3/
[fit3-water]: https://www.samsung.com/ae/support/mobile-devices/how-to-track-water-consumption-on-galaxy-fit3/
[latin-exercise]: https://www.samsung.com/latin_en/support/apps-services/how-to-monitor-exercises-with-samsung-health/
[latin-coach]: https://www.samsung.com/latin_en/support/mobile-devices/how-to-use-the-personalized-running-coach-feature-on-your-galaxy-watch8-and-watch8-classic/
[latin-hr]: https://www.samsung.com/latin_en/support/mobile-devices/measure-your-ecg-with-the-galaxy-watch-series/
[ae-watch6]: https://www.samsung.com/ae/support/mobile-devices/what-are-the-new-features-of-the-samsung-galaxy-watch6-series/
[news-apnea]: https://news.samsung.com/us/samsung-sleep-apnea-feature-galaxy-watch-first-its-kind-authorized-us-fda/
[news-galaxy-ai]: https://news.samsung.com/global/galaxy-ai-is-coming-to-new-galaxy-watch-for-more-motivational-health
[news-uk-sleep]: https://news.samsung.com/uk/samsung-solves-tips-to-recover-from-the-clocks-going-forward
[news-nz-watch]: https://www.samsung.com/nz/news/local/samsung-introduces-next-gen-galaxy-watch-features-for-ai-powered-everyday-health-companion/
[watch9-in]: https://www.samsung.com/in/watches/galaxy-watch/galaxy-watch9-40mm-graphite-lte-sm-l345fzkains/
[ans-apnea]: https://www.samsung.com/us/support/answer/ANS10003276/
[ca-apnea]: https://www.samsung.com/ca/support/mobile-devices/how-to-use-the-sleep-apnea-risk-feature-on-the-samsung-galaxy-watch/
[ae-ring-energy]: https://www.samsung.com/ae/support/mobile-devices/how-to-check-energy-score-with-the-galaxy-ring/
[sdk-energy-type]: https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-energy-score-type/index.html

- SDK: [DataTypes][sdk-datatypes], [LocalTimeGroupUnit][sdk-groupunit], [Steps][sdk-steps], [ActivitySummary][sdk-activity], [StepsGoal][sdk-steps-goal], [Exercise][sdk-exercise], [ExerciseSession][sdk-session], [ExerciseLog][sdk-exlog], [ExerciseLocation][sdk-exloc], [FloorsClimbed][sdk-floors], [Sleep][sdk-sleep], [SleepSession][sdk-sleep-session], [StageType][sdk-stage], [SleepGoal][sdk-sleep-goal], [EnergyScore][sdk-energy], [EnergyScoreType][sdk-energy-type], [HeartRate][sdk-hr], [HeartRate entry][sdk-hr-entry], [BloodOxygen][sdk-spo2], [SkinTemperature][sdk-skin], [BodyTemperature][sdk-bodytemp], [BloodPressure][sdk-bp], [BloodGlucose][sdk-glucose], [BodyComposition][sdk-body], [Nutrition][sdk-nutrition], [NutritionGoal][sdk-nutrition-goal], [WaterIntake][sdk-water], [WaterIntakeGoal][sdk-water-goal], [IrregularHeartRhythmNotification][sdk-ihrn], [SleepApnea][sdk-apnea].
- Suporte Samsung: [passos][ans-steps], [trackers][ans-trackers], [sono US][ans-sleep], [Galaxy AI e Energy Score][ans-ai], [frequência cardíaca][ans-hr], [composição corporal][ans-bia], [pressão][ans-bp], [VO2 e suor][ans-run], [detalhes do treino][ans-workout], [zonas de FC][ans-hrzone], [sono UK][sleep-uk], [sono AU][sleep-au], [temperatura da pele UK][uk-skin], [recursos do Watch][ph-watch], [atividade no Fit3][fit3-activity], [água no Fit3][fit3-water], [exercícios][latin-exercise], [Running Coach][latin-coach], [medição da FC][latin-hr], [Watch6][ae-watch6], [apneia e medições avançadas][ans-apnea], [apneia CA][ca-apnea], [Energy Score no Ring][ae-ring-energy].
- Produto e Newsroom: [Samsung Health US][app-us], [Galaxy Watch8 US][watch8-us], [Galaxy Watch9 IN][watch9-in], [apneia autorizada pela FDA][news-apnea], [Galaxy AI no Galaxy Watch][news-galaxy-ai], [fatores do sleep score][news-uk-sleep], [Galaxy Watch e Energy Score NZ][news-nz-watch].
