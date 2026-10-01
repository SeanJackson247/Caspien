package caspien.optimizer;

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
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Align table = new Align(lines);
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
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
            List<List<BytecodeToken>> fn = lines.subList(i, end + 1);
            List<List<BytecodeToken>> re = reorderFunction(fn, table);
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

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    private static boolean isAlloc(List<BytecodeToken> l) {
        return isMnemonic(l, "ALLOC") && l.size() == 3;
    }

    /** The function's lines with the leading ALLOC run reordered, or null when nothing changes. */
    private List<List<BytecodeToken>> reorderFunction(List<List<BytecodeToken>> fn, Align table) {
        int n = fn.size();
        for (List<BytecodeToken> l : fn) {
            if (isMnemonic(l, "ASM_START")) {
                return null;
            }
        }
        // the leading run: the first ALLOC that follows only header lines, and every ALLOC directly after it
        int first = -1;
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
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
            }
        }
        for (int k = p; k <= last; k++) {
            String nm = fn.get(k).get(1).text;
            if (nm.equals("gt_routine_address") || nm.equals("gt_error_message")) {
                return null;
            }
        }
        for (int k = last + 1; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (isAlloc(l)) {
                String nm = l.get(1).text;
                if (nm.equals("gt_routine_address") || nm.equals("gt_error_message")) {
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
            List<BytecodeToken> l = fn.get(k);
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
            List<BytecodeToken> l = fn.get(k);
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
        final class Slot {
            final int idx;
            final List<BytecodeToken> line;
            final long align;
            final long weight;

            Slot(int idx, List<BytecodeToken> line) {
                this.idx = idx;
                this.line = line;
                this.align = table.of(line.get(2).text);
                this.weight = weights.getOrDefault(line.get(1).text, 0L);
            }

        }
        List<Slot> slots = new ArrayList<>(count);
        for (int k = p; k <= last; k++) {
            slots.add(new Slot(k - p, fn.get(k)));
        }
        List<Slot> sorted = new ArrayList<>(slots);
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
        List<List<BytecodeToken>> result = new ArrayList<>(n);
        result.addAll(fn.subList(0, p));
        for (Slot s : sorted) {
            result.add(s.line);
        }
        result.addAll(fn.subList(last + 1, n));
        return result;
    }

    /** Alignment of a canonical type text; mirrors the LowerOrderGenerator's own table (see the class comment). */
    static final class Align {
        private final Map<String, List<String>> structMembers = new HashMap<>();
        private final Map<String, Long> structCache = new HashMap<>();

        Align(List<List<BytecodeToken>> lines) {
            String cur = null;
            for (List<BytecodeToken> l : lines) {
                if (isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                    cur = l.get(1).text;
                    structMembers.put(cur, new ArrayList<>());
                } else if (isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                    structMembers.get(cur).add(l.get(2).text);
                } else if (isMnemonic(l, "STRUCT_END")) {
                    cur = null;
                }
            }
        }

        long of(String canonical) {
            String rest = canonical;
            int u = rest.indexOf('_');
            if (u > 0) {
                String c = rest.substring(0, u);
                if (c.equals("owns") || c.equals("ref") || c.equals("raw") || c.equals("auto") || c.equals("static")) {
                    return 8;
                }
            }
            u = rest.indexOf('_');
            if (u > 0 && rest.substring(0, u).equals("some")) {
                rest = rest.substring(u + 1);
            }
            u = rest.indexOf('_');
            String base = u > 0 ? rest.substring(u + 1) : rest;
            if (base.startsWith("atomic_")) {
                base = base.substring("atomic_".length());
            }
            return ofBase(base);
        }

        private long ofBase(String base) {
            switch (base) {
                case "u8": case "s8": case "bool": case "char": case "void":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                default:
                    break;
            }
            if (base.startsWith("dynarray(") && base.endsWith(")")) {
                return 8;
            }
            if (base.endsWith("]")) {
                int open = base.lastIndexOf('[');
                if (open > 0) {
                    return of(base.substring(0, open));
                }
            }
            if (base.equals("range") || base.startsWith("range(")) {
                return 8;
            }
            List<String> members = structMembers.get(base);
            if (members != null) {
                Long cached = structCache.get(base);
                if (cached != null) {
                    return cached;
                }
                structCache.put(base, 1L);
                long max = 1;
                for (String m : members) {
                    max = Math.max(max, of(m));
                }
                structCache.put(base, max);
                return max;
            }
            return 8;
        }
    }
}
