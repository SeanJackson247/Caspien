// Before/after comparisons of Caspien (data/history/*.json): one page, the comparison is chosen with the hash (compare.html#step5).
import { changeChart, pairChart } from "./svg.js";
import { compareRows, geomean, withCommas } from "./model.js";
import { getJson, h, inline, sortableTable } from "./ui.js";

const EDITED = [["nbody", "natural f64"], ["nbody", "arr f64"], ["nbody", "arr f32"]];   // rows whose program source was edited (marked in the change chart)

function steps(doc) {
  // programs measured with "C -O2" reference lines: paired bars before above after
  return Object.entries(doc.programs).map(([prog, labels]) => {
    const rows = Object.entries(labels).filter(([, s]) => s.before && s.after).map(([label, s]) => ({ label: label.replace("Caspien ", ""), b: s.before.time_s, a: s.after.time_s }));
    return h("section", {}, h("h3", { text: prog }), h("div", { class: "scroll", html: pairChart(prog, rows, doc.c_o2 && doc.c_o2[prog]).replace("<svg ", '<svg class="chart" ') }));
  });
}

function aggregate(rows) {
  const by = new Map();
  for (const r of rows) { if (!by.has(r.program)) by.set(r.program, []); by.get(r.program).push(r.a / r.b); }
  const tableRows = [...by].map(([p, xs]) => [{ sort: p, text: p }, { sort: xs.length, text: String(xs.length), cls: "n" }, { sort: geomean(xs).toFixed(4), text: ((geomean(xs) - 1) * 100 >= 0 ? "+" : "") + ((geomean(xs) - 1) * 100).toFixed(1) + "%", cls: "n" }]);
  const all = geomean(rows.map(r => r.a / r.b));
  return h("section", {}, h("h3", { text: "Per program" }), sortableTable([{ label: "program", type: "text" }, { label: "variants", type: "num" }, { label: "geomean change", type: "num" }], tableRows),
    h("p", { class: "note", text: `Overall geometric mean of after/before: ${((all - 1) * 100 >= 0 ? "+" : "") + ((all - 1) * 100).toFixed(1)}% over ${rows.length} variants.` }));
}

function table(rows) {
  const cols = [["program", "text"], ["variant", "text"], ["before s", "num"], ["after s", "num"], ["change", "num"], ["size before", "num"], ["size after", "num"]].map(([label, type]) => ({ label, type }));
  const num = (v, t) => ({ sort: v == null ? "" : v, text: t, cls: "n" });
  return sortableTable(cols, rows.map(r => [{ sort: r.program, text: r.program }, { sort: r.label, text: r.label }, num(r.b, r.b.toFixed(3)), num(r.a, r.a.toFixed(3)),
    num(r.pct, (r.pct >= 0 ? "+" : "") + r.pct.toFixed(1) + "%"), num(r.bs, r.bs == null ? "-" : withCommas(r.bs)), num(r.as, r.as == null ? "-" : withCommas(r.as))]));
}

function stdlib(doc) {
  const e = Object.entries(doc.stdlib || {});
  if (!e.length) return null;
  const rows = [];
  for (const [prog, byPreset] of e) for (const [preset, v] of Object.entries(byPreset)) rows.push([{ sort: prog, text: prog }, { sort: preset, text: preset }, { sort: v.without, text: v.without.toFixed(3), cls: "n" }, { sort: v.with, text: v.with.toFixed(3), cls: "n" },
    { sort: v.with / v.without, text: ((v.with / v.without - 1) * 100 >= 0 ? "+" : "") + ((v.with / v.without - 1) * 100).toFixed(1) + "%", cls: "n" }]);
  return h("section", {}, h("h3", { text: "stdlib `@inline`, per inlining preset" }), sortableTable([["program", "text"], ["preset", "text"], ["without s", "num"], ["with s", "num"], ["change", "num"]].map(([label, type]) => ({ label, type })), rows));
}

function page(doc, index) {
  const rows = compareRows(doc);
  const body = doc.c_o2 ? steps(doc) : [h("section", {}, h("div", { class: "scroll", html: changeChart(rows, EDITED).replace("<svg ", '<svg class="chart" ') }),
    h("p", { class: "note", text: "Bars: change in run time of each Caspien variant. Green/red = more than 5% and more than 1.5 times the run-to-run spread; grey = within noise. ✎ = the program source was edited." })), aggregate(rows), stdlib(doc), h("section", {}, table(rows))];
  const meta = doc.meta || {};
  return [
    h("h1", { html: inline(doc.title) }),
    h("nav", { class: "top" }, h("a", { href: "index.html", text: "Benchmarks" }), index.comparisons.map(c => h("a", { href: "#" + c.id, text: c.title.replace(/`/g, "") }))),
    doc.intro ? h("p", { class: "sub", text: doc.intro }) : null,
    doc.edits ? h("section", {}, h("h3", { text: "What changed" }), h("ul", {}, doc.edits.map(e => h("li", { html: inline(e) })))) : null,
    doc.c_o2 ? h("div", { class: "key" }, h("span", { html: '<i style="background:var(--old)"></i>before' }), h("span", { html: '<i style="background:var(--new)"></i>after' }), h("span", { html: '<i style="background:var(--ref)"></i>C -O2 (dashed line)' })) : null,
    ...body,
    ...Object.entries(meta).map(([k, v]) => h("section", {}, h("h3", { text: k[0].toUpperCase() + k.slice(1) }), h("p", { html: inline(String(v)) }))),
  ];
}

async function main() {
  const app = document.getElementById("app");
  const index = await getJson("data/history/index.json");
  const show = async () => {
    const id = location.hash.slice(1) || index.comparisons[0].id;
    const doc = await getJson(`data/history/${id}.json`);
    document.title = doc.title.replace(/`/g, "");
    app.replaceChildren(...page(doc, index).filter(Boolean));
  };
  window.addEventListener("hashchange", show);
  await show();
}
main().catch(e => { document.getElementById("app").replaceChildren(h("p", { class: "bad", text: "Could not load the comparison: " + e.message })); });
