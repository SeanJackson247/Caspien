package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Alignment of a canonical type text; mirrors the LowerOrderGenerator's own table (see the class comment). */
final class VariableAllocationReorderingAlign {
    private final Map<String, List<String>> structMembers = new HashMap<>();
    private final Map<String, Long> structCache = new HashMap<>();

    VariableAllocationReorderingAlign(BytecodeProgram lines) {
        String cur = null;
        for (BytecodeStatement l : lines) {
            if (VariableAllocationReorderingPass.isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                cur = l.get(1).text;
                structMembers.put(cur, new ArrayList<>());
            } else if (VariableAllocationReorderingPass.isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                structMembers.get(cur).add(l.get(2).text);
            } else if (VariableAllocationReorderingPass.isMnemonic(l, "STRUCT_END")) {
                cur = null;
            }
        }
    }

    /** Set by ofNoRecurse to the struct whose alignment is not cached yet (the returned value is then not final). */
    private String missing;

    long of(String canonical) {
        missing = null;
        long v = ofNoRecurse(canonical);
        if (missing == null) {
            return v;
        }
        resolveStructs(missing);
        missing = null;
        return ofNoRecurse(canonical);
    }

    /** Alignment of `canonical` given the cached struct alignments; the first one still missing is left in `missing`. */
    private long ofNoRecurse(String canonical) {
        String cur = canonical;
        while (true) {
            String rest = cur;
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
                    cur = base.substring(0, open); // an array is as aligned as its element
                    continue;
                }
            }
            if (base.equals("range") || base.startsWith("range(")) {
                return 8;
            }
            if (structMembers.get(base) != null) {
                Long cached = structCache.get(base);
                if (cached == null) {
                    missing = base;
                    return 1;
                }
                return cached;
            }
            return 8;
        }
    }

    /** Fills structCache for `root` and every struct below it: max over member alignments, explicit stack, a struct seeded 1 while on it. */
    private void resolveStructs(String root) {
        List<String> stack = new ArrayList<>();
        structCache.put(root, 1L);
        stack.add(root);
        while (!stack.isEmpty()) {
            String name = stack.get(stack.size() - 1);
            long max = 1;
            boolean blocked = false;
            for (String m : structMembers.get(name)) {
                missing = null;
                long a = ofNoRecurse(m);
                if (missing != null) {
                    structCache.put(missing, 1L);
                    stack.add(missing);
                    missing = null;
                    blocked = true;
                    break;
                }
                max = Math.max(max, a);
            }
            if (!blocked) {
                structCache.put(name, max);
                stack.remove(stack.size() - 1);
            }
        }
    }
}
