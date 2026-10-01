#!/bin/bash
# Hand-made register-form (LOB) cases for the float forms of IndexedAccessPass (`R_LEA` + `R_LDX`/`R_STX` -> `R_LDXI`/`R_STXI`,
# scale = access width: 4 for f32, 8 for f64) and for FloatTempPass keeping an `R_FBIN`-defined chain whose only reader is a store
# in xmm (`a[i] -= ...`). Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/floatindex_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv6.java <<'EOF2'
package caspien.lowerorder;
import java.util.*;
public class Drv6 {
    static List<List<BytecodeToken>> parse(String s) {
        List<List<BytecodeToken>> r = new ArrayList<>();
        for (String ln : s.split(" ; ")) {
            List<BytecodeToken> l = new ArrayList<>();
            for (String t : ln.trim().split(" ")) l.add(new BytecodeToken(t, "x", 0, BytecodeToken.Kind.CODE));
            r.add(l);
        }
        return r;
    }
    static String render(List<List<BytecodeToken>> ls) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i > 0) b.append(" ; ");
            for (int k = 0; k < ls.get(i).size(); k++) { if (k > 0) b.append(' '); b.append(ls.get(i).get(k).text); }
        }
        return b.toString();
    }
    static int bad = 0;
    static void ia(String name, String in, String want) { chk(name, render(new IndexedAccessPass().run(parse(in))), want); }
    static void ft(String name, String in, String want) { chk(name, render(new FloatTempPass(true).run(parse(in))), want); }
    static void same(String name, String in) { ia(name, in, in); }
    static void chk(String name, String got, String want) {
        if (got.equals(want)) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "\n   got:  " + got + "\n   want: " + want); }
    }
    public static void main(String[] a) {
        // ---- IndexedAccessPass, float loads ----
        ia("f64 load into a temp register", "R_LEA %t0 &a %v0 8 ; R_LDX 8 %y0 %t0", "R_LDXI 8 %y0 &a %v0 8");
        ia("f32 load (scale 4)", "R_LEA %t0 &a %v0 4 ; R_LDX 4 %y0 %t0", "R_LDXI 4 %y0 &a %v0 4");
        ia("f64 load into a variable register", "R_LEA %t0 &a %v1 8 ; R_LDX 8 %x2 %t0", "R_LDXI 8 %x2 &a %v1 8");
        same("f32 access on a scale-8 lea: unchanged", "R_LEA %t0 &a %v0 8 ; R_LDX 4 %y0 %t0");
        same("f64 access on a scale-4 lea: unchanged", "R_LEA %t0 &a %v0 4 ; R_LDX 8 %y0 %t0");
        same("load into a general register: unchanged", "R_LEA %t0 &a %v0 8 ; R_LDX 8 %t3 %t0");
        same("index is a slot: unchanged", "R_LEA %t0 &a $-8 8 ; R_LDX 8 %y0 %t0");
        same("index is an immediate: unchanged", "R_LEA %t0 &a #2 8 ; R_LDX 8 %y0 %t0");
        same("base is not a global: unchanged", "R_LEA %t0 $-40 %v0 8 ; R_LDX 8 %y0 %t0");
        same("address temp read again: unchanged", "R_LEA %t0 &a %v0 8 ; R_LDX 8 %y0 %t0 ; R_LDX 8 %y1 %t0");
        ia("address temp redefined by a float op afterwards", "R_LEA %t1 &a %v0 8 ; R_LDX 8 %y0 %t1 ; R_FBIN SUB 8 %t1 %y0 %y1", "R_LDXI 8 %y0 &a %v0 8 ; R_FBIN SUB 8 %t1 %y0 %y1");
        same("address temp read by a float op afterwards: unchanged", "R_LEA %t1 &a %v0 8 ; R_LDX 8 %y0 %t1 ; R_FBIN SUB 8 %t2 %t1 %y1");
        // ---- float stores ----
        ia("f64 store", "R_LEA %t0 &a %v0 8 ; R_FBINX ADD 8 %y1 %y0 %y0 ; R_STX 8 %t0 %y1", "R_FBINX ADD 8 %y1 %y0 %y0 ; R_STXI 8 &a %v0 8 %y1");
        ia("f32 store (scale 4)", "R_LEA %t0 &a %v0 4 ; R_STX 4 %t0 %y1", "R_STXI 4 &a %v0 4 %y1");
        ia("store of a variable register", "R_LEA %t0 &a %v1 8 ; R_STX 8 %t0 %x3", "R_STXI 8 &a %v1 8 %x3");
        same("store, width differs from the scale: unchanged", "R_LEA %t0 &a %v0 8 ; R_STX 4 %t0 %y1");
        same("store of a general register: unchanged", "R_LEA %t0 &a %v0 8 ; R_STX 8 %t0 %t2");
        same("store, address temp live afterwards: unchanged", "R_LEA %t0 &a %v0 8 ; R_STX 8 %t0 %y1 ; R_LD 8 %t2 %t0");
        // ---- the gap between lea and consumer ----
        ia("float lines between are passable", "R_LEA %t0 &a %v0 8 ; R_LDX 8 %y0 %t1 ; R_FBINX MUL 8 %y0 %y0 %y1 ; R_XMOV %x0 %y0 ; R_STX 8 %t0 %y0", "R_LDX 8 %y0 %t1 ; R_FBINX MUL 8 %y0 %y0 %y1 ; R_XMOV %x0 %y0 ; R_STXI 8 &a %v0 8 %y0");
        same("index written in between: unchanged", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %v0 #1 ; R_STX 8 %t0 %y0");
        same("index written by R_RMW in between: unchanged", "R_LEA %t0 &a %v0 8 ; R_RMW INC 8 %v0 ; R_LDX 8 %y0 %t0");
        same("index written by R_XTOG in between: unchanged", "R_LEA %t0 &a %v0 8 ; R_XTOG 8 %v0 %x1 ; R_LDX 8 %y0 %t0");
        same("unknown line in between: unchanged", "R_LEA %t0 &a %v0 8 ; FOO 1 ; R_LDX 8 %y0 %t0");
        // a long gap of float lines (the compound-assignment shape) still fuses; 41 lines in between do not
        StringBuilder g = new StringBuilder("R_LEA %t0 &a %v0 8");
        for (int i = 0; i < 30; i++) g.append(" ; R_FBINX ADD 8 %y1 %y1 %y2");
        String body = g.substring(g.indexOf(" ; ") + 3);
        ia("a 30-line gap still fuses", g + " ; R_LDX 8 %y0 %t0", body + " ; R_LDXI 8 %y0 &a %v0 8");
        StringBuilder g2 = new StringBuilder("R_LEA %t0 &a %v0 8");
        for (int i = 0; i < 41; i++) g2.append(" ; R_FBINX ADD 8 %y1 %y1 %y2");
        same("a 41-line gap does not", g2 + " ; R_LDX 8 %y0 %t0");
        // ---- the integer forms are as before ----
        ia("int load, scale 8, size 8", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0", "R_LDI 8 %t1 &a %v0 8");
        same("int load, scale 4: unchanged", "R_LEA %t0 &a %v0 4 ; R_LD 4 %t1 %t0");
        same("int load, scale 4, size 8: unchanged", "R_LEA %t0 &a %v0 4 ; R_LD 8 %t1 %t0");
        same("int store, scale 4: unchanged", "R_LEA %t0 &a %v0 4 ; R_ST 4 %t0 %t1");
        // ---- two array elements in one compound statement ----
        ia("read-modify-write through two leas", "R_LEA %t0 &a %v0 8 ; R_LEA %t1 &a %v0 8 ; R_LDX 8 %y0 %t1 ; R_FBINX MUL 8 %y0 %y0 %y1 ; R_STX 8 %t0 %y0",
                "R_LDXI 8 %y0 &a %v0 8 ; R_FBINX MUL 8 %y0 %y0 %y1 ; R_STXI 8 &a %v0 8 %y0");
        // ---- FloatTempPass: a float-op chain read only by a store stays in xmm ----
        ft("f64 a[i] -= b: the difference stays in xmm", "FUNC_START f ; R_LD 8 %t1 $-8 ; R_LD 8 %t2 $-16 ; R_FBIN SUB 8 %t1 %t1 %t2 ; R_ST 8 %t0 %t1 ; FUNC_END",
                "FUNC_START f ; R_LDX 8 %y0 $-8 ; R_LDX 8 %y1 $-16 ; R_FBINX SUB 8 %y0 %y0 %y1 ; R_STX 8 %t0 %y0 ; FUNC_END");
        ft("f32 a[i] -= b: the difference stays in xmm", "FUNC_START f ; R_LD 4 %t1 $-8 ; R_LD 4 %t2 $-16 ; R_FBIN SUB 4 %t1 %t1 %t2 ; R_ST 4 %t0 %t1 ; FUNC_END",
                "FUNC_START f ; R_LDX 4 %y0 $-8 ; R_LDX 4 %y1 $-16 ; R_FBINX SUB 4 %y0 %y0 %y1 ; R_STX 4 %t0 %y0 ; FUNC_END");
        ft("a plain integer copy is never turned into xmm moves", "FUNC_START f ; R_LD 8 %t1 $-8 ; R_ST 8 %t0 %t1 ; FUNC_END", "FUNC_START f ; R_LD 8 %t1 $-8 ; R_ST 8 %t0 %t1 ; FUNC_END");
        ft("the difference is also read by an integer op: stays general", "FUNC_START f ; R_LD 8 %t1 $-8 ; R_LD 8 %t2 $-16 ; R_FBIN SUB 8 %t1 %t1 %t2 ; R_BIN ADD 8 %t3 %t1 #1 ; R_ST 8 %t0 %t1 ; FUNC_END",
                "FUNC_START f ; R_LDX 8 %y0 $-8 ; R_LDX 8 %y1 $-16 ; R_FBIN SUB 8 %t1 %y0 %y1 ; R_BIN ADD 8 %t3 %t1 #1 ; R_ST 8 %t0 %t1 ; FUNC_END");
        System.out.println(bad == 0 ? "ALL FLOAT INDEX CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF2
javac -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out} -d $T $T/caspien/lowerorder/Drv6.java 2>&1 | grep -v Picked
java -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out}:$T caspien.lowerorder.Drv6
rc=$?
rm -rf $T
exit $rc
