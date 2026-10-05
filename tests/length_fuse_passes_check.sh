#!/bin/bash
# Hand-made register-form (LOB) cases for LengthCompareFusionPass (fuse-length-compare).
# Needs LowerOrderGenerator/out built (env LOB_CP = a mutant classpath instead). Run from anywhere:  bash tests/length_fuse_passes_check.sh
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP="${LOB_CP:-$ROOT/LowerOrderGenerator/out}"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv.java <<'EOF'
package caspien.lowerorder;
import java.util.*;
public class Drv {
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
    static void chk(String name, String got, String want) {
        if (got.equals(want)) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "\n   got:  " + got + "\n   want: " + want); }
    }
    static void fuse(String name, String in, String want) { chk(name, render(new LengthCompareFusionPass(true).run(parse(in))), want); }
    static void same(String name, String in) { fuse(name + ": unchanged", in, in); }
    public static void main(String[] a) {
        // form 1: base loaded from a frame slot, length compared, base reloaded
        fuse("form 1: reload dropped, base stays in the temp",
             "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 $-16 ; R_LDI 8 %t0 %t0 %v0 8 16 ; R_RMW INC 8 %v1 ; @O: ; R_MOV 8 %t0 #1",
             "R_MOV 8 %t0 $-16 ; R_BRCM LT 8 %v0 %t0 @O ; R_LDI 8 %t0 %t0 %v0 8 16 ; R_RMW INC 8 %v1 ; @O: ; R_MOV 8 %t0 #1");
        fuse("form 1: frame-slot index is fine",
             "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 $-24 %t0 @O ; R_MOV 8 %t0 $-16 ; R_LDI 8 %t0 %t0 %v0 8 16 ; @O: ; R_MOV 8 %t0 #1",
             "R_MOV 8 %t0 $-16 ; R_BRCM LT 8 $-24 %t0 @O ; R_LDI 8 %t0 %t0 %v0 8 16 ; @O: ; R_MOV 8 %t0 #1");
        fuse("form 1: no reload (a different line follows), temp redefined there",
             "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #5 ; @O: ; R_MOV 8 %t0 #1",
             "R_MOV 8 %t0 $-16 ; R_BRCM LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #5 ; @O: ; R_MOV 8 %t0 #1");
        same("form 1: the length is read after the branch (temp live on the fall-through path)",
             "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %v0 %t0 @O ; R_ST 8 %t1 %t0 ; @O: ; R_MOV 8 %t0 #1");
        same("form 1: the temp is read at the jump-out label",
             "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 $-16 ; @O: ; R_ST 8 %t1 %t0");
        same("form 1: index is the temp itself", "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %t0 %t0 @O ; R_MOV 8 %t0 $-16 ; @O: ; R_MOV 8 %t0 #1");
        same("form 1: narrow compare", "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 4 %v0 %t0 @O ; R_MOV 8 %t0 $-16 ; @O: ; R_MOV 8 %t0 #1");
        same("form 1: the length is the FIRST compared operand", "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %t0 %v0 @O ; R_MOV 8 %t0 $-16 ; @O: ; R_MOV 8 %t0 #1");
        same("form 1: target label unknown (not in this text)", "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_BRC LT 8 %v0 %t0 @Z ; R_MOV 8 %t0 $-16 ; R_MOV 8 %t0 #1");
        // form 2: base already in a variable register
        fuse("form 2: hoisted base in a variable register",
             "R_LD 8 %t0 %v3 ; R_BRC LT 8 %v0 %t0 @O ; R_LEA %t0 %v3 %v0 8 16 ; R_LD 8 %t0 %t0 ; @O: ; R_MOV 8 %t0 #1",
             "R_BRCM LT 8 %v0 %v3 @O ; R_LEA %t0 %v3 %v0 8 16 ; R_LD 8 %t0 %t0 ; @O: ; R_MOV 8 %t0 #1");
        same("form 2: the loaded length is used again afterwards",
             "R_LD 8 %t0 %v3 ; R_BRC LT 8 %v0 %t0 @O ; R_ST 8 %t1 %t0 ; @O: ; R_MOV 8 %t0 #1");
        same("form 2: a frame-slot index (nothing to gain)", "R_LD 8 %t0 %v3 ; R_BRC LT 8 $-24 %t0 @O ; R_MOV 8 %t0 #3 ; @O: ; R_MOV 8 %t0 #1");
        same("form 2: the temp is live at the jump-out label", "R_LD 8 %t0 %v3 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #3 ; @O: ; R_ST 8 %t1 %t0");
        same("form 2: not a variable-register base (a temp)", "R_LD 8 %t0 %t2 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #3 ; @O: ; R_MOV 8 %t0 #1");
        chk("switch off is the identity",
            render(new LengthCompareFusionPass(false).run(parse("R_LD 8 %t0 %v3 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #3 ; @O: ; R_MOV 8 %t0 #1"))),
            "R_LD 8 %t0 %v3 ; R_BRC LT 8 %v0 %t0 @O ; R_MOV 8 %t0 #3 ; @O: ; R_MOV 8 %t0 #1");
        System.out.println(bad == 0 ? "ALL LENGTH FUSE PASS CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp "$CP" -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp "$CP:$T" caspien.lowerorder.Drv
