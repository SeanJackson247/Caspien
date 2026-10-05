#!/bin/bash
# Hand-made LOB cases for the rsi/rdi variable registers (%v6, %v7; variables-in-arg-registers) in RegVarPromotionPass. Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/arg_regs_passes_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/DrvA.java <<'EOF'
package caspien.lowerorder;
import java.util.*;
public class DrvA {
    static List<List<BytecodeToken>> parse(String s) {
        List<List<BytecodeToken>> r = new ArrayList<>();
        for (String ln : s.split(" ; ")) {
            List<BytecodeToken> l = new ArrayList<>();
            for (String t : ln.trim().split(" ")) l.add(new BytecodeToken(t, "x", 0, BytecodeToken.Kind.CODE));
            r.add(l);
        }
        return r;
    }
    static List<String> text(List<List<BytecodeToken>> ls) {
        List<String> out = new ArrayList<>();
        for (List<BytecodeToken> l : ls) {
            StringBuilder b = new StringBuilder();
            for (BytecodeToken t : l) { if (b.length() > 0) b.append(' '); b.append(t.text); }
            out.add(b.toString());
        }
        return out;
    }
    static List<String> run(String in) { return text(new RegVarPromotionPass(true, true, false, true).run(parse("FUNC_START f ; " + in + " ; FUNC_END"))); }
    static List<String> runOff(String in) { return text(new RegVarPromotionPass(true, true, false, false).run(parse("FUNC_START f ; " + in + " ; FUNC_END"))); }
    static List<String> rangeEnd(String in) { return text(new RangeEndHintPass().run(parse("FUNC_START f ; " + in + " ; FUNC_END"))); }
    /** the register index (0..5) the store tagged "#tag" went to, -1 = memory */
    static int reg(List<String> out, int tag) {
        for (String l : out) {
            String[] p = l.split(" ");
            if (p[p.length - 1].equals("#" + tag)) {
                for (String s : p) if (s.matches("%v[0-9]")) return s.charAt(2) - '0';
                return -1;
            }
        }
        return -2;
    }
    static int bad = 0;
    static void chk(String name, boolean ok, String detail) {
        if (ok) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "   " + detail); }
    }
    static String H(int slot, int w) { return "REGHINT " + slot + " 8 " + w; }
    static String join(String... p) { return String.join(" ; ", p); }
    static final String[] S = {"$-8", "$-16", "$-24", "$-32", "$-40", "$-48", "$-56", "$-64"};
    static String hints() { return join(H(-8, 80), H(-16, 70), H(-24, 60), H(-32, 50), H(-40, 40), H(-48, 30), H(-56, 20), H(-64, 10)); }
    static String defs(int from, int to) { List<String> p = new ArrayList<>(); for (int i = from; i < to; i++) p.add("R_MOV 8 " + S[i] + " #" + (101 + i)); return String.join(" ; ", p); }
    static String uses(int from, int to) { List<String> p = new ArrayList<>(); for (int i = from; i < to; i++) p.add("R_MOV 8 %t0 " + S[i]); return String.join(" ; ", p); }
    static boolean distinct(int... r) { Set<Integer> s = new HashSet<>(); for (int x : r) s.add(x); return s.size() == r.length; }
    static boolean allIn(int lo, int hi, int... r) { for (int x : r) if (x < lo || x > hi) return false; return true; }
    static boolean has(List<String> o, String line) { return o.contains(line); }

    public static void main(String[] a) {
        // A1: eight variables live at once, no call: with the switch on all eight get distinct registers (two of them rsi/rdi = %v6/%v7)
        {
            List<String> o = run(hints() + " ; " + defs(0, 8) + " ; " + uses(0, 8));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105), reg(o, 106), reg(o, 107), reg(o, 108)};
            chk("switch on, call-free: eight live variables, eight distinct registers", distinct(r) && allIn(0, 7, r), o.toString());
            int hi = 0; for (int x : r) if (x >= 6) hi++;
            chk("switch on, call-free: two of them sit in %v6/%v7", hi == 2, o.toString());
        }
        // A2: the switch off: only six registers, the lightest two stay in memory
        {
            List<String> o = runOff(hints() + " ; " + defs(0, 8) + " ; " + uses(0, 8));
            int inReg = 0, inMem = 0;
            for (int i = 101; i <= 108; i++) { if (reg(o, i) >= 0) inReg++; else inMem++; }
            boolean none67 = true; for (int i = 101; i <= 108; i++) if (reg(o, i) >= 6) none67 = false;
            chk("switch off: six in registers, two in memory, none in %v6/%v7", inReg == 6 && inMem == 2 && none67, o.toString());
        }
        // A3: a variable that is live across a call never gets rsi/rdi (nor r8-r10): only callee-saved registers
        {
            List<String> o = run(hints() + " ; " + defs(0, 8) + " ; CALL g ; " + uses(0, 8));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103)};
            chk("live across a call: three callee-saved registers", distinct(r) && allIn(0, 2, r), o.toString());
            boolean mem = true; for (int i = 104; i <= 108; i++) if (reg(o, i) != -1) mem = false;
            chk("live across a call: the other five stay in memory", mem, o.toString());
        }
        // A4: a variable mentioned inside a call's argument bracket never goes to rsi/rdi (they are being filled with the arguments)
        {
            String inBracket = "CC_START sysv_x64 ; R_MOV 8 %t0 $-64 ; R_ARG 0 8 %t0 ; CALL g ; CC_END sysv_x64";
            List<String> o = run(hints() + " ; " + defs(0, 8) + " ; " + uses(0, 7) + " ; " + inBracket);
            int r = reg(o, 108);
            chk("used only inside an argument bracket: not in %v6/%v7", r < 6, o.toString());
        }
        // A5: dead before a call, then a call: rsi/rdi are fine for it (the range ended)
        {
            List<String> o = run(hints() + " ; " + defs(0, 8) + " ; " + uses(0, 8) + " ; CALL g");
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105), reg(o, 106), reg(o, 107), reg(o, 108)};
            chk("all dead before the call: eight registers", distinct(r) && allIn(0, 7, r), o.toString());
        }
        // A6: a non-whitelisted line (here CC_START with a CALL) while two variables are live: they are not given rsi/rdi
        {
            List<String> o = run(hints() + " ; " + defs(0, 8) + " ; " + uses(0, 6) + " ; CALL g ; " + uses(6, 8));
            chk("live across a call at the end: %v6/%v7 not used", reg(o, 107) < 6 && reg(o, 108) < 6, o.toString());
        }
        System.out.println(bad == 0 ? "ALL ARG REGS PASS CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
CP=${LOB_CP:-$ROOT/LowerOrderGenerator/out}
javac -cp $CP -d $T $T/caspien/lowerorder/DrvA.java 2>&1 | grep -v "^Picked"
java -cp $T:$CP caspien.lowerorder.DrvA 2>&1 | grep -v "^Picked"
RC=${PIPESTATUS[0]}
rm -rf $T
exit $RC
