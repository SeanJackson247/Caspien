#!/bin/bash
# Hand-made register-form (LOB) cases for RegVarPromotionPass with variables-in-alloc-functions on/off. Needs LowerOrderGenerator/out built (env LOB_CP = mutant classpath).
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
    static String promote(String in, boolean alloc) { return render(new RegVarPromotionPass(true, false, alloc).run(parse(in))); }
    public static void main(String[] a) {
        String loop = "FUNC_START f ; ALLOC 32 ; REGHINT -8 8 64 ; @L: ; R_MOV 8 %t0 $-8 ; NEW_DYN 0 0 ; R_RMW ADD 8 $-8 #1 ; JMP @L ; FUNC_END";
        String withReg = "FUNC_START f ; ALLOC 32 ; @L: ; R_MOV 8 %t0 %v0 ; NEW_DYN 0 0 ; R_RMW ADD 8 %v0 #1 ; JMP @L ; FUNC_END";
        String without = "FUNC_START f ; ALLOC 32 ; @L: ; R_MOV 8 %t0 $-8 ; NEW_DYN 0 0 ; R_RMW ADD 8 $-8 #1 ; JMP @L ; FUNC_END";
        chk("switch on: a variable live across NEW_DYN gets r13 (%v0)", promote(loop, true), withReg);
        chk("switch off: the function stays blocked, the variable stays in memory", promote(loop, false), without);
        String dot = "FUNC_START f ; ALLOC 32 ; REGHINT -8 8 64 ; @L: ; R_MOV 8 %t0 $-8 ; DOT 8 ; R_RMW ADD 8 $-8 #1 ; JMP @L ; FUNC_END";
        chk("switch on: DOT is a scratch user too", promote(dot, true), "FUNC_START f ; ALLOC 32 ; @L: ; R_MOV 8 %t0 %v0 ; DOT 8 ; R_RMW ADD 8 %v0 #1 ; JMP @L ; FUNC_END");
        chk("switch on: inline assembly still blocks everything", promote("FUNC_START f ; ALLOC 32 ; REGHINT -8 8 64 ; ASM_START ; ASM_END ; @L: ; R_MOV 8 %t0 $-8 ; R_RMW ADD 8 $-8 #1 ; JMP @L ; FUNC_END", true),
            "FUNC_START f ; ALLOC 32 ; ASM_START ; ASM_END ; @L: ; R_MOV 8 %t0 $-8 ; R_RMW ADD 8 $-8 #1 ; JMP @L ; FUNC_END");
        System.out.println(bad == 0 ? "ALL ALLOC VARS PASS CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp "$CP" -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp "$CP:$T" caspien.lowerorder.Drv
