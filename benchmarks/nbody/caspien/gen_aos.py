#!/usr/bin/env python3
"""Generates the three array-of-structs n-body variants (same layout as the reference C program: one `Body{x,y,z,vx,vy,vz,mass}` per planet, 56 bytes):
  nbody_aos_static_f64   fixed `Body[5]` static, functions, loops over `for i in bodies` under real `match i in bodies{}` proofs
  nbody_aos_safe_f64     safe heap dynarray `dyn:<Body>`, loops over 0..5 under `match i in bodies{}` bounds proofs (checked at run time)
  nbody_aos_unsafe_f64   headerless `unsafe dyn:<Body>` (udyn): no index proofs, no length word
The arithmetic and the `assume match .. : finite` lines are the ones of nbody_loop_f64 with `x[i]` rewritten to `bodies[i].x`."""
import os
HERE = os.path.dirname(os.path.abspath(__file__))
INIT = [  # x y z vx vy vz mass
    (0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 39.47841760435743),
    (4.841431442464721, -1.1603200440274284, -0.10362204447112311, 0.606326392995832, 2.81198684491626, -0.02521836165988763, 0.03769367487038949),
    (8.34336671824458, 4.124798564124305, -0.4035234171143214, -1.0107743461787924, 1.8256623712304119, 0.008415761376584154, 0.011286326131968767),
    (12.894369562139131, -15.111151401698631, -0.22330757889265573, 1.0827910064415354, 0.8687130181696082, -0.010832637401363636, 0.0017237240570597112),
    (15.379697114850917, -25.919314609987964, 0.17925877295037118, 0.979090732243898, 0.5946989986476762, -0.034755955504078104, 0.0020336868699246304)]
F = ["x", "y", "z", "vx", "vy", "vz", "mass"]
M = 39.47841760435743

def b(i, f): return f"bodies[{i}].{f}"

def pair(i, j):
    g = lambda e: f"assume match {e} : finite"
    return [
        g(b(i, "x")), g(b(j, "x")), f"let dx = mut ({b(i,'x')} - {b(j,'x')})",
        g(b(i, "y")), g(b(j, "y")), f"let dy = mut ({b(i,'y')} - {b(j,'y')})",
        g(b(i, "z")), g(b(j, "z")), f"let dz = mut ({b(i,'z')} - {b(j,'z')})",
        g("dx"), g("dy"), g("dx*dx"), g("dy*dy"), g("dz"), g("(dx*dx)+(dy*dy)"), g("dz*dz"),
        "let dist = mut sqrt(dx * dx + dy * dy + dz * dz)",
        g("dist"), g("dist*dist"), g("dt"), g("(dist*dist)*dist"),
        "let mag = mut (dt / (dist * dist * dist))",
        g(b(j, "mass")), g("dx*" + b(j, "mass")), g("mag"),
        f"{b(i,'vx')} -= dx * {b(j,'mass')} * mag",
        g("dy*" + b(j, "mass")), f"{b(i,'vy')} -= dy * {b(j,'mass')} * mag",
        g("dz*" + b(j, "mass")), f"{b(i,'vz')} -= dz * {b(j,'mass')} * mag",
        g(b(i, "mass")), g("dx*" + b(i, "mass")), f"{b(j,'vx')} += dx * {b(i,'mass')} * mag",
        g("dy*" + b(i, "mass")), f"{b(j,'vy')} += dy * {b(i,'mass')} * mag",
        g("dz*" + b(i, "mass")), f"{b(j,'vz')} += dz * {b(i,'mass')} * mag"]

def move(i):
    g = lambda e: f"assume match {e} : finite"
    out = [g("dt")]
    for p, v in (("x", "vx"), ("y", "vy"), ("z", "vz")):
        out += [g(b(i, v)), f"{b(i,p)} += dt * {b(i,v)}"]
    return out

def energy_i(i):
    g = lambda e: f"assume match {e} : finite"
    m, vx, vy, vz = (b(i, k) for k in ("mass", "vx", "vy", "vz"))
    return [g(m), g(vx), g(vy), g(f"{vx}*{vx}"), g(f"{vy}*{vy}"), g(vz), g(f"({vx}*{vx})+({vy}*{vy})"), g(f"{vz}*{vz}"), g(f"0.5*{m}"),
            g(f"(({vx}*{vx})+({vy}*{vy}))+({vz}*{vz})"),
            f"e += 0.5 * {m} * ({vx} * {vx} + {vy} * {vy} + {vz} * {vz})"]

def energy_ij(i, j):
    g = lambda e: f"assume match {e} : finite"
    return [g(b(i, "x")), g(b(j, "x")), f"let dx = mut ({b(i,'x')} - {b(j,'x')})",
            g(b(i, "y")), g(b(j, "y")), f"let dy = mut ({b(i,'y')} - {b(j,'y')})",
            g(b(i, "z")), g(b(j, "z")), f"let dz = mut ({b(i,'z')} - {b(j,'z')})",
            g("dx"), g("dy"), g("dx*dx"), g("dy*dy"), g("dz"), g("(dx*dx)+(dy*dy)"), g("dz*dz"),
            "let dist = mut sqrt(dx * dx + dy * dy + dz * dz)",
            g(b(j, "mass")), g(f"{b(i,'mass')}*{b(j,'mass')}"), g("dist"),
            f"e -= ({b(i,'mass')} * {b(j,'mass')}) / dist"]

def mom(i):
    g = lambda e: f"assume match {e} : finite"
    return [g(b(i, "vx")), g(b(i, "mass")), f"px += {b(i,'vx')} * {b(i,'mass')}",
            g(b(i, "vy")), f"py += {b(i,'vy')} * {b(i,'mass')}", g(b(i, "vz")), f"pz += {b(i,'vz')} * {b(i,'mass')}"]

class W:
    def __init__(s): s.l = []; s.d = 0
    def __call__(s, *lines):
        for x in lines: s.l.append("\t" * s.d + x)
    def open(s, line): s(line + "{"); s.d += 1
    def close(s): s.d -= 1; s("}")
    def text(s): return "\n".join(s.l) + "\n"

def proof(w, mode, idx, arr="bodies"):
    """open the proof/loop wrappers; returns number of closes"""
    if mode == "udyn": return 0
    w.open(f"match {idx} in {arr}"); return 1

J0_COUNT = 0

def body_blocks(w, mode, kind):
    """kind: 'advance' | 'energy' | 'mom'. Emits the nested loops (indent by the caller) for one mode (static|safe|udyn)."""
    rng = (lambda v: f"for {v} in bodies") if mode == "static" else (lambda v: f"for {v} in 0..5")
    w.open(rng("i")); n = proof(w, mode, "i")
    if kind == "energy": w(*energy_i("i"))
    if kind == "mom": w(*mom("i"))
    if kind in ("advance", "energy"):
        global J0_COUNT
        J0_COUNT += 1
        j0 = f"j0_{J0_COUNT}"   # one name per loop: the optimizer's variable analysis (and the nested unroll's per-copy renaming) wants each local declared once
        w(f"let {j0} = mut (i + 1)")
        w.open(f"for j in {j0}..5"); m = proof(w, mode, "j")
        w(*(pair("i", "j") if kind == "advance" else energy_ij("i", "j")))
        for _ in range(m): w.close()
        w.close()
    for _ in range(n): w.close()
    w.close()

def decl(mode):
    elems = ", ".join("Body{" + ", ".join(f"{f}= {v!r}" for f, v in zip(F, row)) + "}" for row in INIT)
    if mode == "static": return [f"let static:<mut Body[5]> bodies = mut [{elems}]"]
    if mode == "safe": return [f"let bodies = mut ? dyn([{elems}])"]
    return [f"let bodies = mut unsafe dyn([{elems}])"]

HEAD = """// n-body (Computer Language Benchmarks Game), ported from the reference C program (nbody-gcc-1) -- ARRAY OF STRUCTS: %s.
// GENERATED by gen_aos.py; do not edit by hand.
// Same data layout as the C program (`struct planet {double x,y,z,vx,vy,vz,mass;}`, 56 bytes, `@untyped` = no hidden class id) and the same loop
// structure (i, then j = i+1..4; then the position update), unlike the 35-scalar `nbody_f64` and the struct-of-arrays `nbody_plain_f64`/`nbody_loop_f64`.
// Differences from the C program, forced by the language: `assume match .. : finite` before each float operation (as in the other variants), and
// d*d*d after the sqrt instead of d2*sqrt(d2).
// %s
extern printf(static imut string,...) void
extern sqrt(mut f64) mut f64
extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64
"""
GT = "\n".join('import "../../../stdlib/%s.caspien"' % n for n in ("libc", "gt_init", "gt_register", "gt_alive_check", "gt_destruct", "gt_moved")) + "\n"
STRUCT = """
@untyped
struct Body{@pub{
	x: mut f64
	y: mut f64
	z: mut f64
	vx: mut f64
	vy: mut f64
	vz: mut f64
	mass: mut f64
}}
"""

def gen_static():
    w = W()
    w(*decl("static"), "")
    w.open("func advance(dt: mut f64) void"); w.open("unsafe assume extern global")
    body_blocks(w, "static", "advance")
    w("")  # position update
    w.open("for i in bodies"); w.open("match i in bodies"); w(*move("i")); w.close(); w.close()
    w.close(); w("return"); w.close(); w("")
    w.open("func energy() mut f64"); w("let:<mut f64> e = mut 0.0"); w.open("unsafe assume extern global")
    body_blocks(w, "static", "energy"); w.close(); w("return e"); w.close(); w("")
    w.open("func offsetMomentum() void"); w.open("unsafe assume global")
    for v in "pxyz"[1:]: w(f"let:<mut f64> p{v} = mut 0.0")
    body_blocks(w, "static", "mom")
    for v in "xyz":
        w(f"assume match p{v} : finite", f"assume match p{v}/{M!r} : finite", f"bodies[0].v{v} = mut 0.0 - p{v} / {M!r}")
    w.close(); w("return"); w.close(); w("")
    w.open("func main() void"); w("let n = mut 0"); w.open("unsafe extern"); w('n = atol(getenv("NBODY_N"))'); w.close()
    w("offsetMomentum()", "let e0 = mut energy()"); w.open("unsafe extern"); w('printf("%.9f\\n", e0)'); w.close()
    w.open("for s in 0..n"); w("advance(0.01)"); w.close()
    w("let e1 = mut energy()"); w.open("unsafe extern"); w('printf("%.9f\\n", e1)'); w.close(); w("return"); w.close()
    return w.text()

def gen_dyn(mode):
    w = W(); udyn = mode == "udyn"
    w.open("func main() void")
    if not udyn: w("?catch(e){ return }")
    w.open("unsafe assume extern udyn" if udyn else "unsafe assume extern")
    w("let n = mut 0", 'n = atol(getenv("NBODY_N"))')
    w(*decl(mode))
    w("let:<mut f64> dt = mut 0.01", "let:<mut f64> e = mut 0.0", "let:<mut f64> px = mut 0.0", "let:<mut f64> py = mut 0.0", "let:<mut f64> pz = mut 0.0")
    body_blocks(w, mode, "mom")
    for v in "xyz":
        w(f"assume match p{v} : finite", f"assume match p{v}/{M!r} : finite")
    zero = ["0"] if udyn else []
    m0 = proof(w, mode, "0")
    for v in "xyz": w(f"bodies[0].v{v} = mut 0.0 - p{v} / {M!r}")
    for _ in range(m0): w.close()
    body_blocks(w, mode, "energy")
    w('printf("%.9f\\n", e)')
    w.open("for s in 0..n"); body_blocks(w, mode, "advance")
    w.open("for i in 0..5"); k = proof(w, mode, "i"); w(*move("i"))
    for _ in range(k): w.close()
    w.close(); w.close()
    w("e = mut 0.0")
    body_blocks(w, mode, "energy")
    w('printf("%.9f\\n", e)')
    w.close(); w("return"); w.close()
    return w.text()

for name, mode, desc, fn in [
    ("nbody_aos_static_f64", "static", "static `Body[5]`, `match i in bodies` proofs", lambda: gen_static()),
    ("nbody_aos_safe_f64", "safe", "safe heap dynarray `dyn:<Body>`, bounds proofs", lambda: gen_dyn("safe")),
    ("nbody_aos_unsafe_f64", "udyn", "headerless `unsafe dyn:<Body>`, no index proofs", lambda: gen_dyn("udyn"))]:
    head = HEAD % (desc, "Prints the energy before and after N steps (env NBODY_N).")
    if mode != "static": head = head.replace("extern printf(static imut string,...) void\n", "")  # libc.caspien declares printf
    open(os.path.join(HERE, name + ".caspien"), "w").write(head + (GT if mode != "static" else "") + STRUCT + "\n" + fn())
    print("wrote", name)
