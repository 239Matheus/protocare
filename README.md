# Proto Care

Editor de design com **governança nativa**, construído sobre um fork do [Penpot](https://github.com/penpot/penpot) (MPL-2.0).
O diferencial não é ser mais um editor: é o **Governance Engine**, que audita o arquivo contra o design system (tokens, componentes, acessibilidade), explica cada problema, leva até o elemento, corrige com aprovação e alimenta métricas de saúde do design.

Estado atual (governança ponta a ponta, com evidências):

- 14 regras em CLJC (rodam no backend e no browser), nas cinco áreas:
  - Design system: `token/color`, `token/spacing`, `token/radius`, `token/typography`.
  - Acessibilidade: `accessibility/contrast` (WCAG AA), `accessibility/text-size`, `accessibility/touch-target`.
  - Qualidade da interface: `layout/alignment` e `layout/spacing` (Gestalt: alinhamento e ritmo).
  - Semântica das camadas: `naming/default-name`, `naming/duplicate`.
  - Teste de usabilidade: `usability/broken-link`, `usability/flow`, `usability/dead-end`.
- Auditoria persistida por arquivo, com histórico, status por resultado (`open`, `fixed`, `ignored`) e métrica de resolvidos entre auditorias.
- Interface estilo Figma: barra de ferramentas embaixo e centralizada, com os modos **Design · Protótipo · Inspecionar · Auditoria** como ícones, e trilho vertical de ícones à esquerda (Arquivo, Recursos, Tokens). No dashboard, o menu segue o da home do Figma: perfil no topo, time, itens com ícone e Favoritos.
- Modo **Auditoria**: Executar auditoria, áreas (Design System, Acessibilidade, Qualidade da interface, Semântica das camadas, Teste de usabilidade), filtros, Ir para o elemento (inclusive entre páginas), Corrigir com confirmação e undo, Ignorar. Textos das regras em inglês e português (espanhol usa o inglês por enquanto).
- Badge no Inspect quando uma cor é literal (sem token).
- **Proto Care DS** embutido: design system de exemplo (tokens com temas Light/Dark, componentes e uma tela de teste com erros intencionais), disponível como primeiro modelo em "Bibliotecas e Modelos". Detalhes em `penpot/protocare/design-system/README.md`.
- Política por time (regras, severidade) e papéis `designer`, `design-ops`, `ds-admin`.
- RPC de saúde agregada por projeto e por time (sem ranking de pessoas).
- **Evidências**: trilha `governance_event` (auditorias, achados resolvidos com tempo aberto, mudanças de status, política, instalação do DS) e métricas por área e por semana. Plano completo em `docs/03-analytics-plan.md`.
- Dashboard **Saúde do design** (sidebar do dashboard, seção Governança): visão geral, tendências semanais, Explorar (projetos > arquivos > telas > achados), áreas, regras e pessoas envolvidas, com filtro de projeto, tema do DS (só os usados) e período. Visível para owner/admin, `design-ops` e `ds-admin`.

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

1. Abra `http://localhost:3450` e crie uma conta (ou use **Criar conta de demonstração**). No devenv a verificação de e-mail fica desligada, então a conta entra direto.
2. No dashboard, vá em **Bibliotecas** e clique em **Adicionar ao time** no destaque do Proto Care DS. Ele é importado para Rascunhos e publicado como biblioteca compartilhada. (Também dá para usar o card **Proto Care Design System** em "Bibliotecas e Modelos", na tela de Projetos.) Abra o arquivo "Proto Care DS".
3. Vá para a página **Tela de teste**. Na barra flutuante, clique em **Auditoria** e depois em **Executar auditoria**. Aparecem 20 problemas, 9 corrigíveis, distribuídos nas cinco áreas. Clique numa área para filtrar.
4. Em cada card: o ícone de mira (**Ir para o elemento**) seleciona e centraliza; **Corrigir** abre a confirmação e aplica o token sugerido. `Ctrl+Z` desfaz.
5. Aguarde alguns segundos (salvamento automático) e clique em **Executar auditoria** de novo: os corrigidos somem e o resumo mostra quantos foram resolvidos. Sobram os que pedem decisão de design (contraste, alinhamento, nomes, fluxo).
6. Evidências: cada auditoria, correção e mudança de status vira um evento. No dashboard, abra **Saúde do design** na sidebar para ver as métricas do time. A trilha bruta sai pela RPC `get-governance-events`.
7. No modo **Inspecionar**, cores literais mostram "Valor literal, sem token aplicado".

Para testar num arquivo seu, importe `penpot/protocare/design-system/tokens.json` na aba TOKENS e desenhe usando cores literais e tokens.

## E-mails no devenv

O devenv não envia e-mails reais. Tudo cai no Mailpit, em `http://localhost:1080`. O `tools\devenv.ps1` cria `penpot/backend/scripts/_env.local` (ignorado pelo git) com `disable-email-verification`, para contas locais entrarem sem confirmar e-mail. Contas criadas antes disso podem ser confirmadas pelo link no Mailpit.

## Desenvolvendo no Windows

O Docker Desktop no Windows não repassa eventos de arquivo para o container, então o watch do frontend não percebe edições feitas no host. Depois de editar, rode:

```powershell
tools\devenv.ps1 recompile
```

e recarregue a aba do navegador. Os arquivos `.clj` do backend precisam de restart do backend (janela `backend` do tmux, `Ctrl+C` e `./scripts/start-dev`).

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
