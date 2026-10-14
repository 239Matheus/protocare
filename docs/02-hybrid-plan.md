# Proto Care — Plano híbrido (decisão: 2026-10-14)

Estratégia escolhida: **C — Híbrido**. Engine nativo em `common/` (CLJC) desde o início, validado cedo contra um arquivo real via MCP/Plugin API, e promovido para UI nativa no fork do Penpot.

Base do fork: `penpot/penpot` branch `develop`, commit `1ebb4f3866e75996b20e8db7f65aa8da068676f8` (v2.17.0), importado em `penpot/` sem histórico.

Princípios:
- Código próprio vive em namespaces `app.governance.*` (common), `app.rpc.commands.governance` (backend) e `app.main.ui.workspace.governance` / `app.main.data.workspace.governance` (frontend). Tocar o mínimo possível nos arquivos upstream para facilitar sync com releases do Penpot.
- Regras são dados puros e determinísticas. Sem LLM como fonte de verdade.
- Fix segue **Detect → Explain → Preview → Approve → Apply**, sempre via pipeline de changes (undoável).

---

## Fase 0 — Ambiente (em andamento)

| Item | Estado |
|---|---|
| Clone do Penpot v2.17.0 em `./penpot` | feito |
| Clojure CLI portátil (`tools/deps-clj/deps.exe`) | feito |
| JDK 21 portátil (`tools/jdk/`) | feito (JDK 17 do sistema não serve) |
| Testes JVM do `common` rodando no Windows | feito (`tools/common-test.sh`) |
| Docker Desktop (instalado em 2026-10-14, CLI em `%LOCALAPPDATA%\Programs\DockerDesktop
esourcesin`) | feito |
| Infra mínima para testes de backend: `tools/infra/docker-compose.yml` (Postgres em `localhost:15432`, Valkey em `localhost:6379`) | feito |
| Testes de backend rodam **dentro de container Linux** (`tools/backend-test-docker.*`): o backend usa permissões POSIX via datoteka e não roda em JVM Windows | feito |
| JDK 25 portátil (`tools/jdk/`) substitui o 21: backend e shadow-cljs exigem flag de JVM 23+ | feito |

Sem Docker é possível desenvolver e testar todo o engine (common) e, mais tarde, o backend com um Postgres local. A UI (shadow-cljs) também roda sem Docker, mas o caminho oficial é o devenv.

## Fase 1 — Vertical slice "Color Token Governance" (engine)

Entregue nesta fase:
- `app.governance.rule` — modelo de regra e resultado (schemas Malli).
- `app.governance.context` — contexto analisável a partir do arquivo: páginas, tokens ativos (respeita `tokens-source`), índice cor→token, normalização de cores.
- `app.governance.rules.token-color` — regra `token/color` para fills, strokes e fills de texto. Reporta literal com token equivalente (fixável) e literal sem token (não fixável). Opção `:allow-library-colors?`.
- `app.governance.engine` — `run-audit` com configuração por regra (enabled, severidade), resultados ordenados e sumário (total, por severidade/categoria/página, aderência a tokens).
- `app.governance.fix` — `generate-fix-changes`: substitui literal por token via `changes-builder` (com undo).
- Testes em `common/test/common_tests/governance/token_color_test.cljc`.

Estado: testes verdes no JVM e no JS/Node (6 testes, 44 asserções em ambos). Fase 1 concluída em 2026-10-14.

## Fase 2 — Validação com arquivo real (MCP)

- Subir devenv (`./manage.sh run-devenv --agentic`) ou usar design.penpot.app + plugin MCP.
- Rodar `scripts/mcp/color-audit.js` via `execute_code` num arquivo com tokens para comparar a leitura via Plugin API com a leitura do engine (mesmos nodes, mesmos literais).
- Exportar o arquivo `.penpot` e rodar o engine no JVM sobre ele (via `@penpot/library`/binfile) para confirmar paridade.

## Fase 3 — Backend (concluída em 2026-10-14)

Entregue:
- Migração `0157-add-governance-tables.sql`: `governance_policy`, `governance_role`, `governance_audit`, `governance_audit_result`.
- `app.rpc.commands.governance` registrado em `app.rpc`: `get-governance-policy`, `update-governance-policy`, `get-governance-roles`, `set-governance-role`, `run-audit`, `get-audit`, `get-file-audits`, `update-audit-result-status`, `get-project-health`, `get-team-health`.
- Papéis `designer` | `design-ops` | `ds-admin` em tabela própria (sem tocar `team_profile_rel`). Owner/admin do time sempre pode gerir a política.
- `run-audit` carrega o arquivo e as bibliotecas (tokens source), aplica a política do time, persiste o audit com `engine_version`, carrega status `ignored` da auditoria anterior e calcula `resolved-since-previous`.
- Eventos de `audit_log` via `::audit/props` em `run-audit`, `update-governance-policy` e `set-governance-role`.
- Teste `backend/test/backend_tests/rpc_governance_test.clj` (run/get/history/status carry-over, política, papéis e permissões de health). Verde dentro do container Linux: 3 testes, 49 asserções, 0 falhas. A migração SQL foi aplicada pelo fixture de testes sem erro. Rodar com `toolsackend-test-docker.ps1 --focus backend-tests.rpc-governance-test` (infra: `tools\infra-up.ps1`; derrubar com `tools\infra-down.ps1`).

Plano original:

- Migração SQL: `governance_policy` (por time: regras habilitadas, severidade, thresholds), `audit` (file-id, revn, timestamp, profile-id, summary jsonb), `audit_result` (audit-id, rule-id, node-id, page-id, severity, payload jsonb, status).
- RPC: `run-audit` (carrega file + libraries, chama `engine/run-audit`, persiste), `get-audit`, `get-file-audits`, `get-project-health`, `get-team-governance-policy`, `update-team-governance-policy`.
- Papéis: `governance_role` em `team_profile_rel` (`designer` | `design-ops` | `ds-admin`); checks em `app.rpc.permissions`.
- Evento `audit-run` no `audit_log` e webhook.
- Feature flag `governance/v1`.

## Fase 4 — Frontend (concluída em 2026-10-14, validada ponta a ponta no devenv)

Teste manual no devenv (arquivo "Design tokens starter kit" importado, conta demo):
- Run audit: 30 violações abertas, 4 corrigíveis, 98% de aderência em ~5 s.
- Go to element: seleciona e centraliza; entre páginas troca a página e mantém o painel aberto.
- Fix: modal "Apply fix?" → shape ganha `applied-tokens` e cor normalizada; Ctrl+Z desfaz, Ctrl+Shift+Z refaz.
- Re-audit: 30 → 29 → 28 abertas; `resolved-since-previous` = 1 (bug corrigido: resultados já marcados `fixed` não contavam).
- Inspect: badge "Literal value, no token applied" em cor literal; some quando o token é aplicado.
- Fase 2 (validação via MCP) ficou coberta por este teste no devenv; o script `scripts/mcp/color-audit.js` segue como referência.

Entregue (flag `enable-governance` em `frontend/resources/public/js/config.js`):
- `app.main.data.workspace.governance`: `run-audit`, `fetch-latest-audit`, `go-to-result` (seleciona + centraliza, troca de página se preciso), `apply-fix` (engine `fix` em CLJS + `commit-changes` com undo + marca `fixed`), `set-result-status`, filtros.
- `app.main.ui.workspace.governance` + SCSS: painel na sidebar direita (flag de layout `:governance`): Run audit, sumário (abertas, corrigíveis, aderência), filtros de severidade e status, cards com Go to element / Fix (modal de confirmação) / Ignore / Reopen.
- Botão no `right_header` (ícone de alerta) ao lado do histórico; branch em `sidebar.cljs`; limpeza em `finalize-workspace`.
- Inspect (aba Styles, painel de fill): aviso "Literal value, no token applied" sob cores sem token nem style de biblioteca.
- Traduções `workspace.governance.*` em `en.po` e `es.po`.

Devenv no Windows (aprendizados):
- O clone tinha CRLF em 4.317 arquivos (autocrlf do sistema). Normalizei via clone LF + overlay; `core.autocrlf=false` no repo.
- `manage.sh` usa `env -i` e o Docker CLI do Windows não acha o plugin compose sem `USERPROFILE`/`LOCALAPPDATA`: patch de 2 linhas no `manage.sh`.
- `PENPOT_SOURCE_PATH` precisa ir em forma Windows (`D:/...`). Wrapper: `tools\devenv.ps1 up|status|attach|down`.
- O clone LF auxiliar ficou em `penpot-lf/` (pode ser apagado).

Plano original:

- Botão **Run Audit** na barra superior (flag `governance/v1`).
- Painel lateral "Governance" com lista de resultados, filtros (severidade, categoria, página, status), card por resultado (categoria, regra, severidade, node, problema, atual, esperado, recomendação).
- **Go to element**: selecionar node, trocar de página se preciso, centralizar viewport.
- **Fix**: preview (antes/depois) → aprovação → `generate-fix-changes` → commit com undo.
- Inspect: mostrar token aplicado e badge de conformidade ao lado de cada cor.

## Fase 5 — Dashboard do gestor

- Rota no dashboard: Overview (projetos auditados, auditorias recentes, violações abertas/críticas, aderência, acessibilidade), Trends, Projects, Categories.
- Sem ranking de pessoas.

## Fase 6 — Mais regras

Ordem sugerida: `token/spacing` → `token/radius` → `token/typography` → `accessibility/contrast` → `accessibility/text-size` → `component/official` → `component/property`.

---

## Como rodar hoje

PowerShell:

```powershell
tools\common-test.ps1 --focus common-tests.governance.token-color-test
```

```powershell
tools\common-test-js.ps1 --focus common-tests.governance.token-color-test
```

Git Bash: os mesmos comandos com `tools/common-test.sh` e `tools/common-test-js.sh`.

Toolchain portátil (sem instalação no sistema): `tools/jdk/` (Temurin 21), `tools/deps-clj/deps.exe` (Clojure CLI). O JDK 17 instalado na máquina não serve para o Penpot.
