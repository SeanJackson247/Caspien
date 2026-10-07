#!/usr/bin/env python3
"""
n-body cross-language benchmark: execution time, peak memory, compile time and executable size.

    python3 benchmarks/nbody/bench.py [--n 5000000] [--runs 5] [--out results.json] [--only substring ...]

Builds and runs every implementation that this machine has a toolchain for, and writes one JSON file (default
benchmarks/nbody/results.json) with, per implementation:
  compile_s   wall time of the build command (median of --builds builds; 0 for Node, Bun, Lua and LuaJIT, which have no compile step)
  size_bytes  the executable; for Java, Node and Bun the size of their runtime plus the program (class file / script)
  time_s      wall time of one run (minimum of --runs)
  rss_kb      peak resident set size of one run (median of --runs), from wait4()
  output      the two energies the program prints; ok = matches the C program of the same precision at the same N
Caspien programs are built twice, with every optimisation off and with everything on (all optimizer passes at their most
aggressive setting plus register variables/temporaries). The shipped toolchain.config targets windows_gnu, so on Linux a scratch copy of
the tree is made with `target linux` and `default: sysv_x64`; the shipped file is not touched.
Charts are made from the JSON by charts.py.
"""
import argparse, hashlib, json, os, re, shutil, signal, statistics, subprocess, sys, tarfile, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REF = os.path.join(HERE, "reference")
CAS = os.path.join(HERE, "caspien")

OFF = {
    "deferred-operands": "off", "variables-in-registers": "off", "float-variables-in-registers": "off",
    "float-temporaries-in-registers": "off", "hoist-array-bases": "off", "variables-in-alloc-functions": "off", "fuse-length-compare": "off", "variables-in-arg-registers": "off", "loop-rotation": "off", "copy-forward": "off", "conditional-move": "off", "loop-unrolling": "off", "function-inlining": "off", "constant-folding": "off",
    "variable-elision": "off", "variable-shifting": "off", "struct-unpacking": "off", "dead-control-flow-removal": "off",
    "dead-function-removal": "off", "unused-declaration-removal": "off",
}
FULL = {
    "deferred-operands": "on", "variables-in-registers": "on", "float-variables-in-registers": "on",
    "float-temporaries-in-registers": "on", "hoist-array-bases": "on", "variables-in-alloc-functions": "on", "fuse-length-compare": "on", "variables-in-arg-registers": "on", "loop-rotation": "on", "copy-forward": "on", "conditional-move": "on", "loop-unrolling": "aggressive", "function-inlining": "aggressive",
    "constant-folding": "on", "variable-elision": "on", "variable-shifting": "on", "struct-unpacking": "on",
    "dead-control-flow-removal": "on", "dead-function-removal": "on", "unused-declaration-removal": "on", "bmi2": "on", "avx": "off", "jcc-padding": "on",
}
# (source file, precision, description)
CASPIEN = [
    ("nbody_natural_f64", "f64", "natural (small functions, loops)"),
    ("nbody_plain_f64", "f64", "plain loops"),
    ("nbody_loop_f64", "f64", "looped nested match"),
    ("nbody_arr_f64", "f64", "hand-unrolled, arrays"),
    ("nbody_f64", "f64", "hand-unrolled, scalars"),
    ("nbody_plain", "f32", "plain loops"),
    ("nbody_loop", "f32", "looped nested match"),
    ("nbody_arr", "f32", "hand-unrolled, arrays"),
    ("nbody", "f32", "hand-unrolled, scalars"),
]


LAUNCHER_C = r"""
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/resource.h>
int main(int argc, char **argv){ /* rsswrap <outfile> cmd args... : runs cmd, writes its peak RSS (KB) to outfile */
  pid_t p = fork();
  if(p==0){ execvp(argv[2], argv+2); _exit(127); }
  int st; struct rusage ru; wait4(p,&st,0,&ru);
  FILE *f=fopen(argv[1],"w"); fprintf(f,"%ld\n",ru.ru_maxrss); fclose(f);
  return WIFEXITED(st)?WEXITSTATUS(st):128+WTERMSIG(st);
}
"""
_LAUNCHER = None


def launcher():
    """A tiny C launcher: wait4 on a child of the (large) Python process reports Python's own peak RSS, inherited across
    fork/exec, so every program would show the same number. A small launcher process has no such history."""
    global _LAUNCHER
    if _LAUNCHER is None:
        d = tempfile.mkdtemp(prefix="rsswrap")
        src = os.path.join(d, "w.c")
        open(src, "w").write(LAUNCHER_C)
        _LAUNCHER = os.path.join(d, "rsswrap")
        subprocess.check_call(["gcc", "-O1", "-o", _LAUNCHER, src])
    return _LAUNCHER


def run(cmd, cwd=None, env=None, timeout=900):
    """Run a command; returns (wall seconds, peak RSS in KB, exit code, output text). On timeout the whole process group is killed
    (the launcher forks the program, so killing only the launcher would leave it running) and the result is (timeout, 0, 124, text)."""
    base = os.environ if env is None else env
    env = {k: v for k, v in base.items() if k != "JAVA_TOOL_OPTIONS"}   # even an empty value makes java print "Picked up ..."
    rf = tempfile.mktemp(prefix="rss")
    t0 = time.perf_counter()
    p = subprocess.Popen([launcher(), rf] + list(cmd), cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        stdout, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:   # a row that takes longer than `timeout` is reported as failed/cut off, it must not abort the whole run
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        p.communicate()
        return float(timeout), 0, 124, "TIMEOUT after %s s" % timeout
    t1 = time.perf_counter()
    try:
        kb = int(open(rf).read().strip()); os.unlink(rf)
    except Exception:
        kb = 0
    return t1 - t0, kb, p.returncode, stdout.decode("utf-8", "replace")


# ---- benchmark modes (shared by nbody/bench.py, bench_program.py, bench_suite.py; run_all.py runs every program) ----
#   quick (default): Caspien builds only the optimised ("full") configuration and is measured FIRST. Every other implementation then runs
#       with a time limit equal to the slowest optimised Caspien variant of that program (same precision); one whose first run reaches the
#       limit is killed and recorded as "would have taken longer than optimized Caspien" (a full-colour bar at the limit, flagged `cutoff`).
#       Fewer repeats and builds. `--with-off` also builds the optimisations-off Caspien variants.
#   full: everything: off and optimised Caspien builds, no time limit, more repeats and builds.
MODES = {"quick": dict(runs=3, builds=1, off=False, cutoff=True),
         "full": dict(runs=5, builds=3, off=True, cutoff=False)}
CUTOFF_NOTE = "would have taken longer than optimized Caspien"
MIN_LIMIT = 0.1   # seconds: a program whose slowest optimised Caspien run is millisecond-scale (hello world) would otherwise be decided by process start-up noise


def add_mode_args(ap):
    ap.add_argument("--mode", choices=sorted(MODES), default="quick", help="quick (default): optimised Caspien only, slower competitors cut off; full: everything, no cutoff")
    ap.add_argument("--with-off", action="store_true", help="quick mode: also build and measure the optimisations-off Caspien variants")
    ap.add_argument("--runs", type=int, help="runs per implementation (default 3 quick, 5 full)")
    ap.add_argument("--builds", type=int, help="builds per implementation for the compile time (default 1 quick, 3 full)")
    ap.add_argument("--no-build-cache", action="store_true", help="quick mode: rebuild the other languages' programs instead of reusing cached builds")
    ap.add_argument("--clear-build-cache", action="store_true", help="delete benchmarks/.build_cache first")
    ap.add_argument("--caspien-only", action="store_true", help="measure only the optimised Caspien programs and merge them with the other languages' rows of the existing results.json; writes results.quick.json (--out to change)")
    ap.add_argument("--no-leak-check", action="store_true", help="skip the one extra, untimed run of every Caspien program under tests/alloc_shim.c that reports blocks still registered in the ghost table at exit")


def settings(a):
    m = MODES[a.mode]
    if a.clear_build_cache:
        shutil.rmtree(BUILD_CACHE, ignore_errors=True)
    return dict(mode=a.mode, runs=a.runs or m["runs"], builds=a.builds or m["builds"], off=m["off"] or a.with_off, cutoff=m["cutoff"] and not a.caspien_only,
                build_cache=(a.mode == "quick" and not a.no_build_cache), caspien_only=a.caspien_only, leak_check=not a.no_leak_check)


def existing_rows(S, path, n):
    """--caspien-only: the other implementations' rows from the existing results file (refuses a file made with a different N)."""
    if not S["caspien_only"]:
        return []
    if not os.path.exists(path):
        sys.exit("--caspien-only needs the existing results file %s" % path)
    old = json.load(open(path))
    if old.get("n") != n:
        sys.exit("%s was measured with N=%s, this run uses N=%s: the other languages' rows would not be comparable (pass --n %s)" % (path, old.get("n"), n, old.get("n")))
    return [r for r in old["results"] if r["group"] != "caspien"]


def result_mode(S):
    return "quick, Caspien only (other languages' rows from the existing results.json)" if S["caspien_only"] else S["mode"]


def specific_overrides(prog):
    """The per-program switch overrides of the "specific" build: env BENCH_SPECIFIC_JSON (a JSON object, used by tune_specific.py) or benchmarks/specific.json[prog]."""
    env = os.environ.get("BENCH_SPECIFIC_JSON")
    if env is not None:
        return json.loads(env)
    try:
        return json.load(open(os.path.join(HERE, "..", "specific.json"))).get(prog, {})
    except (OSError, ValueError):
        return {}


def caspien_modes(S, prog=None):
    """[(mode name, config dict)] to build for each Caspien source file: "off" (with --with-off / full mode), "full" (everything on, one config for
    every program) and, when the program has overrides in benchmarks/specific.json, "specific" (full + that program's tuned switches, e.g. jcc-padding off)."""
    modes = ([("off", OFF)] if S["off"] else []) + [("full", FULL)]
    ov = specific_overrides(prog) if prog else {}
    if ov:
        modes.append(("specific", dict(FULL, **ov)))
    return modes


def note_caspien(limits, S, prec, label, t):
    """Record the slowest optimised Caspien time per precision: the limit for everything measured afterwards."""
    if S["cutoff"] and label.endswith("· full") and " ref " not in label + " " and "ref in stdlib" not in label:   # the ref (alive-checked) variants are far slower and must not raise the cutoff
        limits[prec] = max(limits.get(prec, 0.0), t)


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEAK_LOG = os.environ.get("BENCH_LEAK_LOG", os.path.join(REPO_ROOT, ".leaks.log"))


def gt_leak(cmd, cwd, env):
    """Runs a Caspien benchmark binary ONCE more (untimed) under tests/alloc_shim.c and returns the number of blocks still registered in its
    ghost table at exit (0 = it freed everything; None = no table or the check could not run). Valgrind cannot see these leaks: the table
    keeps every registered block reachable."""
    root = REPO_ROOT
    src = os.path.join(root, "tests", "alloc_shim.c")
    so = os.path.join(root, ".cache", "alloc_shim.so")
    try:
        os.makedirs(os.path.dirname(so), exist_ok=True)
        if not os.path.exists(so) or os.path.getmtime(so) < os.path.getmtime(src):
            subprocess.run(["gcc", "-shared", "-fPIC", "-o", so, src, "-ldl"], check=True, capture_output=True)
        exe = cmd[0] if os.path.isabs(cmd[0]) else os.path.join(cwd or ".", cmd[0])
        nm = subprocess.run(["nm", exe], capture_output=True, text=True).stdout
        m = re.search(r"^([0-9a-f]+) [DdBb] ghost_table$", nm, flags=re.M)
        if not m:
            return None
        e = {k: v for k, v in (os.environ if env is None else env).items() if k != "JAVA_TOOL_OPTIONS"}
        e.update(LD_PRELOAD=so, GT_ADDR=m.group(1), GT_EXE=os.path.abspath(exe))
        r = subprocess.run(list(cmd), cwd=cwd, env=e, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=1800)
        g = re.search(r"GT_LEN=(\d+)", r.stderr)
        return int(g.group(1)) if g else None
    except Exception:
        return None


def timed_runs(cmd, cwd, env, S, limits, prec, is_caspien):
    """-> (status, times, rss, outs, text). status 'ok', 'fail' (non-zero exit) or 'cutoff' (first run reached the limit and was killed)."""
    limit = None if is_caspien or not S["cutoff"] or prec not in limits else max(limits[prec], MIN_LIMIT)
    times, rss, outs = [], [], []
    for k in range(S["runs"]):
        dt, kb, rc, out = run(cmd, cwd=cwd, env=env, timeout=limit if limit else 900)
        if rc == 124 and limit:
            if k == 0:
                return "cutoff", [limit], [], [], out
            break   # a later repeat slower than the limit: keep the faster ones already measured
        if rc != 0:
            return "fail", times, rss, outs, out
        times.append(dt); rss.append(kb); outs.append(out.strip().split())
    if is_caspien and S.get("leak_check", True):
        leaked = gt_leak(cmd, cwd, env)
        if leaked:
            msg = "LEAK %s: %d block(s) still registered in the ghost table at exit" % (os.path.basename(cmd[0]), leaked)
            print("!! " + msg, flush=True)
            with open(LEAK_LOG, "a") as f:
                f.write(msg + "\n")
    return "ok", times, rss, outs, ""


def cutoff_row(label, group, lang, prec, note, compile_s, size, limit):
    return {"label": label, "group": group, "lang": lang, "prec": prec, "note": (note + "; " if note else "") + CUTOFF_NOTE,
            "compile_s": compile_s, "size_bytes": size, "time_s": limit, "times": [limit], "rss_kb": 0, "output": [], "cutoff": True}


# ---- build cache for the other languages' programs (quick mode) --------------------------------------------------------------------------------
# A build of a foreign-language program is reused when nothing it depends on changed: the program's source files, the build command, and the
# compiler it runs (path, size, mtime). The files a build adds to the scratch folder (executables, class files, jars, a .NET output folder; not
# the compilers' own cache folders) are stored as a tar.gz under benchmarks/.build_cache and unpacked again on a hit. The compile time stored
# with the entry is reported again (the row's note says so), so quick mode shows the time of the build that made the artifact. Caspien's own
# builds are never cached here: the compiler is what is being measured. The program is still run, and timed, every time. Full mode builds
# everything from scratch.
BUILD_CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".build_cache")
_SKIP_DIRS = ("cache", "obj", "dnhome", "emptyfeed")


def _walk(root, skip=_SKIP_DIRS):
    for dp, dns, fns in os.walk(root):
        dns[:] = sorted(d for d in dns if not any(k in d.lower() for k in skip))
        for f in sorted(fns):
            p = os.path.join(dp, f)
            if os.path.isfile(p) and not os.path.islink(p):
                yield p


def _hash_tree(root, skip=_SKIP_DIRS, scratch=None):
    """Hash of names and contents; `scratch` (the per-run scratch folder) is replaced by a placeholder in the contents, because generated project files mention it."""
    h = hashlib.sha256()
    for p in _walk(root, skip):
        h.update(os.path.relpath(p, root).encode()); h.update(b"\0")
        with open(p, "rb") as f:
            data = f.read()
        if scratch:
            data = data.replace(scratch.encode(), b"<W>")
        h.update(hashlib.sha256(data).digest())
    return h.hexdigest()


def _snapshot(root):
    return {os.path.relpath(p, root): (os.path.getsize(p), os.stat(p).st_mtime_ns) for p in _walk(root)}


def _tool_identity(cmd0, env):
    path = shutil.which(cmd0, path=(env or os.environ).get("PATH")) or cmd0
    try:
        st = os.stat(os.path.realpath(path))
        return "%s %d %d" % (os.path.realpath(path), st.st_size, st.st_mtime_ns)
    except OSError:
        return path


class BuildCache:
    def __init__(self, W, enabled):
        self.W = W
        self.enabled = enabled
        self.src = _hash_tree(W) if enabled else ""     # called right after the reference sources were copied in: everything in W is source
        self.hits = self.misses = 0

    def _key(self, build_cmds):
        h = hashlib.sha256(self.src.encode())
        for bc in build_cmds:
            norm = lambda x: str(x).replace(self.W, "<W>")
            h.update(("\n".join([norm(a) for a in bc["cmd"]]) + "\n" + norm(bc.get("cwd", self.W))).encode())
            env = bc.get("env")
            if env:
                h.update(repr(sorted((k, norm(v)) for k, v in env.items() if os.environ.get(k) != v and k != "JAVA_TOOL_OPTIONS")).encode())
            h.update(_tool_identity(bc["cmd"][0], env).encode())
            cwd = bc.get("cwd", self.W)
            if os.path.abspath(cwd) != os.path.abspath(self.W) and os.path.isdir(cwd):
                h.update(_hash_tree(cwd, _SKIP_DIRS + ("out", "bin"), self.W).encode())    # generated project files (C#)
        return h.hexdigest()

    def compile(self, label, group, build_cmds, builds):
        """Runs (or restores) the build commands. Returns (compile times, reused flag), or None when a build failed."""
        if not build_cmds:
            return [], False
        key = self._key(build_cmds) if self.enabled and group != "caspien" else None
        entry = os.path.join(BUILD_CACHE, key[:2], key) if key else None
        if entry and os.path.isfile(entry + ".tar.gz") and os.path.isfile(entry + ".time"):
            try:
                with tarfile.open(entry + ".tar.gz") as t:
                    t.extractall(self.W)
                ct = float(open(entry + ".time").read())
                self.hits += 1
                return [ct], True
            except (OSError, tarfile.TarError, ValueError):
                pass
        before = _snapshot(self.W) if key else None
        ctimes = []
        for _ in range(builds):
            t = 0.0
            for bc in build_cmds:
                dt, _, rc, out = run(bc["cmd"], cwd=bc.get("cwd", self.W), env=bc.get("env"))
                t += dt
                if rc != 0:
                    print("BUILD FAILED", label, out[-600:], file=sys.stderr)
                    return None
            ctimes.append(t)
        if key:
            self.misses += 1
            try:
                after = _snapshot(self.W)
                new = [p for p, v in after.items() if before.get(p) != v]
                os.makedirs(os.path.dirname(entry), exist_ok=True)
                tmp = entry + ".part"
                with tarfile.open(tmp, "w:gz") as t:
                    for p in new:
                        t.add(os.path.join(self.W, p), arcname=p)
                os.replace(tmp, entry + ".tar.gz")
                open(entry + ".time", "w").write(repr(median(ctimes)))
            except OSError:
                pass
        return ctimes, False


def pick_reference(results, labels):
    """Output of the first label that finished (a cut-off row has no output)."""
    for lab in labels:
        r = next((r for r in results if r["label"] == lab and not r.get("cutoff")), None)
        if r:
            return r["output"]
    r = next((r for r in results if not r.get("cutoff") and r["group"] != "caspien"), None)
    return r["output"] if r else None


def have(tool):
    return shutil.which(tool) is not None


def size_of(path):
    return os.path.getsize(path)


def dir_size(path):
    total = 0
    for dp, _, fs in os.walk(path):
        for f in fs:
            fp = os.path.join(dp, f)
            if os.path.isfile(fp) and not os.path.islink(fp):
                total += os.path.getsize(fp)
    return total


def java_runtime_size():
    """bin/java + lib/ + conf/ of the JRE that runs the program (not the JDK's compiler tools, jmods, docs or headers)."""
    r = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
    m = re.search(r"java\.home = (\S+)", r.stderr)
    home = m.group(1)
    return size_of(os.path.join(home, "bin", "java")) + dir_size(os.path.join(home, "lib")) + dir_size(os.path.join(home, "conf"))


def patch_config(path, kv):
    text = open(path).read()
    for k, v in kv.items():
        text, n = re.subn(r"^%s(:?) .*$" % re.escape(k), lambda m, k=k, v=v: "%s%s %s" % (k, m.group(1), v), text, flags=re.M)
        if n == 0:
            raise SystemExit("config key %s not found in %s" % (k, path))
    open(path, "w").write(text)


def make_caspien_tree(root):
    ct = tempfile.mkdtemp(prefix="caspien_tree_")
    for d in ("ASTGenerator", "Optimizer", "LowerOrderGenerator", "Codegen", "stdlib"):
        shutil.copytree(os.path.join(root, d), os.path.join(ct, d))
    for f in os.listdir(root):
        if f.startswith("Compiler"):
            shutil.copy(os.path.join(root, f), ct)
    cfg = open(os.path.join(root, "toolchain.config")).read()
    cfg = re.sub(r"^target windows_gnu", "target linux", cfg, flags=re.M)
    cfg = re.sub(r"^(\s*)default: win64", r"\1default: sysv_x64", cfg, flags=re.M)
    open(os.path.join(ct, "toolchain.config"), "w").write(cfg)
    return ct


def median(xs):
    return statistics.median(xs) if xs else 0.0


def B_pick(results, labels):
    return pick_reference(results, labels)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5000000)
    add_mode_args(ap)
    ap.add_argument("--out")
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
    S = settings(a)
    a.out = a.out or os.path.join(HERE, "results.quick.json" if S["caspien_only"] else "results.json")
    old_rows = existing_rows(S, os.path.join(HERE, "results.json"), a.n)
    limits = {}
    N = str(a.n)
    W = tempfile.mkdtemp(prefix="nbody_bench_")
    for f in os.listdir(REF):
        shutil.copy(os.path.join(REF, f), W)
    BC = BuildCache(W, S["build_cache"])
    results = []
    envn = dict(os.environ, NBODY_N=N, JAVA_TOOL_OPTIONS="")

    def wanted(label):
        return not a.only or any(s.lower() in label.lower() for s in a.only)

    def measure(label, group, lang, build_cmds, exe_cmd, size_bytes_fn, prec, note=""):
        if not wanted(label) or (S["caspien_only"] and group != "caspien"):
            return
        built = BC.compile(label, group, build_cmds, S["builds"])
        if built is None:
            return
        ctimes, reused = built
        if reused:
            note = (note + "; " if note else "") + "compile time reused from an earlier build (build cache)"
        status, times, rss, outs, text = timed_runs(exe_cmd, W, envn, S, limits, prec, group == "caspien")
        if status == "fail":
            print("RUN FAILED", label, text[-300:], file=sys.stderr)
            return
        if status == "cutoff":
            res = cutoff_row(label, group, lang, prec, note, median(ctimes), size_bytes_fn(), times[0])
            res["compile_cached"] = reused
            results.append(res)
            print("%-34s compile %6.2fs  CUT OFF at %.3fs (%s)" % (label, res["compile_s"], times[0], CUTOFF_NOTE), flush=True)
            return
        res = {
            "label": label, "group": group, "lang": lang, "prec": prec, "note": note,
            "compile_s": median(ctimes), "size_bytes": size_bytes_fn(), "time_s": min(times), "times": times,
            "rss_kb": median(rss), "output": outs[-1],
        }
        note_caspien(limits, S, prec, label, res["time_s"])
        res["compile_cached"] = reused
        results.append(res)
        print("%-34s compile %6.2fs  size %10d  time %7.3fs  rss %8d KB  %s" % (
            label, res["compile_s"], res["size_bytes"], res["time_s"], res["rss_kb"], " ".join(res["output"])), flush=True)

    # ---- Caspien (green): measured first, its slowest optimised time is the limit for every other implementation in quick mode ----
    if have("java") and have("gcc"):
        ct = make_caspien_tree(ROOT)
        base = open(os.path.join(ct, "toolchain.config")).read()
        for src, prec, desc in CASPIEN:
            for mode, kv in caspien_modes(S, "nbody"):
                label = "Caspien %s %s · %s" % ({"nbody": "scalars", "nbody_f64": "scalars"}.get(src, src.replace("nbody_", "").replace("_f64", "")), prec, mode)
                if not wanted(label):
                    continue
                open(os.path.join(ct, "toolchain.config"), "w").write(base)
                patch_config(os.path.join(ct, "toolchain.config"), kv)
                shutil.copy(os.path.join(CAS, src + ".caspien"), os.path.join(ct, "_nb.caspien"))
                exe = os.path.join(W, "cas_%s_%s" % (src, mode))
                measure(label, "caspien", "Caspien", [{"cmd": ["java", "Compiler", "-i", "_nb.caspien", exe, "--no-cache"], "cwd": ct,
                                                       "env": dict(os.environ, JAVA_TOOL_OPTIONS="")}],
                        [exe], lambda e=exe: size_of(e), prec, desc + "; optimisations " + mode)
        shutil.rmtree(ct, ignore_errors=True)
    # ---- bare metal (red) ----
    if have("gcc"):
        for opt, lab in (("-O0", "C -O0"), ("-O2", "C -O2")):
            exe = os.path.join(W, "c_" + opt[1:])
            measure(lab, "bare", "C", [{"cmd": ["gcc", opt, "-o", exe, "nbody.c", "-lm"]}], [exe, N], lambda e=exe: size_of(e), "f64")
        for opt, lab in (("-O0", "C f32 -O0"), ("-O2", "C f32 -O2")):
            exe = os.path.join(W, "cf32_" + opt[1:])
            measure(lab, "bare", "C", [{"cmd": ["gcc", opt, "-o", exe, "nbody_f32.c", "-lm"]}], [exe, N], lambda e=exe: size_of(e), "f32")
    if have("g++"):
        exe = os.path.join(W, "cpp_O2")
        measure("C++ -O2", "bare", "C++", [{"cmd": ["g++", "-O2", "-x", "c++", "-o", exe, "nbody.cpp", "-lm"]}], [exe, N], lambda: size_of(exe), "f64")
    if have("rustc"):
        exe = os.path.join(W, "rs_nbody")
        measure("Rust -O", "bare", "Rust", [{"cmd": ["rustc", "-O", "-o", exe, "nbody.rs"]}], [exe, N], lambda: size_of(exe), "f64")
    if have("go"):
        exe = os.path.join(W, "go_nbody")
        genv = dict(os.environ, GOCACHE=os.path.join(W, "gocache"), GOFLAGS="-buildvcs=false")
        measure("Go", "bare", "Go", [{"cmd": ["go", "build", "-o", exe, "nbody.go"], "env": genv}], [exe, N], lambda: size_of(exe), "f64")
    # ---- compiled + VM (purple) ----
    if have("javac") and have("java"):
        cls = os.path.join(W, "NBody.class")
        measure("Java", "vm", "Java", [{"cmd": ["javac", "-d", W, "NBody.java"], "env": dict(os.environ, JAVA_TOOL_OPTIONS="")}],
                ["java", "-cp", W, "NBody", N], lambda: java_runtime_size() + size_of(cls), "f64",
                "size = bin/java + lib/ + conf/ of the JRE plus NBody.class")
    # ---- interpreted (blue) ----
    if have("node"):
        measure("Node", "js", "JavaScript", [], ["node", "nbody.js", N],
                lambda: size_of(os.path.realpath(shutil.which("node"))) + size_of(os.path.join(W, "nbody.js")), "f64",
                "size = node binary + nbody.js")
    if have("bun"):
        measure("Bun", "js", "JavaScript", [], ["bun", "nbody.js", N],
                lambda: size_of(os.path.realpath(shutil.which("bun"))) + size_of(os.path.join(W, "nbody.js")), "f64",
                "size = bun binary + nbody.js")
    # ---- LuaJIT (Lua 5.4 was dropped from the analysis: too slow to measure) ----
    for exe_name, lab, grp, what in (("luajit", "LuaJIT", "luajit", "tracing JIT"),):
        if have(exe_name):
            path = os.path.realpath(shutil.which(exe_name))
            measure(lab, grp, lab, [], [exe_name, "nbody.lua", N],
                    lambda p=path: size_of(p) + size_of(os.path.join(W, "nbody.lua")), "f64",
                    "size = %s binary + nbody.lua (%s)" % (exe_name, what))
    # ---- the fifteen languages added on 1 Oct 2026 ----
    sys.path.insert(0, os.path.dirname(HERE))
    import newlangs as NL
    for row in NL.rows("nbody", W, W, N):
        measure(row["label"], row["group"], row["lang"], row["build"], row["exe"], row["size"], "f64", row["note"])
    results = old_rows + results
    # ---- correctness: same precision as the C program at the same N (C -O0, or C -O2 when -O0 was cut off) ----
    results.sort(key=lambda r: r["group"] == "caspien")   # Caspien rows last, as in the charts
    ref = {"f64": B_pick(results, ["C -O0", "C -O2"]), "f32": B_pick(results, ["C f32 -O0", "C f32 -O2"])}
    for r in results:
        r["ok"] = False
        if r.get("cutoff"):
            r["ok"] = True      # killed before it produced output: nothing to differ
        elif r["prec"] in ref and ref[r["prec"]] and len(r["output"]) == 2 and len(ref[r["prec"]]) == 2:
            if r["prec"] == "f64":
                r["ok"] = r["output"] == ref["f64"]          # double precision: identical to 9 digits
            else:                                            # f32 ports round differently (operation order): same to ~4 digits
                r["ok"] = all(abs(float(x) - float(y)) < 5e-4 for x, y in zip(r["output"], ref["f32"]))
    json.dump({"n": a.n, "mode": result_mode(S), "runs": S["runs"], "builds": S["builds"], "cutoff_s": limits if S["cutoff"] else None, "results": results}, open(a.out, "w"), indent=1)
    shutil.rmtree(W, ignore_errors=True)
    bad = [r["label"] for r in results if not r["ok"]]
    print("wrote", a.out, "| output differs from the same-precision C program:", bad or "none")


if __name__ == "__main__":
    main()
