#!/bin/bash
# Hand-made stack-form LOB cases for RegisterFormPass: safe dynarray access in register form -- LEN, LOOKUP_DYN (value),
# LOOKUP_DYN_LHS (address), ZEXT and TRUNC, and the optional displacement operand of R_LEA. Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/regform_dyn_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv9.java <<'EOT'
package caspien.lowerorder;
import java.util.*;
public class Drv9 {
    static List<List<BytecodeToken>> parse(String s) {
        List<List<BytecodeToken>> r = new ArrayList<>();
        for (String ln : s.split(" ; ")) {
            List<BytecodeToken> l = new ArrayList<>();
            for (String t : ln.trim().split(" ")) l.add(new BytecodeToken(t, "x", 0, BytecodeToken.Kind.CODE));
            r.add(l);
        }
        return r;
    }
    static List<String> run(String in) {
        List<String> out = new ArrayList<>();
        for (List<BytecodeToken> l : new RegisterFormPass().run(parse(in)).lines) {
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
    static boolean noStack(List<String> o) {
        for (String l : o) {
            String m = l.split(" ")[0];
            if (m.equals("LEN") || m.startsWith("LOOKUP_DYN") || m.equals("ZEXT") || m.equals("TRUNC") || m.equals("PUSH") || m.equals("ASSIGN")) return false;
        }
        return true;
    }
    public static void main(String[] a) {
        // ---- LEN ----
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "LEN", "ASSIGN 8 8 8"));
            chk("LEN of a frame pointer: load the pointer, then the length", has(o, "R_MOV 8 %t0 $-24") && has(o, "R_LD 8 %t0 %t0"), o);
            chk("LEN: no stack form left", noStack(o), o);
        }
        {
            List<String> o = run(J("PUSH 8 $-16", "PUSH 8 $-24", "LEN", "LT_INT 8", "CMP", "JMP @skip"));
            chk("bounds test: index < length is one compare of a slot and a temp", hasPrefix(o, "R_BRF") || hasPrefix(o, "R_BRC") || hasPrefix(o, "R_BIN LT 8 %t"), o);
            chk("bounds test: LEN is a load", has(o, "R_LD 8 %t0 %t0"), o);
        }
        // ---- LOOKUP_DYN ----
        for (int n : new int[]{1, 2, 4, 8}) {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN " + n, "ASSIGN 8 8 8"));
            chk("LOOKUP_DYN " + n + ": address = ptr + 16 + idx*" + n, has(o, "R_LEA %t0 %t0 $-16 " + n + " 16"), o);
            chk("LOOKUP_DYN " + n + ": load of " + n + " bytes through the address", has(o, "R_LD " + n + " %t0 %t0"), o);
            chk("LOOKUP_DYN " + n + ": no stack form left", noStack(o), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 5", "LOOKUP_DYN 8", "ASSIGN 8 8 8"));
            chk("constant index: an immediate operand", has(o, "R_LEA %t0 %t0 #5 8 16"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 3", "ASSIGN 8 8 8"));
            chk("LOOKUP_DYN of a 3-byte element stays in stack form", has(o, "LOOKUP_DYN 3") && !hasPrefix(o, "R_LEA"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "LOOKUP_ARRAY 4", "ASSIGN 8 8 8"));
            chk("LOOKUP_DYN followed by LOOKUP_ARRAY stays in stack form (the backend tags the pushed element)", has(o, "LOOKUP_DYN 8") && has(o, "LOOKUP_ARRAY 4"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "DOT 4 4", "ASSIGN 8 8 8"));
            chk("LOOKUP_DYN followed by DOT stays in stack form", has(o, "LOOKUP_DYN 8") && has(o, "DOT 4 4"), o);
        }
        {
            // the index is a computed temp, the pointer a frame slot
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "PUSH 8 1", "ADD_INT 8", "LOOKUP_DYN 2", "ASSIGN 8 8 8"));
            chk("computed index: the lea reads the temp", hasPrefix(o, "R_BIN ADD 8 %t") && hasPrefix(o, "R_LEA %t") && has(o, "R_LD 2 %t0 %t0") || hasPrefix(o, "R_LD 2 %t"), o);
            chk("computed index: no stack form left", noStack(o), o);
        }
        {
            // a narrow index is loaded first
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 4 $-16", "LOOKUP_DYN 1", "ASSIGN 8 8 8"));
            chk("narrow index slot: loaded at its own width", hasPrefix(o, "R_LD 4 %t"), o);
        }
        // ---- LOOKUP_DYN_LHS ----
        {
            List<String> o = run(J("PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN_LHS 1", "PUSH 1 0", "ASSIGN 1 1 1"));
            chk("LOOKUP_DYN_LHS: address with displacement 16", has(o, "R_LEA %t0 %t0 $-16 1 16"), o);
            chk("LOOKUP_DYN_LHS: the store goes through that address", has(o, "R_ST 1 %t0 #0"), o);
            chk("LOOKUP_DYN_LHS: no stack form left", noStack(o), o);
        }
        {
            List<String> o = run(J("PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN_LHS 3", "PUSH 1 0", "ASSIGN 1 1 1"));
            chk("LOOKUP_DYN_LHS of a 3-byte element: any scale is fine (imul)", has(o, "R_LEA %t0 %t0 $-16 3 16"), o);
        }
        // ---- ZEXT ----
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 1", "ZEXT 1 8", "ASSIGN 8 8 8"));
            chk("ZEXT of a loaded byte: nothing emitted, the store is 8 bytes", !has(o, "ZEXT 1 8") && hasPrefix(o, "R_MOV 8 $-8 %t") , o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 1 $-24", "ZEXT 1 8", "ASSIGN 8 8 8"));
            chk("ZEXT of a narrow frame value: a 1-byte load, no ZEXT", has(o, "R_LD 1 %t0 $-24") && !has(o, "ZEXT 1 8"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "ZEXT 1 8", "ASSIGN 8 8 8"));
            chk("ZEXT 1 of an 8-byte frame value reads only the low byte", has(o, "R_LD 1 %t0 $-24"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "ZEXT 8 8", "ASSIGN 8 8 8"));
            chk("ZEXT 8 8 is nothing", !has(o, "ZEXT 8 8") && has(o, "R_MOV 8 %t0 $-24"), o);
        }
        {
            // a narrow ADD keeps carry bits above its width: the register must be MASKED, not just relabelled
            List<String> o = run(J("ADDR 8 $-8", "PUSH 1 $-24", "PUSH 1 $-32", "ADD_INT 1", "ZEXT 1 8", "ASSIGN 8 8 8"));
            chk("ZEXT of a computed byte: masked with #255", has(o, "R_BIN BAND 8 %t0 %t0 #255") && !has(o, "ZEXT 1 8"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 2 $-24", "PUSH 2 $-32", "ADD_INT 2", "ZEXT 2 8", "ASSIGN 8 8 8"));
            chk("ZEXT of a computed 16-bit value: masked with #65535", has(o, "R_BIN BAND 8 %t0 %t0 #65535"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 4 $-24", "PUSH 8 7", "ADD_INT 8", "ZEXT 4 8", "ASSIGN 8 8 8"));
            chk("ZEXT 4 of a computed value is left to the stack form (the mask is not an immediate)", has(o, "ZEXT 4 8"), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 4", "ZEXT 4 8", "ASSIGN 8 8 8"));
            chk("ZEXT 4 of a loaded 4-byte element: nothing emitted", !has(o, "ZEXT 4 8") && !hasPrefix(o, "R_BIN BAND"), o);
        }
        {
            // a loaded byte widened by ZEXT 1 8 and then used in an 8-byte add must not be masked again or lose its width
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 1", "ZEXT 1 8", "PUSH 8 $-32", "ADD_INT 8", "ASSIGN 8 8 8"));
            chk("ZEXT of an element followed by an 8-byte add", hasPrefix(o, "R_BIN ADD 8 %t") && noStack(o), o);
        }
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 1", "ADD_INT 8", "ZEXT 4 4", "ASSIGN 8 8 8"));
            chk("ZEXT 4 4 still masks to 32 bits (only ZEXT 8 8 is nothing)", has(o, "ZEXT 4 4"), o);
        }
        {
            // an index that is an ADDRESS (the address of a slot) is not a value operand: the pass must decline, not crash
            List<String> o = run(J("PUSH 8 $-24", "ADDR 8 $-16", "LOOKUP_DYN_LHS 1", "PUSH 1 0", "ASSIGN 1 1 1"));
            chk("an address as the index: left in stack form", has(o, "LOOKUP_DYN_LHS 1"), o);
        }
        // ---- TRUNC ----
        {
            List<String> o = run(J("ADDR 1 $-8", "PUSH 8 $-24", "TRUNC 8 1", "ASSIGN 1 1 1"));
            chk("TRUNC of a frame value: a 1-byte load", has(o, "R_LD 1 %t0 $-24") && !has(o, "TRUNC 8 1"), o);
        }
        {
            List<String> o = run(J("ADDR 1 $-8", "PUSH 8 $-24", "PUSH 8 1", "ADD_INT 8", "TRUNC 8 1", "ASSIGN 1 1 1"));
            chk("TRUNC of a computed value: masked with #255", has(o, "R_BIN BAND 8 %t0 %t0 #255") && !has(o, "TRUNC 8 1"), o);
        }
        {
            List<String> o = run(J("ADDR 2 $-8", "PUSH 8 $-24", "PUSH 8 1", "ADD_INT 8", "TRUNC 8 2", "ASSIGN 2 2 2"));
            chk("TRUNC 2 of a computed value: masked with #65535", has(o, "R_BIN BAND 8 %t0 %t0 #65535"), o);
        }
        {
            List<String> o = run(J("ADDR 4 $-8", "PUSH 8 $-24", "PUSH 8 1", "ADD_INT 8", "TRUNC 8 4", "ASSIGN 4 4 4"));
            chk("TRUNC 4 of a computed value is left to the stack form", has(o, "TRUNC 8 4"), o);
        }
        {
            List<String> o = run(J("ADDR 4 $-8", "PUSH 8 $-24", "TRUNC 8 4", "ASSIGN 4 4 4"));
            chk("TRUNC 4 of a frame value: a 4-byte load", has(o, "R_LD 4 %t0 $-24"), o);
        }
        {
            List<String> o = run(J("ADDR 1 $-8", "PUSH 4 $-24", "TRUNC 8 1", "ASSIGN 1 1 1"));
            chk("TRUNC from a 4-byte frame value: the load is 1 byte (same address, little endian)", has(o, "R_LD 1 %t0 $-24"), o);
        }
        {
            List<String> o = run(J("ADDR 4 $-8", "PUSH 1 $-24", "TRUNC 8 4", "ASSIGN 4 4 4"));
            chk("TRUNC to a width above the value's own: left alone", !hasPrefix(o, "R_LD 4") , o);
        }
        // ---- a temp-pressure case: four live temps and a lookup ----
        {
            List<String> o = run(J("ADDR 8 $-8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "ADD_INT 8",
                    "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "ADD_INT 8", "PUSH 8 $-24", "PUSH 8 $-16", "LOOKUP_DYN 8", "ADD_INT 8", "ASSIGN 8 8 8"));
            int lds = 0; for (String l : o) if (l.startsWith("R_LD 8")) lds++;
            int stackLk = 0; for (String l : o) if (l.startsWith("LOOKUP_DYN")) stackLk++;
            chk("four element reads in one expression: every read happens exactly once", lds + stackLk == 4, o);
        }
        System.out.println(bad == 0 ? "ALL REGFORM DYN CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOT
javac -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out} -d $T $T/caspien/lowerorder/Drv9.java 2>&1 | grep -v Picked
java -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out}:$T caspien.lowerorder.Drv9
rc=$?
rm -rf $T
exit $rc
