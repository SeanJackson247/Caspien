#!/bin/bash
# Hand-made stack-form LOB cases for RangeCheckFusionPass (the constant-bounds `match i in arr` test: the 14-line
# PUSH i / PUSH K0 / PUSH K1 / POP r 16 / POP l 8 ... CMP / JMP shape becomes direct compares). Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/rangecheck_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv5.java <<'EOT'
package caspien.lowerorder;
import java.util.*;
public class Drv5 {
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
        for (List<BytecodeToken> l : new RangeCheckFusionPass().run(parse(in))) {
            StringBuilder b = new StringBuilder();
            for (BytecodeToken t : l) { if (b.length() > 0) b.append(' '); b.append(t.text); }
            out.add(b.toString());
        }
        return out;
    }
    static int bad = 0;
    static void chk(String name, boolean ok, List<String> o) {
        if (ok) System.out.println("PASS " + name); else { bad++; System.out.println("FAIL " + name + "   " + o); }
    }
    static String J(String... p) { return String.join(" ; ", p); }
    // the 14-line shape: index slot i, bounds k0 k1, temps r (range) and l (copy)
    static String[] shape(String i, String k0, String k1, String r, String r8, String l, String ge, String lt) {
        return new String[]{"PUSH 8 " + i, "PUSH 8 " + k0, "PUSH 8 " + k1, "POP " + r + " 16", "POP " + l + " 8",
                "PUSH 8 " + l, "PUSH 8 " + r, ge + " 8", "PUSH 8 " + l, "PUSH 8 " + r8, lt + " 8", "AND 1", "CMP", "JMP @L"};
    }
    static String[] std() { return shape("$-128", "0", "5", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"); }
    static String J2(String[]... parts) { List<String> l = new ArrayList<>(); for (String[] p : parts) l.addAll(Arrays.asList(p)); return String.join(" ; ", l); }
    static String[] A(String... s) { return s; }
    public static void main(String[] a) {
        // 1. zero lower bound: only the upper compare is left
        {
            List<String> o = run(J2(A("FUNC_START f"), std(), A("FUNC_END")));
            chk("zero start: reduced to the upper compare", o.equals(Arrays.asList("FUNC_START f", "PUSH 8 $-128", "PUSH 8 5", "LT_INT 8", "CMP", "JMP @L", "FUNC_END")), o);
        }
        // 2. non-zero lower bound: both compares, bounds as literals, AND kept
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "2", "9", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("non-zero start: both compares on the literals", o.equals(Arrays.asList("FUNC_START f", "PUSH 8 $-128", "PUSH 8 2", "GT_EQ_INT 8", "PUSH 8 $-128", "PUSH 8 9", "LT_INT 8", "AND 1", "CMP", "JMP @L", "FUNC_END")), o);
        }
        // 3. a temp slot read again later in the same function: unchanged
        {
            String in = J2(A("FUNC_START f"), std(), A("PUSH 8 $-144", "FUNC_END"));
            List<String> o = run(in);
            chk("temp slot mentioned later in the function: unchanged", o.contains("POP $-144 16") && o.size() == 17, o);
        }
        // 4. ... or earlier
        {
            List<String> o = run(J2(A("FUNC_START f", "PUSH 8 $-152"), std(), A("FUNC_END")));
            chk("temp slot mentioned earlier in the function: unchanged", o.contains("POP $-152 8"), o);
        }
        // 4b. the upper half of the range slot
        {
            List<String> o = run(J2(A("FUNC_START f", "R_MOV 8 $-136 #1"), std(), A("FUNC_END")));
            chk("range slot's upper word mentioned elsewhere: unchanged", o.contains("POP $-144 16"), o);
        }
        // 5. the same offsets used by ANOTHER function do not block it
        {
            List<String> o = run(J2(A("FUNC_START f"), std(), A("FUNC_END", "FUNC_START g", "PUSH 8 $-144", "PUSH 8 $-152", "PUSH 8 $-136", "FUNC_END")));
            chk("same offsets in another function: still rewritten", !o.contains("POP $-144 16"), o);
        }
        // 6. signed compares are never touched
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "0", "5", "$-144", "$-136", "$-152", "SGT_EQ_INT", "SLT_INT"), A("FUNC_END")));
            chk("signed compares: unchanged", o.contains("POP $-144 16"), o);
        }
        // 7. a bound that does not fit 31 bits
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "0", "2147483648", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("bound 2^31: unchanged", o.contains("POP $-144 16"), o);
            o = run(J2(A("FUNC_START f"), shape("$-128", "0", "2147483647", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("bound 2^31-1: rewritten", !o.contains("POP $-144 16"), o);
        }
        // 8. a bound that is not a literal
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "0", "$-8", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("variable bound: unchanged", o.contains("POP $-144 16"), o);
        }
        // 9. the upper compare reads a slot that is not range+8
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "0", "5", "$-144", "$-128", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("upper compare on another slot: unchanged", o.contains("POP $-144 16"), o);
        }
        // 10. the compares use the original index slot instead of the copy
        {
            String[] s = std(); s[5] = "PUSH 8 $-128";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("lower compare on a slot that is not the copy: unchanged", o.contains("POP $-144 16"), o);
        }
        // 11. the index is not a frame slot
        {
            String[] s = std(); s[0] = "PUSH 8 7";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("literal index: unchanged", o.contains("POP $-144 16"), o);
        }
        // 12. the range is popped as 8 bytes, not 16
        {
            String[] s = std(); s[3] = "POP $-144 8";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("8-byte range pop: unchanged", o.contains("POP $-144 8"), o);
        }
        // 13. a 4-byte index
        {
            String[] s = std(); s[0] = "PUSH 4 $-128";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("4-byte index push: unchanged", o.contains("POP $-144 16"), o);
        }
        // 14. no AND / no CMP at the end
        {
            String[] s = std(); s[11] = "OR 1";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("OR instead of AND: unchanged", o.contains("POP $-144 16"), o);
        }
        // 15. two checks one after the other, each with its own temps, both rewritten; the stores of one do not block the other
        {
            List<String> o = run(J2(A("FUNC_START f"), std(), shape("$-128", "0", "5", "$-168", "$-160", "$-176", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            int pops = 0; for (String s : o) if (s.startsWith("POP ")) pops++;
            chk("two consecutive checks both rewritten", pops == 0 && o.size() == 12, o);
        }
        // 16. the same temps reused by a second check: neither can be rewritten (each mentions the other's slots)
        {
            List<String> o = run(J2(A("FUNC_START f"), std(), std(), A("FUNC_END")));
            chk("the same temp slots shared by two checks: unchanged", o.contains("POP $-144 16"), o);
        }
        // 17. no function markers at all: whole text is the scope
        {
            List<String> o = run(J2(std(), A("PUSH 8 $-152")));
            chk("no FUNC_START: a later mention still blocks", o.contains("POP $-144 16"), o);
        }
        // 18. truncated text (shape cut off)
        {
            String[] s = std(); String[] cut = Arrays.copyOf(s, 10);
            List<String> o = run(J2(A("FUNC_START f"), cut));
            chk("truncated shape: unchanged, no crash", o.contains("POP $-144 16"), o);
        }
        // 19. a lower bound of 1 is a real test (only 0 may be dropped)
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "1", "5", "$-144", "$-136", "$-152", "GT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("start 1 keeps the lower compare", o.contains("GT_EQ_INT 8") && o.contains("PUSH 8 1") && o.contains("AND 1"), o);
        }
        // 20. one signed compare and one unsigned compare: unchanged either way round
        {
            List<String> o = run(J2(A("FUNC_START f"), shape("$-128", "0", "5", "$-144", "$-136", "$-152", "SGT_EQ_INT", "LT_INT"), A("FUNC_END")));
            chk("signed lower + unsigned upper: unchanged", o.contains("POP $-144 16"), o);
            o = run(J2(A("FUNC_START f"), shape("$-128", "0", "5", "$-144", "$-136", "$-152", "GT_EQ_INT", "SLT_INT"), A("FUNC_END")));
            chk("unsigned lower + signed upper: unchanged", o.contains("POP $-144 16"), o);
        }
        // 21. the operands of the compares must be the copy and the range slots, each in its place
        {
            String[] s = std(); s[8] = "PUSH 8 $-128";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("upper compare on a slot that is not the copy: unchanged", o.contains("POP $-144 16"), o);
            s = std(); s[6] = "PUSH 8 $-136";
            o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("lower compare against the upper word: unchanged", o.contains("POP $-144 16"), o);
        }
        // 22. the shape must end in CMP / JMP (the result is used as a branch condition)
        {
            String[] s = std(); s[12] = "NOT 1";
            List<String> o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("no CMP after the AND: unchanged", o.contains("POP $-144 16"), o);
            s = std(); s[13] = "RET 8";
            o = run(J2(A("FUNC_START f"), s, A("FUNC_END")));
            chk("no JMP after the CMP: unchanged", o.contains("POP $-144 16"), o);
        }
        System.out.println(bad == 0 ? "ALL RANGE CHECK CASES PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOT
CP=${LOB_CP:-$ROOT/LowerOrderGenerator/out}
javac -cp $CP -d $T $T/caspien/lowerorder/Drv5.java 2>&1 | grep -v Picked
java -cp $CP:$T caspien.lowerorder.Drv5
rc=$?
rm -rf $T
exit $rc
