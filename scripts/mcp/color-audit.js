// Proto Care — validação da regra token/color via Penpot Plugin API.
//
// Uso: colar no `execute_code` do Penpot MCP (ou num plugin) com um arquivo
// aberto que tenha tokens de cor. Produz a mesma lista de violações que
// `app.governance.rules.token-color` deve produzir para comparação.
//
// Não modifica o documento. Para aplicar o fix de um resultado:
//   shape.applyToken(token, ['fill'])   // ou ['stroke-color']

function normalizeColor(value) {
  if (typeof value !== 'string') return null;
  const v = value.trim();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.exec(v);
  if (hex) {
    let body = hex[1];
    if (body.length === 3) body = body.split('').map((c) => c + c).join('');
    return '#' + body.slice(0, 6).toLowerCase();
  }
  const rgb = /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*[0-9.]+\s*)?\)$/.exec(v);
  if (rgb) {
    const h = (n) => Number(n).toString(16).padStart(2, '0');
    return '#' + h(rgb[1]) + h(rgb[2]) + h(rgb[3]);
  }
  return null;
}

// Index: normalized hex -> sorted token names (active sets only)
function buildColorIndex() {
  const catalog = penpot.library.local.tokens;
  const index = {};
  const activeSets = catalog.sets.filter((s) => s.active);
  for (const set of activeSets) {
    for (const token of set.tokens) {
      if (token.type !== 'color') continue;
      const hex = normalizeColor(token.resolvedValue ?? token.value);
      if (!hex) continue;
      (index[hex] ||= []).push(token.name);
    }
  }
  for (const k of Object.keys(index)) index[k] = [...new Set(index[k])].sort();
  return index;
}

function* walk(shape) {
  yield shape;
  if (shape.children) for (const c of shape.children) yield* walk(c);
}

function slotsOf(shape) {
  const slots = [];
  const fills = shape.type === 'text' ? (shape.fills || []) : (shape.fills || []);
  for (const f of fills) {
    if (f.fillColor && !f.fillColorGradient && !f.fillImage) {
      slots.push({ attribute: 'fill', raw: f.fillColor, color: normalizeColor(f.fillColor), refId: f.fillColorRefId });
    }
  }
  for (const s of shape.strokes || []) {
    if (s.strokeColor) {
      slots.push({ attribute: 'stroke-color', raw: s.strokeColor, color: normalizeColor(s.strokeColor), refId: s.strokeColorRefId });
    }
  }
  return slots;
}

function audit({ allowLibraryColors = true } = {}) {
  const index = buildColorIndex();
  const results = [];
  let checked = 0, governed = 0;
  for (const page of penpot.currentFile.pages) {
    for (const shape of walk(page.root)) {
      if (shape === page.root) continue;
      const applied = shape.tokens || {};
      const seen = new Set();
      for (const slot of slotsOf(shape)) {
        checked++;
        const isGoverned = applied[slot.attribute] || (allowLibraryColors && slot.refId);
        if (isGoverned) { governed++; continue; }
        if (!slot.color) continue;
        const key = slot.attribute + '|' + slot.color;
        if (seen.has(key)) continue;
        seen.add(key);
        const candidates = index[slot.color] || [];
        results.push({
          ruleId: 'token/color',
          pageId: page.id,
          nodeId: shape.id,
          nodeName: shape.name,
          attribute: slot.attribute,
          currentValue: slot.raw,
          expectedValue: candidates[0] ?? null,
          candidates,
          fixAvailable: candidates.length > 0,
        });
      }
    }
  }
  return {
    summary: { total: results.length, fixable: results.filter((r) => r.fixAvailable).length, checked, governed, adherence: checked ? governed / checked : 1 },
    results,
  };
}

audit();
