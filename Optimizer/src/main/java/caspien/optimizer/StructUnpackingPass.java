package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a struct instance living in a local variable to act as independent variables, one per member, instead of one opaque
 * struct-shaped slot (switch: {@code struct-unpacking: on|off}, default off).
 *
 * A local {@code ALLOC v T} whose type T is {@code mut_S} or {@code imut_S}, where S is a struct declared in the program with only
 * scalar members (integers, f32/f64, bool), qualifies when EVERY mention of v (or of v.member) is one of:
 *   - its own ALLOC;
 *   - a construction   ADDR v T ; PUSH lit imut_u64 (the hidden ___type slot) ; one expression per member, in declaration order ;
 *                      STACK_LOCK n (padding lines may also sit between members) ; ASSIGN T T T     -- each member expression may only use PUSH, arithmetic/compare/cast operators (a known
 *                      stack effect) and must not mention v;
 *   - a member read    PUSH v.m MT   (not followed by ADDR_OF);
 *   - a member write   ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT   (the left-hand side of an assignment or compound assignment).
 * Anything else -- the whole struct pushed or copied, passed by address (ADDR_OF), a nested or ___type access, an unknown line inside a
 * construction -- leaves v alone. Functions containing ASM_START are skipped.
 *
 * Effect: the ALLOC becomes one ALLOC per member ({@code v__m}, type from the struct's member list); every read/write is redirected to
 * {@code v__m}; a construction becomes one {@code ADDR v__m MT ; expr ; ASSIGN MT MT MT} per member. The hidden ___type slot and the padding
 * are dropped (a program that reads ___type is not touched). The new variables are ordinary scalars, so elision, shifting and the register
 * hints see them.
 */
public class StructUnpackingPass implements OptimizationPass {

    private final boolean enabled;

    public StructUnpackingPass() {
        this(false);
    }

    public StructUnpackingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "struct-unpacking";
    }

    private static final Set<String> BINARY = new HashSet<>(List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(List.of("NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV"));

    /** struct name -> ordered (member name, member type), excluding ___type; only structs whose members are all scalars. */
    private static Map<String, List<String[]>> structs(List<List<BytecodeToken>> L) {
        Map<String, List<String[]>> out = new HashMap<>();
        String cur = null;
        List<String[]> mem = null;
        boolean ok = true;
        for (List<BytecodeToken> l : L) {
            String m = VarAnalysis.mnemonic(l);
            if ("STRUCT_START".equals(m) && l.size() == 2) {
                cur = l.get(1).text;
                mem = new ArrayList<>();
                ok = true;
            } else if ("STRUCT_MEMBER".equals(m) && cur != null && l.size() == 3) {
                String nm = l.get(1).text, ty = l.get(2).text;
                if (nm.equals("___type")) continue;
                if (ConstantFoldingPass.base(ty) == null || ty.startsWith("indeterminate_")) ok = false;
                mem.add(new String[] {nm, ty});
            } else if ("STRUCT_END".equals(m) && cur != null) {
                if (ok && !mem.isEmpty()) out.put(cur, mem);
                cur = null;
            }
        }
        return out;
    }

    private static boolean mentions(List<BytecodeToken> l, String v) {
        for (BytecodeToken t : l) {
            if (t.text.equals(v) || t.text.startsWith(v + ".")) return true;
        }
        return false;
    }

    /** Stack effect of one expression line, or Integer.MIN_VALUE when the line is not understood. */
    private static int effect(List<BytecodeToken> l) {
        String m = VarAnalysis.mnemonic(l);
        if (m == null) return Integer.MIN_VALUE;
        if (m.equals("PUSH") && l.size() == 3) return 1;
        if (BINARY.contains(m) && l.size() == 4) return -1;
        if (UNARY.contains(m)) return 0;
        return Integer.MIN_VALUE;
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Map<String, List<String[]>> table = structs(lines);
        if (table.isEmpty()) {
            return new PassResult(lines, false);
        }
        Set<Integer> remove = new HashSet<>();
        Map<Integer, List<List<BytecodeToken>>> replaceWith = new HashMap<>();   // line index -> lines emitted instead (first line of a removed span)
        Map<Integer, String> renameRead = new HashMap<>();                       // line index of "PUSH v.m T" -> new name
        boolean changed = false;
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
            Map<String, Integer> allocCount = new HashMap<>();
            Set<String> allNames = new HashSet<>();
            for (int i = f[0]; i <= f[1]; i++) {
                List<BytecodeToken> l = lines.get(i);
                for (BytecodeToken t : l) allNames.add(t.text);
                if ("ALLOC".equals(VarAnalysis.mnemonic(l)) && l.size() == 3) allocCount.merge(l.get(1).text, 1, Integer::sum);
            }
            for (int i = f[0]; i <= f[1]; i++) {
                List<BytecodeToken> l = lines.get(i);
                if (!"ALLOC".equals(VarAnalysis.mnemonic(l)) || l.size() != 3) continue;
                String v = l.get(1).text, T = l.get(2).text;
                if (v.startsWith("$") || allocCount.get(v) != 1) continue;
                String sname = T.startsWith("imut_") ? T.substring(5) : T.startsWith("mut_") ? T.substring(4) : null;
                List<String[]> members = sname == null ? null : table.get(sname);
                if (members == null) continue;
                Plan plan = plan(lines, f[0], f[1], i, v, T, members, allNames);
                if (plan == null) continue;
                changed = true;
                remove.addAll(plan.remove);
                replaceWith.putAll(plan.replace);
                renameRead.putAll(plan.rename);
            }
        }
        if (!changed) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            List<List<BytecodeToken>> rep = replaceWith.get(i);
            if (rep != null) out.addAll(rep);
            if (remove.contains(i)) continue;
            String nn = renameRead.get(i);
            out.add(nn == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, nn));
        }
        return new PassResult(out, true);
    }

    private static final class Plan {
        final Set<Integer> remove = new HashSet<>();
        final Map<Integer, List<List<BytecodeToken>>> replace = new HashMap<>();
        final Map<Integer, String> rename = new HashMap<>();
    }

    private static List<BytecodeToken> mk(BytecodeToken like, String... toks) {
        List<BytecodeToken> r = new ArrayList<>();
        for (String s : toks) r.add(new BytecodeToken(s, like.file, like.line, BytecodeToken.Kind.CODE));
        return r;
    }

    /** Returns the rewrite for variable v, or null when some mention is not one of the understood shapes. */
    private static Plan plan(List<List<BytecodeToken>> L, int s, int e, int allocIdx, String v, String T,
                             List<String[]> members, Set<String> allNames) {
        Map<String, String> newName = new LinkedHashMap<>();
        Map<String, String> memType = new HashMap<>();
        for (String[] m : members) {
            String nn = v + "__" + m[0];
            if (allNames.contains(nn)) return null;
            newName.put(m[0], nn);
            memType.put(m[0], m[1]);
        }
        Plan pl = new Plan();
        Set<Integer> handled = new HashSet<>();
        handled.add(allocIdx);
        int n = members.size();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (i == allocIdx || handled.contains(i)) continue;
            if (!mentions(l, v)) continue;
            String m = VarAnalysis.mnemonic(l);
            // member read
            if ("PUSH".equals(m) && l.size() == 3 && l.get(1).text.startsWith(v + ".")) {
                String mem = l.get(1).text.substring(v.length() + 1);
                if (!newName.containsKey(mem)) return null;
                if (i + 1 <= e && "ADDR_OF".equals(VarAnalysis.mnemonic(L.get(i + 1)))) return null;
                if (!l.get(2).text.equals(memType.get(mem))) return null;
                pl.rename.put(i, newName.get(mem));
                continue;
            }
            if (!"ADDR".equals(m) || l.size() != 3 || !l.get(1).text.equals(v) || !l.get(2).text.equals(T)) return null;
            // member write: ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT
            if (i + 2 <= e) {
                List<BytecodeToken> a = L.get(i + 1), b = L.get(i + 2);
                if ("PUSH_FIELDNAME".equals(VarAnalysis.mnemonic(a)) && a.size() == 3) {
                    String mem = a.get(1).text;
                    if (!newName.containsKey(mem)) return null;
                    if (!"DOT_LHS".equals(VarAnalysis.mnemonic(b)) || b.size() != 4 || !b.get(1).text.equals(T)) return null;
                    String mt = memType.get(mem);
                    if (!a.get(2).text.equals(mt) || !b.get(2).text.equals(mt) || !b.get(3).text.equals(mt)) return null;
                    pl.remove.add(i);
                    pl.remove.add(i + 1);
                    pl.remove.add(i + 2);
                    List<List<BytecodeToken>> r = new ArrayList<>();
                    r.add(mk(l.get(0), "ADDR", newName.get(mem), mt));
                    pl.replace.put(i, r);
                    handled.add(i + 1);
                    handled.add(i + 2);
                    // the operands that follow are checked on their own turn (they may not mention v except as member reads)
                    continue;
                }
            }
            // construction: ADDR v T ; PUSH lit imut_u64 ; n expressions ; STACK_LOCK k ; ASSIGN T T T
            int p = i + 1;
            if (p > e) return null;
            List<BytecodeToken> ty = L.get(p);
            if (!"PUSH".equals(VarAnalysis.mnemonic(ty)) || ty.size() != 3 || !ty.get(2).text.equals("imut_u64") || !ty.get(1).text.matches("[0-9]+")) return null;
            p++;
            // The member expressions are postfix; simulate a stack of "start line" markers to find where each one begins.
            List<Integer> starts = new ArrayList<>();
            while (true) {
                if (p > e) return null;
                List<BytecodeToken> x = L.get(p);
                String xm = VarAnalysis.mnemonic(x);
                if ("ASSIGN".equals(xm)) break;
                if (!"STACK_LOCK".equals(xm)) {
                    int ef = effect(x);
                    if (ef == Integer.MIN_VALUE || mentions(x, v)) return null;
                    if (ef == 1) {
                        starts.add(p);
                    } else if (ef == -1) {
                        if (starts.size() < 2) return null;
                        starts.remove(starts.size() - 1);
                    } else if (starts.isEmpty()) {
                        return null;
                    }
                }
                p++;
            }
            if (starts.size() != n) return null;
            List<int[]> groups = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                groups.add(new int[] {starts.get(k), k + 1 < n ? starts.get(k + 1) : p});
            }
            List<BytecodeToken> as = L.get(p);
            if (!"ASSIGN".equals(VarAnalysis.mnemonic(as)) || as.size() != 4 || !as.get(1).text.equals(T) || !as.get(2).text.equals(T) || !as.get(3).text.equals(T)) return null;
            List<List<BytecodeToken>> r = new ArrayList<>();
            int gi = 0;
            for (String[] mm : members) {
                int[] g = groups.get(gi++);
                r.add(mk(l.get(0), "ADDR", newName.get(mm[0]), mm[1]));
                for (int q = g[0]; q < g[1]; q++) {
                    if (!"STACK_LOCK".equals(VarAnalysis.mnemonic(L.get(q)))) r.add(L.get(q));
                }
                r.add(mk(l.get(0), "ASSIGN", mm[1], mm[1], mm[1]));
            }
            for (int q = i; q <= p; q++) {
                pl.remove.add(q);
                handled.add(q);
            }
            pl.replace.put(i, r);
            // note: expressions inside the groups were checked not to mention v; they may mention other variables freely
        }
        // ALLOC replacement
        List<List<BytecodeToken>> allocs = new ArrayList<>();
        for (String[] m : members) allocs.add(mk(L.get(allocIdx).get(0), "ALLOC", newName.get(m[0]), m[1]));
        pl.remove.add(allocIdx);
        pl.replace.put(allocIdx, allocs);
        return pl;
    }
}
