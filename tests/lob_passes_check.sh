#!/bin/bash
# Hand-made register-form (LOB) cases for JumpCleanupPass and IndexedAccessPass. Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/lob_passes_check.sh      (prints one PASS/FAIL line per case)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
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
    static void jc(String name, String in, String want) { chk(name, render(new JumpCleanupPass().run(parse(in))), want); }
    static void ia(String name, String in, String want) { chk(name, render(new IndexedAccessPass().run(parse(in))), want); }
    static void chk(String name, String got, String want) {
        if (got.equals(want)) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "\n   got:  " + got + "\n   want: " + want); }
    }
    public static void main(String[] a) {
        String[][] opp = {{"EQ","NEQ"},{"NEQ","EQ"},{"LT","GT_EQ"},{"GT_EQ","LT"},{"LT_EQ","GT"},{"GT","LT_EQ"},{"SLT","SGT_EQ"},{"SGT_EQ","SLT"},{"SLT_EQ","SGT"},{"SGT","SLT_EQ"}};
        for (String[] o : opp)
            jc("jump over jump, " + o[0], "R_BRC " + o[0] + " 8 %t0 #1 @L1 ; JMP @Lend ; @L1:", "R_BRC " + o[1] + " 8 %t0 #1 @Lend ; @L1:");
        jc("other code after the jump: unchanged", "R_BRC EQ 8 %t0 #1 @L1 ; JMP @L2 ; R_MOV 8 %t1 #0 ; @L1:", "R_BRC EQ 8 %t0 #1 @L1 ; JMP @L2 ; R_MOV 8 %t1 #0 ; @L1:");
        jc("jump after a jump is dead", "JMP @A ; JMP @B ; @X:", "JMP @A ; @X:");
        jc("a label stops dead-jump removal", "JMP @A ; @X: ; JMP @B", "JMP @A ; @X: ; JMP @B");
        jc("stack form: CMP + JMP is conditional, the JMP after it is reachable", "CMP ; JMP @A ; JMP @B ; @A:", "CMP ; JMP @A ; JMP @B ; @A:");
        jc("stack form: conditional jump to the next label stays", "CMP ; JMP @L1 ; @L1:", "CMP ; JMP @L1 ; @L1:");
        jc("stack form: break chain: only the dead jump goes", "CMP ; JMP @E ; JMP @B ; JMP @E ; @E:", "CMP ; JMP @E ; JMP @B ; @E:");
        jc("jump to the next label", "JMP @L1 ; @L1:", "@L1:");
        jc("jump to a later label stays", "JMP @L1 ; R_MOV 8 %t0 #1 ; @L1:", "JMP @L1 ; R_MOV 8 %t0 #1 ; @L1:");
        jc("jump past two labels", "JMP @L2 ; @L1: ; @L2:", "@L1: ; @L2:");
        jc("chain from if c { break }", "R_BRC EQ 8 %t0 #1 @L1 ; JMP @Lend ; JMP @L1 ; @L1:", "R_BRC NEQ 8 %t0 #1 @Lend ; @L1:");
        ia("load", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0", "R_LDI 8 %t1 &a %v0 8");
        ia("load, temp live afterwards: unchanged", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; R_ST 8 %t2 %t0", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; R_ST 8 %t2 %t0");
        ia("load, temp redefined afterwards", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; R_MOV 8 %t0 #5", "R_LDI 8 %t1 &a %v0 8 ; R_MOV 8 %t0 #5");
        ia("load, temp used as base of a later lea: unchanged", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; R_LEA %t0 %t0 #1 8", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; R_LEA %t0 %t0 #1 8");
        ia("index written in between: unchanged", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %v0 #1 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %v0 #1 ; R_LD 8 %t1 %t0");
        ia("index written by R_RMW in between: unchanged", "R_LEA %t0 &a %v0 8 ; R_RMW ADD 8 %v0 #1 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a %v0 8 ; R_RMW ADD 8 %v0 #1 ; R_LD 8 %t1 %t0");
        ia("other register written in between", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %v1 #1 ; R_LD 8 %t1 %t0", "R_MOV 8 %v1 #1 ; R_LDI 8 %t1 &a %v0 8");
        ia("unknown line in between: unchanged", "R_LEA %t0 &a %v0 8 ; FOO 1 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a %v0 8 ; FOO 1 ; R_LD 8 %t1 %t0");
        ia("store", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %t1 #3 ; R_ST 8 %t0 %t1", "R_MOV 8 %t1 #3 ; R_STI 8 &a %v0 8 %t1");
        ia("store of an immediate", "R_LEA %t0 &a %v0 8 ; R_ST 8 %t0 #7", "R_STI 8 &a %v0 8 #7");
        ia("store of the address itself: unchanged", "R_LEA %t0 &a %v0 8 ; R_ST 8 %t0 %t0", "R_LEA %t0 &a %v0 8 ; R_ST 8 %t0 %t0");
        ia("index is a slot: unchanged", "R_LEA %t0 &a $-8 8 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a $-8 8 ; R_LD 8 %t1 %t0");
        ia("index is an immediate: unchanged", "R_LEA %t0 &a #3 8 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a #3 8 ; R_LD 8 %t1 %t0");
        ia("4-byte access: unchanged", "R_LEA %t0 &a %v0 8 ; R_LD 4 %t1 %t0", "R_LEA %t0 &a %v0 8 ; R_LD 4 %t1 %t0");
        ia("scale 4: unchanged", "R_LEA %t0 &a %v0 4 ; R_LD 8 %t1 %t0", "R_LEA %t0 &a %v0 4 ; R_LD 8 %t1 %t0");
        ia("base is a slot", "R_LEA %t0 $-8 %v0 8 ; R_LD 8 %t1 %t0", "R_LDI 8 %t1 $-8 %v0 8");
        ia("register base", "R_LEA %t0 %v1 %v0 8 ; R_LD 8 %t1 %t0", "R_LDI 8 %t1 %v1 %v0 8");
        ia("register base written in between: unchanged", "R_LEA %t0 %v1 %v0 8 ; R_MOV 8 %v1 %t2 ; R_LD 8 %t1 %t0", "R_LEA %t0 %v1 %v0 8 ; R_MOV 8 %v1 %t2 ; R_LD 8 %t1 %t0");
        ia("temp dead at a label", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t1 %t0 ; @L: ; R_ST 8 %t2 %t0", "R_LDI 8 %t1 &a %v0 8 ; @L: ; R_ST 8 %t2 %t0");
        ia("destination equals the address temp", "R_LEA %t0 &a %v0 8 ; R_LD 8 %t0 %t0 ; R_ST 8 %t2 %t0", "R_LDI 8 %t0 &a %v0 8 ; R_ST 8 %t2 %t0");
        ia("consumer is not a load/store of the temp: unchanged", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %t1 %t0", "R_LEA %t0 &a %v0 8 ; R_MOV 8 %t1 %t0");
        ia("two in a row", "R_LEA %t0 &a %v0 8 ; R_LEA %t1 &b %v1 8 ; R_LD 8 %t1 %t1 ; R_ST 8 %t0 %t1", "R_LDI 8 %t1 &b %v1 8 ; R_STI 8 &a %v0 8 %t1");
        System.out.println(bad == 0 ? "ALL LOB PASS CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp $ROOT/LowerOrderGenerator/out -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp $ROOT/LowerOrderGenerator/out:$T caspien.lowerorder.Drv
rc=$?
rm -rf $T
exit $rc
