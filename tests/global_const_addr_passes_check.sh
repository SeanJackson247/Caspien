#!/bin/bash
# Hand-made register-form (LOB) cases for GlobalConstAddrPass. Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/global_const_addr_passes_check.sh      (prints one PASS/FAIL line per case)
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
    static void df(String name, String in, String want) { chk(name, render(new GlobalConstAddrPass().run(parse(in))), want); }
    static void chk(String name, String got, String want) {
        if (got.equals(want)) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "\n   got:  " + got + "\n   want: " + want); }
    }
    public static void main(String[] a) {
        df("load", "R_LEA %t0 &W #9 8 ; R_LD 8 %v6 %t0", "R_LD 8 %v6 &W+72");
        df("load index 0: plain symbol", "R_LEA %t0 &W #0 8 ; R_LD 8 %v6 %t0", "R_LD 8 %v6 &W");
        df("narrow element", "R_LEA %t0 &B #5 1 ; R_LD 1 %t1 %t0", "R_LD 1 %t1 &B+5");
        df("u32 element", "R_LEA %t0 &C #7 4 ; R_LD 4 %t1 %t0", "R_LD 4 %t1 &C+28");
        df("odd width: unchanged", "R_LEA %t0 &C #7 4 ; R_LD 3 %t1 %t0", "R_LEA %t0 &C #7 4 ; R_LD 3 %t1 %t0");
        df("store", "R_LEA %t0 &W #16 8 ; R_BIN ADD 8 %t1 %v3 %v4 ; R_ST 8 %t0 %t1", "R_BIN ADD 8 %t1 %v3 %v4 ; R_ST 8 &W+128 %t1");
        df("store of an immediate", "R_LEA %t0 &W #2 8 ; R_ST 8 %t0 #7", "R_ST 8 &W+16 #7");
        df("store of the address itself: unchanged", "R_LEA %t0 &W #2 8 ; R_ST 8 %t0 %t0", "R_LEA %t0 &W #2 8 ; R_ST 8 %t0 %t0");
        df("temp live afterwards: unchanged", "R_LEA %t0 &W #9 8 ; R_LD 8 %v6 %t0 ; R_ST 8 %t0 %v6", "R_LEA %t0 &W #9 8 ; R_LD 8 %v6 %t0 ; R_ST 8 %t0 %v6");
        df("temp redefined afterwards", "R_LEA %t0 &W #9 8 ; R_LD 8 %v6 %t0 ; R_MOV 8 %t0 #5", "R_LD 8 %v6 &W+72 ; R_MOV 8 %t0 #5");
        df("load into the address temp itself", "R_LEA %t0 &W #9 8 ; R_LD 8 %t0 %t0 ; R_ST 8 %t1 %t0", "R_LD 8 %t0 &W+72 ; R_ST 8 %t1 %t0");
        df("lea with displacement", "R_LEA %t0 &W #1 8 16 ; R_LD 8 %v6 %t0", "R_LD 8 %v6 &W+24");
        df("variable index: unchanged", "R_LEA %t0 &W %v1 8 ; R_LD 8 %v6 %t0", "R_LEA %t0 &W %v1 8 ; R_LD 8 %v6 %t0");
        df("frame slot base: unchanged", "R_LEA %t0 $-64 #1 8 ; R_LD 8 %v6 %t0", "R_LEA %t0 $-64 #1 8 ; R_LD 8 %v6 %t0");
        df("unknown line in between: unchanged", "R_LEA %t0 &W #1 8 ; FOO 1 ; R_LD 8 %v6 %t0", "R_LEA %t0 &W #1 8 ; FOO 1 ; R_LD 8 %v6 %t0");
        df("other register written in between", "R_LEA %t0 &W #1 8 ; R_MOV 8 %v1 #1 ; R_LD 8 %v6 %t0", "R_MOV 8 %v1 #1 ; R_LD 8 %v6 &W+8");
        df("float load", "R_LEA %t0 &F #3 8 ; R_LDX 8 %y0 %t0", "R_LDX 8 %y0 &F+24");
        df("float store", "R_LEA %t0 &F #3 4 ; R_STX 4 %t0 %x1", "R_STX 4 &F+12 %x1");
        df("float odd width: unchanged", "R_LEA %t0 &F #3 8 ; R_LDX 2 %y0 %t0", "R_LEA %t0 &F #3 8 ; R_LDX 2 %y0 %t0");
        df("two in a row", "R_LEA %t0 &W #1 8 ; R_LD 8 %v6 %t0 ; R_LEA %t0 &W #2 8 ; R_LD 8 %v7 %t0", "R_LD 8 %v6 &W+8 ; R_LD 8 %v7 &W+16");
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp $ROOT/LowerOrderGenerator/out -d $T $T/caspien/lowerorder/Drv.java 2>&1 | grep -v Picked
java -cp $ROOT/LowerOrderGenerator/out:$T caspien.lowerorder.Drv
rc=$?
rm -rf $T
exit $rc
