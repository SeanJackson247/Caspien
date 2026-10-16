#!/usr/bin/env node
// Standalone SVG charts for the README (GitHub cannot run the site). Reuses the site's own modules, so the pictures cannot drift from the page.
//   node tools/export_svgs.mjs [--quick] [outdir]   default: img/ (--quick: use data/<id>.quick.json where present, default img-quick/)
// Writes time_<program>[-dark].svg, overview_time[-dark].svg and summary_<metric>_{vs_c,safe_vs_rust}[-dark].svg, and prints the README selection.
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import * as M from "../site/js/model.js";
import { barChart, esc, ratioChart } from "../site/js/svg.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SITE = join(ROOT, "site");
const QUICK = process.argv.includes("--quick");
const OUT = process.argv.slice(2).find(a => a !== "--quick") || join(ROOT, QUICK ? "img-quick" : "img");
const json = p => JSON.parse(readFileSync(join(SITE, p), "utf8"));

const cfg = json("languages.json"), catalogue = json("data/index.json");
const present = readdirSync(join(SITE, "data"));
const files = Object.fromEntries(catalogue.programs.filter(p => present.includes(p.id + ".json")).map(p => [p.id, json(QUICK && present.includes(p.id + ".quick.json") ? `data/${p.id}.quick.json` : `data/${p.id}.json`)]));
const D = M.prepare(cfg, catalogue, files, json("data/rust_safe.json"));

const THEMES = {
  light: { card: "#ffffff", line: "#d2d8cf", fg: "#1d2320", muted: "#5d665f", grid: "#dfe3dc", off: "#cfe9d9", pick: "light" },
  dark: { card: "#1f2220", line: "#3a413b", fg: "#e8ece6", muted: "#a1aaa2", grid: "#343a35", off: "#28402f", pick: "dark" },
};
const FONT = 'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif"';
const KEY_NOTE = "Green = Caspien (hatched = optimisations off, solid = everything on), red = native, purple = JVM/.NET/Wasm, blue = interpreted.";

function style(t) {
  const lang = cfg.languages.map(l => `.k-${l.key}{--c:${l[t.pick]}}`).join("");
  return `<style>.grid{stroke:${t.grid};stroke-width:1}.noise{stroke:#e0a800;stroke-width:1.6}.tick{fill:${t.muted};font-size:11px}.lbl{fill:${t.fg};font-size:12.5px}` +
    `.val{fill:${t.fg};font-size:12px}.grp{fill:${t.muted};font-size:10.5px}.bar{fill:var(--c)}.bar.off{fill:url(#hatch);stroke:var(--c);stroke-width:1.2}` +
    `.ttl{fill:${t.fg};font-size:15px;font-weight:600}.sub{fill:${t.muted};font-size:11.5px}${lang}</style>`;
}

/** Title, subtitle and footer lines around a chart drawn by the site code. */
function wrap(svg, themeName, title, sub, foot) {
  const t = THEMES[themeName];
  const m = svg.match(/^<svg viewBox="0 0 (\d+) (\d+)"[^>]*>([\s\S]*)<\/svg>\s*$/);
  const w = +m[1], h = +m[2];
  const body = m[3].replace(/<rect class="hit"[^>]*\/>/g, "").replace(/ data-tip="[^"]*"/g, "");
  const top = 46, bot = 44, H = h + top + bot;
  const caspien = cfg.languages.find(l => l.key === "caspien")[t.pick];
  const hatch = `<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="${t.off}"/>` +
    `<line x1="0" y1="0" x2="0" y2="6" stroke="${caspien}" stroke-width="2.4"/></pattern></defs>`;
  const lines = foot.map((l, i) => `<text class="sub" x="16" y="${h + top + 18 + 14 * i}">${esc(l)}</text>`).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${H}" width="${w}" height="${H}" ${FONT}>${style(t)}${hatch}` +
    `<rect width="${w}" height="${H}" rx="8" fill="${t.card}" stroke="${t.line}"/><text class="ttl" x="16" y="24">${esc(title)}</text>` +
    `<text class="sub" x="16" y="40">${esc(sub)}</text><g transform="translate(0,${top})">${body}</g>${lines}</svg>\n`;
}

const out = [];
const save = (name, text) => { writeFileSync(join(OUT, name), text); out.push(name); };
mkdirSync(OUT, { recursive: true });

const METRICS = [["time_s", "Execution time"], ["rss_kb", "Peak memory"], ["compile_s", "Compile time"], ["size_bytes", "Executable size"]];
const common = M.overviewTimeRows(D);

for (const theme of ["light", "dark"]) {
  const suf = theme === "light" ? "" : "-dark";
  for (const p of D.programs) {
    const sub = `${M.countText(p)}  |  fastest of ${p.runs} runs, Linux 2-core VM; every output equals the C reference`;
    const foot = ["Green = Caspien (hatched = optimisations off), red = native, purple = JVM/.NET/Wasm, blue = interpreted.", "Gold lines: best time -/+ typical run-to-run noise (median repeat minus best)."];
    save(`time_${p.id}${suf}.svg`, wrap(barChart(D, p.results, "time_s", "Execution time, " + p.title).svg, theme, "Execution time: " + p.title, sub, foot));
  }
  if (common.rows.length) {
    save(`overview_time${suf}.svg`, wrap(ratioChart(common.rows, { log: false }), theme,
      `Caspien against ${cfg.languages.length - 1} other languages: execution time over ${common.programs} programs`,
      `Geometric mean of time / C -O2 time over the same ${common.programs} programs for every row. Lower is faster; 1x = C -O2.`,
      [KEY_NOTE, "Fastest of 3 runs, Linux 2-core VM. Hello World and Mandelbrot (one Caspien variant) are excluded; unsafe variants are in the per-program charts."]));
  }
  for (const [metric, name] of METRICS) for (const safe of [false, true]) {
    const refName = safe ? "Rust -O (free build)" : "C -O2";
    const rows = M.summaryRows(D, metric, safe);
    save(`summary_${metric}_${safe ? "safe_vs_rust" : "vs_c"}${suf}.svg`, wrap(ratioChart(rows), theme,
      `${name}: ${safe ? "memory-safe implementations" : "every language"}, relative to ${refName}`,
      `Geometric mean of ${name.toLowerCase()} / ${refName} on the same program, over the programs where both exist. Lower is better; 1x = ${refName}.`,
      [KEY_NOTE, (safe ? "Safe filter: Caspien without unsafe-dynarray variants, GC/runtime-safe languages, Rust where rustc accepts the port under -F unsafe_code."
        : "C, C++ and Rust: free build. Caspien variants that exist only on some programs are averaged over those programs.") + (metric === "time_s" ? "  >= : time cut off in a quick run (lower bound)." : "")]));
  }
}
console.log(`${out.length} files in ${OUT}`);
const sel = M.selection(D);
console.log("Caspien fastest 'everything on' variant / C -O2, per program (low = faster than C):");
for (const s of sel) console.log(`  ${M.fixed(s.ratio, 2).padStart(5)}x  ${s.id.padEnd(14)} ${s.label}`);
console.log(`README selection (lowest ratio, median, highest ratio): ${sel[0].id}, ${sel[sel.length >> 1].id}, ${sel[sel.length - 1].id}`);
