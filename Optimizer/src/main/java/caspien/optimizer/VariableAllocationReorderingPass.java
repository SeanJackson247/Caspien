package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variable-allocation reordering (`variable-allocation-reordering: on|off`, default off).
 *
 * Frame offsets are assigned by the LowerOrderGenerator's AddressLoweringPass purely from the order of a function's ALLOC lines: each slot goes
 * immediately below the previous one, rounded DOWN to its own alignment, and the gap this leaves above it is wasted. So `u8, u64, u8, u64`
 * loses 7 bytes twice. This pass reorders the function's hoisted ALLOC run so the gaps disappear:
 *
 *   1. alignment, largest first (8, 4, 2, 1). Every slot's size is a multiple of its alignment (structs are padded to their alignment, an
 *      array's is its element's), so once the slots are sorted the only padding left is the final round-up of the frame to 16.
 *   2. inside one alignment class, the slot with the larger use weight first (the same weight RegVarHintPass uses: each PUSH/ADDR mention
 *      counts 8^loopDepth, depth capped at 4), so hot variables sit nearest rbp, where an x86 displacement fits one byte.
 *   3. the original order (a stable sort), so equal keys never move.
 *
 * What stays put: `gt_routine_address` (rbp-8) and `gt_error_message` (rbp-16) must be the first two slots of every frame that has them
 * (GT_UNWIND and its MSG copy address them by those offsets) and are pinned there. Parameters are not ALLOCs yet at this point in the
 * pipeline (`ARG` lines): ArgToAllocLoweringPass, which runs afterwards in the LowerOrderGenerator, inserts their slots right behind the
 * pinned ones, so they stay ahead of every reordered local. Only the leading, contiguous ALLOC run is touched (ALLOC lines that appear later,
 * such as the inline temporary of an async `par` call, keep their place). A function with an ASM block, or with an ALLOC of one of the two
 * pinned names anywhere but the very front of the run, is left alone.
 *
 * Correctness needs no analysis: a local is only ever mentioned by name, offsets are computed after this pass, nothing else depends on the
 * slot order (the language does not define the layout of locals), and this pass never adds, removes or renames a line. The alignment table
 * below mirrors AddressLoweringPass.Sizes.alignOf (storage pointers 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/
 * string/range/dynarray 8, a fixed array its element's, a struct the max over its members, anything unknown 8); a wrong estimate could only
 * cost some padding, never correctness.
 */
public class VariableAllocationReorderingPass implements OptimizationPass {

    private final boolean enabled;

    public VariableAllocationReorderingPass() {
        this(false);
    }

    public VariableAllocationReorderingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-allocation-reordering";
    }

    @Override
    public PassResult run(BytecodeProgram lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        VariableAllocationReorderingAlign table = new VariableAllocationReorderingAlign(lines);
        BytecodeProgram out = new BytecodeProgram(lines.size());
        boolean changed = false;
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!isMnemonic(lines.get(i), "FUNC_START")) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int end = i;
            while (end < n && !isMnemonic(lines.get(end), "FUNC_END")) {
                end++;
            }
            end = Math.min(end, n - 1);
            BytecodeProgram fn = lines.slice(i, end + 1);
            BytecodeProgram re = reorderFunction(fn, table);
            if (re != null) {
                changed = true;
                out.addAll(re);
            } else {
                out.addAll(fn);
            }
            i = end + 1;
        }
        return new PassResult(changed ? out : lines, changed);
    }

    static boolean isMnemonic(BytecodeStatement line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    private static boolean isAlloc(BytecodeStatement l) {
        return isMnemonic(l, "ALLOC") && l.size() == 3;
    }

    /** The function's lines with the leading ALLOC run reordered, or null when nothing changes. */
    private BytecodeProgram reorderFunction(BytecodeProgram fn, VariableAllocationReorderingAlign table) {
        int n = fn.size();
        for (BytecodeStatement l : fn) {
            if (isMnemonic(l, "ASM_START")) {
                return null;
            }
        }
        // the leading run: the first ALLOC that follows only header lines, and every ALLOC directly after it
        int first = -1;
        for (int k = 0; k < n; k++) {
            BytecodeStatement l = fn.get(k);
            if (isAlloc(l)) {
                first = k;
                break;
            }
            if (!(isMnemonic(l, "FUNC_START") || isMnemonic(l, "FUNC_DECORATE") || isMnemonic(l, "RETURNS") || isMnemonic(l, "ARG")
                    || isMnemonic(l, "ALLOC_STATIC"))) {
                return null;
            }
        }
        if (first < 0) {
            return null;
        }
        int last = first;
        while (last + 1 < n && isAlloc(fn.get(last + 1))) {
            last++;
        }
        // pinned prefix
        int p = first;
        if (p <= last && fn.get(p).get(1).text.equals("gt_routine_address")) {
            p++;
            if (p <= last && fn.get(p).get(1).text.equals("gt_error_message")) {
                p++;
                // stack traces: the id and trace-pointer slots stay at rbp-24 / rbp-32
                while (p <= last && fn.get(p).get(1).text.startsWith("gt_trace_")) {
                    p++;
                }
            }
        }
        for (int k = p; k <= last; k++) {
            String nm = fn.get(k).get(1).text;
            if (nm.equals("gt_routine_address") || nm.equals("gt_error_message") || nm.startsWith("gt_trace_")) {
                return null;
            }
        }
        for (int k = last + 1; k < n; k++) {
            BytecodeStatement l = fn.get(k);
            if (isAlloc(l)) {
                String nm = l.get(1).text;
                if (nm.equals("gt_routine_address") || nm.equals("gt_error_message") || nm.startsWith("gt_trace_")) {
                    return null;
                }
            }
        }
        int count = last - p + 1;
        if (count < 2) {
            return null;
        }
        // weights: 8^loopDepth per mention (a backward JMP to an already-seen label closes a loop over [label, jmp])
        int[] delta = new int[n + 1];
        Map<String, Integer> labelAt = new HashMap<>();
        for (int k = 0; k < n; k++) {
            BytecodeStatement l = fn.get(k);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), k);
            } else if (isMnemonic(l, "JMP") && l.size() >= 2) {
                Integer at = labelAt.get(l.get(1).text);
                if (at != null) {
                    delta[at]++;
                    delta[k + 1]--;
                }
            }
        }
        Map<String, Long> weights = new HashMap<>();
        int run = 0;
        for (int k = 0; k < n; k++) {
            run += delta[k];
            BytecodeStatement l = fn.get(k);
            if (isMnemonic(l, "ALLOC") || isMnemonic(l, "FUNC_START")) {
                continue;
            }
            long w = 1L << (3 * Math.min(run, 4));
            for (int t = 1; t < l.size(); t++) {
                if (l.get(t).kind == BytecodeToken.Kind.CODE) {
                    weights.merge(l.get(t).text, w, Long::sum);
                }
            }
        }
        List<VariableAllocationReorderingSlot> slots = new ArrayList<>(count);
        for (int k = p; k <= last; k++) {
            BytecodeStatement decl = fn.get(k);
            slots.add(new VariableAllocationReorderingSlot(k - p, decl, table.of(decl.get(2).text),
                    weights.getOrDefault(decl.get(1).text, 0L)));
        }
        List<VariableAllocationReorderingSlot> sorted = new ArrayList<>(slots);
        sorted.sort((a, b) -> {
            if (a.align != b.align) {
                return Long.compare(b.align, a.align);
            }
            if (a.weight != b.weight) {
                return Long.compare(b.weight, a.weight);
            }
            return Integer.compare(a.idx, b.idx);
        });
        boolean same = true;
        for (int k = 0; k < count; k++) {
            if (sorted.get(k) != slots.get(k)) {
                same = false;
                break;
            }
        }
        if (same) {
            return null;
        }
        BytecodeProgram result = new BytecodeProgram(n);
        result.addAll(fn.slice(0, p));
        for (VariableAllocationReorderingSlot s : sorted) {
            result.add(s.line);
        }
        result.addAll(fn.slice(last + 1, n));
        return result;
    }

}
