package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Struct sizes from the STRUCT_START ... STRUCT_END declarations. */
final class SizeofResolutionSizes {
    /** struct name -> its members' canonical types, with padding as a negative marker (-n). */
    private final Map<String, List<Object>> decls = new HashMap<>();
    private final Map<String, Long> cache = new HashMap<>();

    SizeofResolutionSizes(BytecodeProgram lines) {
        String cur = null;
        for (BytecodeStatement l : lines) {
            if (SizeofResolutionPass.isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                cur = l.get(1).text;
                decls.put(cur, new ArrayList<>());
            } else if (SizeofResolutionPass.isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                decls.get(cur).add(l.get(2).text);
            } else if (SizeofResolutionPass.isMnemonic(l, "STRUCT_PADDING") && l.size() >= 2 && cur != null) {
                decls.get(cur).add(Long.valueOf(l.get(1).text));
            } else if (SizeofResolutionPass.isMnemonic(l, "STRUCT_END")) {
                cur = null;
            }
        }
    }

    /** Set by sizeStep when the size of a struct (times `needMult`) is still needed to finish the type. */
    private String needStruct;
    private long needMult;

    /**
     * Size of the named struct, or -1 if it has no declaration (or it is nested deeper than 64 levels). Nested structs are
     * summed with an explicit stack of frames {struct, depth, next member, sum so far, multiplier waiting for the child}.
     */
    long structSize(String name, int depth) {
        Long c = cache.get(name);
        if (c != null) {
            return c;
        }
        if (decls.get(name) == null || depth > 64) {
            return -1;
        }
        List<String> names = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        List<Integer> nextMember = new ArrayList<>();
        List<Long> sums = new ArrayList<>();
        List<Long> waitingMult = new ArrayList<>();
        names.add(name);
        depths.add(depth);
        nextMember.add(0);
        sums.add(0L);
        waitingMult.add(1L);
        long finished = 0;
        boolean childDone = false;
        while (!names.isEmpty()) {
            int top = names.size() - 1;
            List<Object> members = decls.get(names.get(top));
            int i = nextMember.get(top);
            long sum = sums.get(top);
            if (childDone) {
                sum += waitingMult.get(top) * (finished < 0 ? 8 : finished); // the member that was waiting on the child struct (a negative size reads as one word)
                i++;
                childDone = false;
            }
            boolean descended = false;
            while (i < members.size()) {
                Object o = members.get(i);
                if (o instanceof Long) {
                    sum += (Long) o;
                    i++;
                    continue;
                }
                needStruct = null;
                long v = sizeStep((String) o);
                if (needStruct == null) {
                    sum += v;
                    i++;
                    continue;
                }
                String child = needStruct;
                int childDepth = depths.get(top) + 1;
                Long known = cache.get(child);
                if (known != null) {
                    sum += needMult * (known < 0 ? 8 : known);
                    i++;
                    continue;
                }
                if (childDepth > 64) {
                    sum += needMult * 8; // too deep: structSize says -1, the caller reads that as one word
                    i++;
                    continue;
                }
                nextMember.set(top, i);
                sums.set(top, sum);
                waitingMult.set(top, needMult);
                names.add(child);
                depths.add(childDepth);
                nextMember.add(0);
                sums.add(0L);
                waitingMult.add(1L);
                descended = true;
                break;
            }
            if (descended) {
                continue;
            }
            cache.put(names.get(top), sum);
            finished = sum;
            childDone = true;
            names.remove(top);
            depths.remove(top);
            nextMember.remove(top);
            sums.remove(top);
            waitingMult.remove(top);
        }
        return finished;
    }

    /**
     * Size of a canonical type, except that when it ends in a struct the answer is left to the caller: `needStruct` is that
     * struct and `needMult` the product of the array lengths around it (the return value is then meaningless).
     */
    private long sizeStep(String canonical) {
        long mult = 1;
        String cur = canonical;
        while (true) {
            String rest = cur;
            for (String st : new String[]{"owns_", "ref_", "raw_", "auto_", "static_"}) {
                if (rest.startsWith(st)) {
                    return mult * 8;
                }
            }
            if (rest.startsWith("some_")) {
                rest = rest.substring(5);
            }
            for (String mu : new String[]{"mut_", "imut_", "indeterminate_"}) {
                if (rest.startsWith(mu)) {
                    rest = rest.substring(mu.length());
                    break;
                }
            }
            if (rest.startsWith("atomic_")) {
                rest = rest.substring(7);
            }
            String base = rest;
            switch (base) {
                case "u8": case "s8": case "bool": case "char": case "void":
                    return mult;
                case "u16": case "s16":
                    return mult * 2;
                case "u32": case "s32": case "f32":
                    return mult * 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return mult * 8;
                case "range":
                    return mult * 16;
                default:
                    break;
            }
            if (base.startsWith("range(")) {
                return mult * 16;
            }
            if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
                return mult * 8;
            }
            if (base.endsWith("]")) {
                int open = base.lastIndexOf('[');
                if (open > 0) {
                    long n;
                    try {
                        n = Long.parseLong(base.substring(open + 1, base.length() - 1));
                    } catch (NumberFormatException e) {
                        return mult * 8;
                    }
                    mult *= n;
                    cur = base.substring(0, open);
                    continue;
                }
            }
            if (decls.containsKey(base)) {
                needStruct = base;
                needMult = mult;
                return 0;
            }
            return mult * 8;
        }
    }
}
