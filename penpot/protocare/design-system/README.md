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
| Tela de teste | Tela de login com 5 erros intencionais e uma nota explicando cada um. |

Tokens (aba TOKENS), no formato DTCG de `tokens.json`:

- **Primitives**: paleta (`palette.purple.*`, `palette.gray.*`, verde, vermelho, âmbar, azul), `space.0` a `space.8`, `radius.*`, `size.*`, `font.*`, `border.width.*`, `opacity.disabled`.
- **Typography**: `typography.display`, `heading.lg`, `heading.md`, `body.lg`, `body.md`, `body.sm`, `label.md`, `caption`.
- **Semantic - Light** e **Semantic - Dark**: `color.bg.*`, `color.text.*`, `color.action.*`, `color.border.*`, `color.feedback.*`.
- Temas: `Mode/Light` (ativo) e `Mode/Dark`.

Tudo usa tokens, exceto os erros intencionais da Tela de teste. Por isso a auditoria do arquivo recém-importado encontra exatamente estes 5 problemas:

| Elemento | Cor literal | Sugestão |
|---|---|---|
| Link "Esqueci minha senha" | `#6D28D9` | `color.text.link` |
| Divisor | `#E5E7EB` | `color.border.default` |
| Botão "Criar conta" desenhado à mão | `#7C3AED` | `color.action.primary` |
| Texto do botão "Criar conta" | `#FFFFFF` | `color.text.inverse` |
| Texto "Termos de uso" | `#333333` | nenhuma (fora do DS) |

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
