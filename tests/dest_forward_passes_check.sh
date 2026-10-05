#!/bin/bash
# Hand-made register-form (LOB) cases for DestForwardingPass. Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/dest_forward_passes_check.sh      (prints one PASS/FAIL line per case)
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
    static void df(String name, String in, String want) { chk(name, render(new DestForwardingPass().run(parse(in))), want); }
    static void chk(String name, String got, String want) {
        if (got.equals(want)) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "\n   got:  " + got + "\n   want: " + want); }
    }
    public static void main(String[] a) {
        df("load into var", "R_LDI 8 %t0 %t0 %v3 8 ; R_MOV 8 %v5 %t0", "R_LDI 8 %v5 %t0 %v3 8");
        df("load with displacement", "R_LDD 4 %t1 %v0 16 ; R_MOV 8 %v2 %t1", "R_LDD 4 %v2 %v0 16");
        df("plain load", "R_LD 2 %t0 %t0 ; R_MOV 8 %v1 %t0", "R_LD 2 %v1 %t0");
        df("temp live afterwards: unchanged", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_ST 8 %t2 %t0", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_ST 8 %t2 %t0");
        df("temp redefined afterwards", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_MOV 8 %t0 #5", "R_LD 8 %v1 %t1 ; R_MOV 8 %t0 #5");
        df("temp read by its own redefinition: unchanged", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_BIN ADD 8 %t0 %t0 #1", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_BIN ADD 8 %t0 %t0 #1");
        df("label after the copy ends the temp's life", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; @L1:", "R_LD 8 %v1 %t1 ; @L1:");
        df("conditional branch after the copy: scan goes on", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_BRC LT 8 %v1 #3 @L1 ; R_ST 8 %t2 %t0", "R_LD 8 %t0 %t1 ; R_MOV 8 %v1 %t0 ; R_BRC LT 8 %v1 #3 @L1 ; R_ST 8 %t2 %t0");
        df("copy of a variable", "R_MOV 8 %t0 %v1 ; R_MOV 8 %v3 %t0 ; R_MOV 8 %t0 #0", "R_MOV 8 %v3 %v1 ; R_MOV 8 %t0 #0");
        df("copy of a slot", "R_MOV 8 %t0 $-232 ; R_MOV 8 %v4 %t0 ; JMP @X", "R_MOV 8 %v4 $-232 ; JMP @X");
        df("narrow R_MOV: unchanged", "R_MOV 4 %t0 $-232 ; R_MOV 8 %v4 %t0 ; JMP @X", "R_MOV 4 %t0 $-232 ; R_MOV 8 %v4 %t0 ; JMP @X");
        df("shift by an immediate", "R_BIN SHR 8 %t0 %t0 #1 ; R_MOV 8 %v3 %t0 ; JMP @X", "R_BIN SHR 8 %v3 %t0 #1 ; JMP @X");
        df("shift by a variable: unchanged", "R_BIN SHR 8 %t0 %t0 %v2 ; R_MOV 8 %v3 %t0 ; JMP @X", "R_BIN SHR 8 %t0 %t0 %v2 ; R_MOV 8 %v3 %t0 ; JMP @X");
        df("add", "R_BIN ADD 8 %t0 %v1 $-232 ; R_MOV 8 %v1 %t0 ; JMP @X", "R_BIN ADD 8 %v1 %v1 $-232 ; JMP @X");
        df("compare: unchanged", "R_BIN LT 8 %t0 %v1 %v2 ; R_MOV 8 %v1 %t0 ; JMP @X", "R_BIN LT 8 %t0 %v1 %v2 ; R_MOV 8 %v1 %t0 ; JMP @X");
        df("division: unchanged", "R_BIN DIV 8 %t0 %v1 %v2 ; R_MOV 8 %v1 %t0 ; JMP @X", "R_BIN DIV 8 %t0 %v1 %v2 ; R_MOV 8 %v1 %t0 ; JMP @X");
        df("lea: unchanged", "R_LEA %t0 %v1 %v2 8 ; R_MOV 8 %v1 %t0 ; JMP @X", "R_LEA %t0 %v1 %v2 8 ; R_MOV 8 %v1 %t0 ; JMP @X");
        df("copy into a temp: unchanged", "R_LD 8 %t0 %t1 ; R_MOV 8 %t2 %t0 ; JMP @X", "R_LD 8 %t0 %t1 ; R_MOV 8 %t2 %t0 ; JMP @X");
        df("producer writes another register: unchanged", "R_LD 8 %t1 %t0 ; R_MOV 8 %v1 %t0 ; JMP @X", "R_LD 8 %t1 %t0 ; R_MOV 8 %v1 %t0 ; JMP @X");
        df("dead zero-init", "R_MOV 8 %v5 #0 ; R_MOV 8 %t0 $-48 ; R_LDI 8 %v5 %t0 %v3 8", "R_MOV 8 %t0 $-48 ; R_LDI 8 %v5 %t0 %v3 8");
        df("zero-init read before the redefinition: kept", "R_MOV 8 %v5 #0 ; R_ST 8 %t0 %v5 ; R_LD 8 %v5 %t0", "R_MOV 8 %v5 #0 ; R_ST 8 %t0 %v5 ; R_LD 8 %v5 %t0");
        df("zero-init read by its own redefinition: kept", "R_MOV 8 %v5 #0 ; R_BIN ADD 8 %v5 %v5 #1", "R_MOV 8 %v5 #0 ; R_BIN ADD 8 %v5 %v5 #1");
        df("zero-init before a label: kept", "R_MOV 8 %v5 #0 ; @L1: ; R_MOV 8 %v5 #1", "R_MOV 8 %v5 #0 ; @L1: ; R_MOV 8 %v5 #1");
        df("zero-init before a branch: kept", "R_MOV 8 %v5 #0 ; R_BRC LT 8 %v1 #3 @L1 ; R_MOV 8 %v5 #1", "R_MOV 8 %v5 #0 ; R_BRC LT 8 %v1 #3 @L1 ; R_MOV 8 %v5 #1");
        df("zero-init before an R_RMW: kept", "R_MOV 8 %v5 #0 ; R_RMW ADD 8 %v5 #1 ; R_MOV 8 %v5 #1", "R_MOV 8 %v5 #0 ; R_RMW ADD 8 %v5 #1 ; R_MOV 8 %v5 #1");
        df("zero-init before an unknown line: kept", "R_MOV 8 %v5 #0 ; FOO 1 ; R_MOV 8 %v5 #1", "R_MOV 8 %v5 #0 ; FOO 1 ; R_MOV 8 %v5 #1");
        df("forward then the old value dead: two pairs", "R_MOV 8 %v5 #0 ; R_MOV 8 %t0 $-48 ; R_LDI 8 %t0 %t0 %v3 8 ; R_MOV 8 %v5 %t0 ; JMP @X", "R_MOV 8 %t0 $-48 ; R_LDI 8 %v5 %t0 %v3 8 ; JMP @X");
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp $ROOT/LowerOrderGenerator/out -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp $ROOT/LowerOrderGenerator/out:$T caspien.lowerorder.Drv
rc=$?
rm -rf $T
exit $rc
