# Proto Care DS

Design system de exemplo que vem dentro do Proto Care para testar a governança.
Duas formas de colocar no time:

- Tela **Bibliotecas** do dashboard: botão **Adicionar ao time** (comando `install-design-system`). Importa para Rascunhos e publica como biblioteca compartilhada; se já existir, só garante que está compartilhado.
- Card **Proto Care Design System** em "Bibliotecas e Modelos" (tela de Projetos): importa como arquivo comum.

## O que tem

| Página | Conteúdo |
|---|---|
| Leia-me | O que é o DS e como testar. |
| Foundations | Cores semânticas, espaçamento, raio e tipografia, todos com tokens aplicados. |
| Components | Button (Primary, Secondary), Input, Badge (Sucesso, Erro, Atenção, Info) e Card. |
| Tela de teste | Tela de login com erros intencionais nas cinco áreas da auditoria, a tela "Recuperar senha" e uma nota explicando cada erro. |

Tokens (aba TOKENS), no formato DTCG de `tokens.json`:

- **Primitives**: paleta (`palette.purple.*`, `palette.gray.*`, verde, vermelho, âmbar, azul), `space.0` a `space.8`, `radius.*`, `size.*`, `font.*`, `border.width.*`, `opacity.disabled`.
- **Typography**: `typography.display`, `heading.lg`, `heading.md`, `body.lg`, `body.md`, `body.sm`, `label.md`, `caption`.
- **Semantic - Light** e **Semantic - Dark**: `color.bg.*`, `color.text.*`, `color.action.*`, `color.border.*`, `color.feedback.*`.
- Temas: `Mode/Light` (ativo) e `Mode/Dark`.

Tudo usa tokens e um ritmo de espaçamento consistente, exceto os erros intencionais da Tela de teste. Por isso a auditoria do arquivo recém-importado encontra exatamente estes 20 problemas, 9 corrigíveis com um clique:

| Área | Regra | Elemento | Problema | Corrigível |
|---|---|---|---|---|
| Design system | `token/color` | Link "Esqueci minha senha" | `#6D28D9` literal (`color.text.link`) | sim |
| Design system | `token/color` | Divisor | `#E5E7EB` literal (`color.border.default`) | sim |
| Design system | `token/color` | Botão "Criar conta" desenhado à mão | `#7C3AED` literal (`color.action.primary`) | sim |
| Design system | `token/color` | Texto do botão "Criar conta" | `#FFFFFF` literal (`color.text.inverse`) | sim |
| Design system | `token/color` | Texto "Versão" | `#9CA3AF` literal (`color.text.disabled`) | sim |
| Design system | `token/color` | Texto "Termos de uso" | `#333333`, fora do DS | não |
| Design system | `token/radius` | Botão "Criar conta" desenhado à mão | raio 8 literal (`radius.md`) | sim |
| Design system | `token/typography` | Link, "Termos de uso", texto do botão | estilo de texto literal igual a um token | sim (3) |
| Design system | `token/typography` | Texto "Versão" | 10px, sem token equivalente | não |
| Acessibilidade | `accessibility/contrast` | Texto "Versão" | contraste 2,43:1 (mínimo 4,5:1) | não |
| Acessibilidade | `accessibility/text-size` | Texto "Versão" | 10px (mínimo 12px) | não |
| Acessibilidade | `accessibility/touch-target` | Link "Esqueci minha senha" | 200x21 (mínimo 24x24) | não |
| Qualidade da interface | `layout/alignment` | "Rótulo senha" | 2px fora da borda esquerda da coluna | não |
| Qualidade da interface | `layout/spacing` | Campo senha | espaço de 6px onde o resto usa 8px | não |
| Semântica das camadas | `naming/default-name` | Barra do topo | nome padrão "Rectangle" | não |
| Semântica das camadas | `naming/duplicate` | Campo senha | mesmo nome do campo e-mail ("Input / Default") | não |
| Teste de usabilidade | `usability/flow` | "Login — mobile" | a página tem navegação mas nenhum fluxo | não |
| Teste de usabilidade | `usability/dead-end` | "Recuperar senha — mobile" | a tela não tem saída | não |

Corrigir o `#9CA3AF` com `color.text.disabled` resolve o achado de design system, mas não o de contraste: o token certo para o texto é outro. É um bom exemplo de por que as áreas são auditadas em separado.

## Usar os tokens em outro arquivo

Na aba TOKENS de qualquer arquivo, use **Importar** e escolha `tokens.json`. Ou publique o arquivo "Proto Care DS" como biblioteca compartilhada e conecte-o aos outros arquivos.

## Regenerar

O arquivo `.penpot` é gerado a partir de `tokens.json` por `build.mjs`, com o construtor oficial `@penpot/library` do próprio fork. Rode dentro do container do devenv:

```bash
cd ~/penpot/library && pnpm install && pnpm run build
```

```bash
node ~/penpot/protocare/design-system/build.mjs
```

Saída: `backend/resources/app/onboarding/protocare-ds.penpot`, servido como modelo embutido (entrada `protocare-ds` em `backend/resources/app/onboarding.edn`). A miniatura do card vem de `thumbnail.py`:

```bash
python thumbnail.py
```
