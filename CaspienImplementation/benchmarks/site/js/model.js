// Pure data logic: no DOM, no fetch. Used by the page (ui.js) and by the SVG export tool (tools/export_svgs.mjs).

export const MODE_NAME = { off: "optimisations off", full: "everything on", specific: "tuned per program" };
export const CAS_MODES = ["off", "full", "specific"];

/** Attach what the rows need: kind (group, or "caspien"), key, mode. Rows measured by the harness already carry key and mode. */
export function prepare(cfg, catalogue, files, rustSafe) {
  const programs = [];
  for (const entry of catalogue.programs) {
    const data = files[entry.id];
    if (!data) continue;
    const results = data.results.filter(r => !r.label.startsWith("Lua 5.4")).map(r => ({
      ...r, kind: r.label.startsWith("Caspien") ? "caspien" : r.group, mode: r.mode || "", note: r.note || "", prec: r.prec || "",
    }));
    programs.push({ ...entry, n: data.n, runs: data.runs, mode: data.mode, results });
  }
  const langs = new Map(cfg.languages.map(l => [l.key, l]));
  return { cfg, programs, langs, rustSafe: rustSafe || {}, safeKeys: new Set(cfg.languages.filter(l => l.safe && l.key !== "caspien").map(l => l.key)) };
}

export const overviewPrograms = D => D.programs.filter(p => p.overview !== false);
export const groupName = (D, kind) => D.cfg.groups[kind];

// ---- row helpers ----------------------------------------------------------------------------------------------------------------------------
export function cRef(R) {
  for (const lab of ["C -O2", "C -O2 (free)"]) { const r = R.find(x => x.label === lab); if (r) return r; }
}
export function rustRef(R) {
  for (const lab of ["Rust -O (free)", "Rust -O"]) { const r = R.find(x => x.label === lab); if (r) return r; }
}
/** The row "<prefix> · <mode>"; "specific" falls back to "full" for a program without overrides. */
export function find(R, prefix, mode) {
  const r = R.find(x => x.label === `${prefix} · ${mode}`);
  if (r || mode !== "specific") return r;
  return find(R, prefix, "full");
}
/** A cut-off row (killed at the slowest optimised Caspien time) has a time, a compile time and a size, but no peak memory. */
export const known = (r, metric) => !(r.cutoff && metric === "rss_kb");

export function portUnsafe(D, prog, key) { return key === "rust" && !D.rustSafe[prog]; }
/** The memory-safe filter: safe Caspien variants, and languages whose runtime or compiler provides safety (Rust only where rustc confirmed the port). */
export function safeRow(D, prog, r) {
  if (r.key === "caspien") return !r.label.includes("unsafe");
  return D.safeKeys.has(r.key) && !portUnsafe(D, prog, r.key);
}

// ---- formatting -----------------------------------------------------------------------------------------------------------------------------
/** Fixed decimals with exact ties rounded to even, like Python's "%.1f" (toFixed rounds an exact tie up), so figures match the earlier pages. */
export function fixed(x, d) {
  const t = x.toFixed(d + 30);                      // the exact decimal expansion of the binary value
  const tail = t.slice(t.length - 30);
  if (!/^50*$/.test(tail)) return x.toFixed(d);
  const n = Math.round(Number(t.slice(0, t.length - 30)) * 10 ** d);
  return ((n % 2 === 0 ? n : n + 1) / 10 ** d).toFixed(d);
}

export function fmt(metric, v) {
  if (metric === "time_s" || metric === "compile_s") return v >= 0.005 ? fixed(v, 2) + " s" : v === 0 ? "0 s" : fixed(v, 3) + " s";
  if (metric === "rss_kb") return fixed(v / 1024, 1) + " MB";
  if (v >= 1 << 20) return fixed(v / (1 << 20), 1) + " MB";
  if (v >= 1 << 10) return fixed(v / (1 << 10), 1) + " KB";
  return Math.round(v) + " B";
}
export const vtext = (r, metric) => (r.cutoff && metric === "time_s" ? "> " : "") + fmt(metric, r[metric]);
export const withCommas = n => Number(n).toLocaleString("en-US");
export const countText = p => (p.count || "%s").replace("%s", withCommas(p.n));
/** Python's %g */
export const g = (x, digits = 6) => String(Number(Number(x).toPrecision(digits)));

export function geomean(xs) { return Math.exp(xs.reduce((s, x) => s + Math.log(x), 0) / xs.length); }

// ---- the cross-program overview: every language x every program, plus the geometric mean of the ratio to C -O2 --------------------------------
/** Which Caspien variants appear as rows: naive, then optimized (safe), then optimized (unsafe); each off / everything on / tuned. */
function casVariants(p) {
  const opts = p.columns;
  return [["Caspien naive", p.naive], ["Caspien optimized (safe)", opts[0] && opts[0][1]], ["Caspien optimized (unsafe)", opts[1] && opts[1][1]]];
}

/** -> { columns: [program titles], rows: [{ label, key, off, group, cells: [null | {v, text, ratio, lower, bad}], gm, gmLower }] } */
export function overview(D, metric, safe = false) {
  const progs = overviewPrograms(D);
  const rows = [];
  const add = (label, key, group, off, pick) => {
    const cells = []; const ratios = []; let nlow = 0;
    for (const p of progs) {
      const R = p.results; const c2 = cRef(R); let r = pick(p, R);
      if (r && safe && !safeRow(D, p.id, r)) r = null;
      if (!r || !known(r, metric)) { cells.push(null); continue; }
      const ratio = c2 && c2[metric] > 0 && r[metric] > 0 ? r[metric] / c2[metric] : null;
      if (ratio) ratios.push(ratio);
      const lower = !!(r.cutoff && metric === "time_s");     // killed at the limit: the true time is longer, so the ratio is a lower bound
      if (lower && ratio) nlow++;
      cells.push({ v: r[metric], text: vtext(r, metric), ratio, lower, bad: r.ok === false });
    }
    if (cells.every(c => c === null)) return;
    rows.push({ label, key, off, group, cells, gm: ratios.length ? geomean(ratios) : null, gmLower: nlow > 0 });
  };
  for (const l of D.cfg.languages) {
    if (!l.overview || (safe && !D.safeKeys.has(l.key))) continue;
    add(l.overview.label, l.key, groupName(D, l.group), false, (p, R) => l.overview.candidates.map(c => R.find(r => r.label === c)).find(Boolean));
  }
  for (const [lab, prefixOf] of [["Caspien naive", 0], ["Caspien optimized (safe)", 1], ["Caspien optimized (unsafe)", 2]]) {
    if (safe && lab.includes("unsafe")) continue;
    for (const mode of CAS_MODES) {
      add(`${lab}, ${MODE_NAME[mode]}`, "caspien", groupName(D, "caspien"), mode === "off", (p, R) => {
        const pre = casVariants(p)[prefixOf][1];
        return pre ? find(R, pre, mode) : null;
      });
    }
  }
  return { columns: progs.map(p => p.title), rows };
}

// ---- Caspien variants of one program: naive / optimized, off / everything on, against the best C build ----------------------------------------
export function caspienSummary(p) {
  const R = p.results; const c2 = cRef(R); const out = [];
  const list = [["naive", p.naive], ...p.columns.map(([col, pre]) => [col, pre])];
  for (const [kind, prefix] of list) {
    for (const mode of CAS_MODES) {
      const r = find(R, prefix, mode); if (!r) continue;
      out.push({ kind, mode: mode === "full" ? "everything on" : mode === "off" ? "off" : "tuned per program", r, ratio: c2 && c2.time_s > 0 ? r.time_s / c2.time_s : null });
    }
  }
  return out;
}

// ---- README summary charts: geometric mean of metric / reference over the programs where both exist ----------------------------------------------
const REF_ROWS = [["Caspien ref naive", "Caspien ref naive"], ["Caspien ref safe", "Caspien ref safe"], ["Caspien ref unsafe", "Caspien ref unsafe"],
  ["Caspien naive, ref HashMap", "Caspien naive, ref in stdlib"]];

export function summaryRows(D, metric, safe) {
  const refGet = safe ? rustRef : cRef;
  const progs = overviewPrograms(D).filter(p => p.id !== "helloworld");
  const cands = [];
  for (const l of D.cfg.languages) {
    if (!l.overview || (safe && !D.safeKeys.has(l.key))) continue;
    cands.push([l.overview.label, l.key, "", (p, R) => l.overview.candidates.map(c => R.find(r => r.label === c)).find(Boolean)]);
  }
  for (const [lab, idx] of [["Caspien naive", 0], ["Caspien optimized (safe)", 1], ["Caspien optimized (unsafe)", 2]]) {
    if (safe && lab.includes("unsafe")) continue;
    for (const mode of CAS_MODES) cands.push([`${lab}, ${MODE_NAME[mode]}`, "caspien", mode, (p, R) => { const pre = casVariants(p)[idx][1]; return pre ? find(R, pre, mode) : null; }]);
  }
  for (const [lab, prefix] of REF_ROWS) {
    if (safe && lab.includes("unsafe")) continue;
    cands.push([lab, "caspien", "full", (p, R) => find(R, prefix, "full")]);
  }
  const refLabels = new Set(REF_ROWS.map(x => x[0]));
  const out = [];
  for (let [lab, key, mode, get] of cands) {
    const ratios = []; let low = false;
    for (const p of progs) {
      const R = p.results; const ref = refGet(R); let r = get(p, R);
      if (r && safe && !safeRow(D, p.id, r)) r = null;
      if (!(r && ref && known(r, metric) && known(ref, metric) && r[metric] > 0 && ref[metric] > 0)) continue;
      ratios.push(r[metric] / ref[metric]);
      low = low || !!(r.cutoff && metric === "time_s");
    }
    if (ratios.length >= (refLabels.has(lab) ? 2 : 3)) {
      if (refLabels.has(lab)) lab = `${lab} [${ratios.length} programs]`;
      out.push({ label: lab, key, mode, gm: geomean(ratios), n: ratios.length, low });
    }
  }
  return out.sort((a, b) => a.gm - b.gm);
}

/** Programs on which every row of the time overview exists (C -O2, and Caspien naive and optimized safe in both configurations). */
export function commonPrograms(D) {
  return overviewPrograms(D).filter(p => p.id !== "helloworld" && p.columns.length && cRef(p.results) &&
    [p.naive, p.columns[0][1]].every(pre => ["off", "full"].every(m => find(p.results, pre, m))));
}

/** The time overview used by the README (overview_time.svg): the same programs for every row. */
export function overviewTimeRows(D) {
  const common = commonPrograms(D); const rows = [];
  const gm = pick => {
    const rs = [];
    for (const p of common) {
      const R = p.results; const c2 = cRef(R); const r = pick(p, R);
      if (!(r && c2 && r.time_s > 0 && c2.time_s > 0)) return null;
      rs.push(r.time_s / c2.time_s);
    }
    return rs.length ? geomean(rs) : null;
  };
  for (const l of D.cfg.languages) {
    if (!l.overview) continue;
    const v = gm((p, R) => l.overview.candidates.map(c => R.find(r => r.label === c)).find(Boolean));
    if (v) rows.push({ label: l.overview.label, key: l.key, mode: "", gm: v, n: common.length });
  }
  for (const [lab, idx] of [["Caspien naive", 0], ["Caspien optimized (safe)", 1]]) {
    for (const mode of CAS_MODES) {
      const v = gm((p, R) => { const pre = casVariants(p)[idx][1]; return pre ? find(R, pre, mode) : null; });
      if (v) rows.push({ label: `${lab}, ${MODE_NAME[mode]}`, key: "caspien", mode, gm: v, n: common.length });
    }
  }
  return { rows: rows.sort((a, b) => a.gm - b.gm), programs: common.length };
}

/** The programs the README features by a fixed rule: lowest, median and highest ratio of Caspien's fastest "everything on" variant to C -O2. */
export function selection(D) {
  const out = [];
  for (const p of overviewPrograms(D).filter(p => p.id !== "helloworld")) {
    const c2 = cRef(p.results);
    const c = p.results.filter(r => r.label.startsWith("Caspien") && (r.label.endsWith("· full") || r.label.endsWith("· specific")) && r.ok !== false);
    if (!c.length || !c2) continue;
    const b = c.reduce((a, r) => (r.time_s < a.time_s ? r : a));
    out.push({ ratio: b.time_s / c2.time_s, id: p.id, title: p.title, label: b.label });
  }
  return out.sort((a, b) => a.ratio - b.ratio);
}

// ---- before / after comparisons (data/history/*.json) ------------------------------------------------------------------------------------------
/** -> rows { program, label, b, a, pct, spread, bs, as, ok } for the labels present on both sides. */
export function compareRows(doc) {
  const rows = [];
  for (const [prog, labels] of Object.entries(doc.programs)) {
    for (const [label, s] of Object.entries(labels)) {
      if (!s.before || !s.after) continue;
      const spread = Math.max(...[s.before, s.after].map(x => (x.times && x.times.length ? median(x.times) / Math.min(...x.times) - 1 : 0))) * 100;
      rows.push({ program: prog, label: label.replace("Caspien ", "").replace(" · full", ""), b: s.before.time_s, a: s.after.time_s,
        pct: (s.after.time_s / s.before.time_s - 1) * 100, spread, bs: s.before.size_bytes, as: s.after.size_bytes, ok: s.after.ok });
    }
  }
  return rows;
}
export function median(xs) { const s = [...xs].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; }
