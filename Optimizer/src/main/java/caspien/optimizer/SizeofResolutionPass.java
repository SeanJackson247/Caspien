package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the front end's symbolic {@code SIZEOF TypeName <type>} into {@code PUSH n <type>}.
 *
 * The front end folds {@code sizeof} for primitives, pointers and ranges (their size never
 * changes) but leaves a struct, or an array of structs, symbolic, and does the same for the scale
 * of {@code raw Struct} pointer arithmetic. A struct's real size (the hidden {@code ___type}
 * word, member padding, trailing padding) is only settled here, in the STRUCT declarations the
 * program actually carries. Resolving it late lets struct member reordering run first and
 * constant folding (which needs the PUSH) run afterwards, so the size {@code unsafe} code
 * passes to malloc or memcopy, and the stride of a pointer step, always match the layout the
 * Lower Order Generator will use.
 *
 * Size of a struct = the sum of its STRUCT_MEMBER sizes and STRUCT_PADDING bytes, read from the
 * declaration as it stands now. Member sizes follow the same rules as the Lower Order
 * Generator's SizeCalculator: any storage-keyword type is an 8-byte pointer, u8/s8/bool/char 1,
 * u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, range 16, dynarray 8, a fixed array
 * is element size times its length, a known struct is its own size (a mangled generic name is
 * looked up whole), anything else (an enum) 8.
 *
 * Always on: the SIZEOF mnemonic must never reach the Lower Order Generator. A SIZEOF naming a
 * struct with no declaration is an internal error.
 */
public class SizeofResolutionPass implements OptimizationPass {

    @Override
    public String name() {
        return "sizeof-resolution";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        boolean any = false;
        for (List<BytecodeToken> l : lines) {
            if (isMnemonic(l, "SIZEOF")) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new PassResult(lines, false);
        }
        Sizes sizes = new Sizes(lines);
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (List<BytecodeToken> l : lines) {
            if (isMnemonic(l, "SIZEOF") && l.size() >= 3) {
                String name = l.get(1).text;
                long n = sizes.structSize(name, 0);
                if (n < 0) {
                    BytecodeToken at = l.get(0);
                    throw new IllegalStateException("internal error: SIZEOF names struct '" + name
                            + "' which has no STRUCT declaration (" + at.file + ":" + at.line + ")");
                }
                List<BytecodeToken> r = new ArrayList<>(l.size());
                BytecodeToken m = l.get(0);
                r.add(new BytecodeToken("PUSH", m.file, m.line, BytecodeToken.Kind.CODE));
                r.add(new BytecodeToken(Long.toString(n), m.file, m.line, BytecodeToken.Kind.CODE));
                for (int i = 2; i < l.size(); i++) {
                    r.add(l.get(i));
                }
                out.add(r);
            } else {
                out.add(l);
            }
        }
        return new PassResult(out, true);
    }

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    /** Struct sizes from the STRUCT_START ... STRUCT_END declarations. */
    static final class Sizes {
        /** struct name -> its members' canonical types, with padding as a negative marker (-n). */
        private final Map<String, List<Object>> decls = new HashMap<>();
        private final Map<String, Long> cache = new HashMap<>();

        Sizes(List<List<BytecodeToken>> lines) {
            String cur = null;
            for (List<BytecodeToken> l : lines) {
                if (isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                    cur = l.get(1).text;
                    decls.put(cur, new ArrayList<>());
                } else if (isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                    decls.get(cur).add(l.get(2).text);
                } else if (isMnemonic(l, "STRUCT_PADDING") && l.size() >= 2 && cur != null) {
                    decls.get(cur).add(Long.valueOf(l.get(1).text));
                } else if (isMnemonic(l, "STRUCT_END")) {
                    cur = null;
                }
            }
        }

        /** Size of the named struct, or -1 if it has no declaration. */
        long structSize(String name, int depth) {
            Long c = cache.get(name);
            if (c != null) {
                return c;
            }
            List<Object> members = decls.get(name);
            if (members == null || depth > 64) {
                return -1;
            }
            long sum = 0;
            for (Object o : members) {
                if (o instanceof Long) {
                    sum += (Long) o;
                } else {
                    sum += sizeOfType((String) o, depth + 1);
                }
            }
            cache.put(name, sum);
            return sum;
        }

        long sizeOfType(String canonical, int depth) {
            String rest = canonical;
            for (String st : new String[]{"owns_", "ref_", "raw_", "auto_", "static_"}) {
                if (rest.startsWith(st)) {
                    return 8;
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
            return sizeOfBase(rest, depth);
        }

        private long sizeOfBase(String base, int depth) {
            switch (base) {
                case "u8": case "s8": case "bool": case "char": case "void":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                case "range":
                    return 16;
                default:
                    break;
            }
            if (base.startsWith("range(")) {
                return 16;
            }
            if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
                return 8;
            }
            if (base.endsWith("]")) {
                int open = base.lastIndexOf('[');
                if (open > 0) {
                    long n;
                    try {
                        n = Long.parseLong(base.substring(open + 1, base.length() - 1));
                    } catch (NumberFormatException e) {
                        return 8;
                    }
                    long el = sizeOfType(base.substring(0, open), depth);
                    return el * n;
                }
            }
            if (decls.containsKey(base)) {
                long s = structSize(base, depth);
                return s < 0 ? 8 : s;
            }
            return 8;
        }
    }
}
