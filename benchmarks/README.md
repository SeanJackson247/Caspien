# Caspien benchmarks

Three things, kept apart:

| folder | job | knows about |
|---|---|---|
| `harness/` | **measures**: builds every implementation, times it, writes JSON | programs, toolchains. Nothing about charts |
| `site/` | **presents**: static HTML + JS that draws charts and tables from the JSON | the JSON in `site/data/`. Nothing about compilers |
| `tools/` | glue: README images, compile-time run, leak scan, switch tuning, Rust-safe check | both, through their public files |

`programs/` holds the sources (`programs.json` is the one manifest: size, environment variable, Caspien variants, reference labels, tolerances).
`experiments/` holds one-off studies (recursion cost, ghost-table ids). `docs/` holds the written results.

## Look at the results

    jwebserver -d site            # then open http://127.0.0.1:8000/
    python3 -m http.server -d site   # any static server does

`index.html` = every table and chart (charts are drawn when a section is opened), `compare.html#<id>` = before/after studies (`site/data/history/`).
The page needs HTTP (`fetch`), not `file://`.

## Measure

Run from this folder; it sits inside a Caspien checkout (`CASPIEN_REPO=/path` to point elsewhere).

    python3 -m harness run                       # all programs, quick mode
    python3 -m harness run lru sieve --mode full # published numbers
    python3 -m harness run lru --caspien-only    # re-measure Caspien, keep the other languages' rows (writes lru.quick.json)
    python3 -m harness check lru zig             # build one of the newer languages' ports (fortran, objc, odin, zig, ... see -h) and compare its output with C
    python3 -m harness index                     # rewrite site/data/index.json

`python3 -m harness run -h` explains quick versus full. Results go straight to `site/data/<program>.json` (one row per line, so diffs stay readable).

## Tools

    node tools/export_svgs.mjs        # img/*.svg for the README; uses the site's own JS, so the pictures cannot drift from the page
    python3 tools/compile_times.py    # site/data/compile_times.json
    python3 tools/leak_scan.py        # ghost-table leak check of every Caspien variant at small N
    python3 tools/tune_specific.py    # harness/specific.json (per-program switch overrides)
    python3 tools/rust_safe_check.py  # site/data/rust_safe.json

## Layout of the site code

`js/model.js` pure data logic (no DOM; also used by Node) - `js/svg.js` pure SVG-string builders - `js/ui.js` DOM helpers, tooltip, sortable table -
`js/app.js` the main page - `js/compare.js` the comparison page - `style.css` the only stylesheet - `languages.json` the one language table
(names, colours, groups, overview labels, memory-safety flag).
