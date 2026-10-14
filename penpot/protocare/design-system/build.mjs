// Proto Care — builds the "Proto Care DS" Penpot file (.penpot) from tokens.json.
//
// Runs inside the devenv container (it needs the compiled @penpot/library):
//
//   cd ~/penpot/library && pnpm install && pnpm run build      # once
//   node ~/penpot/protocare/design-system/build.mjs
//
// Output: backend/resources/app/onboarding/protocare-ds.penpot, served as the
// "Proto Care Design System" builtin template in the dashboard.
//
// Every shape uses tokens (applied tokens + resolved values), except the
// shapes on the "Tela de teste" page that are wrong on purpose so the audit
// has something to find.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import * as penpot from "../../library/target/library/penpot.js";

const here = dirname(fileURLToPath(import.meta.url));
const tokensDoc = JSON.parse(readFileSync(join(here, "tokens.json"), "utf8"));
const outFile = join(here, "../../backend/resources/app/onboarding/protocare-ds.penpot");

// ---------------------------------------------------------------------------
// Token resolution (Mode/Light)

const ACTIVE_SETS = ["Primitives", "Typography", "Semantic - Light"];
const tokens = {};

function collect(node, path) {
  for (const [key, value] of Object.entries(node)) {
    if (key.startsWith("$")) continue;
    if (value && typeof value === "object" && "$value" in value) {
      tokens[[...path, key].join(".")] = value;
    } else if (value && typeof value === "object") {
      collect(value, [...path, key]);
    }
  }
}
for (const set of ACTIVE_SETS) collect(tokensDoc[set], []);

const REF = /^\{([^}]+)\}$/;

function resolve(name, depth = 0) {
  const token = tokens[name];
  if (!token) throw new Error(`unknown token ${name}`);
  if (depth > 16) throw new Error(`reference loop at ${name}`);
  const value = token.$value;
  const match = typeof value === "string" && value.match(REF);
  return match ? resolve(match[1], depth + 1) : value;
}

function resolveValue(value) {
  const match = typeof value === "string" && value.match(REF);
  return match ? resolve(match[1]) : value;
}

const hex = (name) => String(resolve(name)).toLowerCase();
const num = (name) => Number(resolve(name));

function typography(name) {
  const value = resolve(name);
  return {
    size: Number(resolveValue(value.fontSizes)),
    weight: String(resolveValue(value.fontWeights)),
    lineHeight: String(value.lineHeight),
  };
}

// ---------------------------------------------------------------------------
// Shape helpers

const VARIANT = { 400: "regular", 600: "600", 700: "bold" };

function textContent(text, { color, size, weight, lineHeight, align = "left", vAlign = "top" }) {
  const style = {
    fills: [{ fillColor: color, fillOpacity: 1 }],
    fontId: "sourcesanspro",
    fontFamily: "sourcesanspro",
    fontVariantId: VARIANT[weight] ?? "regular",
    fontSize: String(size),
    fontWeight: String(weight),
    fontStyle: "normal",
    lineHeight: String(lineHeight),
    letterSpacing: "0",
    textTransform: "none",
    textAlign: align,
    textDecoration: "none",
    textDirection: "ltr",
  };
  return {
    type: "root",
    verticalAlign: vAlign,
    children: [
      {
        type: "paragraph-set",
        children: [{ type: "paragraph", ...style, children: [{ text, ...style }] }],
      },
    ],
  };
}

const solid = (color) => [{ fillColor: color, fillOpacity: 1 }];

const stroke = (color, width = 1) => [
  { strokeColor: color, strokeOpacity: 1, strokeWidth: width, strokeStyle: "solid", strokeAlignment: "inner" },
];

const radius = (value) => ({ r1: value, r2: value, r3: value, r4: value });

const radiusTokens = (name) => ({ r1: name, r2: name, r3: name, r4: name });

const strokeWidthTokens = (name) => ({
  strokeWidthTop: name,
  strokeWidthRight: name,
  strokeWidthBottom: name,
  strokeWidthLeft: name,
});

/** Tokenized text: color token + typography token. */
function tokenText(name, text, { x, y, width, height, color, typo, align, vAlign, extra = {} }) {
  const t = typography(typo);
  return {
    kind: "text",
    key: name,
    props: {
      name,
      x,
      y,
      width,
      height: height ?? Math.ceil(t.size * Number(t.lineHeight)),
      growType: "fixed",
      content: textContent(text, { color: hex(color), ...t, align, vAlign }),
      appliedTokens: { fill: color, typography: typo },
      ...extra,
    },
  };
}

/** Literal text: no tokens (used for intentional violations). */
function literalText(name, text, { x, y, width, height, color, size, weight = "400", lineHeight = "1.5", align }) {
  return {
    kind: "text",
    key: name,
    props: {
      name,
      x,
      y,
      width,
      height: height ?? Math.ceil(size * Number(lineHeight)),
      growType: "fixed",
      content: textContent(text, { color, size, weight, lineHeight, align }),
    },
  };
}

// ---------------------------------------------------------------------------
// Drawing specs (plain trees, so components can be drawn again as copies)

function draw(ctx, node, { dx = 0, dy = 0, refs = null, ids = {}, rootExtra = null } = {}) {
  const props = { ...node.props, x: node.props.x + dx, y: node.props.y + dy };
  if (refs) props.shapeRef = refs[node.key];
  if (rootExtra) Object.assign(props, rootExtra);

  let id;
  switch (node.kind) {
    case "board":
      id = ctx.addBoard(props);
      for (const child of node.children ?? []) draw(ctx, child, { dx, dy, refs, ids });
      ctx.closeBoard();
      break;
    case "rect":
      id = ctx.addRect(props);
      break;
    case "text":
      id = ctx.addText(props);
      break;
    default:
      throw new Error(`unknown node kind ${node.kind}`);
  }
  ids[node.key] = id;
  return ids;
}

function board(name, props, children = []) {
  return { kind: "board", key: name, props: { name, fills: [], strokes: [], ...props }, children };
}

function rect(name, props) {
  return { kind: "rect", key: name, props: { name, strokes: [], ...props } };
}

function flexRow({ padV, padH, gap = "space.2", justify = "center" }) {
  return {
    layout: "flex",
    layoutFlexDir: "row",
    layoutWrapType: "nowrap",
    layoutAlignItems: "center",
    layoutJustifyContent: justify,
    layoutAlignContent: "stretch",
    layoutGapType: "multiple",
    layoutGap: { rowGap: 0, columnGap: num(gap) },
    layoutPaddingType: "multiple",
    layoutPadding: { p1: num(padV), p2: num(padH), p3: num(padV), p4: num(padH) },
  };
}

const paddingTokens = (padV, padH) => ({ p1: padV, p2: padH, p3: padV, p4: padH });

// ---------------------------------------------------------------------------
// Components

function buttonSpec(variant) {
  const primary = variant === "Primary";
  const bg = primary ? "color.action.primary" : "color.action.secondary";
  const fg = primary ? "color.text.inverse" : "color.action.primary";
  const w = 160;
  const h = num("size.control-md");
  return board(
    `Button / ${variant}`,
    {
      x: 0,
      y: 0,
      width: w,
      height: h,
      fills: solid(hex(bg)),
      ...radius(num("radius.md")),
      ...flexRow({ padV: "space.2", padH: "space.4" }),
      appliedTokens: {
        fill: bg,
        height: "size.control-md",
        ...radiusTokens("radius.md"),
        ...paddingTokens("space.2", "space.4"),
        columnGap: "space.2",
      },
    },
    [
      tokenText("Label", "Continuar", {
        x: num("space.4"),
        y: num("space.2"),
        width: w - 2 * num("space.4"),
        height: h - 2 * num("space.2"),
        color: fg,
        typo: "typography.label.md",
        align: "center",
        vAlign: "center",
        extra: { layoutItemHSizing: "fill", layoutItemVSizing: "fill" },
      }),
    ],
  );
}

function inputSpec() {
  const w = 342;
  const h = num("size.control-md");
  return board(
    "Input / Default",
    {
      x: 0,
      y: 0,
      width: w,
      height: h,
      fills: solid(hex("color.bg.surface")),
      strokes: stroke(hex("color.border.default"), num("border.width.thin")),
      ...radius(num("radius.md")),
      ...flexRow({ padV: "space.2", padH: "space.3", justify: "start" }),
      appliedTokens: {
        fill: "color.bg.surface",
        strokeColor: "color.border.default",
        ...strokeWidthTokens("border.width.thin"),
        height: "size.control-md",
        ...radiusTokens("radius.md"),
        ...paddingTokens("space.2", "space.3"),
      },
    },
    [
      tokenText("Placeholder", "Digite aqui", {
        x: num("space.3"),
        y: num("space.2"),
        width: w - 2 * num("space.3"),
        height: h - 2 * num("space.2"),
        color: "color.text.secondary",
        typo: "typography.body.md",
        vAlign: "center",
        extra: { layoutItemHSizing: "fill", layoutItemVSizing: "fill" },
      }),
    ],
  );
}

function badgeSpec(kind, label) {
  const bg = `color.feedback.${kind}.bg`;
  const fg = `color.feedback.${kind}.fg`;
  const w = 88;
  const h = 24;
  return board(
    `Badge / ${label}`,
    {
      x: 0,
      y: 0,
      width: w,
      height: h,
      fills: solid(hex(bg)),
      ...radius(num("radius.full")),
      ...flexRow({ padV: "space.1", padH: "space.3" }),
      appliedTokens: { fill: bg, ...radiusTokens("radius.full"), ...paddingTokens("space.1", "space.3") },
    },
    [
      tokenText("Label", label, {
        x: num("space.3"),
        y: num("space.1"),
        width: w - 2 * num("space.3"),
        height: h - 2 * num("space.1"),
        color: fg,
        typo: "typography.caption",
        align: "center",
        vAlign: "center",
        extra: { layoutItemHSizing: "fill", layoutItemVSizing: "fill" },
      }),
    ],
  );
}

function cardSpec() {
  const pad = num("space.5");
  const gap = num("space.2");
  const w = 320;
  const inner = w - 2 * pad;
  const title = typography("typography.heading.md");
  const titleH = Math.ceil(title.size * Number(title.lineHeight));
  const bodyH = 48;
  const h = pad + titleH + gap + bodyH + pad;
  return board(
    "Card / Default",
    {
      x: 0,
      y: 0,
      width: w,
      height: h,
      fills: solid(hex("color.bg.surface")),
      strokes: stroke(hex("color.border.default"), num("border.width.thin")),
      ...radius(num("radius.lg")),
      layout: "flex",
      layoutFlexDir: "column",
      layoutWrapType: "nowrap",
      layoutAlignItems: "start",
      layoutJustifyContent: "start",
      layoutAlignContent: "stretch",
      layoutGapType: "multiple",
      layoutGap: { rowGap: gap, columnGap: 0 },
      layoutPaddingType: "simple",
      layoutPadding: { p1: pad, p2: pad, p3: pad, p4: pad },
      appliedTokens: {
        fill: "color.bg.surface",
        strokeColor: "color.border.default",
        ...strokeWidthTokens("border.width.thin"),
        ...radiusTokens("radius.lg"),
        ...paddingTokens("space.5", "space.5"),
        rowGap: "space.2",
      },
    },
    [
      tokenText("Title", "Título do card", {
        x: pad,
        y: pad,
        width: inner,
        height: titleH,
        color: "color.text.primary",
        typo: "typography.heading.md",
        extra: { layoutItemHSizing: "fill" },
      }),
      tokenText("Body", "Texto de apoio do card, com duas linhas de conteúdo para mostrar o estilo.", {
        x: pad,
        y: pad + titleH + gap,
        width: inner,
        height: bodyH,
        color: "color.text.secondary",
        typo: "typography.body.md",
        extra: { layoutItemHSizing: "fill" },
      }),
    ],
  );
}

/** Draws a component main instance and registers the component. */
function addMainComponent(ctx, spec, { x, y, path }) {
  const componentId = ctx.genId();
  const ids = draw(ctx, spec, {
    dx: x,
    dy: y,
    rootExtra: { componentFile: ctx.currentFileId, componentId, componentRoot: true, mainInstance: true },
  });
  const rootId = ids[spec.key];
  const parts = spec.props.name.split(" / ");
  ctx.addComponent({
    componentId,
    fileId: ctx.currentFileId,
    name: parts[parts.length - 1],
    path: parts.slice(0, -1).join(" / ") || path || "",
    frameId: rootId,
  });
  return { componentId, ids, spec };
}

/** Draws a copy of a component. `overrides` patches node props by key. */
function addInstance(ctx, main, { x, y, overrides = {} }) {
  const spec = structuredClone(main.spec);
  (function patch(node) {
    if (overrides[node.key]) Object.assign(node.props, overrides[node.key]);
    (node.children ?? []).forEach(patch);
  })(spec);
  return draw(ctx, spec, {
    dx: x,
    dy: y,
    refs: main.ids,
    rootExtra: { componentFile: ctx.currentFileId, componentId: main.componentId, componentRoot: true },
  });
}

// ---------------------------------------------------------------------------
// Pages

function pageReadme(ctx) {
  ctx.addPage({ name: "Leia-me" });
  const W = 960;
  const x = 48;
  const inner = W - 2 * x;
  let y = 48;
  const children = [];
  const push = (node, height, gap = 12) => {
    children.push(node);
    y += height + gap;
  };

  const lh = (typo) => {
    const t = typography(typo);
    return Math.ceil(t.size * Number(t.lineHeight));
  };

  push(tokenText("Título", "Proto Care DS", { x, y, width: inner, color: "color.text.primary", typo: "typography.display" }), lh("typography.display"), 8);
  push(
    tokenText("Subtítulo", "Design system de exemplo para testar a governança do Proto Care.", {
      x, y, width: inner, color: "color.text.secondary", typo: "typography.body.lg",
    }),
    lh("typography.body.lg"),
    32,
  );

  const section = (title, lines) => {
    push(tokenText(title, title, { x, y, width: inner, color: "color.text.primary", typo: "typography.heading.md" }), lh("typography.heading.md"), 12);
    lines.forEach((line, i) =>
      push(
        tokenText(`${title} ${i + 1}`, line, { x, y, width: inner, color: "color.text.secondary", typo: "typography.body.md" }),
        lh("typography.body.md"),
        i === lines.length - 1 ? 32 : 6,
      ),
    );
  };

  section("O que tem aqui", [
    "• Tokens (aba TOKENS): primitivos, tipografia e cores semânticas, com os temas Mode/Light e Mode/Dark.",
    "• Foundations: cores, espaçamento, raio e tipografia.",
    "• Components: Button, Input, Badge e Card, todos usando tokens.",
    "• Tela de teste: uma tela de login com erros intencionais para a auditoria encontrar.",
  ]);
  section("Como testar", [
    "1. Abra a página Tela de teste.",
    "2. Na barra flutuante, clique em Auditoria e depois em Executar auditoria.",
    "3. Use Ir para o elemento e Corrigir nos problemas encontrados.",
    "4. Para usar o DS em outros arquivos, publique este arquivo como biblioteca compartilhada.",
  ]);

  draw(
    ctx,
    board("Proto Care DS — Leia-me", {
      x: 0,
      y: 0,
      width: W,
      height: y + 16,
      fills: solid(hex("color.bg.surface")),
      ...radius(num("radius.xl")),
      appliedTokens: { fill: "color.bg.surface", ...radiusTokens("radius.xl") },
    }, children),
  );
  ctx.closePage();
}

const SEMANTIC_COLORS = Object.keys(tokens).filter((name) => name.startsWith("color."));

function pageFoundations(ctx) {
  ctx.addPage({ name: "Foundations" });
  // Children use coordinates relative to the section; the whole tree is
  // shifted to (x, y) when drawn.
  const sectionBoard = (name, x, y, width, height, children) =>
    draw(ctx, board(name, {
      x: 0, y: 0, width, height,
      fills: solid(hex("color.bg.surface")),
      ...radius(num("radius.lg")),
      appliedTokens: { fill: "color.bg.surface", ...radiusTokens("radius.lg") },
    }, children), { dx: x, dy: y });

  const heading = (title, x, y) =>
    tokenText(title, title, { x, y, width: 600, color: "color.text.primary", typo: "typography.heading.lg" });

  // Colors
  {
    const cols = 4;
    const colW = 280;
    const rowH = 80;
    const children = [heading("Cores semânticas", 32, 32)];
    SEMANTIC_COLORS.forEach((name, i) => {
      const x = 32 + (i % cols) * colW;
      const y = 104 + Math.floor(i / cols) * rowH;
      children.push(
        rect(`Swatch ${name}`, {
          x, y, width: 56, height: 56,
          fills: solid(hex(name)),
          strokes: stroke(hex("color.border.default"), num("border.width.thin")),
          ...radius(num("radius.md")),
          appliedTokens: {
            fill: name,
            strokeColor: "color.border.default",
            ...strokeWidthTokens("border.width.thin"),
            ...radiusTokens("radius.md"),
          },
        }),
        tokenText(`Nome ${name}`, name, { x: x + 68, y: y + 8, width: 200, color: "color.text.primary", typo: "typography.label.md" }),
        tokenText(`Valor ${name}`, hex(name).toUpperCase(), { x: x + 68, y: y + 30, width: 200, color: "color.text.secondary", typo: "typography.caption" }),
      );
    });
    const rows = Math.ceil(SEMANTIC_COLORS.length / cols);
    sectionBoard("Cores", 0, 0, 32 * 2 + cols * colW - 24, 104 + rows * rowH + 16, children);
  }

  // Spacing
  const spacing = Object.keys(tokens).filter((n) => n.startsWith("space.") && n !== "space.0");
  {
    const children = [heading("Espaçamento", 32, 32)];
    spacing.forEach((name, i) => {
      const y = 104 + i * 32;
      children.push(
        tokenText(`Nome ${name}`, `${name} · ${num(name)}px`, { x: 32, y, width: 180, color: "color.text.primary", typo: "typography.body.sm" }),
        rect(`Barra ${name}`, {
          x: 220, y: y + 4, width: num(name), height: 12,
          fills: solid(hex("color.action.primary")),
          ...radius(num("radius.sm")),
          appliedTokens: { fill: "color.action.primary", width: name, ...radiusTokens("radius.sm") },
        }),
      );
    });
    sectionBoard("Espaçamento", 0, 640, 520, 104 + spacing.length * 32 + 24, children);
  }

  // Radius
  const radii = Object.keys(tokens).filter((n) => n.startsWith("radius."));
  {
    const children = [heading("Raio", 32, 32)];
    radii.forEach((name, i) => {
      const x = 32 + i * 104;
      children.push(
        rect(`Raio ${name}`, {
          x, y: 104, width: 72, height: 72,
          fills: solid(hex("color.action.secondary")),
          strokes: stroke(hex("color.border.focus"), num("border.width.thick")),
          ...radius(Math.min(num(name), 36)),
          appliedTokens: {
            fill: "color.action.secondary",
            strokeColor: "color.border.focus",
            ...strokeWidthTokens("border.width.thick"),
            ...radiusTokens(name),
          },
        }),
        tokenText(`Nome ${name}`, name, { x, y: 188, width: 96, color: "color.text.primary", typo: "typography.caption" }),
      );
    });
    sectionBoard("Raio", 560, 640, 32 * 2 + radii.length * 104 - 32, 232, children);
  }

  // Typography
  const typos = Object.keys(tokens).filter((n) => n.startsWith("typography."));
  {
    const children = [heading("Tipografia", 32, 32)];
    let y = 104;
    typos.forEach((name) => {
      const t = typography(name);
      const h = Math.ceil(t.size * Number(t.lineHeight));
      children.push(
        tokenText(`Nome ${name}`, `${name} · ${t.size}/${t.lineHeight} · ${t.weight}`, {
          x: 32, y, width: 320, color: "color.text.secondary", typo: "typography.caption",
        }),
        tokenText(`Amostra ${name}`, "A governança faz parte do design", {
          x: 360, y, width: 700, height: h, color: "color.text.primary", typo: name,
        }),
      );
      y += Math.max(h, 24) + 20;
    });
    sectionBoard("Tipografia", 0, 1200, 1100, y + 12, children);
  }
  ctx.closePage();
}

function pageComponents(ctx) {
  ctx.addPage({ name: "Components" });
  const mains = {
    buttonPrimary: addMainComponent(ctx, buttonSpec("Primary"), { x: 0, y: 0 }),
    buttonSecondary: addMainComponent(ctx, buttonSpec("Secondary"), { x: 200, y: 0 }),
    input: addMainComponent(ctx, inputSpec(), { x: 0, y: 96 }),
    badgeSuccess: addMainComponent(ctx, badgeSpec("success", "Sucesso"), { x: 0, y: 192 }),
    badgeError: addMainComponent(ctx, badgeSpec("error", "Erro"), { x: 120, y: 192 }),
    badgeWarning: addMainComponent(ctx, badgeSpec("warning", "Atenção"), { x: 240, y: 192 }),
    badgeInfo: addMainComponent(ctx, badgeSpec("info", "Info"), { x: 360, y: 192 }),
    card: addMainComponent(ctx, cardSpec(), { x: 0, y: 272 }),
  };
  ctx.closePage();
  return mains;
}

function pageTestScreen(ctx, mains) {
  ctx.addPage({ name: "Tela de teste" });

  const W = 390;
  const H = 844;
  const x = 24;
  const inner = W - 2 * x;

  ctx.addBoard({
    name: "Login — mobile",
    x: 0,
    y: 0,
    width: W,
    height: H,
    fills: solid(hex("color.bg.canvas")),
    strokes: [],
    appliedTokens: { fill: "color.bg.canvas" },
  });

  const add = (node) => draw(ctx, node);

  // Correct usage: tokens and component instances
  add(tokenText("Título", "Entrar", { x, y: 96, width: inner, color: "color.text.primary", typo: "typography.heading.lg" }));
  add(tokenText("Subtítulo", "Acesse sua conta Proto Care", { x, y: 144, width: inner, color: "color.text.secondary", typo: "typography.body.md" }));
  add(tokenText("Rótulo e-mail", "E-mail", { x, y: 200, width: inner, color: "color.text.primary", typo: "typography.label.md" }));
  addInstance(ctx, mains.input, { x, y: 222, overrides: { Placeholder: { content: textContent("seu@email.com", { color: hex("color.text.secondary"), ...typography("typography.body.md"), vAlign: "center" }), touched: ["text-content-group", "geometry-group"] } } });
  add(tokenText("Rótulo senha", "Senha", { x, y: 278, width: inner, color: "color.text.primary", typo: "typography.label.md" }));
  addInstance(ctx, mains.input, { x, y: 300, overrides: { Placeholder: { content: textContent("••••••••", { color: hex("color.text.secondary"), ...typography("typography.body.md"), vAlign: "center" }), touched: ["text-content-group", "geometry-group"] } } });

  // Violation 1: link with a literal color that matches color.text.link
  add(literalText("Link esqueci minha senha", "Esqueci minha senha", { x, y: 352, width: 200, color: "#6d28d9", size: 14 }));

  addInstance(ctx, mains.buttonPrimary, {
    x,
    y: 400,
    overrides: {
      "Button / Primary": { width: inner, touched: ["geometry-group"] },
      Label: { width: inner - 2 * num("space.4"), touched: ["geometry-group"] },
    },
  });

  // Violation 2: divider with a literal color that matches color.border.default
  add(rect("Divisor", { x, y: 464, width: inner, height: 1, fills: solid("#e5e7eb") }));

  // Violations 3 and 4: button drawn by hand instead of the component
  add(
    board("Botão criar conta (desenhado à mão)", {
      x, y: 489, width: inner, height: 40,
      fills: solid("#7c3aed"),
      ...radius(8),
    }, [
      literalText("Texto criar conta", "Criar conta", {
        x: x + 16, y: 489 + 8, width: inner - 32, height: 24, color: "#ffffff", size: 14, weight: "600", lineHeight: "1.2", align: "center",
      }),
    ]),
  );

  // Violation 5: a color that does not exist in the design system
  add(literalText("Termos de uso", "Ao continuar, você aceita os Termos de uso.", { x, y: 780, width: inner, color: "#333333", size: 14, align: "center" }));

  ctx.closeBoard();

  // Note explaining the intentional errors (tokenized, so it adds no noise)
  const noteLines = [
    "1. Link com a cor literal #6D28D9 (token: color.text.link)",
    "2. Divisor com a cor literal #E5E7EB (token: color.border.default)",
    "3. Botão desenhado à mão com #7C3AED (token: color.action.primary)",
    "4. Texto do botão com #FFFFFF (token: color.text.inverse)",
    "5. Texto com #333333, uma cor fora do design system",
  ];
  const noteW = 480;
  const lineH = Math.ceil(14 * 1.5);
  const children = [
    tokenText("Título da nota", "Erros intencionais para a auditoria", {
      x: 24, y: 24, width: noteW - 48, color: "color.feedback.warning.fg", typo: "typography.label.md",
    }),
    ...noteLines.map((line, i) =>
      tokenText(`Erro ${i + 1}`, line, {
        x: 24, y: 56 + i * (lineH + 4), width: noteW - 48, color: "color.feedback.warning.fg", typo: "typography.body.sm",
      }),
    ),
  ];
  draw(
    ctx,
    board("Nota — erros intencionais", {
      x: 0,
      y: 0,
      width: noteW,
      height: 56 + noteLines.length * (lineH + 4) + 20,
      fills: solid(hex("color.feedback.warning.bg")),
      ...radius(num("radius.lg")),
      appliedTokens: { fill: "color.feedback.warning.bg", ...radiusTokens("radius.lg") },
    }, children),
    { dx: 440, dy: 0 },
  );

  ctx.closePage();
}

// ---------------------------------------------------------------------------
// Build

const ctx = penpot.createBuildContext({ referer: "protocare" });
ctx.addFile({ name: "Proto Care DS" });
ctx.addTokensLib(tokensDoc);

pageReadme(ctx);
pageFoundations(ctx);
const mains = pageComponents(ctx);
pageTestScreen(ctx, mains);

ctx.closeFile();

const bytes = await penpot.exportAsBytes(ctx);
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, bytes);
console.log(`written ${outFile} (${bytes.length} bytes)`);
