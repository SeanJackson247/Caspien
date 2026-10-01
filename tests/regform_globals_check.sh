#!/bin/bash
# Hand-made stack-form LOB cases for RegisterFormPass: global scalar reads (`PUSH n <global>` -> `R_LD n %tD &sym`) and the
# "faithful flush" exception (a RISKY region is flushed instead of rolled back in front of a call bracket or a plain
# stack arithmetic op when every remembered entry is one 8-byte word). Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/regform_globals_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv4.java <<'EOF'
package caspien.lowerorder;
import java.util.*;
public class Drv4 {
    static List<List<BytecodeToken>> parse(String s) {
        List<List<BytecodeToken>> r = new ArrayList<>();
        for (String ln : s.split(" ; ")) {
            List<BytecodeToken> l = new ArrayList<>();
            for (String t : ln.trim().split(" ")) l.add(new BytecodeToken(t, "x", 0, BytecodeToken.Kind.CODE));
            r.add(l);
        }
        return r;
    }
    static final RegisterFormPass PASS = new RegisterFormPass();   // ONE instance for every case: the global-name set must be refilled per run
    static List<String> run(String in) {
        List<String> out = new ArrayList<>();
        for (List<BytecodeToken> l : PASS.run(parse(in)).lines) {
            StringBuilder b = new StringBuilder();
            for (BytecodeToken t : l) { if (b.length() > 0) b.append(' '); b.append(t.text); }
            out.add(b.toString());
        }
        return out;
    }
    static boolean has(List<String> o, String exact) { return o.contains(exact); }
    static boolean hasPrefix(List<String> o, String p) { for (String l : o) if (l.startsWith(p)) return true; return false; }
    static int bad = 0;
    static void chk(String name, boolean ok, List<String> o) {
        if (ok) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "   " + o); }
    }
    static String J(String... p) { return String.join(" ; ", p); }
    public static void main(String[] a) {
        // 1. a declared global read is loaded into a temp and the add is fused
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("global read -> R_LD from the symbol", has(o, "R_LD 8 %t0 &g"), o);
            chk("global read: no stack-form push of the global is left", !has(o, "PUSH 8 g"), o);
            chk("global read: the add is fused", hasPrefix(o, "R_BIN ADD 8 "), o);
        }
        // 2. ALLOC_STATIC declares a global too
        {
            List<String> o = run(J("ALLOC_STATIC h 8", "ADDR 8 $-8", "PUSH 8 h", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("ALLOC_STATIC name is a global", has(o, "R_LD 8 %t0 &h"), o);
        }
        // 3. a name nobody declared stays in stack form (it could be a string id, a function ...)
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 foo", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("an undeclared name is not fused", has(o, "PUSH 8 foo") && !hasPrefix(o, "R_LD"), o);
        }
        // 4. the declared set is refilled per run: g from case 1 is NOT a global here
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 g", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("the global set is per run", has(o, "PUSH 8 g") && !hasPrefix(o, "R_LD"), o);
        }
        // 5. a dotted alias into a parent block stays in stack form
        {
            List<String> o = run(J("GLOBAL a.0 8 0", "ADDR 8 $-8", "PUSH 8 a.0", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("a dotted global alias is not fused", has(o, "PUSH 8 a.0") && !hasPrefix(o, "R_LD"), o);
        }
        // 6. the backend looks at the line after a push of an atomic swap: not fused
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "PUSH 8 1", "ATOMIC_SWAP 8 8 8", "ASSIGN 8 8 8"));
            chk("PUSH of a global before ATOMIC_SWAP stays a PUSH", has(o, "PUSH 8 g") && !hasPrefix(o, "R_LD"), o);
        }
        // 7. narrow widths are loaded at their own width
        {
            List<String> o = run(J("GLOBAL g 4 0", "ADDR 8 $-8", "PUSH 4 g", "PUSH 8 1", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("a 4-byte global is loaded as 4 bytes", has(o, "R_LD 4 %t0 &g"), o);
        }
        // 8. more global reads than temps: no crash; the fifth read has no temp, the region is risky and a PUSH is not on the faithful list -> rolled back whole
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "PUSH 8 g", "PUSH 8 g", "PUSH 8 g", "PUSH 8 g",
                    "ADD_INT 8", "ADD_INT 8", "ADD_INT 8", "ADD_INT 8", "ASSIGN 8 8 8"));
            int loads = 0, pushes = 0; for (String l : o) { if (l.startsWith("R_LD 8 %t")) loads++; if (l.equals("PUSH 8 g")) pushes++; }
            chk("five global reads with four temps: all five reads still happen (the region is all-or-nothing)", loads + pushes == 5, o);
        }
        // 9. a pending operand beneath a call: the region is FLUSHED, the global read before the call survives as a load
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "CC_START sysv_x64", "CALL f 0", "CC_END sysv_x64",
                    "PUSH_RET_INT 8", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("call under a pending global read: the load is kept (no rollback)", has(o, "R_LD 8 %t0 &g"), o);
            chk("call under a pending global read: the value is pushed in front of the call", has(o, "R_PUSH 8 %t0"), o);
            int iLoad = o.indexOf("R_LD 8 %t0 &g"), iCall = o.indexOf("CALL f 0");
            chk("call under a pending global read: the load comes BEFORE the call", iLoad >= 0 && iCall > iLoad, o);
        }
        // 10. same with an address and a frame value pending (the n-body `e = e - ...`)
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 $-8", "ADDR 8 $-16", "PUSH 8 g", "PUSH 8 g", "MUL_INT 8",
                    "ASSIGN 8 8 8", "CC_START sysv_x64", "CALL f 0", "CC_END sysv_x64", "PUSH_RET_INT 8", "SUB_INT 8", "ASSIGN 8 8 8"));
            chk("pending address and frame value under a call: earlier fusion kept", hasPrefix(o, "R_BIN MUL 8 "), o);
        }
        // 11. a NARROW remembered entry under a call: rolled back to the original lines (the old behaviour)
        {
            List<String> o = run(J("GLOBAL g 4 0", "ADDR 8 $-8", "PUSH 4 g", "CC_START sysv_x64", "CALL f 0", "CC_END sysv_x64",
                    "PUSH_RET_INT 8", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("a narrow pending entry under a call: rolled back", has(o, "PUSH 4 g") && !hasPrefix(o, "R_LD"), o);
        }
        // 12. an unknown mnemonic in a risky region is NOT in the faithful list: rolled back
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "FOO_BAR 8", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("an unknown mnemonic in a risky region: rolled back", has(o, "PUSH 8 g") && !hasPrefix(o, "R_LD"), o);
        }
        // 13. a stack arithmetic op the pass does not fuse (signed divide) after a global read: flushed, the op stays, the load stays
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "PUSH 8 g", "DIV_INT 8", "ASSIGN 8 8 8"));
            chk("an unfused stack op after global reads: loads kept, op in stack form", has(o, "DIV_INT 8") && hasPrefix(o, "R_LD 8 %t"), o);
        }
        // 14. a stack float op the pass does not fuse (NEG_FLOAT) after a global read: flushed, the load stays
        {
            List<String> o = run(J("GLOBAL g 8 0", "ADDR 8 $-8", "PUSH 8 g", "NEG_FLOAT 8", "ASSIGN 8 8 8"));
            chk("an unfused stack float op after a global read: load kept, op in stack form", has(o, "NEG_FLOAT 8") && has(o, "R_LD 8 %t0 &g"), o);
        }
        System.out.println(bad == 0 ? "ALL REGFORM GLOBAL CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out} -d $T $T/caspien/lowerorder/Drv4.java 2>&1 | grep -v Picked
java -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out}:$T caspien.lowerorder.Drv4
rc=$?
rm -rf $T
exit $rc
