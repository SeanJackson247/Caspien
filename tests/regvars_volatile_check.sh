#!/bin/bash
# Hand-made LOB cases for (1) the caller-saved variable registers r8/r9/r10 (%v3..%v5) in RegVarPromotionPass and (2) RangeEndHintPass
# (the end of a `for` range split off its 16-byte range so it can live in a register). Needs LowerOrderGenerator/out built.
# Run from the project root:  bash tests/regvars_volatile_check.sh        (env LOB_CP = run against a mutant classpath)
export JAVA_TOOL_OPTIONS=
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d)
mkdir -p $T/caspien/lowerorder
cat > $T/caspien/lowerorder/Drv8.java <<'EOF'
package caspien.lowerorder;
import java.util.*;
public class Drv8 {
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
    static List<String> run(String in) { return text(new RegVarPromotionPass(true, true).run(parse("FUNC_START f ; " + in + " ; FUNC_END"))); }
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
    static final String[] S = {"$-8", "$-16", "$-24", "$-32", "$-40"};
    static String hints() { return join(H(-8, 50), H(-16, 40), H(-24, 30), H(-32, 20), H(-40, 10)); }
    static String defs(int from, int to) { List<String> p = new ArrayList<>(); for (int i = from; i < to; i++) p.add("R_MOV 8 " + S[i] + " #" + (101 + i)); return String.join(" ; ", p); }
    static String uses(int from, int to) { List<String> p = new ArrayList<>(); for (int i = from; i < to; i++) p.add("R_MOV 8 %t0 " + S[i]); return String.join(" ; ", p); }
    static boolean distinct(int... r) { Set<Integer> s = new HashSet<>(); for (int x : r) s.add(x); return s.size() == r.length; }
    static boolean allIn(int lo, int hi, int... r) { for (int x : r) if (x < lo || x > hi) return false; return true; }
    static boolean has(List<String> o, String line) { return o.contains(line); }

    public static void main(String[] a) {
        // ---------- r8/r9/r10 ----------
        // V1: five variables live at once, no call anywhere: all five get a register, the extra two in the caller-saved ones
        {
            List<String> o = run(hints() + " ; " + defs(0, 5) + " ; " + uses(0, 5));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)};
            chk("call-free: five live variables all get a register", distinct(r) && allIn(0, 5, r), o.toString());
            int vol = 0; for (int x : r) if (x >= 3) vol++;
            chk("call-free: the two extra ones sit in r8/r9/r10", vol >= 2, o.toString());
        }
        // V2: a CALL while all five are live: only the three callee-saved registers, D and E stay in memory
        {
            List<String> o = run(hints() + " ; " + defs(0, 5) + " ; CALL g ; " + uses(0, 5));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103)};
            chk("live across a call: three variables, all callee-saved", distinct(r) && allIn(0, 2, r), o.toString());
            chk("live across a call: D and E stay in memory", reg(o, 104) == -1 && reg(o, 105) == -1, o.toString());
        }
        // V3: A, B, C live across the call, D defined and dead before it: D takes a caller-saved register, the others callee-saved
        {
            List<String> o = run(hints() + " ; " + defs(0, 4) + " ; " + uses(3, 4) + " ; CALL g ; " + uses(0, 3));
            chk("dead before the call: D uses r8..r10", reg(o, 104) >= 3, o.toString());
            chk("live across the call: A, B, C stay callee-saved", allIn(0, 2, reg(o, 101), reg(o, 102), reg(o, 103)) && distinct(reg(o, 101), reg(o, 102), reg(o, 103)), o.toString());
        }
        // V4: the other call-like lines clobber too
        for (String call : new String[]{"EXTERN_CALL puts", "INVOKE", "SLEEP s", "CC_START sysv_x64", "GT_REGISTER"}) {
            List<String> o = run(hints() + " ; " + defs(0, 5) + " ; " + call + " ; " + uses(0, 5));
            chk("live across '" + call + "': nothing in r8..r10", allIn(-1, 2, reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)), o.toString());
        }
        // V5: a line that is not known to leave r8..r10 alone (unknown mnemonic) counts as a clobber
        {
            List<String> o = run(hints() + " ; " + defs(0, 5) + " ; SOMETHING_NEW 8 ; " + uses(0, 5));
            chk("live across an unknown mnemonic: nothing in r8..r10", allIn(-1, 2, reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)), o.toString());
        }
        // V6: a variable read BY a clobbering line (an argument load) is excluded even though it dies there
        {
            List<String> o = run(hints() + " ; " + defs(0, 5) + " ; R_ARG 0 8 " + S[4] + " ; " + uses(0, 4));
            chk("read by a call-argument line: not in r8..r10", reg(o, 105) <= 2, o.toString());
        }
        // V6b: the same, but with the callee-saved registers all taken by A, B, C (live across the call): D, read by the argument
        //      line and dead afterwards, must stay in memory (a caller-saved register is not allowed for it)
        {
            List<String> o = run(hints() + " ; " + defs(0, 4) + " ; R_ARG 0 8 " + S[3] + " ; CALL g ; " + uses(0, 3));
            chk("read by a call-argument line, callee-saved registers full: stays in memory", reg(o, 104) == -1, o.toString());
            chk("... and A, B, C keep the callee-saved registers", allIn(0, 2, reg(o, 101), reg(o, 102), reg(o, 103)) && distinct(reg(o, 101), reg(o, 102), reg(o, 103)), o.toString());
        }
        // V7: a loop with a call in its body: a variable live around the back edge is live across the call
        {
            String loop = join(defs(0, 5), "@L:", uses(0, 5), "CALL g", "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints() + " ; " + loop);
            chk("loop with a call: nothing live around the loop is in r8..r10", allIn(-1, 2, reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)), o.toString());
            String loop2 = join(defs(0, 5), "@L:", uses(0, 5), "R_BRC NEQ 8 %t1 #0 @L");
            o = run(hints() + " ; " + loop2);
            chk("loop without a call: all five variables get registers", distinct(reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)) && allIn(0, 5, reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)), o.toString());
        }
        // V8: an unwind edge: a variable read in a catch label is live across the call even if nothing reads it after the call
        {
            String body = join(defs(0, 4), "PUSH_LABEL @catch_1 code_addr", "CALL g", uses(1, 3), "RET", "@catch_1:", uses(0, 1), "RET");
            List<String> o = run(hints() + " ; " + body);
            chk("unwind edge: the variable read only in the catch label is not in r8..r10", reg(o, 101) <= 2 && reg(o, 101) >= 0, o.toString());
        }
        // V9: arguments 5 and 6 are read from r8/r9 at function entry: any ARGk with k >= 2 forbids r8/r9 (it is r10 only)
        {
            List<String> o = run(hints() + " ; PUSH 8 ARG4 ; " + defs(0, 5) + " ; " + uses(0, 5));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)};
            boolean noR8R9 = true; for (int x : r) if (x == 3 || x == 4) noR8R9 = false;
            chk("function reads ARG4: r8 and r9 are not used", noR8R9, o.toString());
            int n = 0; for (int x : r) if (x >= 0) n++;
            chk("function reads ARG4: r10 still is", n == 4, o.toString());
        }
        // V9b: the Windows ABI passes argument 3 (ARG2) in r8 and argument 4 (ARG3) in r9: the same ban, from index 2 up
        for (String ar : new String[]{"ARG2", "ARG3", "ARG5"}) {
            List<String> o = run(hints() + " ; PUSH 8 " + ar + " ; " + defs(0, 5) + " ; " + uses(0, 5));
            boolean noR8R9 = true; for (int x : new int[]{reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)}) if (x == 3 || x == 4) noR8R9 = false;
            chk("function reads " + ar + ": r8 and r9 are not used", noR8R9, o.toString());
        }
        {   // ARG0 and ARG1 are rdi/rsi (rcx/rdx): no restriction
            List<String> o = run(hints() + " ; PUSH 8 ARG1 ; " + defs(0, 5) + " ; " + uses(0, 5));
            chk("function reads only ARG1: r8, r9 and r10 all usable", distinct(reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)) && allIn(0, 5, reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)), o.toString());
        }
        // V10: a float call result goes through r10: with PUSH_RET_FLOAT or R_GETRETF in the function r10 is off limits
        for (String fl : new String[]{"PUSH_RET_FLOAT", "R_GETRETF 4 %x0"}) {
            List<String> o = run(hints() + " ; " + fl + " ; " + defs(0, 5) + " ; " + uses(0, 5));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)};
            boolean noR10 = true; for (int x : r) if (x == 5) noR10 = false;
            chk("function has " + fl.split(" ")[0] + ": r10 is not used", noR10, o.toString());
        }
        // V11: %t3 in use leaves two callee-saved registers; the caller-saved ones fill up the rest
        {
            List<String> o = run(hints() + " ; R_MOV 8 %t3 #0 ; " + defs(0, 5) + " ; " + uses(0, 5));
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103), reg(o, 104), reg(o, 105)};
            boolean no2 = true; for (int x : r) if (x == 2) no2 = false;
            chk("%t3 used: r12 is not a variable register", no2 && distinct(r) && allIn(0, 5, r), o.toString());
        }
        // V11b: %t3 used leaves two callee-saved registers: three variables live across a call get two registers, the third stays in memory
        {
            List<String> o = run(hints() + " ; R_MOV 8 %t3 #0 ; " + defs(0, 3) + " ; CALL g ; " + uses(0, 3));
            chk("%t3 used, live across a call: only r13 and r14", allIn(0, 1, reg(o, 101), reg(o, 102)) && distinct(reg(o, 101), reg(o, 102)) && reg(o, 103) == -1, o.toString());
        }
        // V12: with a call in the text but nothing live across it, a function with at most three hot variables is unchanged (top-N path)
        {
            String h3 = join(H(-8, 50), H(-16, 40), H(-24, 30));
            List<String> o = run(h3 + " ; " + defs(0, 3) + " ; CALL g ; " + uses(0, 3));
            chk("three variables: the plain top-N path, callee-saved only", allIn(0, 2, reg(o, 101), reg(o, 102), reg(o, 103)), o.toString());
        }

        // ---------- functions that allocate (backend scratch r12-r14): only r8..r10, only where no call-like line is crossed ----------
        // B1: a function with NEW and a hot call-free loop: the loop variables get r8/r9/r10, never r13/r14/r12
        for (String blk : new String[]{"NEW 16 m8 m8", "NEW_DYN 8 4", "RESIZE 8", "CLONE 16", "DOT 8 8 8", "LOOKUP_ARRAY 8 8"}) {
            String loop = join(defs(0, 3), "@L:", uses(0, 3), "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints() + " ; " + blk + " ; " + loop);
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103)};
            chk("blocked by '" + blk.split(" ")[0] + "', call-free loop: three variables in r8..r10", distinct(r) && allIn(3, 5, r), o.toString());
        }
        // B2: the variable live across the allocation stays in memory; the loop variables after it are promoted
        {
            String body = join(defs(0, 1), "NEW 16 m8 m8", uses(0, 1), defs(1, 3), "@L:", uses(1, 3), "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints() + " ; " + body);
            chk("blocked: variable live across the NEW stays in memory", reg(o, 101) == -1, o.toString());
            chk("blocked: variables defined after the NEW get r8..r10", allIn(3, 5, reg(o, 102), reg(o, 103)) && distinct(reg(o, 102), reg(o, 103)), o.toString());
        }
        // B3: a variable whose last read is before the allocation may use r8..r10 (dead across it); one read after it must not
        {
            List<String> o = run(hints() + " ; " + defs(0, 2) + " ; R_MOV 8 %t0 " + S[0] + " ; NEW 16 m8 m8 ; R_MOV 8 %t1 " + S[1]);
            chk("blocked: dead before the NEW -> caller-saved register", reg(o, 101) >= 3, o.toString());
            chk("blocked: live across the NEW -> memory", reg(o, 102) == -1, o.toString());
        }
        // B3b: a variable read BY the allocating line itself is excluded
        {
            List<String> o = run(hints() + " ; " + defs(0, 1) + " ; NEW 16 m8 m8 " + S[0] + " ; R_MOV 8 %t0 #0");
            chk("blocked: named by the NEW line -> memory", reg(o, 101) == -1, o.toString());
        }
        // B4: a loop with a call in its body inside an allocating function: nothing live around it is promoted
        {
            String loop = join(defs(0, 3), "@L:", uses(0, 3), "CALL g", "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints() + " ; NEW 16 m8 m8 ; " + loop);
            chk("blocked, loop with a call: nothing in registers", reg(o, 101) == -1 && reg(o, 102) == -1 && reg(o, 103) == -1, o.toString());
        }
        // B5: no callee-saved register (r13/r14/r12) is ever handed out in a blocked function, even with only one hot variable
        {
            String loop = join(defs(0, 1), "@L:", uses(0, 1), "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(H(-8, 50) + " ; NEW 16 m8 m8 ; " + loop);
            chk("blocked, one variable: caller-saved register, not r13/r14", reg(o, 101) >= 3, o.toString());
        }
        // B6: inline assembly still blocks everything
        {
            String loop = join(defs(0, 1), "@L:", uses(0, 1), "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(H(-8, 50) + " ; ASM_START ; ASM_END ; " + loop);
            chk("asm block: nothing promoted", reg(o, 101) == -1, o.toString());
        }
        // B7: ARGk (k >= 2) in a blocked function still removes r8/r9
        {
            String loop = join(defs(0, 3), "@L:", uses(0, 3), "R_BRC NEQ 8 %t1 #0 @L");
            List<String> o = run(hints() + " ; PUSH 8 ARG3 ; NEW 16 m8 m8 ; " + loop);
            int[] r = {reg(o, 101), reg(o, 102), reg(o, 103)};
            boolean noR8R9 = true; for (int x : r) if (x == 3 || x == 4) noR8R9 = false;
            chk("blocked + ARG3: r8 and r9 not used", noR8R9, o.toString());
        }

        // ---------- the end of a for range ----------
        String loopRead = "@L: ; PUSH 8 $-8 ; PUSH 8 $-16 ; LT_INT 8 ; CMP ; JMP @E ; JMP @L ; @E:";
        {
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead);
            chk("range: split into two 8-byte stores", has(o, "ADDR 8 $-16") && has(o, "ADDR 8 $-8") && o.indexOf("ASSIGN 8 8 8") > 0 && !has(o, "ASSIGN 16 16 16") && !has(o, "ADDR 16 $-16"), o.toString());
            chk("range: the order is start, then end", o.indexOf("ADDR 8 $-16") < o.indexOf("PUSH 8 $-40") && o.indexOf("PUSH 8 $-40") < o.indexOf("ADDR 8 $-8") && o.indexOf("ADDR 8 $-8") < o.indexOf("PUSH 8 $-48"), o.toString());
            chk("range: a hint for the end slot follows the ALLOC", has(o, "REGHINT -8 8 8") || o.stream().anyMatch(x -> x.startsWith("REGHINT -8 8 ")), o.toString());
            chk("range: the hint line is right behind the ALLOC", o.indexOf("ALLOC 64") + 1 == o.indexOf(o.stream().filter(x -> x.startsWith("REGHINT")).findFirst().orElse("?")), o.toString());
        }
        {   // the hint weight: the end is stored once (1) and read in a loop (8)
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead);
            chk("range: weight 1 (store) + 8 (read in a loop) = 9", has(o, "REGHINT -8 8 9"), o.toString());
        }
        {   // a computed end (and start): the split falls between the two value expressions
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-24 ; PUSH 8 $-32 ; ADD_INT 8 ; PUSH 8 $-40 ; PUSH 8 $-48 ; MUL_INT 8 ; ASSIGN 16 16 16 ; " + loopRead);
            int i1 = o.indexOf("ADDR 8 $-8");
            chk("range: computed start and end are split between the expressions",
                i1 > 0 && o.get(i1 - 1).equals("ASSIGN 8 8 8") && o.get(i1 - 2).equals("ADD_INT 8") && o.get(i1 + 3).equals("MUL_INT 8"), o.toString());
        }
        {   // a start that is one push and an end that is an expression
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-24 ; PUSH 8 $-32 ; PUSH 8 $-40 ; SUB_INT 8 ; ASSIGN 16 16 16 ; " + loopRead);
            int i1 = o.indexOf("ADDR 8 $-8");
            chk("range: single-push start, computed end", i1 > 0 && o.get(i1 - 2).equals("PUSH 8 $-24") && o.get(i1 + 3).equals("SUB_INT 8"), o.toString());
        }
        {   // weight below 8: still split (harmless), no hint
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; PUSH 8 $-8 ; POP $-56 8");
            chk("range: not read in a loop -> split, but no hint", has(o, "ADDR 8 $-8") && o.stream().noneMatch(x -> x.startsWith("REGHINT")), o.toString());
        }
        String base = "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; ";
        String[][] reject = {
            {"the range read as 16 bytes", base + loopRead + " ; PUSH 16 $-16"},
            {"the range address taken elsewhere", base + loopRead + " ; ADDR 16 $-16"},
            {"the end slot written on its own", base + loopRead + " ; ADDR 8 $-8"},
            {"the end slot read with another size", base + loopRead + " ; PUSH 4 $-8"},
            {"the start slot written on its own", base + loopRead + " ; ADDR 8 $-16"},
            {"an offset inside the 16 bytes", base + loopRead + " ; PUSH 8 $-12"},
            {"a wide read that overlaps the range from below", base + loopRead + " ; PUSH 24 $-24"},
            {"the end expression reads the range", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-8 ; ASSIGN 16 16 16 ; " + loopRead},
            {"the start expression reads the range", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-16 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead},
            {"a call in the end expression", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; CALL g ; ASSIGN 16 16 16 ; " + loopRead},
            {"an unsupported operator in the end expression", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; PUSH 8 $-56 ; DIV_INT 8 ; ASSIGN 16 16 16 ; " + loopRead},
            {"an unsupported operator in the start expression", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; DIV_INT 8 ; PUSH 8 $-56 ; ASSIGN 16 16 16 ; " + loopRead},
            {"only one value pushed", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; ASSIGN 16 16 16 ; " + loopRead},
            {"three values pushed", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; PUSH 8 $-56 ; ASSIGN 16 16 16 ; " + loopRead},
            {"an 8-byte address for a 16-byte construction", "ALLOC 64 ; ADDR 8 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead},
            {"a different construction size", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 8 16 ; " + loopRead},
            {"a 4-byte value pushed", "ALLOC 64 ; ADDR 16 $-16 ; PUSH 4 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead},
        };
        for (String[] rj : reject) {
            List<String> o = rangeEnd(rj[1]);
            chk("range left alone: " + rj[0], o.equals(text(parse("FUNC_START f ; " + rj[1] + " ; FUNC_END"))), o.toString());
        }
        {   // two loops reusing the same slot: both constructions are split, the weights add up
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead
                + " ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-56 ; ASSIGN 16 16 16 ; " + loopRead.replace("@L", "@M").replace("@E", "@F"));
            long splits = o.stream().filter(x -> x.equals("ADDR 8 $-8")).count();
            chk("range: two constructions of one slot are both split, weights add (1+8+1+8)", splits == 2 && has(o, "REGHINT -8 8 18"), o.toString());
        }
        {   // one bad mention spoils every construction of that slot
            List<String> o = rangeEnd("ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead
                + " ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-56 ; ASSIGN 16 16 16 ; PUSH 16 $-16");
            chk("range: a 16-byte read after the second construction keeps both as they were", !has(o, "ADDR 8 $-8") && !has(o, "ASSIGN 8 8 8"), o.toString());
        }
        {   // two different range slots in one function are decided independently
            List<String> o = rangeEnd("ALLOC 96 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead
                + " ; ADDR 16 $-64 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; @M: ; PUSH 8 $-56 ; PUSH 8 $-64 ; LT_INT 8 ; CMP ; JMP @F ; JMP @M ; @F: ; PUSH 16 $-64");
            chk("range: the first slot is split, the second (read as 16 bytes) is not", has(o, "ADDR 8 $-8") && has(o, "ADDR 16 $-64") && !has(o, "ADDR 8 $-56"), o.toString());
        }
        {   // no ALLOC to hang the hint on: untouched
            List<String> o = rangeEnd("ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead);
            chk("range: no ALLOC line -> function left exactly as it was", has(o, "ADDR 16 $-16") && !has(o, "ASSIGN 8 8 8"), o.toString());
        }
        {   // other functions are not affected by this function's mentions
            List<String> o = text(new RangeEndHintPass().run(parse("FUNC_START f ; ALLOC 64 ; ADDR 16 $-16 ; PUSH 8 $-40 ; PUSH 8 $-48 ; ASSIGN 16 16 16 ; " + loopRead + " ; FUNC_END ; FUNC_START g ; ALLOC 64 ; PUSH 16 $-16 ; FUNC_END")));
            chk("range: another function reading the same offset as 16 bytes does not matter", has(o, "ADDR 8 $-8"), o.toString());
        }
        System.out.println(bad == 0 ? "ALL VOLATILE REGISTER AND RANGE END CHECKS PASSED" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
EOF
CP=${LOB_CP:-$ROOT/LowerOrderGenerator/out}
javac -cp $CP -d $T $T/caspien/lowerorder/Drv8.java 2>&1 | grep -v "^Picked"
java -cp $T:$CP caspien.lowerorder.Drv8 2>&1 | grep -v "^Picked"
RC=${PIPESTATUS[0]}
rm -rf $T
exit $RC
