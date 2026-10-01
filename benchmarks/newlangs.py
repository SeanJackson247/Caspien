#!/usr/bin/env python3
"""
The languages added to the benchmarks on 1 Oct 2026: Fortran, Objective-C, D (LDC and GDC), Nim, Crystal, OCaml, Odin, Zig, Chapel,
Codon, Swift, C#, Kotlin and WebAssembly. One table (LANGS) says, for each of them, which source file it reads, how it is built,
how it is run and how its "size" is counted; the three harnesses (nbody/bench.py, bench_program.py, bench_suite.py) call rows() to
get their measurement rows, so the build commands live in exactly one place.

Source layout (the same as every other language): benchmarks/<program>/reference/<stem>.<ext>
    Fortran   <stem>.f90        Objective-C <stem>.m         D        <stem>.d           Nim      <stem>.nim
    Crystal   <stem>.cr         OCaml       <stem>.ml        Odin     <stem>.odin        Zig      <stem>.zig
    Chapel    <stem>.chpl       Codon       <stem>_codon.py  Swift    <stem>.swift       C#       <stem>.cs
    Kotlin    <stem>.kt         WebAssembly: no source of its own, the C program <stem>.c is compiled to wasm32-wasi
The program takes N as argv[1] (like the C program), prints the same line as the C program, and does the same work with the same
kind of data structures. A port that cannot be built here is simply missing from the table; nothing is faked.

Self-test (builds one language's port of one program, runs it and compares with the C -O2 output):
    python3 benchmarks/newlangs.py check sieve zig [--n 1000000]
    python3 benchmarks/newlangs.py check all zig          (every program that has a port)
Toolchains (installed 1 Oct 2026, see benchmarks/RESULTS.md): apt gfortran, gdc, ldc, gobjc, ocaml-nox, nim, crystal, dotnet-sdk-8.0;
GitHub releases Odin dev-2025-01, Chapel 2.3.0 (.deb), Codon 0.17.0, Kotlin 2.0.21, wasmtime 25.0.0, swiftlang 6.0.3 (Ubuntu pool .deb,
unpacked to /opt/tc/swift); pip ziglang. Dart could not be installed (its download hosts are not reachable from the sandbox).
"""
import glob, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B   # noqa: E402

TC = "/opt/tc"
SWIFTC = os.path.join(TC, "swift/usr/bin/swiftc")
KOTLINC = os.path.join(TC, "kotlinc/bin/kotlinc")
CODON = os.path.join(TC, "codon-deploy/bin/codon")
WASMTIME = os.path.join(TC, "wasmtime-v25.0.0-x86_64-linux/wasmtime")
ZIG = [sys.executable, "-m", "ziglang"]
DOTNET_ROOT_GLOB = "/usr/lib/dotnet/shared/Microsoft.NETCore.App/*"

# key -> (label, group, ext). group: bare = manual memory, native = natively compiled with GC / refcounting / runtime,
# vm = managed runtime, wasm = WebAssembly.
LANGS = {
    "fortran": ("Fortran", "bare", "f90"),
    "objc": ("Objective-C", "bare", "m"),
    "odin": ("Odin", "bare", "odin"),
    "zig": ("Zig", "bare", "zig"),
    "chapel": ("Chapel", "bare", "chpl"),
    "ldc": ("D (LDC)", "native", "d"),
    "gdc": ("D (GDC)", "native", "d"),
    "nim": ("Nim", "native", "nim"),
    "crystal": ("Crystal", "native", "cr"),
    "ocaml": ("OCaml", "native", "ml"),
    "swift": ("Swift", "native", "swift"),
    "codon": ("Codon", "native", "py"),
    "csharp": ("C#", "vm", "cs"),
    "kotlin": ("Kotlin", "vm", "kt"),
    "wasm": ("WebAssembly", "wasm", "c"),
}
KEYS = list(LANGS)
# the languages whose port file is shared (the ext table above is per source file, D is one file for two compilers)
SRC_KEY = {"gdc": "ldc"}


def src_path(refdir, stem, key):
    ext = LANGS[key][2]
    if key == "codon":
        return os.path.join(refdir, stem + "_codon.py")
    return os.path.join(refdir, stem + "." + ext)


def tool_ok(key):
    return {
        "fortran": lambda: B.have("gfortran"), "objc": lambda: B.have("gcc"), "odin": lambda: B.have("odin"),
        "zig": lambda: subprocess.run(ZIG + ["version"], capture_output=True).returncode == 0,
        "chapel": lambda: B.have("chpl"), "ldc": lambda: B.have("ldc2"), "gdc": lambda: B.have("gdc"), "nim": lambda: B.have("nim"),
        "crystal": lambda: B.have("crystal"), "ocaml": lambda: B.have("ocamlopt"), "swift": lambda: os.path.exists(SWIFTC),
        "codon": lambda: os.path.exists(CODON), "csharp": lambda: B.have("dotnet"), "kotlin": lambda: os.path.exists(KOTLINC) and B.have("java"),
        "wasm": lambda: os.path.exists(WASMTIME),
    }[key]()


def dotnet_runtime_size():
    dirs = sorted(glob.glob(DOTNET_ROOT_GLOB))
    return B.dir_size(dirs[-1]) if dirs else 0


def rows(stem, W, refdir, N, only=None):
    """Measurement rows for every new language that has a port of `stem` in refdir and a working toolchain.
    Each row: dict(label, group, lang, build=[{cmd,cwd,env}], exe=[...], size=callable, note)."""
    out = []
    for key in KEYS:
        label, group, ext = LANGS[key]
        if key == "wasm":
            src = os.path.join(refdir, stem + ".c")
        else:
            src = src_path(refdir, stem, key)
        if not os.path.exists(src) or not tool_ok(key):
            continue
        if only and not any(s.lower() in label.lower() for s in only):
            continue
        sp = os.path.basename(src)
        exe = os.path.join(W, "new_" + key)
        env = dict(os.environ)
        note = ""
        if key == "fortran":
            build = [{"cmd": ["gfortran", "-O2", "-o", exe, sp]}]
            run = [exe, N]
        elif key == "objc":
            build = [{"cmd": ["gcc", "-O2", "-x", "objective-c", "-o", exe, sp, "-lobjc", "-lm"]}]
            run = [exe, N]
            note = "GNU libobjc runtime, gcc -O2"
        elif key == "odin":
            build = [{"cmd": ["odin", "build", sp, "-file", "-o:speed", "-out:" + exe]}]
            run = [exe, N]
            note = "-o:speed, bounds checks on (Odin default)"
        elif key == "zig":
            c = os.path.join(W, "zigcache_" + stem)
            build = [{"cmd": ZIG + ["build-exe", sp, "-O", "ReleaseFast", "-femit-bin=" + exe, "--cache-dir", c, "--global-cache-dir", c + "g"]}]
            run = [exe, N]
            note = "ReleaseFast (no safety checks), cold cache build"
        elif key == "chapel":
            build = [{"cmd": ["chpl", "--fast", sp, "-o", exe]}]
            run = [exe, N]
            note = "chpl --fast"
        elif key == "ldc":
            build = [{"cmd": ["ldc2", "-O2", "-release", "-of=" + exe, sp]}]
            run = [exe, N]
            note = "ldc2 -O2 -release (bounds checks only in @safe code)"
        elif key == "gdc":
            build = [{"cmd": ["gdc", "-O2", "-frelease", "-o", exe, sp]}]
            run = [exe, N]
            note = "gdc -O2 -frelease"
        elif key == "nim":
            build = [{"cmd": ["nim", "c", "-d:release", "--hints:off", "--warnings:off", "--nimcache:" + os.path.join(W, "nimcache_" + stem), "-o:" + exe, sp]}]
            run = [exe, N]
            note = "nim c -d:release (bounds checks on), default memory manager"
        elif key == "crystal":
            env["CRYSTAL_CACHE_DIR"] = os.path.join(W, "crcache_" + stem)
            build = [{"cmd": ["crystal", "build", "--release", "-o", exe, sp], "env": env}]
            run = [exe, N]
            note = "crystal build --release, Boehm GC"
        elif key == "ocaml":
            build = [{"cmd": ["ocamlopt", "-O3", "-inline", "200", "-o", exe, sp]}]
            run = [exe, N]
            note = "ocamlopt -inline 200 (no flambda: -O3 is accepted and ignored)"
        elif key == "swift":
            build = [{"cmd": [SWIFTC, "-O", "-static-stdlib", sp, "-o", exe]}]
            run = [exe, N]
            note = "swiftc -O -static-stdlib (overflow and bounds checks on)"
        elif key == "codon":
            build = [{"cmd": [CODON, "build", "-release", "-o", exe, sp]}]
            run = [exe, N]
            note = "codon build -release (Python syntax, compiled with LLVM)"
        elif key == "csharp":
            proj = os.path.join(W, "cs_" + stem)
            os.makedirs(proj, exist_ok=True)
            shutil.copy(src, os.path.join(proj, "Program.cs"))
            open(os.path.join(proj, "bench.csproj"), "w").write(
                '<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework>'
                '<Optimize>true</Optimize><AllowUnsafeBlocks>true</AllowUnsafeBlocks><InvariantGlobalization>true</InvariantGlobalization>'
                '<TieredPGO>true</TieredPGO><ImplicitUsings>disable</ImplicitUsings><Nullable>disable</Nullable>'
                '<AssemblyName>bench</AssemblyName><SatelliteResourceLanguages>en</SatelliteResourceLanguages>'
                '<RestoreSources>' + os.path.join(W, 'emptyfeed') + '</RestoreSources><RestoreIgnoreFailedSources>true</RestoreIgnoreFailedSources>'
                '<NuGetAudit>false</NuGetAudit></PropertyGroup></Project>')
            os.makedirs(os.path.join(W, 'emptyfeed'), exist_ok=True)
            env.update(DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", DOTNET_SKIP_FIRST_TIME_EXPERIENCE="1", DOTNET_CLI_HOME=os.path.join(W, "dnhome"))
            bindir = os.path.join(proj, "out")
            build = [{"cmd": ["dotnet", "build", "-c", "Release", "-o", bindir, "--nologo", "-v", "q"], "cwd": proj, "env": env}]
            run = ["dotnet", os.path.join(bindir, "bench.dll"), N]
            note = ".NET 8 JIT (tiered, PGO), size = shared runtime dir + bench.dll"
            out.append(dict(label=label, group=group, lang=label, build=build, exe=run,
                            size=lambda b=bindir: dotnet_runtime_size() + B.size_of(os.path.join(b, "bench.dll")), note=note, env=env))
            continue
        elif key == "kotlin":
            jar = os.path.join(W, "kt_%s.jar" % stem)
            build = [{"cmd": [KOTLINC, sp, "-include-runtime", "-d", jar, "-nowarn"], "env": dict(os.environ, JAVA_TOOL_OPTIONS="")}]
            run = ["java", "-jar", jar, N]
            out.append(dict(label=label, group=group, lang=label, build=build, exe=run,
                            size=lambda j=jar: B.java_runtime_size() + B.size_of(j), note="kotlinc 2.0.21 on the JVM, size = JRE + jar (jar includes the Kotlin runtime)", env=None))
            continue
        elif key == "wasm":
            c = os.path.join(W, "zigcache_wasm_" + stem)
            wasm = os.path.join(W, "new_wasm.wasm")
            cwasm = os.path.join(W, "new_wasm.cwasm")
            build = [{"cmd": ZIG + ["cc", "-target", "wasm32-wasi", "-O2", "-o", wasm, sp, "-lm"],
                      "env": dict(os.environ, ZIG_LOCAL_CACHE_DIR=c, ZIG_GLOBAL_CACHE_DIR=c + "g")},
                     {"cmd": [WASMTIME, "compile", "-o", cwasm, wasm]}]
            run = [WASMTIME, "run", "--allow-precompiled", cwasm, N]
            out.append(dict(label=label, group=group, lang=label, build=build, exe=run,
                            size=lambda w=wasm: B.size_of(WASMTIME) + B.size_of(w),
                            note="the C program built with zig cc -target wasm32-wasi -O2, ahead-of-time compiled by wasmtime 25 (Cranelift); size = wasmtime binary + .wasm; wasm32 address space", env=None))
            continue
        out.append(dict(label=label, group=group, lang=label, build=build, exe=run, size=lambda e=exe: B.size_of(e), note=note, env=None))
    return out


SMALL = {"sieve": 1000000, "strings": 100000, "graph": 20000, "sorting": 20000, "binarytrees": 12, "mandelbrot": 200, "fasta": 100000,
         "knucleotide": 200000, "lru": 100000, "merkletrees": 1000, "helloworld": 1, "json_serde": 20000, "nbody": 100000,
         "fannkuchredux": 8, "spectralnorm": 200}


def run_check(prog, key, n):
    n = n or SMALL.get(prog, 1000)
    refroot = os.path.join(HERE, prog, "reference")
    if not os.path.isdir(refroot):
        print("no such program", prog); return 2
    stem = prog if prog != "nbody" else "nbody"
    W = tempfile.mkdtemp(prefix="newlang_" + key + "_")
    for f in os.listdir(refroot):
        shutil.copy(os.path.join(refroot, f), W)
    ref_exe = os.path.join(W, "ref_c")
    rc = subprocess.run(["gcc", "-O2", "-o", ref_exe, os.path.join(W, stem + ".c"), "-lm"], capture_output=True, text=True)
    if rc.returncode:
        print("reference C build failed", rc.stderr[-500:]); return 2
    want = subprocess.run([ref_exe, str(n)], capture_output=True, text=True).stdout.strip()
    rs = [r for r in rows(stem, W, W, str(n)) if r["lang"] and r["label"] and (key == "all" or r["label"] == LANGS[key][0])]
    if not rs:
        print("NO PORT or no toolchain for", prog, key); return 1
    bad = 0
    for r in rs:
        dt = 0.0
        for bc in r["build"]:
            d1, _, code, o = B.run(bc["cmd"], cwd=bc.get("cwd", W), env=bc.get("env"))
            dt += d1
            if code:
                print("BUILD FAILED", prog, r["label"], "\n", o[-1500:]); bad += 1; break
        else:
            dt2, kb, code, o = B.run(r["exe"], cwd=W, env=r.get("env"))
            got = o.strip()
            ok = code == 0 and got == want
            print("%-6s %-12s %-12s build %6.2fs run %7.3fs rss %7d KB  %s" % ("OK" if ok else "DIFF", prog, r["label"], dt, dt2, kb, "" if ok else "want=%r got=%r" % (want[:80], got[:80])))
            bad += 0 if ok else 1
    shutil.rmtree(W, ignore_errors=True)
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "check":
        n = 0
        if "--n" in sys.argv:
            n = int(sys.argv[sys.argv.index("--n") + 1])
        progs = sorted(d for d in os.listdir(HERE) if os.path.isdir(os.path.join(HERE, d, "reference"))) if sys.argv[2] == "all" else [sys.argv[2]]
        code = 0
        for p in progs:
            code |= run_check(p, sys.argv[3], n)
        sys.exit(code)
    print(__doc__)
