# Proto Care — Análise do Penpot como editor-base

Data: 2026-10-14
Fonte: clone raso de `penpot/penpot` (v2.17.0, MPL-2.0) em `./penpot`.
Brief de produto: `C:\Users\mathe\Downloads\design-care-editor-mvp.md` (Design Care Editor MVP).

> O brief sugere avaliar o OpenPencil. Esta análise cobre o Penpot, que foi o repositório indicado.

---

## 1. Visão geral do repositório

Monorepo com módulos independentes. Linguagem dominante: **Clojure / ClojureScript**.

| Módulo | Stack | Papel |
|---|---|---|
| `frontend/` | ClojureScript, React 19 via Rumext, Potok (eventos), RxJS, SCSS modules, shadow-cljs | SPA do editor (workspace), dashboard, viewer, inspect |
| `backend/` | Clojure JVM, Integrant, PostgreSQL, Redis/Valkey, RPC HTTP | API `/api/rpc/command/<cmd>`, persistência, auth, workers, audit |
| `common/` | CLJC (roda em JVM e JS) | **Modelo de dados**, schemas Malli, geometria, change pipeline, tokens, componentes, validação de arquivo |
| `render-wasm/` | Rust + Skia → WASM | Renderer de canvas (feature flag `render-wasm/v1`, não default) |
| `exporter/` | ClojureScript + Playwright | Export headless SVG/PDF |
| `plugins/` | TypeScript (pnpm workspace) | Tipos da Plugin API (`index.d.ts`, 5.9k linhas), runtime sandbox (SES), plugins de exemplo |
| `mcp/` | TypeScript, MCP SDK | Servidor MCP que executa código da Plugin API via um plugin-ponte por WebSocket |
| `library/` | ClojureScript → `@penpot/library` | Builder JS de arquivos `.penpot` fora da UI |
| `media-processor/` | Node, sharp, FontForge | Processamento de imagens/fontes |
| `docs/` | Eleventy | Guia técnico e de usuário |

Dependências: `frontend → common`, `backend → common`, `exporter → common`, `frontend → render-wasm`.

### Licença
MPL-2.0 (copyleft por arquivo). Permite fork, renomear e uso comercial. Obrigações: arquivos MPL modificados continuam MPL e com fonte disponível; arquivos novos nossos podem ter outra licença. Não há política de marca no repositório; evitar usar o nome/logo "Penpot" no produto.

### Ambiente de desenvolvimento
Oficialmente via Docker (`./manage.sh pull-devenv && ./manage.sh run-devenv`) com sessão tmux dentro do container. Imagem inclui JDK, Clojure 1.12, Node 24, Rust/Emscripten. Portas: UI `https://localhost:3449`, MCP `http://localhost:4401/mcp`. No Windows precisa de Docker Desktop + WSL2. Há modo `--agentic` com Serena/MCP para agentes.

O repo tem um sistema de memórias para agentes em `.serena/memories/` (`critical-info.md` é a raiz). Vale ler antes de tocar em qualquer módulo.

---

## 2. Modelo de documento (o que o Governance Engine vai ler)

Hierarquia conceitual:

```
Team → Project → File
File.data
 ├── pages (PagesList) → Page → objects (ShapeTree: map id → Shape)
 ├── components (ComponentsList)   ← componentes locais da biblioteca
 ├── colors (ColorsList)           ← cores de biblioteca (styles)
 ├── typographies (TypographiesList)
 ├── media
 └── tokens-lib (TokensLib)        ← design tokens DTCG
File ↔ File : libraries (file_library_rel)  ← bibliotecas compartilhadas
```

Schema das shapes: `common/src/app/common/types/shape.cljc`.
Tipos: `:frame :group :bool :rect :path :text :circle :svg-raw :image`.

### Atributos relevantes para governança

| Atributo | Significado | Uso no engine |
|---|---|---|
| `:fills [{:fill-color "#hex" :fill-opacity :fill-color-ref-id :fill-color-ref-file ...}]` | Preenchimentos. `fill-color-ref-id` = referência a cor de biblioteca (style) | Regra `token/color`: fill literal sem `applied-tokens.fill` e sem `ref-id` |
| `:strokes [{:stroke-color :stroke-width :stroke-color-ref-id ...}]` | Bordas | `token/color`, `token/border-width` |
| `:applied-tokens {:fill "color.action.primary" :stroke-color ... :r1..:r4 :p1..:p4 :m1..:m4 :font-size ...}` | **Tokens aplicados por nome** (não por id). Schema em `types/token.cljc` (`schema:applied-tokens`) | Fonte da verdade de "usa token ou não" |
| `:r1 :r2 :r3 :r4` | Raios dos cantos | `token/radius` |
| `:layout` (`:flex`/`:grid`), `:layout-gap`, `:layout-padding`, `:layout-item-margin`, sizing | Auto Layout (flex/grid) | `token/spacing`, `layout/spacing` |
| `:content` (texto), `:typography-ref-id`, atributos de fonte por bloco | Texto rico (DraftJS-like, v2 em migração) | `token/typography`, `accessibility/text-size` |
| `:component-id`, `:component-file`, `:component-root`, `:main-instance`, `:shape-ref`, `:touched #{...}` | Modelo de componentes (main vs cópia, cadeia de herança, overrides) | `component/official`, `component/property` |
| `:variant-id`, `:variant-name`, container `:is-variant-container` | Variantes (`variants/v1`) | `component/property` |
| `:plugin-data {ns {k v}}` | Dados arbitrários por plugin/namespace | Lugar sem migração para metadados de governança (ex.: a11y label, status de issue) |
| `:hidden :blocked :opacity :blend-mode :shadow :blur` | Visual | contraste efetivo, visibilidade |

Observações:
- Tokens aplicados guardam **nomes**, resolvidos nos sets ativos do tema. Rename de token tem que atualizar `applied-tokens`.
- Componentes: uma shape pode ser ao mesmo tempo main-instance, cópia aninhada e root. `ctk/in-component-copy?` ≈ `(some? :shape-ref)`. Cadeia de `:shape-ref` pode atravessar arquivos (bibliotecas remotas).
- Já existe um **validador estrutural de arquivo** em `common/src/app/common/files/validate.cljc` (integridade de componentes, parent/children). É um bom precedente de "regras sobre o documento" no estilo do repo, mas é estrutural, não de governança.

### Design tokens nativos (`types/tokens_lib.cljc`, 2.4k linhas)
- Tipos: color, dimension, spacing, sizing, borderRadius, borderWidth, opacity, rotation, number, fontFamilies, fontSizes, fontWeights, letterSpacing, textCase, textDecoration, shadow, typography (composite), string, boolean, other.
- Estrutura: `TokensLib { sets, set-groups, themes, active-themes }`. Tema oculto interno controla sets ativos sem tema de usuário.
- Import/export DTCG multi-set (`$themes`, `$metadata.tokenSetOrder`).
- Resolução de valores via Style Dictionary ou `tokenscript` (feature flag).
- Aplicação (frontend `app.main.data.workspace.tokens.application`): escreve nome em `:applied-tokens` **e** o valor concreto no atributo da shape, em uma transação de undo. Spacing tem caminho especial (gap/padding no container, margin nos filhos).

Isto cobre quase tudo que o brief chama de "Design System → tokens". Não precisamos inventar modelo de tokens.

---

## 3. Pipeline de mutação (como o "Fix" deve escrever)

Nada muta o mapa de objetos diretamente. Tudo passa por **changes**:

- Famílias: `:add-obj :mod-obj :del-obj :add-component :mod-component :mov-objects :reg-objects ...`.
- `:mod-obj` carrega `:operations [{:type :set :attr :fills :val [...] :ignore-touched ...}]`.
- Builder fluente: `app.common.files.changes-builder` (`pcb/update-shapes ids f`).
- Cada transação tem `:redo-changes` + `:undo-changes`; é payload de persistência e base do undo.
- Backend aplica via `files_update.clj` (`update-file` RPC), valida e versiona (`file_change`, `revn`).

Consequência: a autocorreção "literal → token" do MVP é exatamente o que `apply-token` já faz. O Fix pode reusar essa função ou emitir `applyToken` via Plugin API.

---

## 4. Pontos de extensão

### 4.1 Plugin API (JS, sandbox SES) — `plugins/libs/plugin-types/index.d.ts`
- Contexto: `penpot.currentFile`, `currentPage`, `root`, `selection`, `library.local/connected`, `library.local.tokens` (TokenCatalog com sets, themes, resolvedValue), `fonts`, `history.undoBlockBegin/Finish`, `ui.open/sendMessage`.
- Shape: `fills`, `strokes`, `tokens: {property → tokenName}`, `applyToken(token, props)`, `isComponentInstance`, `componentRefShape()`, `layoutChild`, `flex/grid`, `plugin-data` via `getPluginData/setPluginData` e `getSharedPluginData`.
- Eventos: `selectionchange`, `shapechange`, `filechange`, `pagechange`, `contentsave`.
- Permissões de manifest: `content:read/write`, `library:read/write`, `comment:*`, `user:read`, `allow:downloads`, `allow:localstorage`.
- Plugins ficam registrados **por perfil** (`profile.props.plugins`, máx. 50), instalados por URL de `manifest.json`. Não há registro por time/organização.
- Exemplos úteis em `plugins/apps/`: `contrast-plugin` (contraste da seleção), `colors-to-tokens-plugin` (cores de biblioteca → tokens), `poc-tokens-plugin`.
- Limite: os getters da API são proxies sobre o store; percorrer um arquivo grande inteiro por Plugin API é lento e roda na thread da UI.

### 4.2 MCP server — `mcp/`
Servidor TypeScript que fala com um plugin-ponte por WebSocket e executa código JS da Plugin API no arquivo aberto (`execute_code`, `export_shape`, `import_image`, `cljs_repl`). Serve de referência para automação externa e testes.

### 4.3 Backend RPC — `backend/src/app/rpc/commands/*.clj`
Comandos definidos com `sv/defmethod` + schema Malli. Permissões via `app.rpc.permissions` (falha → `:not-found`, não vaza existência). Dados do arquivo ficam em `file_data` como Fressian (`bytea`) — o backend consegue carregar o arquivo inteiro em CLJ e aplicar funções de `common/` sem UI. Isso habilita **auditoria server-side**.

### 4.4 Persistência disponível para analytics
- `audit_log` (particionada por data, `name`, `type`, `profile_id`, `props jsonb`): eventos de RPC. Padrão pronto para registrar "audit executada" e métricas.
- `webhook` + `webhook_delivery` por time: fan-out de eventos para sistema externo.
- `file_change` / `page_version` / `file_data_fragment`: histórico e snapshots → "documentVersion" do brief.
- `job` + `job_event`: substrato de background jobs com progresso. Candidato natural para "audit em lote".

### 4.5 Feature flags — `common/src/app/common/features.cljc`
Flags por arquivo/time (`design-tokens/v1`, `variants/v1`, `plugins/runtime`, `render-wasm/v1`…). Uma flag `governance/v1` seria o jeito idiomático de ligar/desligar o módulo por time.

### 4.6 Handoff existente — `frontend/src/app/main/ui/inspect/` + `app.util.code-gen`
Modo Inspect (viewer e workspace): painéis de atributos (fill, stroke, text, layout, geometry, shadow, variant), geração de CSS/HTML/SVG, anotações de componente, exports. Hoje **não mostra o token aplicado** nos atributos de fill (grep em `inspect/attributes/fill.cljs` não encontra tokens). Esse é o ponto exato onde "handoff + governança" do Proto Care entra: mostrar token, status de conformidade e violações por node no mesmo painel que o dev já usa.

---

## 5. Permissões atuais vs perfis do brief

Penpot tem papéis por **time**: `owner > admin > editor > viewer` (`team_profile_rel`), mais rels por projeto/arquivo. Não existe papel "Design Ops" nem "Admin de Design System".

Mapeamento proposto:

| Brief | Penpot | Como obter |
|---|---|---|
| Designer | `editor` do time | já existe |
| Gestor / Design Ops | `viewer`/`admin` + flag própria | novo campo `governance_role` em `team_profile_rel` ou tabela própria |
| Admin / Design System | `admin`/`owner` + flag própria | idem; gerencia tokens, regras, thresholds |

Regras de autorização devem seguir o padrão `app.rpc.permissions` (fail-closed, `:not-found`).

---

## 6. Mapeamento das regras do MVP para o modelo Penpot

| Regra do brief | Dados usados | Determinismo |
|---|---|---|
| `token/color` | `fills[*].fill-color`, `strokes[*].stroke-color`, `applied-tokens.fill/stroke-color`, `fill-color-ref-id` (style de biblioteca), tokens ativos do tipo `color` | total; match exato de hex (normalizar caixa/alpha) |
| `token/spacing` | `layout-gap`, `layout-padding`, `layout-item-margin`, `applied-tokens.p1..m4/column-gap/row-gap`, tokens `spacing` | total |
| `token/typography` | blocos de texto em `:content` (`font-family`, `font-size`, `font-weight`, `line-height`), `typography-ref-id`, `applied-tokens.font-*`, tokens `typography` | total |
| `token/radius` | `r1..r4`, `applied-tokens.r1..r4`, tokens `borderRadius` | total |
| `component/official` | `component-id` + `component-file` ∈ bibliotecas marcadas como oficiais (`file_library_rel` + lista de arquivos oficiais da org) | total |
| `component/property` | `variant-properties` do componente vs `variant-name` da cópia; `touched` em grupos proibidos | total |
| `accessibility/contrast` | cor efetiva do texto (fill do bloco) vs cor de fundo do ancestral com fill opaco; WCAG 2.x | determinístico, com heurística para fundos com imagem/gradiente |
| `accessibility/text-size` | `font-size` em blocos de texto vs threshold | total |
| `structure/component-usage` | shape "parecida" com componente oficial (mesmo nome/estrutura) | heurístico; fase 2 |

Todas as regras lêem só `common/` → podem rodar no backend (JVM) ou no frontend (JS) com o mesmo código CLJC. Isso casa com a diretriz "sem LLM como fonte de verdade".

---

## 7. Riscos e restrições

1. **Stack**: Clojure/ClojureScript no core. Qualquer modificação do editor (sidebar, inspect, highlight de node) é CLJS. TypeScript só na Plugin API e MCP. Precisa decidir se o time aceita Clojure.
2. **Devenv pesado**: Docker + tmux + JVM + shadow-cljs + Rust. Em Windows, WSL2 obrigatório. Primeira compilação demora.
3. **Texto em migração** (`text-editor/v2`, `text-editor-wasm`): o modelo de `:content` tem duas gerações. Regras de tipografia devem usar os helpers de `app.common.types.text`, não ler o mapa cru.
4. **Plugins são por usuário**, não por organização: não dá para "forçar" um plugin de governança via instalação de plugin; só via fork do frontend ou flag de time.
5. **Volume**: ~6.5k arquivos. Manter um fork sincronizado com upstream (releases mensais) tem custo. Mitigar isolando nosso código em namespaces próprios (`app.governance.*`, `app.main.ui.governance.*`) e tocando o mínimo nos arquivos existentes.

---

## 8. Estratégias possíveis para o Proto Care

### A. Plugin-first (sem fork)
Governance Engine em TypeScript rodando como plugin Penpot. Report em painel de plugin. Fix via `applyToken`. Analytics enviadas para um backend próprio.
- Prós: zero Clojure, independente do upstream, rápido para validar hipótese.
- Contras: não é "nativo" (viola princípio §19 do brief), instalação por usuário, não entra no Inspect, sem permissões de org, performance limitada pela Plugin API.

### B. Fork com módulo de governança nativo (recomendado para o produto)
- `common/src/app/governance/`: regras CLJC puras (`defmulti evaluate` por `rule-id`), contexto de auditoria construído a partir de `file.data` + bibliotecas + tokens ativos.
- `backend/src/app/rpc/commands/governance.clj`: `run-audit`, `get-audit`, `get-project-health`; tabelas `governance_policy`, `audit`, `audit_result`; eventos em `audit_log`; job em background para arquivos grandes.
- `frontend/src/app/main/ui/workspace/governance/`: botão "Run Audit", painel de resultados, filtro, "Go to element" (`select-shapes` + scroll), "Fix" com preview → `apply-token`.
- Inspect: coluna "Token / Conformidade" ao lado do valor.
- Flag `governance/v1`.
- Prós: cumpre o brief inteiro, reaproveita 100% do modelo de dados, pipeline de changes e permissões.
- Contras: Clojure obrigatório; manter fork.

### C. Híbrido (recomendado para o MVP)
Começar com **B em escopo mínimo** (engine em `common/` + RPC + painel), mas **validar as regras primeiro via MCP/Plugin API** usando o devenv agentic antes de escrever UI. O MCP já permite percorrer o arquivo e aplicar tokens, então a vertical slice "Color Token Governance" pode ser provada em dias, e depois promovida para o módulo nativo.

---

## 9. Vertical slice recomendada (Color Token Governance) sobre o Penpot

```
Canvas (workspace)
 ↓
Shape com fills[0].fill-color = "#6750A4" e sem applied-tokens.fill
 ↓ Run Audit (RPC governance/run-audit ou local no frontend)
Governance Engine (common): build-context → evaluate token/color
 ↓
RuleResult {rule-id node-id severity current "#6750A4" expected "color.action.primary" fix-available true}
 ↓ Audit Report (painel lateral, filtros)
Go to element → select-shapes + zoom-to-selected
 ↓ Fix (preview + aprovação)
apply-token (escreve applied-tokens.fill + fills) via changes-builder (undoável)
 ↓ Run Audit de novo → violação some
 ↓ audit_log / tabela audit → Dashboard do gestor
```

---

## 10. Próximos passos sugeridos

1. Decidir entre estratégias A/B/C (afeta linguagem e devenv).
2. Subir o devenv (Docker Desktop + WSL2) e abrir um arquivo com tokens para inspecionar `applied-tokens` real via REPL/MCP.
3. Escrever o esqueleto de `app.governance.rules` em CLJC com a regra `token/color` e testes em `common/test`.
4. Definir o schema de `Audit`/`AuditResult` (seção 14 do brief) e as migrações SQL.
5. Prototipar o painel "Run Audit" no workspace e a integração no Inspect.
