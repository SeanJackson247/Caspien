"""Building Caspien programs: the optimiser configurations, the scratch compiler tree, and the compile command."""
import json, os, re, shutil, tempfile

from . import paths

# Every optimiser switch off, and everything on. "specific" = FULL plus a program's own overrides (specific.json).
OFF = {
    "deferred-operands": "off", "variables-in-registers": "off", "float-variables-in-registers": "off", "float-temporaries-in-registers": "off",
    "hoist-array-bases": "off", "variables-in-alloc-functions": "off", "fuse-length-compare": "off", "variables-in-arg-registers": "off",
    "loop-rotation": "off", "copy-forward": "off", "conditional-move": "off", "loop-unrolling": "off", "loop-unroll-nested": "off",
    "function-inlining": "off", "constant-folding": "off", "variable-elision": "off", "variable-shifting": "off", "struct-unpacking": "off",
    "dead-control-flow-removal": "off", "dead-function-removal": "off", "unused-declaration-removal": "off",
}
FULL = {
    "deferred-operands": "on", "variables-in-registers": "on", "float-variables-in-registers": "on", "float-temporaries-in-registers": "on",
    "hoist-array-bases": "on", "variables-in-alloc-functions": "on", "fuse-length-compare": "on", "variables-in-arg-registers": "on",
    "loop-rotation": "on", "copy-forward": "on", "conditional-move": "on", "loop-unrolling": "aggressive", "loop-unroll-nested": "on",
    "function-inlining": "aggressive", "constant-folding": "on", "variable-elision": "on", "variable-shifting": "on", "struct-unpacking": "on",
    "dead-control-flow-removal": "on", "dead-function-removal": "on", "unused-declaration-removal": "on", "bmi2": "on", "avx": "off", "jcc-padding": "on",
}

# Where a program is placed in the scratch tree: four directories deep, like programs/<name>/caspien/ in the repository, so the programs' own
# `import "stdlib/..."` lines resolve unchanged.
SRC_DIR = os.path.join("bench", "p", "q", "c")
SRC_NAME = "_bp.caspien"


def specific_overrides(prog):
    """Switch overrides of the "specific" build: env BENCH_SPECIFIC_JSON (a JSON object, used by tools/tune_specific.py) or specific.json[prog]."""
    env = os.environ.get("BENCH_SPECIFIC_JSON")
    if env is not None:
        return json.loads(env)
    try:
        with open(paths.SPECIFIC) as f:
            return json.load(f).get(prog, {})
    except (OSError, ValueError):
        return {}


def modes(S, prog=None):
    """[(mode name, config dict)] to build for each Caspien source file: "off" (with --with-off / full mode), "full" (everything on) and, when the
    program has overrides in specific.json, "specific" (full + that program's tuned switches)."""
    out = ([("off", OFF)] if S["off"] else []) + [("full", FULL)]
    ov = specific_overrides(prog) if prog else {}
    if ov:
        out.append(("specific", dict(FULL, **ov)))
    return out


def patch_config(path, kv):
    with open(path) as f:
        text = f.read()
    for k, v in kv.items():
        text, n = re.subn(r"^%s(:?) .*$" % re.escape(k), lambda m, k=k, v=v: "%s%s %s" % (k, m.group(1), v), text, flags=re.M)
        if n == 0:
            raise SystemExit("config key %s not found in %s" % (k, path))
    with open(path, "w") as f:
        f.write(text)


class Tree:
    """A scratch copy of the compiler (the shipped toolchain.config targets windows_gnu; here `target linux` + `default: sysv_x64`)."""

    def __init__(self, root=None):
        root = root or paths.REPO
        self.dir = tempfile.mkdtemp(prefix="caspien_tree_")
        for d in ("ASTGenerator", "Optimizer", "LowerOrderGenerator", "Codegen"):
            shutil.copytree(os.path.join(root, d), os.path.join(self.dir, d))
        for f in os.listdir(root):
            if f.startswith("Compiler"):
                shutil.copy(os.path.join(root, f), self.dir)
        with open(os.path.join(root, "toolchain.config")) as f:
            cfg = f.read()
        cfg = re.sub(r"^target windows_gnu", "target linux", cfg, flags=re.M)
        cfg = re.sub(r"^(\s*)default: win64", r"\1default: sysv_x64", cfg, flags=re.M)
        self.config = os.path.join(self.dir, "toolchain.config")
        with open(self.config, "w") as f:
            f.write(cfg)
        self._base = cfg
        os.makedirs(os.path.join(self.dir, SRC_DIR), exist_ok=True)

    def prepare(self, source_file, kv):
        """Writes the optimiser configuration kv and copies the program in; -> the path to give the compiler (relative to the tree)."""
        with open(self.config, "w") as f:
            f.write(self._base)
        patch_config(self.config, kv)
        rel = os.path.join(SRC_DIR, SRC_NAME)
        shutil.copy(source_file, os.path.join(self.dir, rel))
        return rel

    def compile_command(self, source_rel, exe):
        return {"cmd": ["java", "Compiler", "-i", source_rel, exe, "--no-cache"], "cwd": self.dir, "env": dict(os.environ, JAVA_TOOL_OPTIONS="", CASPIEN_STDLIB=os.path.normpath(os.path.join(paths.REPO, "..", "stdlib")))}

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)
