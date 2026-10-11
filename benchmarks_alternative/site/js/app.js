// The benchmark page: loads data/, builds every section. Bar charts and the "all numbers" tables are drawn when their <details> is opened.
import * as M from "./model.js";
import { barChart, tipText } from "./svg.js";
import { getJson, h, hatchDefs, inline, installLanguageColours, installTooltip, sortableTable, swatch } from "./ui.js";

const LOAD = async () => {
  const [cfg, catalogue, notes, rustSafe, compile, recursion] = await Promise.all([
    getJson("languages.json"), getJson("data/index.json"), getJson("data/notes.json"), getJson("data/rust_safe.json"),
    getJson("data/compile_times.json").catch(() => null), getJson("data/recursion.json").catch(() => null)]);
  const files = {};
  await Promise.all(catalogue.programs.map(async p => { files[p.id] = await getJson(`data/${p.id}.json`); }));
  return { D: M.prepare(cfg, catalogue, files, rustSafe), notes, compile, recursion };
};

const num = (v, text, extra = "", bad = false) => ({ sort: v == null ? "" : v, html: text + extra, cls: "n" + (bad ? " bad" : "") });
const heading = (tag, title, sub) => h(tag, {}, title, sub ? h("span", { class: "nn", text: sub }) : null);

// ---- overview tables -------------------------------------------------------------------------------------------------------------------------------
function overviewTable(D, metric, safe) {
  const ov = M.overview(D, metric, safe);
  const cols = [{ label: "Implementation", type: "text" }, { label: "Group", type: "text" }, ...ov.columns.map(c => ({ label: c, type: "num" })), { label: "Geomean vs C -O2", type: "num" }];
  const rows = ov.rows.map(r => [
    { sort: r.label, html: swatch(r.key, r.off) + inline(r.label) },
    { sort: r.group, text: r.group },
    ...r.cells.map(c => c === null ? { sort: "", text: "-", cls: "n" }
      : num(c.v, c.text, c.ratio ? `  <span class="grp">${c.lower ? "&ge;" : ""}${c.ratio.toFixed(1)}×</span>` : "", c.bad)),
    { sort: r.gm == null ? "" : r.gm.toFixed(4), html: `<b>${r.gm == null ? "-" : (r.gmLower ? "&ge;" : "") + r.gm.toFixed(2) + "×"}</b>`, cls: "n" },
  ]);
  const t = sortableTable(cols, rows, { sticky: true, resettable: true });
  t.querySelector("p.note").append("Click a column header to sort by it (click again to reverse). The grey figure is the ratio to C -O2 on the same program; the last column is the geometric mean of those ratios over the programs where both exist. C, C++ and Rust: the free build. A red value means the output differs from the C reference.");
  return t;
}

// ---- bar charts (drawn on demand) ----------------------------------------------------------------------------------------------------------------
function chartBox(D, R, metric, title) {
  const { svg, rows } = barChart(D, R, metric, title);
  const box = h("div", { class: "scroll", html: svg.replace("<svg ", '<svg class="chart" ') });
  box._chart = { rows, metric, title, D };
  return box;
}

function metricSection(D, m) {
  const sec = h("div", { class: "metric", id: m.slug });
  sec.append(heading("h2", m.title, m.note));
  sec.append(h("section", {}, heading("h3", "Every language on every program"), overviewTable(D, m.id, false)));
  sec.append(h("section", {}, heading("h3", "Memory-safe only", "safe Caspien variants and memory-safe languages"), overviewTable(D, m.id, true)));
  const details = h("details", { class: "charts" }, h("summary", { text: "Bar charts, one per program" }));
  details.addEventListener("toggle", () => {
    if (!details.open || details.dataset.built) return;
    details.dataset.built = "1";
    details.append(h("nav", { class: "sub2" }, D.programs.map(p => h("a", { href: `#${m.slug}-${p.id}`, text: p.title }))));
    for (const p of D.programs) {
      const R = p.results; const Rs = R.filter(r => M.safeRow(D, p.id, r));
      const dropped = R.some(r => D.safeKeys.has(r.key) && !M.safeRow(D, p.id, r));
      details.append(h("section", { class: "chart", id: `${m.slug}-${p.id}` },
        heading("h3", p.title, M.countText(p)), chartBox(D, R, m.id, `${m.title} – ${p.title}`),
        heading("h4", "Same chart, memory-safe only", (dropped ? "Rust left out for this program: rustc rejects the port under -F unsafe_code. " : "") + "safe Caspien variants against implementations whose runtime or compiler provides memory safety"),
        chartBox(D, Rs, m.id, `${m.title} – ${p.title} (memory-safe only)`)));
    }
  });
  sec.append(details);
  return sec;
}

// ---- Caspien variants, programs, all numbers -------------------------------------------------------------------------------------------------------
function caspienSection(D) {
  const sec = h("div", { class: "metric", id: "caspien" }, heading("h2", "Caspien variants", "naive against optimized, optimisations off against everything on"));
  const cols = [["Caspien version", "text"], ["Optimisations", "text"], ["Time", "num"], ["Memory", "num"], ["Compile", "num"], ["Size", "num"], ["Time vs C -O2", "num"]].map(([label, type]) => ({ label, type }));
  for (const p of D.programs.filter(p => p.overview !== false)) {
    const rows = M.caspienSummary(p).map(s => [
      { sort: s.kind, text: s.kind }, { sort: s.mode, text: s.mode },
      num(s.r.time_s, M.fmt("time_s", s.r.time_s)), num(s.r.rss_kb, M.fmt("rss_kb", s.r.rss_kb)), num(s.r.compile_s, M.fmt("compile_s", s.r.compile_s)),
      num(s.r.size_bytes, M.fmt("size_bytes", s.r.size_bytes)),
      { sort: s.ratio == null ? "" : s.ratio.toFixed(4), text: s.ratio == null ? "-" : s.ratio.toFixed(1) + "×", cls: "n" }]);
    sec.append(h("section", { id: `p-${p.id}` }, heading("h3", p.title, M.countText(p)), h("p", { class: "note", text: `${p.what}. Time is the fastest of ${p.runs} runs.` }), sortableTable(cols, rows)));
  }
  return sec;
}

function programsSection(D) {
  const rows = D.programs.map(p => h("tr", {}, h("td", {}, h("a", { href: `#p-${p.id}`, text: p.title })), h("td", { class: "n", text: M.countText(p) }), h("td", { class: "wrap", text: p.what || "" })));
  return h("section", { id: "programs" }, h("h2", { text: "The programs" }), h("div", { class: "scroll" }, h("table", { class: "progs" },
    h("thead", {}, h("tr", {}, h("th", { class: "plain", text: "Program" }), h("th", { class: "plain n", text: "Size" }), h("th", { class: "plain", text: "What it does, and how Caspien versions differ" }))), h("tbody", {}, rows))));
}

function allNumbersTable(D, p) {
  const cols = [["Implementation", "text"], ["Group", "text"], ["Time", "num"], ["Memory", "num"], ["Compile", "num"], ["Size", "num"], ["Output", "text"], ["Matches C", "text"]].map(([label, type]) => ({ label, type }));
  const sorted = [...p.results].sort((a, b) => (a.kind < b.kind ? -1 : a.kind > b.kind ? 1 : a.label < b.label ? -1 : 1));
  const rows = sorted.map(r => [
    { sort: r.label, html: swatch(r.key, r.mode === "off") + inline(r.label) }, { sort: M.groupName(D, r.kind), text: M.groupName(D, r.kind) },
    num(r.time_s, M.vtext(r, "time_s") + (r.cutoff ? " (cut off)" : "")),
    M.known(r, "rss_kb") ? num(r.rss_kb, M.fmt("rss_kb", r.rss_kb)) : { sort: "", text: "-", cls: "n" },
    num(r.compile_s, M.fmt("compile_s", r.compile_s)), num(r.size_bytes, M.fmt("size_bytes", r.size_bytes)),
    { sort: r.output.join(" "), text: r.output.join(" "), cls: "mono" },
    { sort: r.ok === false ? "no" : "yes", html: r.ok === false ? "<b>no</b>" : r.cutoff ? "n/a (cut off)" : "yes" }]);
  return sortableTable(cols, rows);
}

function allNumbersSection(D) {
  const sec = h("div", { class: "metric", id: "allnumbers" }, heading("h2", "All numbers", "every implementation, every metric, per program"));
  for (const p of D.programs) {
    const d = h("details", {}, h("summary", { text: p.title }));
    d.addEventListener("toggle", () => { if (d.open && !d.dataset.built) { d.dataset.built = "1"; d.append(allNumbersTable(D, p)); } });
    sec.append(d);
  }
  return sec;
}

// ---- recursion experiment ---------------------------------------------------------------------------------------------------------------------------
function recursionSection(rec, notes) {
  if (!rec) return null;
  const T = notes.recursion; const sec = h("div", { class: "metric", id: "recursion" }, heading("h2", "Recursion against loops", T.intro));
  for (const g of ["chain", "tree"]) {
    const rows = rec.results.filter(r => r.group === g).sort((a, b) => a.time_s - b.time_s);
    const base = rows.find(r => r.label === T[g].ref).time_s;
    const body = rows.map(r => h("tr", {}, h("td", { text: r.label }), h("td", { class: "n", text: r.time_s.toFixed(3) }), h("td", { class: "n" + (r.time_s / base > 3 ? " bad" : ""), text: (r.time_s / base).toFixed(1) + "×" })));
    const text = T[g].note.replace("{depth}", rec.depth).replace("{reps}", M.withCommas(rec.reps)).replace("{tree}", rec.tree);
    sec.append(h("section", { id: `rec-${g}` }, h("h3", { text: T[g].title }), h("p", { class: "note", text: `${text} Baseline: ${T[g].ref} (${base.toFixed(3)} s). Fastest of ${rec.runs || 3} runs.` }),
      h("div", { class: "scroll" }, h("table", {}, h("thead", {}, h("tr", {}, h("th", { class: "plain", text: "Implementation" }), h("th", { class: "plain n", text: "Seconds" }), h("th", { class: "plain n", text: "vs baseline" }))), h("tbody", {}, body)))));
  }
  sec.append(h("section", {}, h("ul", {}, T.bullets.map(b => h("li", { html: inline(b) })))));
  return sec;
}

// ---- compile times of every Caspien program (compile_times.json) beside the other languages' stored times ---------------------------------------------
function compileSection(D, ct) {
  if (!ct) return null;
  const OTHERS = ["C -O2", "Rust", "Go", "Zig", "Odin", "C++ -O2"];
  const byProg = new Map();
  for (const r of ct.caspien_full) { if (!byProg.has(r.program)) byProg.set(r.program, []); byProg.get(r.program).push(r); }
  const others = p => (D.programs.find(x => x.id === p)?.results || []).filter(r => OTHERS.some(o => r.label.startsWith(o)) && r.compile_s != null);
  let mx = Math.max(...ct.caspien_full.map(r => r.compile_s), 0);
  for (const p of byProg.keys()) for (const r of others(p)) mx = Math.max(mx, r.compile_s);
  const sec = h("div", { class: "metric", id: "compile" }, heading("h2", "Caspien compile times", `every program compiled now, median of ${ct.builds} builds`),
    h("p", { class: "note", text: "Caspien: every benchmark program compiled with all optimisations on, no stage cache, median of the builds (whole compiler run: front end, optimiser, lowering, codegen, gcc). Other bars: the other languages' compile times as stored from the last full run (not re-measured). Nothing was run." }));
  for (const [prog, rs] of byProg) {
    const s = h("section", {}, h("h3", { text: prog }));
    const row = (label, v, cls, key) => h("div", { class: "cr" }, h("div", { class: "l", text: label }), h("div", {}, h("div", { class: "bb " + (cls || "") + " k-" + key, style: `width:${(100 * v / mx).toFixed(1)}%` })), h("div", { class: "v", text: v.toFixed(2) + " s" }));
    for (const r of rs) s.append(row("Caspien " + r.variant, r.compile_s, "", "caspien"));
    for (const r of others(prog)) s.append(row(r.label + " (stored)", r.compile_s, "o", r.key));
    sec.append(s);
  }
  return sec;
}

function legend(cfg) {
  return h("div", { class: "legend" }, cfg.legend.map(([key, text]) => h("span", { html: swatch(key, false) + inline(text) })));
}

async function main() {
  const app = document.getElementById("app");
  const { D, notes, compile, recursion } = await LOAD();
  installLanguageColours(D.cfg);
  const nlang = new Set(D.programs.flatMap(p => p.results.map(r => r.key))).size;
  const metrics = D.cfg.metrics;
  const nav = [...metrics.map(m => [m.slug, m.title]), ["caspien", "Caspien variants"], ...(recursion ? [["recursion", "Recursion"]] : []), ...(compile ? [["compile", "Compile times"]] : []), ["programs", "Programs"], ["allnumbers", "All numbers"], ["notes", "Notes"]];
  app.replaceChildren(hatchDefs(),
    h("h1", { text: "Caspien against other languages" }), h("p", { class: "sub", text: notes.intro.replace("{nlang}", nlang) }),
    h("nav", { class: "top" }, nav.map(([id, t]) => h("a", { href: "#" + id, text: t })), h("a", { href: "compare.html", text: "Before and after" })),
    legend(D.cfg),
    ...metrics.map(m => metricSection(D, m)), caspienSection(D), recursionSection(recursion, notes), compileSection(D, compile), programsSection(D), allNumbersSection(D),
    h("div", { class: "metric", id: "notes" }, h("h2", { text: "Notes" }), h("section", {}, h("ul", {}, notes.notes.map(n => h("li", { html: inline(n) }))))),
    h("p", { class: "foot", html: inline(notes.footer) }));
  installTooltip(row => {
    const box = row.closest(".scroll"); const c = box && box._chart;
    return c ? tipText(c.D, c.rows[+row.dataset.i], c.metric, c.title) : null;
  });
  if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView();
}

main().catch(e => { document.getElementById("app").replaceChildren(h("p", { class: "bad", text: "Could not load the results: " + e.message + ". Serve this folder over HTTP (jwebserver -d site), file:// cannot fetch the JSON." })); });
