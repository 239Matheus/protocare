# Proto Care

Editor de design com **governança nativa**, construído sobre um fork do [Penpot](https://github.com/penpot/penpot) (MPL-2.0).
O diferencial não é ser mais um editor: é o **Governance Engine**, que audita o arquivo contra o design system (tokens, componentes, acessibilidade), explica cada problema, leva até o elemento, corrige com aprovação e alimenta métricas de saúde do design.

Estado atual (vertical slice **Color Token Governance**, ponta a ponta):

- Regra `token/color` em CLJC (roda no backend e no browser).
- Auditoria persistida por arquivo, com histórico, status por resultado (`open`, `fixed`, `ignored`) e métrica de resolvidos entre auditorias.
- Painel **Governance** no workspace: Run audit, filtros, Go to element (inclusive entre páginas), Fix com confirmação e undo, Ignore.
- Badge no Inspect quando uma cor é literal (sem token).
- Política por time (regras, severidade) e papéis `designer`, `design-ops`, `ds-admin`.
- RPC de saúde agregada por projeto e por time (sem ranking de pessoas).

## Layout do repositório

| Caminho | Conteúdo |
|---|---|
| `penpot/` | Fork do Penpot 2.17 (commit base em `docs/02-hybrid-plan.md`). Código próprio em `app.governance.*`, `app.rpc.commands.governance`, `app.main.data.workspace.governance`, `app.main.ui.workspace.governance`. |
| `docs/` | Brief, análise de arquitetura do Penpot e plano por fases. |
| `tools/` | Scripts para Windows: devenv, infra de testes, testes de `common` e `backend`. |
| `scripts/mcp/` | Script de referência da regra de cor para a Plugin API do Penpot. |

## Pré-requisitos (Windows)

1. **Docker Desktop** (backend WSL2).
2. **Git for Windows** (os scripts `.ps1` chamam o Git Bash para o `manage.sh` do Penpot).
3. Toolchain portátil em `tools/` (não versionado, ~250 MB):
   - JDK 25 Temurin descompactado em `tools/jdk/jdk-25.*`
     (`https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk`).
   - `deps.exe` do [deps.clj](https://github.com/borkdude/deps.clj/releases) em `tools/deps-clj/`.
   Só são necessários para rodar os testes do módulo `common` no host. O app completo roda dentro do container.

Primeira vez: o clone precisa estar em **LF**. Se o Git do sistema usa `core.autocrlf=true`, rode `git config core.autocrlf false` antes de clonar, ou clone com `git -c core.autocrlf=false clone ...`.

## Subir o app (devenv do Penpot)

```powershell
tools\devenv.ps1 up
```

Isso sobe a infra (Postgres, Valkey, RustFS, Mailpit, LDAP) e o container principal. Dentro dele uma sessão tmux instala dependências, compila o renderer WASM, o frontend (shadow-cljs) e inicia o backend. A primeira vez leva de 20 a 40 minutos; depois, poucos minutos.

Acompanhe com:

```powershell
tools\devenv.ps1 status
```

O app está pronto quando o backend logar `welcome to penpot` e o frontend `[:main] Build completed`.

- UI: `http://localhost:3450` (ou `https://localhost:3449`, certificado autoassinado).
- O script cria `penpot/frontend/resources/public/js/config.js` com as flags `enable-governance enable-demo-users disable-onboarding`.

## Testar o fluxo de governança

1. Abra `http://localhost:3450`, clique em **Criar uma conta** e depois em **Criar conta de demonstração**.
2. No dashboard, em "Bibliotecas e Modelos", adicione **Design tokens starter kit** e abra o arquivo.
3. Desenhe um retângulo (tecla `R`) e, no painel Design, dê a ele a cor `#087EB4` (valor do token `blue.500`).
4. No canto superior direito, clique no ícone de alerta (**Governance**) e em **Run audit**.
5. No card do retângulo: **Go to element** seleciona e centraliza; **Fix** abre a confirmação e aplica o token `blue.500`. `Ctrl+Z` desfaz.
6. Clique em **Run audit** de novo: a violação some e o resumo mostra uma a menos.
7. Na aba **Inspecionar**, uma cor literal mostra "Literal value, no token applied"; a corrigida mostra o token.

## Rodar os testes

Engine (módulo `common`), no host:

```powershell
tools\common-test.ps1 --focus common-tests.governance.token-color-test
```

```powershell
tools\common-test-js.ps1 --focus common-tests.governance.token-color-test
```

Backend (precisa de Postgres e Valkey; roda num container Linux):

```powershell
tools\infra-up.ps1
```

```powershell
tools\backend-test-docker.ps1 --focus backend-tests.rpc-governance-test
```

## Sincronizar com o Penpot upstream

O fork guarda o commit base do Penpot em `docs/02-hybrid-plan.md`. O diretório `.git` original do clone fica em `penpot/.git-upstream/` na máquina de desenvolvimento (ignorado). Para trazer uma release nova, use `git merge -s subtree` a partir de um remote `upstream`, ou refaça o overlay a partir de um clone limpo mantendo os namespaces `app.governance.*`.

## Licença

O código do Penpot permanece sob MPL-2.0. Os arquivos novos do Proto Care seguem a mesma licença por enquanto.
