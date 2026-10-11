"""How every non-Caspien language is built, run and sized. One place, used by every program.

rows(program, W, N) -> list of dicts: label, key, group, lang, prec, build=[{cmd,cwd,env}], exe=[...], size=callable, note[, env].
  key    stable language id used by the site for colours and filters ("c", "rust", "zig", ...)
  group  bare = manual memory, native = natively compiled with GC / refcounting / runtime, vm, js, luajit, wasm
A language whose toolchain is missing, or that has no port of the program, produces no row; nothing is faked.
"""
import glob, os, shutil, subprocess, sys

from . import core

TC = "/opt/tc"   # toolchains installed by hand (see docs/RESULTS.md)
SWIFTC = os.path.join(TC, "swift/usr/bin/swiftc")
KOTLINC = os.path.join(TC, "kotlinc/bin/kotlinc")
CODON = os.path.join(TC, "codon-deploy/bin/codon")
WASMTIME = os.path.join(TC, "wasmtime-v25.0.0-x86_64-linux/wasmtime")
ZIG = [sys.executable, "-m", "ziglang"]
DOTNET_GLOB = "/usr/lib/dotnet/shared/Microsoft.NETCore.App/*"


def _noj():
    return dict(os.environ, JAVA_TOOL_OPTIONS="")


class Ctx:
    """Everything a language builder needs about the program being built."""

    def __init__(self, P, W, N):
        self.P, self.W, self.N = P, W, N
        self.stem = P["stem"]
        self.java_class = P["java_class"]
        self.mem = P.get("leak", False)                 # allocating programs also come in a "leak" build (never frees)
        self.libs = P.get("libs", ["-lm"])
        self.prec = P.get("prec", "")
        self.extra_c = P.get("extra_c", [])             # e.g. nbody's single-precision C program
        self.allowed = P.get("languages")               # None = every language
        self.rows = []

    def add(self, label, key, group, lang, build, exe, size, note="", prec=None, env=None):
        if self.allowed is not None and key not in self.allowed:
            return
        self.rows.append(dict(label=label, key=key, group=group, lang=lang, prec=self.prec if prec is None else prec,
                              build=build, exe=exe, size=size, note=note, env=env))

    def path(self, name):
        return os.path.join(self.W, name)

    def exists(self, ext, stem=None):
        return os.path.exists(self.path((stem or self.stem) + ext))


# ---- the languages every program had from the start ---------------------------------------------------------------------------------------

def _c(c):
    if not core.have("gcc"):
        return
    sources = [(c.stem, "C", c.prec)] + [(x["src"], x["label"], x["prec"]) for x in c.extra_c]
    for stem, name, prec in sources:
        for opt in ("-O0", "-O2"):
            for mode, flags in (("free", []), ("leak", ["-DLEAK"])):
                if mode == "leak" and (not c.mem or opt == "-O0" or stem != c.stem):
                    continue
                exe = c.path("c%s_%s_%s" % (opt, mode, stem))
                label = "%s %s%s" % (name, opt, " (%s)" % mode if c.mem and opt != "-O0" else "")
                c.add(label, "c", "bare", "C", [{"cmd": ["gcc", opt] + flags + ["-o", exe, stem + ".c"] + c.libs}], [exe, c.N],
                      lambda e=exe: core.size_of(e), prec=prec)


def _cpp(c):
    if not core.have("g++"):
        return
    for mode, flags in (("free", []), ("leak", ["-DLEAK"])):
        if mode == "leak" and not c.mem:
            continue
        exe = c.path("cpp_%s" % mode)
        c.add("C++ -O2" + (" (%s)" % mode if c.mem else ""), "cpp", "bare", "C++",
              [{"cmd": ["g++", "-O2", "-x", "c++"] + flags + ["-o", exe, c.stem + ".cpp"] + c.libs}], [exe, c.N], lambda e=exe: core.size_of(e))


def _rust(c):
    if not core.have("rustc"):
        return
    for mode, flags in (("free", []), ("leak", ["--cfg", "leak"])):
        if mode == "leak" and not c.mem:
            continue
        exe = c.path("rs_%s" % mode)
        c.add("Rust -O" + (" (%s)" % mode if c.mem else ""), "rust", "bare", "Rust",
              [{"cmd": ["rustc", "-O"] + flags + ["-o", exe, c.stem + ".rs"]}], [exe, c.N], lambda e=exe: core.size_of(e))


def _go(c):
    if not core.have("go"):
        return
    exe = c.path("go_bin")
    genv = dict(os.environ, GOCACHE=c.path("gocache"), GOFLAGS="-buildvcs=false")
    c.add("Go", "go", "bare", "Go", [{"cmd": ["go", "build", "-o", exe, c.stem + ".go"], "env": genv}], [exe, c.N], lambda: core.size_of(exe))


def _java(c):
    if not (core.have("javac") and core.have("java")):
        return
    cls = c.path(c.java_class + ".class")
    c.add("Java", "java", "vm", "Java", [{"cmd": ["javac", "-d", c.W, c.java_class + ".java"], "env": _noj()}], ["java", "-cp", c.W, c.java_class, c.N],
          lambda: core.java_runtime_size() + core.size_of(cls), "size = bin/java + lib/ + conf/ of the JRE plus the class file")


def _script(c, tool, label, key, group, lang, ext, note):
    if not core.have(tool):
        return
    binary = os.path.realpath(shutil.which(tool))
    c.add(label, key, group, lang, [], [tool, c.stem + ext, c.N],
          lambda: core.size_of(binary) + core.size_of(c.path(c.stem + ext)), note)


# ---- the fifteen languages added on 1 Oct 2026 ----------------------------------------------------------------------------------------------
# key -> (label, group, source extension)
NEW = {
    "fortran": ("Fortran", "bare", "f90"), "objc": ("Objective-C", "bare", "m"), "odin": ("Odin", "bare", "odin"), "zig": ("Zig", "bare", "zig"),
    "chapel": ("Chapel", "bare", "chpl"), "ldc": ("D (LDC)", "native", "d"), "gdc": ("D (GDC)", "native", "d"), "nim": ("Nim", "native", "nim"),
    "crystal": ("Crystal", "native", "cr"), "ocaml": ("OCaml", "native", "ml"), "swift": ("Swift", "native", "swift"),
    "codon": ("Codon", "native", "py"), "csharp": ("C#", "vm", "cs"), "kotlin": ("Kotlin", "vm", "kt"), "wasm": ("WebAssembly", "wasm", "c"),
}


def new_source(c, key):
    """The port's source file name in the scratch folder. D has one file for both compilers; Codon is Python syntax; WebAssembly is the C program."""
    ext = NEW[key][2]
    if key == "codon":
        return c.stem + "_codon.py"
    return c.stem + "." + ext


def new_toolchain_ok(key):
    return {
        "fortran": lambda: core.have("gfortran"), "objc": lambda: core.have("gcc"), "odin": lambda: core.have("odin"),
        "zig": lambda: subprocess.run(ZIG + ["version"], capture_output=True).returncode == 0,
        "chapel": lambda: core.have("chpl"), "ldc": lambda: core.have("ldc2"), "gdc": lambda: core.have("gdc"), "nim": lambda: core.have("nim"),
        "crystal": lambda: core.have("crystal"), "ocaml": lambda: core.have("ocamlopt"), "swift": lambda: os.path.exists(SWIFTC),
        "codon": lambda: os.path.exists(CODON), "csharp": lambda: core.have("dotnet"),
        "kotlin": lambda: os.path.exists(KOTLINC) and core.have("java"), "wasm": lambda: os.path.exists(WASMTIME),
    }[key]()


def _dotnet_runtime_size():
    dirs = sorted(glob.glob(DOTNET_GLOB))
    return core.dir_size(dirs[-1]) if dirs else 0


def _simple(c, key, build, note=""):
    """The usual shape: one build command, then `exe N`."""
    label, group, _ = NEW[key]
    exe = c.path("new_" + key)
    cmd, env = build(exe)
    c.add(label, key, group, label, [dict(cmd=cmd, **({"env": env} if env else {}))], [exe, c.N], lambda: core.size_of(exe), note)


def _new(c, key):
    sp = new_source(c, key)
    st = c.stem
    W = c.W
    label, group, _ = NEW[key]
    if key == "fortran":
        _simple(c, key, lambda e: (["gfortran", "-O2", "-o", e, sp], None))
    elif key == "objc":
        _simple(c, key, lambda e: (["gcc", "-O2", "-x", "objective-c", "-o", e, sp, "-lobjc", "-lm"], None), "GNU libobjc runtime, gcc -O2")
    elif key == "odin":
        _simple(c, key, lambda e: (["odin", "build", sp, "-file", "-o:speed", "-out:" + e], None), "-o:speed, bounds checks on (Odin default)")
    elif key == "zig":
        cache = c.path("zigcache_" + st)
        _simple(c, key, lambda e: (ZIG + ["build-exe", sp, "-O", "ReleaseFast", "-femit-bin=" + e, "--cache-dir", cache, "--global-cache-dir", cache + "g"], None),
                "ReleaseFast (no safety checks), cold cache build")
    elif key == "chapel":
        _simple(c, key, lambda e: (["chpl", "--fast", sp, "-o", e], None), "chpl --fast")
    elif key == "ldc":
        _simple(c, key, lambda e: (["ldc2", "-O2", "-release", "-of=" + e, sp], None), "ldc2 -O2 -release (bounds checks only in @safe code)")
    elif key == "gdc":
        _simple(c, key, lambda e: (["gdc", "-O2", "-frelease", "-o", e, sp], None), "gdc -O2 -frelease")
    elif key == "nim":
        _simple(c, key, lambda e: (["nim", "c", "-d:release", "--hints:off", "--warnings:off", "--nimcache:" + c.path("nimcache_" + st), "-o:" + e, sp], None),
                "nim c -d:release (bounds checks on), default memory manager")
    elif key == "crystal":
        env = dict(os.environ, CRYSTAL_CACHE_DIR=c.path("crcache_" + st))
        _simple(c, key, lambda e: (["crystal", "build", "--release", "-o", e, sp], env), "crystal build --release, Boehm GC")
    elif key == "ocaml":
        _simple(c, key, lambda e: (["ocamlopt", "-O3", "-inline", "200", "-o", e, sp], None), "ocamlopt -inline 200 (no flambda: -O3 is accepted and ignored)")
    elif key == "swift":
        _simple(c, key, lambda e: ([SWIFTC, "-O", "-static-stdlib", sp, "-o", e], None), "swiftc -O -static-stdlib (overflow and bounds checks on)")
    elif key == "codon":
        _simple(c, key, lambda e: ([CODON, "build", "-release", "-o", e, sp], None), "codon build -release (Python syntax, compiled with LLVM)")
    elif key == "csharp":
        proj = c.path("cs_" + st)
        os.makedirs(proj, exist_ok=True)
        shutil.copy(c.path(sp), os.path.join(proj, "Program.cs"))
        with open(os.path.join(proj, "bench.csproj"), "w") as f:
            f.write('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework>'
                    '<Optimize>true</Optimize><AllowUnsafeBlocks>true</AllowUnsafeBlocks><InvariantGlobalization>true</InvariantGlobalization>'
                    '<TieredPGO>true</TieredPGO><ImplicitUsings>disable</ImplicitUsings><Nullable>disable</Nullable>'
                    '<AssemblyName>bench</AssemblyName><SatelliteResourceLanguages>en</SatelliteResourceLanguages>'
                    '<RestoreSources>' + c.path("emptyfeed") + '</RestoreSources><RestoreIgnoreFailedSources>true</RestoreIgnoreFailedSources>'
                    '<NuGetAudit>false</NuGetAudit></PropertyGroup></Project>')
        os.makedirs(c.path("emptyfeed"), exist_ok=True)
        env = dict(os.environ, DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", DOTNET_SKIP_FIRST_TIME_EXPERIENCE="1", DOTNET_CLI_HOME=c.path("dnhome"))
        out = os.path.join(proj, "out")
        c.add(label, key, group, label, [{"cmd": ["dotnet", "build", "-c", "Release", "-o", out, "--nologo", "-v", "q"], "cwd": proj, "env": env}],
              ["dotnet", os.path.join(out, "bench.dll"), c.N], lambda: _dotnet_runtime_size() + core.size_of(os.path.join(out, "bench.dll")),
              ".NET 8 JIT (tiered, PGO), size = shared runtime dir + bench.dll", env=env)
    elif key == "kotlin":
        jar = c.path("kt_%s.jar" % st)
        c.add(label, key, group, label, [{"cmd": [KOTLINC, sp, "-include-runtime", "-d", jar, "-nowarn"], "env": _noj()}], ["java", "-jar", jar, c.N],
              lambda: core.java_runtime_size() + core.size_of(jar), "kotlinc 2.0.21 on the JVM, size = JRE + jar (jar includes the Kotlin runtime)")
    elif key == "wasm":
        cache = c.path("zigcache_wasm_" + st)
        wasm, cwasm = c.path("new_wasm.wasm"), c.path("new_wasm.cwasm")
        c.add(label, key, group, label,
              [{"cmd": ZIG + ["cc", "-target", "wasm32-wasi", "-O2", "-o", wasm, sp, "-lm"], "env": dict(os.environ, ZIG_LOCAL_CACHE_DIR=cache, ZIG_GLOBAL_CACHE_DIR=cache + "g")},
               {"cmd": [WASMTIME, "compile", "-o", cwasm, wasm]}],
              [WASMTIME, "run", "--allow-precompiled", cwasm, c.N], lambda: core.size_of(WASMTIME) + core.size_of(wasm),
              "the C program built with zig cc -target wasm32-wasi -O2, ahead-of-time compiled by wasmtime 25 (Cranelift); size = wasmtime binary + .wasm; wasm32 address space")


def rows(P, W, N, only=None):
    """Measurement rows for every language that has a port of the program in W and a working toolchain."""
    c = Ctx(P, W, N)
    _c(c); _cpp(c); _rust(c); _go(c); _java(c)
    _script(c, "node", "Node", "node", "js", "JavaScript", ".js", "size = node binary + script")
    _script(c, "bun", "Bun", "bun", "js", "JavaScript", ".js", "size = bun binary + script")
    _script(c, "luajit", "LuaJIT", "luajit", "luajit", "LuaJIT", ".lua", "size = luajit binary + script (tracing JIT)")
    for key in NEW:
        if c.allowed is not None and key not in c.allowed:
            continue
        if not os.path.exists(c.path(new_source(c, key))) or not new_toolchain_ok(key):
            continue
        _new(c, key)
    if only:
        c.rows = [r for r in c.rows if any(s.lower() in r["label"].lower() for s in only)]
    return c.rows
