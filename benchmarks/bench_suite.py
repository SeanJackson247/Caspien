#!/usr/bin/env python3
"""
Generic cross-language benchmark runner for the programs added after n-body / fannkuch-redux / spectral-norm
(sieve, strings, graph, sorting). Same four metrics as the other harnesses (execution time, peak memory, compile time, size).

    python3 benchmarks/bench_suite.py sieve [--n N] [--runs 3] [--builds 1] [--only substring ...] [--out file.json]

Layout of one program: benchmarks/<name>/reference/<name>.{c,cpp,rs,go,js,lua} + <JavaClass>.java, benchmarks/<name>/caspien/*.caspien.
Bare-metal programs that allocate come in two builds: "free" (cleans up after itself) and "leak" (-DLEAK / --cfg leak: never frees).
Every program prints a short, deterministic result; the output of the C -O0 build is the reference every row is compared with.
Caspien variants are measured with every optimisation off and with everything on. Caspien sources import the stdlib as
"../../../stdlib/x.caspien"; the harness copies them into a scratch tree and rewrites that to "stdlib/x.caspien".
"""
import argparse, json, os, re, shutil, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B   # noqa: E402
import newlangs as NL   # noqa: E402

PROGRAMS = {
    "sieve": dict(title="Sieve of Eratosthenes", env="SIEVE_N", n=100000000, java_class="Sieve", leak=True,
                  caspien=[("sieve_naive", "naive (DynamicArray)", "stdlib DynamicArray<u8>, get/set under bounds proofs"),
                           ("sieve_safe", "optimized safe", "safe dynarray, bounds proofs"),
                           ("sieve_unsafe", "optimized unsafe", "unsafe dynarray, no checks")]),
    "strings": dict(title="String manipulation", env="STRINGS_N", n=20000000, java_class="Strings", leak=True,
                    caspien=[("strings_naive", "naive (String class)", "stdlib String: appendChar/charAt/setCharAt per character"),
                             ("strings_safe", "optimized safe", "safe dynarray<u8> buffers, bounds proofs"),
                             ("strings_unsafe", "optimized unsafe", "unsafe dynarray<u8> buffers, no checks")]),
    "graph": dict(title="Heap graph search", env="GRAPH_N", n=2000000, java_class="Graph", leak=True,
                  caspien=[("graph_naive", "naive (DynamicArray)", "stdlib DynamicArray<u64> x4 (index-based edges), get/set under bounds proofs"),
                           ("graph_safe", "optimized safe", "safe dynarrays, bounds proofs"),
                           ("graph_unsafe", "optimized unsafe", "unsafe dynarrays, no checks"),
                           ("graph_struct_safe", "struct nodes, safe", "ONE safe dynarray of Node structs (value, dist, 4 edge indices), bounds proofs"),
                           ("graph_struct_unsafe", "struct nodes, unsafe", "ONE unsafe dynarray of Node structs, no checks"),
                           ("graph_ref_naive", "ref naive", "REF: every node its own heap object, 4 plain `ref` edges to nodes of the same struct, owned by a dynarray of owns slots; BFS queue = stdlib DynamicArray<QRef>; alive proof per deref"),
                           ("graph_ref_safe", "ref safe", "REF: self-referential Node (4 `ref` edges), owner dynarray + queue of refs in safe dynarrays, alive proof per deref"),
                           ("graph_ref_unsafe", "ref unsafe", "REF: as ref safe, but the queue of refs is an unsafe dynarray (the owner dynarray must stay safe)")]),
    "sorting": dict(title="Sorting and searching", env="SORT_N", n=2000000, java_class="Sorting", leak=True,
                    caspien=[("sorting_naive", "naive (DynamicArray)", "stdlib DynamicArray<u64>, get/set under bounds proofs"),
                             ("sorting_safe", "optimized safe", "safe dynarrays, bounds proofs"),
                             ("sorting_unsafe", "optimized unsafe", "unsafe dynarrays, no checks")]),
    'binarytrees': dict(**{'title': 'Binary trees', 'env': 'BINARYTREES_N', 'n': 17, 'java_class': 'BinaryTrees', 'leak': True, 'caspien': [('binarytrees_naive', 'naive (DynamicArray)', 'index-based tree: ONE stdlib DynamicArray<Node> pool + DynamicArray<u64> work stack, free-list pool, get/setPtr under bounds proofs'), ('binarytrees_safe', 'optimized safe', 'index-based tree: ONE safe dynarray of Node structs (l, r indices) + free list, bounds proofs'), ('binarytrees_unsafe', 'optimized unsafe', 'index-based tree: ONE unsafe dynarray of Node structs + free list, no checks'), ('binarytrees_ref_naive', 'ref naive', 'REF: every node its own heap object (new Node) with `ref` l/r members of the same struct, owned by a pool of owns slots; stacks = stdlib DynamicArray; alive proof per deref'), ('binarytrees_ref_safe', 'ref safe', 'REF: self-referential Node, owner pool + stacks in safe dynarrays, alive proof per deref'), ('binarytrees_ref_unsafe', 'ref unsafe', 'REF: as ref safe, with unsafe dynarray work stacks (the owner pool must stay safe)')]}),
    'mandelbrot': dict(**{'title': 'Mandelbrot', 'env': 'MANDELBROT_N', 'n': 3000, 'java_class': 'Mandelbrot', 'leak': False, 'caspien': [('mandelbrot', 'f64 scalars', 'f64 scalar maths, no arrays or allocation (loop indices mirrored as f64, no int-to-float cast)')]}),
    'fasta': dict(**{'title': 'FASTA generation', 'env': 'FASTA_N', 'n': 80000000, 'java_class': 'Fasta', 'leak': True, 'caspien': [('fasta_naive', 'naive (DynamicArray)', 'stdlib DynamicArray<u8>/<u64> for the output buffer, constant table and thresholds, get/set under bounds proofs'), ('fasta_safe', 'optimized safe', 'safe dynarrays, bounds proofs'), ('fasta_unsafe', 'optimized unsafe', 'unsafe dynarrays, no checks')]}),
    'knucleotide': dict(**{'title': 'k-nucleotide (hash map)', 'env': 'KNUC_N', 'n': 20000000, 'java_class': 'Knucleotide', 'leak': True, 'caspien': [('knucleotide_naive', 'naive (stdlib HashMap)', 'stdlib HashMap<u64> (insecure_hashOf per key, fixed capacity) + DynamicArray, get/set under bounds proofs'), ('knucleotide_safe', 'optimized safe', 'hand-written open-addressing table in safe dynarrays, bounds proofs'), ('knucleotide_unsafe', 'optimized unsafe', 'hand-written open-addressing table in unsafe dynarrays, no checks'), ('knucleotide_naive_refstd', 'naive, ref in stdlib', 'identical to naive, but the stdlib HashMap is stdlib/ref/hash_map.caspien (separate chaining over `ref` nodes)')]}),
    'lru': dict(**{'title': 'LRU cache', 'env': 'LRU_N', 'n': 20000000, 'java_class': 'Lru', 'leak': True, 'caspien': [('lru_naive', 'naive (DynamicArray + HashMap)', 'stdlib DynamicArray<u64> node pool + stdlib HashMap<u64> (key -> node index; no remove, evicted keys map to a NONE sentinel), get/set under bounds proofs'), ('lru_safe', 'optimized safe', 'safe dynarrays: index-based doubly linked recency list + chained hash map threaded through the node pool, bounds proofs'), ('lru_unsafe', 'optimized unsafe', 'unsafe dynarrays, same index-based list and chained hash map, no checks'), ('lru_ref_naive', 'ref naive', 'REF: doubly linked list + hash chains of heap Entry nodes with `ref` prv/nxt/hn members; bucket heads in a stdlib DynamicArray<QRef>; alive proof per deref'), ('lru_ref_safe', 'ref safe', 'REF: self-referential Entry (prv/nxt/hn refs), owner pool + bucket heads in safe dynarrays'), ('lru_ref_unsafe', 'ref unsafe', 'REF: as ref safe, bucket heads in an unsafe dynarray'), ('lru_naive_refstd', 'naive, ref in stdlib', 'identical to naive, but the stdlib HashMap is stdlib/ref/hash_map.caspien (separate chaining over `ref` nodes)')]}),
    'merkletrees': dict(**{'title': 'Merkle tree (SHA-256)', 'env': 'MERKLE_N', 'n': 140000, 'java_class': 'MerkleTrees', 'leak': True, 'caspien': [('merkletrees_naive', 'naive (DynamicArray)', 'real SHA-256; stdlib DynamicArray<u32> x3 (leaf data, flat tree of 8-word digests, proof path), get/set under bounds proofs'), ('merkletrees_safe', 'optimized safe', 'real SHA-256; safe dynarrays, bounds proofs'), ('merkletrees_unsafe', 'optimized unsafe', 'real SHA-256; unsafe dynarrays, no checks')]}),
    'helloworld': dict(**{'title': 'Hello World', 'env': 'HELLOWORLD_N', 'n': 1, 'java_class': 'HelloWorld', 'leak': False, 'caspien': [('helloworld', 'plain printf', 'single printf of the line, no allocation (N ignored); measures startup, compile time and binary size')]}),
    'json_serde': dict(**{'title': 'JSON serialise + parse', 'env': 'JSON_SERDE_N', 'n': 4000000, 'java_class': 'JsonSerde', 'leak': True, 'caspien': [('json_serde_naive', 'naive (DynamicArray)', 'stdlib DynamicArray<u8> buffers + ONE DynamicArray<Rec> for the records, get/setPtr under bounds proofs'), ('json_serde_safe', 'optimized safe', 'safe dynarrays (u8 buffers + one dynarray of Rec structs), bounds proofs'), ('json_serde_unsafe', 'optimized unsafe', 'unsafe dynarrays (u8 buffers + one dynarray of Rec structs), no checks')]}),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("program", choices=sorted(PROGRAMS))
    ap.add_argument("--n", type=int)
    B.add_mode_args(ap)
    ap.add_argument("--out")
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
    S = B.settings(a)
    limits = {}
    name = a.program
    P = PROGRAMS[name]
    n = a.n or P["n"]
    N = str(n)
    pdir = os.path.join(HERE, name)
    REF, CAS = os.path.join(pdir, "reference"), os.path.join(pdir, "caspien")
    out_json = a.out or os.path.join(pdir, "results.quick.json" if S["caspien_only"] else "results.json")
    old_rows = B.existing_rows(S, os.path.join(pdir, "results.json"), n)
    jc = P["java_class"]
    W = tempfile.mkdtemp(prefix=name + "_bench_")
    for f in os.listdir(REF):
        shutil.copy(os.path.join(REF, f), W)
    BC = B.BuildCache(W, S["build_cache"])
    results = []
    envn = dict(os.environ, JAVA_TOOL_OPTIONS="")
    envn[P["env"]] = N
    noj = dict(os.environ, JAVA_TOOL_OPTIONS="")

    def wanted(label):
        return not a.only or any(s.lower() in label.lower() for s in a.only)

    def measure(label, group, lang, build_cmds, exe_cmd, size_fn, note=""):
        if not wanted(label) or (S["caspien_only"] and group != "caspien"):
            return
        built = BC.compile(label, group, build_cmds, S["builds"])
        if built is None:
            return
        ctimes, reused = built
        if reused:
            note = (note + "; " if note else "") + "compile time reused from an earlier build (build cache)"
        status, times, rss, outs, text = B.timed_runs(exe_cmd, W, envn, S, limits, "", group == "caspien")
        if status == "fail":
            print("RUN FAILED", label, text[-300:], file=sys.stderr)
            return
        if status == "cutoff":
            r = B.cutoff_row(label, group, lang, "", note, B.median(ctimes), size_fn(), times[0])
            r["compile_cached"] = reused
            results.append(r)
            print("%-40s compile %6.2fs  CUT OFF at %.3fs (%s)" % (label, r["compile_s"], times[0], B.CUTOFF_NOTE), flush=True)
            return
        r = {"label": label, "group": group, "lang": lang, "prec": "", "note": note, "compile_s": B.median(ctimes),
             "size_bytes": size_fn(), "time_s": min(times), "times": times, "rss_kb": B.median(rss), "output": outs[-1]}
        B.note_caspien(limits, S, "", label, r["time_s"])
        r["compile_cached"] = reused
        results.append(r)
        print("%-40s compile %6.2fs  size %10d  time %8.3fs  rss %8d KB  %s" % (
            label, r["compile_s"], r["size_bytes"], r["time_s"], r["rss_kb"], " ".join(r["output"])[:50]), flush=True)

    mem = P["leak"]
    # ---- Caspien (green) ----
    if B.have("java") and B.have("gcc") and P["caspien"]:
        ct = B.make_caspien_tree(ROOT)
        base = open(os.path.join(ct, "toolchain.config")).read()
        for src, kind, desc in P["caspien"]:
            text = open(os.path.join(CAS, src + ".caspien")).read().replace("../../../stdlib/", "stdlib/")
            for mode, kv in B.caspien_modes(S, a.program):
                label = "Caspien %s · %s" % (kind, mode)
                if not wanted(label):
                    continue
                open(os.path.join(ct, "toolchain.config"), "w").write(base)
                B.patch_config(os.path.join(ct, "toolchain.config"), kv)
                open(os.path.join(ct, "_bp.caspien"), "w").write(text)
                exe = os.path.join(W, "cas_%s_%s" % (src, mode))
                measure(label, "caspien", "Caspien", [{"cmd": ["java", "Compiler", "-i", "_bp.caspien", exe, "--no-cache"], "cwd": ct, "env": noj}],
                        [exe], lambda e=exe: B.size_of(e), desc + "; optimisations " + mode)
        shutil.rmtree(ct, ignore_errors=True)
    # ---- bare metal (red) ----
    if B.have("gcc"):
        for opt in ("-O0", "-O2"):
            for mode, flags in (("free", []), ("leak", ["-DLEAK"])):
                if mode == "leak" and (not mem or opt == "-O0"):
                    continue
                exe = os.path.join(W, "c%s_%s" % (opt, mode))
                lab = "C %s%s" % (opt, " (%s)" % mode if mem and opt != "-O0" else "")
                measure(lab, "bare", "C", [{"cmd": ["gcc", opt] + flags + ["-o", exe, name + ".c", "-lm"]}], [exe, N], lambda e=exe: B.size_of(e))
    if B.have("g++"):
        for mode, flags in (("free", []), ("leak", ["-DLEAK"])):
            if mode == "leak" and not mem:
                continue
            exe = os.path.join(W, "cpp_%s" % mode)
            measure("C++ -O2" + (" (%s)" % mode if mem else ""), "bare", "C++",
                    [{"cmd": ["g++", "-O2", "-x", "c++"] + flags + ["-o", exe, name + ".cpp", "-lm"]}], [exe, N], lambda e=exe: B.size_of(e))
    if B.have("rustc"):
        for mode, flags in (("free", []), ("leak", ["--cfg", "leak"])):
            if mode == "leak" and not mem:
                continue
            exe = os.path.join(W, "rs_%s" % mode)
            measure("Rust -O" + (" (%s)" % mode if mem else ""), "bare", "Rust",
                    [{"cmd": ["rustc", "-O"] + flags + ["-o", exe, name + ".rs"]}], [exe, N], lambda e=exe: B.size_of(e))
    if B.have("go"):
        exe = os.path.join(W, "go_bin")
        genv = dict(os.environ, GOCACHE=os.path.join(W, "gocache"), GOFLAGS="-buildvcs=false")
        measure("Go", "bare", "Go", [{"cmd": ["go", "build", "-o", exe, name + ".go"], "env": genv}], [exe, N], lambda: B.size_of(exe))
    # ---- compiled + VM (purple) ----
    if B.have("javac") and B.have("java"):
        cls = os.path.join(W, jc + ".class")
        measure("Java", "vm", "Java", [{"cmd": ["javac", "-d", W, jc + ".java"], "env": noj}], ["java", "-cp", W, jc, N],
                lambda: B.java_runtime_size() + B.size_of(cls), "size = bin/java + lib/ + conf/ of the JRE plus the class file")
    # ---- JavaScript (blue) ----
    if B.have("node"):
        measure("Node", "js", "JavaScript", [], ["node", name + ".js", N],
                lambda: B.size_of(os.path.realpath(shutil.which("node"))) + B.size_of(os.path.join(W, name + ".js")), "size = node binary + script")
    if B.have("bun"):
        measure("Bun", "js", "JavaScript", [], ["bun", name + ".js", N],
                lambda: B.size_of(os.path.realpath(shutil.which("bun"))) + B.size_of(os.path.join(W, name + ".js")), "size = bun binary + script")
    if B.have("luajit"):
        path = os.path.realpath(shutil.which("luajit"))
        measure("LuaJIT", "luajit", "LuaJIT", [], ["luajit", name + ".lua", N],
                lambda p=path: B.size_of(p) + B.size_of(os.path.join(W, name + ".lua")), "size = luajit binary + script (tracing JIT)")
    # ---- the fifteen languages added on 1 Oct 2026 (benchmarks/newlangs.py has the build commands) ----
    for row in NL.rows(name, W, W, N):
        measure(row["label"], row["group"], row["lang"], row["build"], row["exe"], row["size"], row["note"])
    results = old_rows + results
    results.sort(key=lambda r: r["group"] == "caspien")   # Caspien rows last, as in the charts
    ref = B.pick_reference(results, ["C -O0", "C -O2", "C -O2 (free)"])
    for r in results:
        r["ok"] = bool(r.get("cutoff")) or (ref is not None and r["output"] == ref)   # a cut-off row never produced output
    json.dump({"program": name, "title": P["title"], "n": n, "mode": B.result_mode(S), "runs": S["runs"], "builds": S["builds"], "cutoff_s": limits.get("") if S["cutoff"] else None, "results": results},
              open(out_json, "w"), indent=1)
    shutil.rmtree(W, ignore_errors=True)
    print("wrote", out_json, "| differs from the C reference:", [r["label"] for r in results if not r["ok"]] or "none", "| cut off:", sum(1 for r in results if r.get("cutoff")))


if __name__ == "__main__":
    main()
