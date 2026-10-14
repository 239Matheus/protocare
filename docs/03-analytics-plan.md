# Proto Care — Plano de analytics e evidências (2026-10-14)

Objetivo: ter **evidência** de que a governança melhora o trabalho. Não basta mostrar quantos problemas existem hoje; é preciso mostrar o que foi encontrado, o que foi resolvido, quanto tempo levou, e se a qualidade evolui entre auditorias. Tudo sem ranking de pessoas (brief, seções 5, 11 e 12).

Este documento separa o que **já é capturado** (fase A, implementada) do que é **proposta** (fases B a F).

---

## 1. Perguntas que o analytics precisa responder

| Pergunta | Quem pergunta | Métrica |
|---|---|---|
| Os arquivos estão melhorando? | Design Ops | Achados abertos ao longo do tempo, por área |
| A auditoria gera ação ou é ignorada? | Design Ops, DS | Taxa de correção, taxa de ignorados |
| Quanto tempo um problema fica aberto? | Design Ops | Tempo mediano até resolver, por área e severidade |
| O design system está sendo adotado? | Admin DS | Aderência a tokens, adoção de componentes (futuro) |
| Onde estão os problemas de acessibilidade? | Design Ops, acessibilidade | Achados de acessibilidade abertos por projeto |
| Quais regras geram ruído? | Admin DS | Taxa de ignorados por regra, reabertos |
| A correção automática ajuda? | Produto | Corrigidos pelo painel ÷ resolvidos |
| O protótipo está pronto para teste? | Pesquisa | Achados de usabilidade abertos (links, fluxo, saída) |

---

## 2. O que já é capturado (fase A, implementada)

### 2.1 Tabelas

- `governance_audit`: uma linha por execução (arquivo, projeto, time, versão do engine, `summary` e `stats`).
- `governance_audit_result`: um achado por execução. Novas colunas (migração `0158`):
  - `area`: área da auditoria (design-system, accessibility, ui-quality, layer-semantics, usability).
  - `first_seen_at`: quando o achado foi detectado pela primeira vez. É herdado entre auditorias pela identidade do achado: regra + elemento + atributo.
- `governance_event` (migração `0158`): trilha **append-only** de evidências. Colunas: `type`, `team_id`, `project_id`, `file_id`, `audit_id`, `result_id`, `profile_id`, `rule_id`, `area`, `severity`, `props` (JSON puro, consultável direto no SQL), `created_at`.

O evento é gravado na **mesma transação** da mudança que descreve: se a mudança falha, não há evento.

### 2.2 Catálogo de eventos

| `type` | Quando | `props` |
|---|---|---|
| `audit-run` | Toda auditoria | `engine-version`, `page-id` (auditoria de página), `total`, `fixable`, `resolved-since-previous`, `token-adherence`, `by-area`, `by-severity` |
| `finding-resolved` | Na auditoria seguinte, para cada achado anterior que **não é mais detectado** (corrigido pelo painel ou à mão; ignorados não contam; auditoria de página só resolve achados da própria página) | `node-id`, `previous-status`, `fix-available`, `open-seconds` |
| `result-status` | Mudança de status no painel (corrigir, ignorar, reabrir) | `from`, `to`, `fix-available`, `open-seconds` |
| `policy-updated` | Mudança na política do time | `rules` (ativação e severidade por regra), `options` |
| `design-system-installed` | Instalação do Proto Care DS no time | `name` |

`open-seconds` é o tempo entre `first_seen_at` e o evento: é a base do tempo até resolver.

### 2.3 Consultas (RPC)

- `get-governance-events`: trilha de evidências, mais recentes primeiro. Filtros: `file-id` ou `team-id` (+ `project-id`), `types`, `since`, `limit` (até 500).
  - Por arquivo: quem pode ler o arquivo.
  - Por time ou projeto: owner/admin do time, `design-ops` ou `ds-admin`.
- `get-governance-metrics`: métricas agregadas de um time ou projeto num período (padrão 90 dias). Mesmas permissões da visão de time.
  - Por regra: abertos, resolvidos, corrigidos pelo painel, ignorados, reabertos e taxa de ignorados. Por severidade: abertos.
- `get-governance-permissions`: papel de governança e flags do perfil atual no time (usado para mostrar o dashboard só a quem pode vê-lo).
  - Totais: arquivos auditados, auditorias, abertos, resolvidos, taxa de correção.
  - Por área: abertos, resolvidos, corrigidos pelo painel, ignorados, reabertos, taxa de correção, tempo mediano até resolver.
  - Por semana: auditorias, arquivos, achados (soma dos totais de cada auditoria, então um achado conta uma vez por auditoria), achados resolvidos.
- Já existentes: `get-project-health` e `get-team-health` (fotografia da última auditoria de cada arquivo).

Cobertura de testes: `backend/test/backend_tests/rpc_governance_test.clj` (`audits-leave-evidence`) cobre o ciclo completo: auditar, marcar como corrigido, corrigir de fato, auditar de novo, ler a trilha e as métricas, e checar as permissões.

### 2.4 Limites conhecidos da captura

- Um achado só vira `finding-resolved` quando alguém audita de novo. Sem nova auditoria, "corrigido" no painel é uma declaração, não uma evidência.
- Apagar o elemento também resolve o achado. É correto para a qualidade do arquivo, mas pode inflar a taxa de correção. Fase C: registrar se o nó ainda existe.
- A identidade do achado é regra + elemento + atributo. Mudar a regra de versão pode mudar a identidade; por isso `audit-run` guarda `engine-version`.
- Auditorias de uma página não resolvem achados de outras páginas, mas a próxima auditoria completa compara com a de página, então `first_seen_at` dos achados de outras páginas reinicia. Recomendação: o painel sempre roda auditoria completa (hoje é assim).

---

## 3. Definição das métricas

| Métrica | Fórmula | Fonte |
|---|---|---|
| Achados abertos | Achados com status `open` na última auditoria de cada arquivo | `governance_audit_result` |
| Achados por área / severidade | Agrupamento dos abertos | idem |
| Aderência a tokens | Slots de cor com token ÷ slots de cor auditados | `summary.token-adherence` |
| Resolvidos | Contagem de `finding-resolved` no período | `governance_event` |
| Taxa de correção | Resolvidos ÷ (resolvidos + abertos) | idem |
| Tempo até resolver | Mediana de `open-seconds` dos `finding-resolved` | idem |
| Uso da correção automática | `result-status` com `to = fixed` ÷ resolvidos | idem |
| Taxa de ignorados por regra | `result-status` com `to = ignored` ÷ achados da regra | idem; sinal de regra mal calibrada |
| Reabertos | `result-status` com `to = open` | idem |
| Cobertura | Arquivos auditados no período ÷ arquivos alterados no período | `governance_event` + `file.modified_at` (fase B) |
| Evolução | Achados por semana e resolvidos por semana | `get-governance-metrics.weekly` |

### 3.1 Design Health (proposta, não implementada)

O brief pede uma métrica conceitual sem fórmula fechada. Proposta v0, para calibrar com 4 a 6 semanas de dados reais:

- Peso por severidade: crítico 10, alto 5, médio 2, baixo 1, info 0.
- Nota por área = `100 × (1 − min(1, Σ pesos dos abertos ÷ (camadas auditadas × fator)))`. Requer gravar no `summary` o número de camadas auditadas (fase C).
- Design Health do arquivo = média ponderada das áreas: Design system 30, Acessibilidade 30, Qualidade 15, Usabilidade 15, Semântica 10.
- Projeto e time: média das notas dos arquivos, ponderada por camadas.
- Exibir sempre a nota **junto** com os números que a compõem, nunca sozinha.

---

## 4. Dashboards (fase B, implementada)

| Visão | Conteúdo | Fonte |
|---|---|---|
| Overview | Arquivos auditados, auditorias recentes, abertos, críticos, aderência a tokens, acessibilidade abertos, taxa de correção | `get-team-health` + `get-governance-metrics` |
| Tendências | Linhas semanais de detectados e resolvidos; tempo mediano até resolver por área | `get-governance-metrics.weekly` e `by-area` |
| Projetos | Tabela por projeto com as mesmas colunas da Overview | `get-team-health.projects` + métricas com `project-id` |
| Áreas | Para cada uma das cinco áreas: abertos, resolvidos, taxa de correção, tempo até resolver, corrigidos pelo painel, ignorados | `get-governance-metrics.by-area` |
| Regras | Para o admin do DS: achados, ignorados e reabertos por regra, para calibrar a política | `get-governance-events` agregado |
| Trilha | Lista de eventos de um arquivo (quem auditou, o que foi resolvido e quando) | `get-governance-events` com `file-id` |

Implementação: rota `dashboard-health`, item "Saúde do design" na seção "Governança" da sidebar (só aparece para owner/admin, `design-ops` e `ds-admin`), página `frontend/src/app/main/ui/dashboard/health.cljs`. Filtros de projeto e período (30, 90 ou 180 dias); clicar num projeto filtra o dashboard e a aba Projetos passa a listar os arquivos. Gráficos semanais mostram semanas sem atividade como zero. A visão "Trilha" por arquivo fica para a fase D, junto com a exportação.

---

## 5. Privacidade e ética

- **Sem ranking de pessoas.** As métricas agregam por time, projeto, arquivo, área e regra. `get-governance-metrics` não expõe `profile_id`.
- A trilha bruta (`get-governance-events`) mostra quem fez cada ação, como um histórico de versões. É evidência de processo, não avaliação de desempenho. A UI não deve somar eventos por pessoa.
- Ao excluir um perfil, `profile_id` vira nulo (`ON DELETE SET NULL`); ao excluir time, projeto ou arquivo, os eventos vão junto (`CASCADE`).
- Visões por squad (fase F) só com grupos de 3 pessoas ou mais.
- Retenção proposta: eventos por 24 meses; depois, só agregados mensais.

---

## 6. Exportação (fase D)

- CSV e JSON da trilha e das métricas, com os mesmos filtros das RPCs.
- View SQL `governance_event_flat` para BI (Metabase, Looker Studio) com `props` expandido.
- Webhook opcional em `audit-run` para integrar com CI de design ou Slack.

---

## 7. Teste de usabilidade (fase E)

Hoje a área "Teste de usabilidade" verifica se o protótipo está **pronto para teste** (links quebrados, fluxo, telas sem saída). O módulo de testes adiciona a evidência com usuários:

- Modelo: `usability_study` (protótipo, fluxo inicial, roteiro), `usability_task` (enunciado, tela de sucesso, tempo limite), `usability_session` (participante anônimo, dispositivo, consentimento), `usability_task_result` (sucesso, tempo, cliques, cliques fora de área, abandono).
- Métricas: taxa de sucesso por tarefa, tempo na tarefa, cliques errados, SEQ (dificuldade por tarefa, 1 a 7) e SUS (questionário de 10 itens, 0 a 100).
- Eventos: `usability-session-completed` em `governance_event`, para cruzar com auditorias. Exemplo de pergunta: telas com achados de acessibilidade abertos têm menor taxa de sucesso?
- Execução no modo Protótipo do Proto Care, sem ferramenta externa.

---

## 8. Como provar valor (evidência do próprio produto)

- Linha de base: primeira auditoria de cada arquivo.
- Hipótese 1: em 4 semanas, arquivos auditados semanalmente reduzem achados abertos de severidade alta ou crítica em 50%.
- Hipótese 2: com correção automática, o tempo mediano até resolver achados corrigíveis fica abaixo de 1 dia.
- Hipótese 3: times com o DS instalado têm aderência a tokens acima de 90%.
- Método: comparar a primeira e a última auditoria de cada arquivo no período, por área. Times com `design-system-installed` formam uma coorte.

---

## 9. Roteiro

| Fase | Entrega | Estado |
|---|---|---|
| A | Captura: `governance_event`, `area` e `first_seen_at` nos achados, `get-governance-events`, `get-governance-metrics`, testes | feito |
| B | Dashboard "Saúde do design" (Visão geral, Tendências, Projetos, Áreas, Regras) | feito |
| C | Camadas auditadas no `summary`, Design Health v0, marcação de nó apagado | |
| D | Exportação CSV/JSON, view para BI, retenção | |
| E | Módulo de teste de usabilidade | |
| F | Squads, adoção de componentes oficiais (`component/official`), sugestões com IA explicadas | |

---

## 10. Consultas úteis

Tempo mediano até resolver por área, últimos 30 dias:

```sql
SELECT area,
       count(*) AS resolvidos,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY (props->>'open-seconds')::numeric) / 3600 AS horas
  FROM governance_event
 WHERE team_id = :team AND type = 'finding-resolved'
   AND created_at >= now() - interval '30 days'
 GROUP BY area;
```

Regras mais ignoradas:

```sql
SELECT rule_id, count(*) AS ignorados
  FROM governance_event
 WHERE team_id = :team AND type = 'result-status' AND props->>'to' = 'ignored'
 GROUP BY rule_id
 ORDER BY ignorados DESC;
```
