// Pure SVG string builders (no DOM). The page inserts them as they are; tools/export_svgs.mjs wraps them in a standalone file for the README.
import { fixed, fmt, g, groupName, known, vtext } from "./model.js";

export const esc = s => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const CHART = { W: 900, LBL: 270, RIGHT: 190, BH: 17, GAP: 6, TOP: 8 };

function nice(x) {
  const p = 10 ** Math.floor(Math.log10(x));
  for (const m of [1, 2, 2.5, 5, 10]) if (x <= m * p) return m * p;
}

/** Noise = how much slower the median repeat is than the best one (the bar is the best of the repeats); one stray slow repeat does not move it. */
export function noiseOf(r, metric) {
  const ts = metric === "time_s" ? r.times : null;
  if (!ts || ts.length < 2) return { noise: 0, spread: 0 };
  const s = [...ts].sort((a, b) => a - b);
  const noise = ts.length > 2 ? s[s.length >> 1] - s[0] : ts.reduce((a, b) => a + b, 0) / ts.length - s[0];
  return { noise, spread: s[s.length - 1] - s[0] };
}

export function tipText(D, r, metric, title) {
  const { noise, spread } = noiseOf(r, metric);
  return `${r.label}\n${title}: ${vtext(r, metric)}${r.cutoff && metric === "time_s" ? " -- killed there: would have taken longer than optimized Caspien" : ""}` +
    `${spread ? ` (gold lines: ±${fmt(metric, noise)} = median repeat minus best; slowest repeat +${fmt(metric, spread)})` : ""}\ngroup: ${groupName(D, r.kind)}` +
    `${r.ok === false ? "\nOUTPUT DIFFERS from the C reference" : ""}`;
}

/** One bar per implementation, best first. -> { svg, rows } (rows = the sorted rows, index = the data-i of each <g class="row">). */
export function barChart(D, R, metric, title) {
  const { W, LBL, RIGHT, BH, GAP, TOP } = CHART;
  const rows = R.filter(r => known(r, metric)).sort((a, b) => a[metric] - b[metric] || (a.label < b.label ? -1 : 1));
  const vmax = Math.max(...rows.map(r => r[metric])) || 1;
  const vmin = Math.min(...rows.map(r => r[metric]).filter(v => v > 0), 1e300) === 1e300 ? 1 : Math.min(...rows.map(r => r[metric]).filter(v => v > 0));
  const H = TOP + rows.length * (BH + GAP) + 26;
  const plot = W - LBL - RIGHT;
  const ticks = [];
  let sx;
  if (metric === "size_bytes") {
    const lo = 10 ** Math.floor(Math.log10(vmin)); const hi = 10 ** Math.ceil(Math.log10(vmax));
    const l0 = Math.log10(lo); const l1 = Math.log10(hi);
    sx = v => plot * (Math.log10(Math.max(v, lo)) - l0) / (l1 - l0);
    for (let e = lo; e <= hi * 1.001; e *= 10) ticks.push([e, e < 1e6 ? `${g(e / 1e3)} KB` : `${g(e / 1e6)} MB`]);
  } else {
    const unit = metric === "rss_kb" ? 1024 : 1;
    const st = nice(vmax / unit / 5) * unit; const topV = st * Math.ceil(vmax / st);
    sx = v => plot * v / topV;
    for (let k = 0; k * st <= topV * 1.001; k++) ticks.push([k * st, null]);
  }
  const o = [`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(title)}, one bar per implementation">`];
  for (const [v, t] of ticks) {
    const x = LBL + sx(v);
    o.push(`<line class="grid" x1="${fixed(x, 1)}" x2="${fixed(x, 1)}" y1="${TOP - 2}" y2="${H - 22}"/>`);
    o.push(`<text class="tick" x="${fixed(x, 1)}" y="${H - 8}" text-anchor="middle">${esc(t === null ? g(Math.round((metric === "rss_kb" ? v / 1024 : v) * 1e4) / 1e4) : t)}</text>`);
  }
  rows.forEach((r, i) => {
    const y = TOP + i * (BH + GAP); const v = r[metric];
    const w = v > 0 ? Math.max(sx(v), 1.5) : 1.5;
    const { noise } = noiseOf(r, metric);
    const cls = "k-" + r.key + (r.mode === "off" ? " off" : "");
    o.push(`<g class="row" data-i="${i}"><rect class="hit" x="0" y="${y - (GAP >> 1)}" width="${W}" height="${BH + GAP}"/>`);
    o.push(`<text class="lbl" x="${LBL - 8}" y="${fixed((y + BH * 0.72), 1)}" text-anchor="end">${esc(r.label)}</text>`);
    o.push(`<rect class="bar ${cls}" x="${LBL}" y="${y}" width="${fixed(w, 1)}" height="${BH}" rx="3"/>`);
    let tx = w;
    if (noise > 0 && v > 0) {   // two gold lines at the best time -/+ the typical noise
      for (const xv of [Math.max(v - noise, 0), v + noise]) {
        const gx = LBL + Math.min(Math.max(sx(xv), 0), plot * 1.04);
        o.push(`<line class="noise" x1="${fixed(gx, 1)}" x2="${fixed(gx, 1)}" y1="${y}" y2="${y + BH}"/>`);
        tx = Math.max(tx, gx - LBL);
      }
    }
    o.push(`<text class="val" x="${fixed((LBL + tx + 6), 1)}" y="${fixed((y + BH * 0.72), 1)}">${esc(vtext(r, metric) + (r.cutoff && metric === "time_s" ? " (cut off)" : ""))} <tspan class="grp">${esc(groupName(D, r.kind))}</tspan></text></g>`);
  });
  o.push("</svg>");
  return { svg: o.join(""), rows };
}

/** Bars of a geometric-mean ratio per row (the README summary charts). rows: { label, key, mode, gm, low }. */
export function ratioChart(rows, { log = null } = {}) {
  const W = 900, LBL = 300, BH = 17, GAP = 6, TOP = 8, plot = W - LBL - 150;
  const vmax = Math.max(...rows.map(r => r.gm)); const vmin = Math.min(...rows.map(r => r.gm));
  const logax = log === null ? (vmax > 12 || vmin < 0.2) : log;   // a wide spread (compile time, executable size) gets a log axis
  let sx; let ticks;
  if (logax) {
    const lo = Math.floor(Math.log10(Math.min(vmin, 1))); const hi = Math.ceil(Math.log10(Math.max(vmax, 1.01)));
    sx = v => plot * (Math.log10(v) - lo) / (hi - lo);
    ticks = []; for (let e = lo; e <= hi; e++) ticks.push([10 ** e, `${g(10 ** e)}x`]);
  } else {
    const step = vmax <= 12 ? 1 : 2; const topT = Math.ceil(vmax / step) * step;
    sx = v => plot * v / topT;
    ticks = []; for (let t = 0; t <= topT; t += step) ticks.push([t, `${t}x`]);
  }
  const H = TOP + rows.length * (BH + GAP) + 26;
  const o = [`<svg viewBox="0 0 ${W} ${H}">`];
  for (const [v, txt] of ticks) {
    const x = LBL + (v > 0 ? sx(v) : 0);
    o.push(`<line class="grid" x1="${fixed(x, 1)}" x2="${fixed(x, 1)}" y1="${TOP - 2}" y2="${H - 22}"/><text class="tick" x="${fixed(x, 1)}" y="${H - 8}" text-anchor="middle">${txt}</text>`);
  }
  if (logax) o.push(`<text class="tick" x="${W - 8}" y="${H - 8}" text-anchor="end">log scale</text>`);
  rows.forEach((r, i) => {
    const y = TOP + i * (BH + GAP); const w = Math.max(sx(r.gm), 1.5);
    const cls = "k-" + r.key + (r.mode === "off" ? " off" : "");
    o.push(`<g class="row"><text class="lbl" x="${LBL - 8}" y="${fixed((y + BH * 0.72), 1)}" text-anchor="end">${esc(r.label)}</text>` +
      `<rect class="bar ${cls}" x="${LBL}" y="${y}" width="${fixed(w, 1)}" height="${BH}" rx="3"/>` +
      `<text class="val" x="${fixed((LBL + w + 6), 1)}" y="${fixed((y + BH * 0.72), 1)}">${r.low ? "&gt;=" : ""}${r.gm.toFixed(2)}x</text></g>`);
  });
  o.push("</svg>");
  return o.join("");
}

/** Diverging bars: change per row in percent, after versus before (comparison pages). rows: compareRows(). */
export function changeChart(rows, edited = []) {
  const W = 980, LBL = 330, RW = 330, ROWH = 19, TOP = 54; const x0 = LBL + RW / 2; const scale = (RW / 2 - 14) / 25;
  const H = TOP + rows.length * ROWH + 40;
  const o = [`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Change in run time per Caspien program, after versus before">`];
  for (const pc of [-20, -10, 0, 10, 20]) {
    const x = x0 + pc * scale;
    o.push(`<line x1="${fixed(x, 1)}" y1="${TOP - 8}" x2="${fixed(x, 1)}" y2="${H - 30}" class="grid"/><text x="${fixed(x, 1)}" y="${TOP - 14}" class="tick" text-anchor="middle">${pc ? (pc > 0 ? "+" : "") + pc + "%" : "0"}</text>`);
  }
  o.push(`<rect x="${fixed((x0 - 5 * scale), 1)}" y="${TOP - 8}" width="${fixed((10 * scale), 1)}" height="${H - 30 - TOP + 8}" class="band"/>`);
  o.push(`<text x="${fixed((x0 - 110), 1)}" y="14" class="tick" text-anchor="middle">&#8592; faster</text><text x="${fixed((x0 + 110), 1)}" y="14" class="tick" text-anchor="middle">slower &#8594;</text>`);
  let last = null;
  rows.forEach((r, i) => {
    const y = TOP + i * ROWH;
    if (r.program !== last) {
      o.push(`<text x="6" y="${y + 13}" class="prog">${esc(r.program)}</text>`);
      if (i) o.push(`<line x1="0" y1="${y - 2}" x2="${W}" y2="${y - 2}" class="sep"/>`);
      last = r.program;
    }
    const mark = edited.some(([p, l]) => p === r.program && l === r.label) ? "✎ " : "";
    o.push(`<text x="${LBL - 6}" y="${y + 13}" class="lbl" text-anchor="end">${esc(mark + r.label.slice(0, 44))}</text>`);
    const w = Math.min(Math.abs(r.pct) * scale, RW / 2 - 2);
    const sig = Math.abs(r.pct) > Math.max(5, r.spread * 1.5) && r.b >= 0.02;   // a few milliseconds is startup noise
    const cls = r.pct < 0 && sig ? "bar-fast" : r.pct > 0 && sig ? "bar-slow" : "bar-flat";
    const bx = r.pct < 0 ? x0 - w : x0;
    o.push(`<rect x="${fixed(bx, 1)}" y="${y + 3}" width="${fixed(Math.max(w, 1.5), 1)}" height="${ROWH - 7}" class="${cls}"><title>${esc(r.program + " " + r.label)}: ${r.b.toFixed(3)} s before, ${r.a.toFixed(3)} s after (${r.pct >= 0 ? "+" : ""}${fixed(r.pct, 1)}%)</title></rect>`);
    const tx = r.pct < 0 ? [bx - 4, "end"] : [bx + w + 4, "start"];
    o.push(`<text x="${fixed(tx[0], 1)}" y="${y + 14}" class="val" text-anchor="${tx[1]}">${r.pct >= 0 ? "+" : ""}${fixed(r.pct, 1)}%</text>`);
  });
  o.push("</svg>");
  return o.join("");
}

/** Paired bars (before above after) for one program, with a dashed C -O2 line (the step comparisons). rows: [{label, b, a}] */
export function pairChart(title, rows, c2) {
  const W = 640, LBL = 150, RH = 15;
  let mx = Math.max(...rows.map(r => Math.max(r.b, r.a)));
  mx = Math.max(mx, c2 || 0) * 1.12; const sc = (W - LBL - 90) / mx;
  const h = 24 + rows.length * (2 * RH + 14) + 22; let y = 18;
  const o = [`<svg viewBox="0 0 ${W} ${h}" role="img" aria-label="${esc(title)} before and after">`];
  if (c2) { const cx = LBL + c2 * sc; o.push(`<line class="ref" x1="${fixed(cx, 1)}" x2="${fixed(cx, 1)}" y1="6" y2="${h - 20}"/><text class="tick" x="${fixed((cx + 4), 1)}" y="${h - 6}">C -O2 ${c2.toFixed(2)} s</text>`); }
  for (const r of rows) {
    o.push(`<text class="lbl" x="${LBL - 8}" y="${y + RH + 4}" text-anchor="end">${esc(r.label)}</text>`);
    o.push(`<rect class="old" x="${LBL}" y="${y}" width="${fixed((r.b * sc), 1)}" height="${RH - 2}" rx="3"/><text class="val" x="${fixed((LBL + r.b * sc + 5), 1)}" y="${y + RH - 5}">${r.b.toFixed(2)} s before</text>`);
    y += RH;
    o.push(`<rect class="new" x="${LBL}" y="${y}" width="${fixed((r.a * sc), 1)}" height="${RH - 2}" rx="3"/><text class="val" x="${fixed((LBL + r.a * sc + 5), 1)}" y="${y + RH - 5}"><tspan class="b">${r.a.toFixed(2)} s after</tspan> (${(r.a - r.b) / r.b * 100 >= 0 ? "+" : ""}${((r.a - r.b) / r.b * 100).toFixed(0)}%)</text>`);
    y += RH + 14;
  }
  o.push("</svg>");
  return o.join("");
}
