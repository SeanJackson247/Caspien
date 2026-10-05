#!/bin/bash
# Hand-made register-form (LOB) cases for LoopHoistPass (hoist-array-bases) and the displacement form of IndexedAccessPass.
# Needs LowerOrderGenerator/out built (env LOB_CP = a mutant classpath instead). Run from anywhere:  bash tests/loop_hoist_passes_check.sh
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
    static void hoist(String name, String in, String want) { chk(name, render(new LoopHoistPass(true).run(parse(in))), want); }
    static void same(String name, String in) { hoist(name + ": unchanged", in, in); }
    static void ia(String name, String in, String want) { chk(name, render(new IndexedAccessPass().run(parse(in))), want); }
    // the loop body of the basic case: two pointer-access starts on slot $-16
    static final String BODY = "R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; R_MOV 8 %t0 $-16 ; R_LEA %t0 %t0 %v0 1 16 ; R_LD 1 %t0 %t0";
    static final String BODYX = "R_MOV 8 %t0 $-72 ; R_LD 8 %t0 %t0 ; R_MOV 8 %t0 $-72 ; R_LEA %t0 %t0 %v0 1 16 ; R_LD 1 %t0 %t0";
    public static void main(String[] a) {
        String pre = "FUNC_START f ; ALLOC 64 ; ";
        hoist("basic loop", pre + "@L: ; " + BODY + " ; R_RMW INC 8 %v0 ; JMP @L ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; R_MOV 8 $-72 $-16 ; @L: ; " + BODYX + " ; R_RMW INC 8 %v0 ; JMP @L ; FUNC_END");
        same("slot written in the loop", pre + "@L: ; " + BODY + " ; R_MOV 8 $-16 %t1 ; JMP @L ; FUNC_END");
        same("slot is the target of ADDR in the loop (resize/assignment)", pre + "@L: ; " + BODY + " ; ADDR 8 $-16 ; JMP @L ; FUNC_END");
        same("wider store covering the slot (struct assignment)", pre + "@L: ; " + BODY + " ; ADDR 32 $-32 ; JMP @L ; FUNC_END");
        same("unknown access at or below the slot", pre + "@L: ; " + BODY + " ; R_LEA %t1 $-24 #0 1 ; JMP @L ; FUNC_END");
        same("R_RMW on the slot", pre + "@L: ; " + BODY + " ; R_RMW ADD 8 $-16 %t1 ; JMP @L ; FUNC_END");
        hoist("store to a neighbour slot below does not block",
              pre + "@L: ; " + BODY + " ; R_MOV 8 $-24 %t1 ; JMP @L ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; R_MOV 8 $-72 $-16 ; @L: ; " + BODYX + " ; R_MOV 8 $-24 %t1 ; JMP @L ; FUNC_END");
        hoist("write above the slot (higher offset) does not block",
              pre + "@L: ; " + BODY + " ; R_MOV 8 $-8 %t1 ; JMP @L ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; R_MOV 8 $-72 $-16 ; @L: ; " + BODYX + " ; R_MOV 8 $-8 %t1 ; JMP @L ; FUNC_END");
        same("address taken somewhere in the function (ADDR_OF)", pre + "ADDR_OF 8 $-16 ; @L: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("address taken below the slot within a struct's reach (ADDR_OF)", pre + "ADDR_OF 8 $-40 ; @L: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("address passed as an argument (R_ARGA)", pre + "R_ARGA 0 $-16 ; @L: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("a single pointer-access start", pre + "@L: ; R_MOV 8 %t0 $-16 ; R_LD 8 %t0 %t0 ; JMP @L ; FUNC_END");
        same("a read that is not the start of an access", pre + "@L: ; R_MOV 8 %t0 $-16 ; R_MOV 8 %t1 $-16 ; R_ST 8 %t2 %t0 ; JMP @L ; FUNC_END");
        same("header also entered from above", pre + "JMP @L ; @L: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("a label inside the loop entered from outside", pre + "JMP @M ; @L: ; R_MOV 8 %t5 #1 ; @M: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("inline assembly in the function", pre + "ASM_START ; ASM_END ; @L: ; " + BODY + " ; JMP @L ; FUNC_END");
        same("no loop", pre + BODY + " ; FUNC_END");
        hoist("a conditional back edge is a loop too",
              pre + "@L: ; " + BODY + " ; R_BRC LT 8 %v0 #9 @L ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; R_MOV 8 $-72 $-16 ; @L: ; " + BODYX + " ; R_BRC LT 8 %v0 #9 @L ; FUNC_END");
        hoist("nested loops: one copy before the outer loop",
              pre + "@O: ; @I: ; " + BODY + " ; JMP @I ; JMP @O ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; R_MOV 8 $-72 $-16 ; @O: ; @I: ; " + BODYX + " ; JMP @I ; JMP @O ; FUNC_END");
        hoist("outer loop writes the slot: the inner loop gets its own copy",
              pre + "@O: ; R_MOV 8 $-16 %t1 ; @I: ; " + BODY + " ; JMP @I ; JMP @O ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 128 ; @O: ; R_MOV 8 $-16 %t1 ; R_MOV 8 $-72 $-16 ; @I: ; " + BODYX + " ; JMP @I ; JMP @O ; FUNC_END");
        hoist("two sibling loops: two copies",
              pre + "@L: ; " + BODY + " ; JMP @L ; @M: ; " + BODY + " ; JMP @M ; FUNC_END",
              "FUNC_START f ; ALLOC 80 ; REGHINT -72 8 16 ; REGHINT -80 8 16 ; R_MOV 8 $-72 $-16 ; @L: ; " + BODYX + " ; JMP @L ; R_MOV 8 $-80 $-16 ; @M: ; "
              + BODYX.replace("$-72", "$-80") + " ; JMP @M ; FUNC_END");
        // finish(): copy in a register / copy left in memory
        String in = pre + "@L: ; " + BODY + " ; R_RMW INC 8 %v0 ; JMP @L ; FUNC_END";
        LoopHoistPass p = new LoopHoistPass(true);
        String hoisted = render(p.run(parse(in)));
        String promoted = hoisted.replace("$-72", "%v1").replace(" ; REGHINT %v1 8 16", "").replace("REGHINT -72 8 16 ; ", "");
        chk("finish: promoted copy: frame back, copy propagated into the access lines", render(p.finish(parse(promoted))),
            "FUNC_START f ; ALLOC 64 ; R_MOV 8 %v1 $-16 ; @L: ; R_LD 8 %t0 %v1 ; R_LEA %t0 %v1 %v0 1 16 ; R_LD 1 %t0 %t0 ; R_RMW INC 8 %v0 ; JMP @L ; FUNC_END");
        LoopHoistPass p2 = new LoopHoistPass(true);
        String h2 = render(p2.run(parse(in)));
        chk("finish: copy that stayed in memory is undone completely", render(p2.finish(parse(h2.replace("REGHINT -72 8 16 ; ", "")))), in);
        chk("finish: switch off is the identity", render(new LoopHoistPass(false).finish(parse(in))), in);
        chk("run: switch off is the identity", render(new LoopHoistPass(false).run(parse(in))), in);
        chk("copy propagation: the temp is used afterwards, so no propagation", render(new LoopHoistPass(true).finish(parse("FUNC_START g ; ALLOC 16 ; R_MOV 8 %t0 %v1 ; R_LD 8 %t2 %t0 ; R_ST 8 %t0 %t2 ; FUNC_END"))),
            "FUNC_START g ; ALLOC 16 ; R_MOV 8 %t0 %v1 ; R_LD 8 %t2 %t0 ; R_ST 8 %t0 %t2 ; FUNC_END");
        // IndexedAccessPass with a displacement (the safe dynarray header)
        ia("disp: load", "R_LEA %t0 %v1 %v0 1 16 ; R_LD 1 %t2 %t0", "R_LDI 1 %t2 %v1 %v0 1 16");
        ia("disp: base is the destination temp", "R_LEA %t0 %t0 %v0 1 16 ; R_LD 1 %t0 %t0", "R_LDI 1 %t0 %t0 %v0 1 16");
        ia("disp: store of an immediate", "R_LEA %t0 %t0 %v0 1 16 ; R_ST 1 %t0 #0", "R_STI 1 %t0 %v0 1 #0 16");
        ia("disp: store of a temp", "R_LEA %t0 %v1 %v0 4 16 ; R_MOV 8 %t1 #3 ; R_ST 4 %t0 %t1", "R_MOV 8 %t1 #3 ; R_STI 4 %v1 %v0 4 %t1 16");
        ia("disp: float load", "R_LEA %t0 %v1 %v0 8 16 ; R_LDX 8 %x0 %t0", "R_LDXI 8 %x0 %v1 %v0 8 16");
        ia("disp: float store", "R_LEA %t0 %v1 %v0 8 16 ; R_STX 8 %t0 %x0", "R_STXI 8 %v1 %v0 8 %x0 16");
        ia("disp: temp live afterwards: unchanged", "R_LEA %t0 %v1 %v0 1 16 ; R_LD 1 %t2 %t0 ; R_ST 8 %t3 %t0", "R_LEA %t0 %v1 %v0 1 16 ; R_LD 1 %t2 %t0 ; R_ST 8 %t3 %t0");
        ia("disp: access width differs from the scale: unchanged", "R_LEA %t0 %v1 %v0 1 16 ; R_LD 4 %t2 %t0", "R_LEA %t0 %v1 %v0 1 16 ; R_LD 4 %t2 %t0");
        ia("no disp still 5 tokens", "R_LEA %t0 %v1 %v0 8 ; R_LD 8 %t2 %t0", "R_LDI 8 %t2 %v1 %v0 8");
        System.out.println(bad == 0 ? "ALL LOOP HOIST PASS CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp "$CP" -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp "$CP:$T" caspien.lowerorder.Drv
