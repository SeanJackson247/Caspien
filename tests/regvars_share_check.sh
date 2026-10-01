#!/bin/bash
# Hand-made register-form (LOB) cases for the register SHARING in RegVarPromotionPass (liveness colouring, used when a function has more
# hot variables than registers). Needs LowerOrderGenerator/out built. Run from the project root:  bash tests/regvars_share_check.sh
# Each variable has one marker store "... #<tag>"; the check reads which register that store was renamed to (or "mem" if the slot stayed).
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv2.java <<'EOF'
package caspien.lowerorder;
import java.util.*;
public class Drv2 {
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
        for (List<BytecodeToken> l : new RegVarPromotionPass(true, true).run(parse("FUNC_START f ; " + in + " ; FUNC_END"))) {
            StringBuilder b = new StringBuilder();
            for (BytecodeToken t : l) { if (b.length() > 0) b.append(' '); b.append(t.text); }
            out.add(b.toString());
        }
        return out;
    }
    /** register the marker store "#tag" ended up in: "%v1", "%x3", or "mem" */
    static String reg(List<String> out, int tag) {
        for (String l : out) {
            if (l.startsWith("R_XVAR")) continue;
            String[] p = l.split(" ");
            if (p[p.length - 1].equals("#" + tag)) {
                for (String s : p) if (s.matches("%[vx][0-9]")) return s;
                return "mem";
            }
        }
        return "missing";
    }
    static int bad = 0;
    static void chk(String name, boolean ok, String detail) {
        if (ok) System.out.println("PASS " + name);
        else { bad++; System.out.println("FAIL " + name + "   " + detail); }
    }
    static String H(int slot, int w, String f) { return "REGHINT " + slot + " " + (f.equals("") ? 8 : (f.equals("f4") ? 4 : 8)) + " " + w + (f.equals("") ? "" : " f"); }
    static String join(String... p) { return String.join(" ; ", p); }
    public static void main(String[] a) {
        // five integer variables A..E (slots -8..-40, weights 50..10, tags 101..105); the limit is 3 (no %t3 in the text)
        String hints = join(H(-8, 50, ""), H(-16, 40, ""), H(-24, 30, ""), H(-32, 20, ""), H(-40, 10, ""));
        String[] S = {"$-8", "$-16", "$-24", "$-32", "$-40"};
        // 1. disjoint: every variable is defined and read before the next one starts -> all five get a register
        {
            List<String> p = new ArrayList<>(); p.add(hints);
            for (int i = 0; i < 5; i++) { p.add("R_MOV 8 " + S[i] + " #" + (101 + i)); p.add("R_MOV 8 %t0 " + S[i]); }
            List<String> o = run(String.join(" ; ", p));
            boolean all = true; for (int i = 0; i < 5; i++) all &= reg(o, 101 + i).startsWith("%v");
            chk("disjoint lifetimes: all five variables get a register", all, o.toString());
            chk("disjoint lifetimes: A and B share (heaviest first, lowest register)", reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 2. all five live at once: only the three heaviest win, D and E stay in memory
        {
            List<String> p = new ArrayList<>(); p.add(hints);
            for (int i = 0; i < 5; i++) p.add("R_MOV 8 " + S[i] + " #" + (101 + i));
            p.add("CALL g");   // every variable is live across a call: none may use the caller-saved r8/r9/r10 (see regvars_volatile_check.sh)
            for (int i = 0; i < 5; i++) p.add("R_MOV 8 %t0 " + S[i]);
            List<String> o = run(String.join(" ; ", p));
            Set<String> regs = new HashSet<>(List.of(reg(o, 101), reg(o, 102), reg(o, 103)));
            chk("all live at once: A, B, C in three different registers", regs.size() == 3 && !regs.contains("mem"), o.toString());
            chk("all live at once: D and E stay in memory", reg(o, 104).equals("mem") && reg(o, 105).equals("mem"), o.toString());
        }
        // 3. loop: A is read in the loop (live around the back edge), B is defined inside it -> different registers
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            String loop = join("R_MOV 8 " + S[0] + " #101", "@L:", "R_MOV 8 " + S[1] + " #102", "R_MOV 8 %t0 " + S[1], "R_MOV 8 %t0 " + S[0], "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints + " ; " + loop + " ; " + rest);
            chk("loop: a variable read around the back edge does not share with one defined in the loop", !reg(o, 101).equals(reg(o, 102)) && reg(o, 101).startsWith("%v") && reg(o, 102).startsWith("%v"), o.toString());
            chk("loop: later disjoint variables still get registers", reg(o, 103).startsWith("%v") && reg(o, 104).startsWith("%v") && reg(o, 105).startsWith("%v"), o.toString());
        }
        // 4. two variables read before any write are both live at entry -> different registers
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            List<String> o = run(hints + " ; R_MOV 8 %t0 " + S[0] + " ; R_MOV 8 %t0 " + S[1] + " ; R_MOV 8 " + S[0] + " #101 ; R_MOV 8 " + S[1] + " #102 ; " + rest);
            chk("live at entry: two variables read before any write do not share", !reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 5. a write that is never read still clobbers: B's dead store while A is live must not use A's register
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            List<String> o = run(hints + " ; R_MOV 8 " + S[0] + " #101 ; R_MOV 8 " + S[1] + " #102 ; R_MOV 8 %t0 " + S[0] + " ; " + rest);
            chk("dead write: a store whose value is never read does not share with a live variable", !reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 6. a call can unwind into a landing pad / catch label that is only referenced by a PUSH_LABEL: A, read only there, is live across the call
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            String body = join("R_MOV 8 " + S[0] + " #101", "R_MOV 8 " + S[1] + " #102", "R_MOV 8 %t0 " + S[1], "PUSH_LABEL @catch_1 code_addr", "CALL g", "RET", "@catch_1:", "R_MOV 8 %t0 " + S[0], "RET");
            List<String> o = run(hints + " ; " + body + " ; " + rest);
            chk("unwind edge: a variable read only in a catch label does not share with one defined before the call", !reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 8. stack-form CMP + JMP is a conditional jump: the fall-through path reads A, so A is live across B's definition
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            String body = join("R_MOV 8 " + S[0] + " #101", "R_MOV 8 " + S[1] + " #102", "R_MOV 8 %t0 " + S[1], "CMP", "JMP @L", "R_MOV 8 %t0 " + S[0], "@L:");
            List<String> o = run(hints + " ; " + body + " ; " + rest);
            chk("stack-form CMP + JMP keeps its fall-through", !reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 9. the back edge matters: A is read at the top of the loop, B is defined later in the same pass; nothing else reads A
        {
            String rest = join("R_MOV 8 " + S[2] + " #103", "R_MOV 8 %t0 " + S[2], "R_MOV 8 " + S[3] + " #104", "R_MOV 8 %t0 " + S[3], "R_MOV 8 " + S[4] + " #105", "R_MOV 8 %t0 " + S[4]);
            String loopBrc = join("R_MOV 8 " + S[0] + " #101", "@L:", "R_MOV 8 %t0 " + S[0], "R_MOV 8 " + S[1] + " #102", "R_MOV 8 %t0 " + S[1], "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints + " ; " + loopBrc + " ; " + rest);
            chk("back edge by R_BRC: a variable read at the loop top is live around it", !reg(o, 101).equals(reg(o, 102)), o.toString());
            String loopJmp = join("R_MOV 8 " + S[0] + " #101", "@L:", "R_MOV 8 %t0 " + S[0], "R_MOV 8 " + S[1] + " #102", "R_MOV 8 %t0 " + S[1], "R_BRC EQ 8 %t1 #0 @E", "JMP @L", "@E:");
            o = run(hints + " ; " + loopJmp + " ; " + rest);
            chk("back edge by JMP: a variable read at the loop top is live around it", !reg(o, 101).equals(reg(o, 102)), o.toString());
        }
        // 7. floats: five f32 fillers live the whole function take x0..x4; in the middle A(f32) B(f64) C(f32) D(f64) follow each other
        //    only x5 is left: A takes it, C shares it (same width, disjoint), B and D (f64) cannot share an f32 register and stay in memory
        {
            List<String> p = new ArrayList<>();
            for (int i = 0; i < 5; i++) p.add(H(-8 * (i + 1), 100 - i, "f4"));
            p.add(H(-48, 50, "f4")); p.add(H(-56, 40, "f8")); p.add(H(-64, 30, "f4")); p.add(H(-72, 20, "f8"));
            for (int i = 0; i < 5; i++) p.add("R_ST 4 $" + (-8 * (i + 1)) + " #" + (201 + i));
            p.add("R_ST 4 $-48 #206"); p.add("R_LD 4 %t0 $-48");
            p.add("R_MOV 8 $-56 #207"); p.add("R_MOV 8 %t0 $-56");
            p.add("R_ST 4 $-64 #208"); p.add("R_LD 4 %t0 $-64");
            p.add("R_MOV 8 $-72 #209"); p.add("R_MOV 8 %t0 $-72");
            for (int i = 0; i < 5; i++) p.add("R_LD 4 %t0 $" + (-8 * (i + 1)));
            List<String> o = run(String.join(" ; ", p));
            Set<String> f = new HashSet<>(); for (int i = 0; i < 5; i++) f.add(reg(o, 201 + i));
            chk("floats: five simultaneously live f32 variables take five different registers", f.size() == 5 && !f.contains("mem"), o.toString());
            chk("floats: A takes the last register, C (same width, disjoint) shares it", reg(o, 206).startsWith("%x") && reg(o, 206).equals(reg(o, 208)) && !f.contains(reg(o, 206)), o.toString());
            chk("floats: an f64 never shares an f32 register (B and D stay in memory)", reg(o, 207).equals("mem") && reg(o, 209).equals("mem"), o.toString());
            int decl = 0; for (String l : o) if (l.startsWith("R_XVAR " + reg(o, 206) + " ")) decl++;
            chk("floats: one R_XVAR declaration per register even when two variables share it", decl == 1, o.toString());
        }
        // 10. a float assigned by a stack-form "ADDR x ... ASSIGN" pair is written where the value lands (the ASSIGN), not at the ADDR:
        //     the old value of H is read inside the assignment, so H is live across A's whole life and A cannot share H's register
        {
            List<String> p = new ArrayList<>();
            for (int i = 0; i < 5; i++) p.add(H(-8 * (i + 1), 100 - i, "f4"));
            p.add(H(-48, 50, "f4")); p.add(H(-56, 40, "f4"));
            for (int i = 0; i < 5; i++) p.add("R_ST 4 $" + (-8 * (i + 1)) + " #" + (301 + i));
            p.add("R_ST 4 $-48 #306");
            p.add("R_ST 4 $-56 #307"); p.add("R_LD 4 %t0 $-56");
            p.add("ADDR 4 $-48"); p.add("R_LD 4 %t0 $-48"); p.add("R_FBIN ADD 4 %t0 %t0 #1065353216"); p.add("ASSIGN 4 4 4");
            p.add("R_LD 4 %t0 $-48");
            for (int i = 0; i < 5; i++) p.add("R_LD 4 %t0 $" + (-8 * (i + 1)));
            List<String> o = run(String.join(" ; ", p));
            boolean popx = false; for (String l : o) if (l.startsWith("R_POPX 4 ")) popx = true;
            chk("pair assignment: recognised (R_POPX emitted)", popx, o.toString());
            chk("pair assignment: A does not share the register of H, which is read inside the assignment", !reg(o, 307).equals(reg(o, 306)), o.toString());
        }
        System.out.println(bad == 0 ? "ALL REGVAR SHARING CHECKS PASSED" : (bad + " FAILED"));
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
javac -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out} -d $T $T/caspien/lowerorder/Drv2.java 2>&1 | grep -v Picked
java -cp ${LOB_CP:-$ROOT/LowerOrderGenerator/out}:$T caspien.lowerorder.Drv2
rc=$?
rm -rf $T
exit $rc
