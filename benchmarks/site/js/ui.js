// DOM helpers shared by the pages: element builder, inline markup, sortable tables, tooltip, language colours, data loading.
import { esc } from "./svg.js";

export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === false || v == null) continue;
    if (k === "html") el.innerHTML = v; else if (k === "text") el.textContent = v; else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null) el.append(kid.nodeType ? kid : document.createTextNode(kid));
  return el;
}

/** The inline markup used in the JSON texts: **bold**, _italic_, `code`. Everything else is escaped. */
export function inline(text) {
  return esc(text).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>").replace(/(^|[\s(])_([^_]+)_(?=[\s).,;:]|$)/g, "$1<i>$2</i>");
}

export async function getJson(path) {
  const r = await fetch(path);
  if (!r.ok) throw new Error(`${path}: ${r.status}`);
  return r.json();
}

/** One <style> element: the language colours as custom properties (light and dark) and the .k-<key> classes. */
export function installLanguageColours(cfg) {
  const light = cfg.languages.map(l => `--lang-${l.key}:${l.light};`).join("");
  const dark = cfg.languages.map(l => `--lang-${l.key}:${l.dark};`).join("");
  const cls = cfg.languages.map(l => `.k-${l.key}{--c:var(--lang-${l.key})}`).join("");
  document.head.append(h("style", { text: `:root{${light}}@media (prefers-color-scheme:dark){:root{${dark}}}${cls}` }));
}

/** The hatch pattern used for "optimisations off" bars; one hidden SVG per page, referenced by every chart. */
export function hatchDefs() {
  return h("div", { html: '<svg width="0" height="0" style="position:absolute"><defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="var(--off-fill)"/><line x1="0" y1="0" x2="0" y2="6" style="stroke:var(--lang-caspien)" stroke-width="2.4"/></pattern></defs></svg>' });
}

// ---- tooltip: one handler for every chart; the text is computed from the data when the mouse is over a row -----------------------------------
export function installTooltip(tipOf) {
  const tip = document.getElementById("tip");
  document.addEventListener("mousemove", e => {
    const row = e.target.closest && e.target.closest("g.row[data-i]");
    const text = row && tipOf(row);
    if (!text) { tip.style.display = "none"; return; }
    tip.textContent = text; tip.style.display = "block";
    tip.style.left = Math.min(e.clientX + 14, window.innerWidth - 220) + "px"; tip.style.top = e.clientY + 14 + "px";
  });
}

// ---- sortable table -----------------------------------------------------------------------------------------------------------------------------
/** cols: [{label, type: "text"|"num"}]; rows: [[{sort, html?, text?, cls?}]]. Click a header to sort, click again to reverse. */
export function sortableTable(cols, rows, { sticky = false, resettable = false } = {}) {
  const table = h("table", { class: "sortable" + (sticky ? " ov" : "") });
  const head = h("tr");
  cols.forEach((c, ci) => head.append(h("th", { "data-type": c.type, class: c.type === "num" ? "n" : null }, h("button", { type: "button", text: c.label, "data-c": ci }))));
  table.append(h("thead", {}, head));
  const body = h("tbody");
  rows.forEach((cells, i) => {
    const tr = h("tr", { "data-i": i });
    for (const c of cells) tr.append(h("td", { class: c.cls || null, "data-v": c.sort == null ? "" : c.sort, html: c.html, text: c.html == null ? c.text : null }));
    body.append(tr);
  });
  table.append(body);
  const heads = [...head.children];
  const sortBy = (ci, asc, text) => {
    const trs = [...body.rows];
    trs.sort((a, b) => {
      const x = a.cells[ci].dataset.v, y = b.cells[ci].dataset.v;
      if (text) return asc ? x.localeCompare(y) : y.localeCompare(x);
      const nx = x === "" ? NaN : parseFloat(x), ny = y === "" ? NaN : parseFloat(y);
      if (isNaN(nx) && isNaN(ny)) return 0; if (isNaN(nx)) return 1; if (isNaN(ny)) return -1;
      return asc ? nx - ny : ny - nx;
    });
    trs.forEach(r => body.append(r));
  };
  heads.forEach((th, ci) => th.querySelector("button").addEventListener("click", () => {
    const asc = th.getAttribute("aria-sort") !== "ascending";
    heads.forEach(x => x.removeAttribute("aria-sort"));
    th.setAttribute("aria-sort", asc ? "ascending" : "descending");
    sortBy(ci, asc, th.dataset.type === "text");
  }));
  const wrap = h("div", { class: "scroll" }, table);
  if (!resettable) return wrap;
  const reset = h("button", { type: "button", class: "reset", text: "Reset order" });
  reset.addEventListener("click", () => {
    heads.forEach(x => x.removeAttribute("aria-sort"));
    [...body.rows].sort((a, b) => a.dataset.i - b.dataset.i).forEach(r => body.append(r));
  });
  return h("div", {}, wrap, h("p", { class: "note" }, reset));
}

export const swatch = (key, off) => `<span class="sw k-${key}${off ? " off" : ""}"></span>`;
