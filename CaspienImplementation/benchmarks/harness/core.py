"""Running and timing a command: wall time, peak RSS, process-group timeout, the build cache, the Caspien leak check, run modes."""
import hashlib, os, re, shutil, signal, statistics, subprocess, sys, tarfile, tempfile, time

from . import paths

# ---- tools ----------------------------------------------------------------------------------------------------------------------------------

def have(tool):
    return shutil.which(tool) is not None


def median(xs):
    return statistics.median(xs) if xs else 0.0


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
    home = re.search(r"java\.home = (\S+)", r.stderr).group(1)
    return size_of(os.path.join(home, "bin", "java")) + dir_size(os.path.join(home, "lib")) + dir_size(os.path.join(home, "conf"))


# ---- one timed run --------------------------------------------------------------------------------------------------------------------------

_LAUNCHER_C = r"""
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
_launcher = None


def launcher():
    """A tiny C launcher. wait4 on a child of the (large) Python process would report Python's own peak RSS, inherited across fork/exec, so
    every program would show the same number; a small launcher process has no such history."""
    global _launcher
    if _launcher is None:
        d = tempfile.mkdtemp(prefix="rsswrap")
        src = os.path.join(d, "w.c")
        with open(src, "w") as f:
            f.write(_LAUNCHER_C)
        _launcher = os.path.join(d, "rsswrap")
        subprocess.check_call(["gcc", "-O1", "-o", _launcher, src])
    return _launcher


def run(cmd, cwd=None, env=None, timeout=900):
    """-> (wall seconds, peak RSS in KB, exit code, output text). On timeout the whole process group is killed (the launcher forks the program,
    so killing only the launcher would leave it running) and the result is (timeout, 0, 124, text)."""
    base = os.environ if env is None else env
    env = {k: v for k, v in base.items() if k != "JAVA_TOOL_OPTIONS"}   # even an empty value makes java print "Picked up ..."
    rf = tempfile.mktemp(prefix="rss")
    t0 = time.perf_counter()
    p = subprocess.Popen([launcher(), rf] + list(cmd), cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        stdout, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        p.communicate()
        return float(timeout), 0, 124, "TIMEOUT after %s s" % timeout
    t1 = time.perf_counter()
    try:
        with open(rf) as f:
            kb = int(f.read().strip())
        os.unlink(rf)
    except (OSError, ValueError):
        kb = 0
    return t1 - t0, kb, p.returncode, stdout.decode("utf-8", "replace")


# ---- run modes ------------------------------------------------------------------------------------------------------------------------------
#   quick (default): Caspien builds only the optimised ("full") configuration and is measured FIRST. Every other implementation then runs with a
#       time limit equal to the slowest optimised Caspien variant of that program (same precision); one whose first run reaches the limit is
#       killed and recorded as "would have taken longer than optimized Caspien" (a full-colour bar at the limit, flagged `cutoff`).
#       Fewer repeats and builds. `--with-off` also builds the optimisations-off Caspien variants.
#   full: everything: off and optimised Caspien builds, no time limit, more repeats and builds.
MODES = {"quick": dict(runs=3, builds=1, off=False, cutoff=True),
         "full": dict(runs=5, builds=3, off=True, cutoff=False)}
CUTOFF_NOTE = "would have taken longer than optimized Caspien"
MIN_LIMIT = 0.1   # seconds: a program whose slowest optimised Caspien run is millisecond-scale (hello world) would otherwise be decided by start-up noise


def settings(a):
    m = MODES[a.mode]
    if a.clear_build_cache:
        shutil.rmtree(paths.BUILD_CACHE, ignore_errors=True)
    return dict(mode=a.mode, runs=a.runs or m["runs"], builds=a.builds or m["builds"], off=m["off"] or a.with_off,
                cutoff=m["cutoff"] and not a.caspien_only, build_cache=(a.mode == "quick" and not a.no_build_cache),
                caspien_only=a.caspien_only, leak_check=not a.no_leak_check)


def note_caspien(limits, S, prec, label, t):
    """Record the slowest optimised Caspien time per precision: the limit for everything measured afterwards. The `ref` (alive-checked)
    variants are far slower and must not raise it."""
    if S["cutoff"] and label.endswith("· full") and " ref " not in label + " " and "ref in stdlib" not in label:
        limits[prec] = max(limits.get(prec, 0.0), t)


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
        leaked = ghost_table_leaks(cmd, cwd, env)
        if leaked:
            msg = "LEAK %s: %d block(s) still registered in the ghost table at exit" % (os.path.basename(cmd[0]), leaked)
            print("!! " + msg, flush=True)
            with open(paths.LEAK_LOG, "a") as f:
                f.write(msg + "\n")
    return "ok", times, rss, outs, ""


def cutoff_row(label, group, lang, prec, note, compile_s, size, limit):
    return {"label": label, "group": group, "lang": lang, "prec": prec, "note": (note + "; " if note else "") + CUTOFF_NOTE,
            "compile_s": compile_s, "size_bytes": size, "time_s": limit, "times": [limit], "rss_kb": 0, "output": [], "cutoff": True}


# ---- leak check -----------------------------------------------------------------------------------------------------------------------------

def ghost_table_leaks(cmd, cwd, env):
    """Runs a Caspien benchmark binary ONCE more (untimed) under tests/alloc_shim.c and returns the number of blocks still registered in its ghost
    table at exit (0 = freed everything; None = no table or the check could not run). Valgrind cannot see these leaks: the table keeps every
    registered block reachable."""
    src = os.path.join(paths.REPO, "tests", "alloc_shim.c")
    so = os.path.join(paths.REPO, ".cache", "alloc_shim.so")
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
    except (OSError, subprocess.SubprocessError):
        return None


# ---- build cache for the other languages' programs (quick mode) ------------------------------------------------------------------------------
# A foreign-language build is reused when nothing it depends on changed: the program's source files, the build command and the compiler it runs
# (path, size, mtime). The files a build adds to the scratch folder are stored as a tar.gz under .build_cache and unpacked again on a hit; the
# compile time stored with the entry is reported again (the row's note says so). Caspien's own builds are never cached here: the compiler is
# what is being measured. The program is still run, and timed, every time. Full mode builds everything from scratch.
_SKIP_DIRS = ("cache", "obj", "dnhome", "emptyfeed")


def _walk(root, skip=_SKIP_DIRS):
    for dp, dns, fns in os.walk(root):
        dns[:] = sorted(d for d in dns if not any(k in d.lower() for k in skip))
        for f in sorted(fns):
            p = os.path.join(dp, f)
            if os.path.isfile(p) and not os.path.islink(p):
                yield p


def _hash_tree(root, skip=_SKIP_DIRS, scratch=None):
    """Hash of names and contents; `scratch` (the per-run scratch folder) becomes a placeholder, because generated project files mention it."""
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
        self.src = _hash_tree(W) if enabled else ""     # created right after the reference sources were copied in: everything in W is source

    def _key(self, build_cmds):
        h = hashlib.sha256(self.src.encode())
        norm = lambda x: str(x).replace(self.W, "<W>")
        for bc in build_cmds:
            cwd = bc.get("cwd", self.W)
            h.update(("\n".join(norm(a) for a in bc["cmd"]) + "\n" + norm(cwd)).encode())
            env = bc.get("env")
            if env:
                h.update(repr(sorted((k, norm(v)) for k, v in env.items() if os.environ.get(k) != v and k != "JAVA_TOOL_OPTIONS")).encode())
            h.update(_tool_identity(bc["cmd"][0], env).encode())
            if os.path.abspath(cwd) != os.path.abspath(self.W) and os.path.isdir(cwd):
                h.update(_hash_tree(cwd, _SKIP_DIRS + ("out", "bin"), self.W).encode())    # generated project files (C#)
        return h.hexdigest()

    def compile(self, label, group, build_cmds, builds):
        """Runs (or restores) the build commands. -> (compile times, reused flag), or None when a build failed."""
        if not build_cmds:
            return [], False
        key = self._key(build_cmds) if self.enabled and group != "caspien" else None
        entry = os.path.join(paths.BUILD_CACHE, key[:2], key) if key else None
        if entry and os.path.isfile(entry + ".tar.gz") and os.path.isfile(entry + ".time"):
            try:
                with tarfile.open(entry + ".tar.gz") as t:
                    t.extractall(self.W)
                with open(entry + ".time") as f:
                    return [float(f.read())], True
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
            try:
                after = _snapshot(self.W)
                new = [p for p, v in after.items() if before.get(p) != v]
                os.makedirs(os.path.dirname(entry), exist_ok=True)
                tmp = entry + ".part"
                with tarfile.open(tmp, "w:gz") as t:
                    for p in new:
                        t.add(os.path.join(self.W, p), arcname=p)
                os.replace(tmp, entry + ".tar.gz")
                with open(entry + ".time", "w") as f:
                    f.write(repr(median(ctimes)))
            except OSError:
                pass
        return ctimes, False


def provenance():
    """Who measured what, where and when; stored in every results file so a committed result says what it measured (git is the history)."""
    import datetime, platform
    def git(*a):
        try:
            return subprocess.run(["git", "-C", paths.REPO] + list(a), capture_output=True, text=True, timeout=20).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""
    return {"date": datetime.datetime.now().astimezone().isoformat(timespec="minutes"), "compiler_commit": git("rev-parse", "--short", "HEAD") or None,
            "compiler_dirty": bool(git("status", "--porcelain", "--", "ASTGenerator", "Optimizer", "LowerOrderGenerator", "Codegen", "../stdlib")),
            "machine": "%s, %d cores, %s" % (platform.machine(), os.cpu_count() or 0, platform.system())}
