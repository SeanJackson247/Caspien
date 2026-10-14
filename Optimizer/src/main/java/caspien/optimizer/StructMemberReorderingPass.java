package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reorders a struct's own members, largest alignment first, so the padding the front end put between
 * them (and after the last one) disappears and the struct gets smaller
 * (switch: {@code struct-member-reordering: on|off}, default off).
 *
 * Runs first in the Optimizer's outer loop, ahead of SizeofResolutionPass and StructUnpackingPass, so the layout is settled before
 * anything reads it. Everything that reads a member does it by NAME (member reads/writes, offsets worked out later by the Lower Order
 * Generator from the declaration), so only two things are positional and must be rewritten together with the declaration: a
 * construction site (the hidden class id, then one value per member in declaration order, with {@code STACK_LOCK n} for each padding gap)
 * and static sizes folded by the front end (see the pin below).
 *
 * A struct is reordered only when ALL of these hold (otherwise it is left exactly as it was):
 *   - it has at least two members, all plain scalars (u8..u64, s8..s64, bool, char, f32, f64; no pointer, no atomic, no nested struct,
 *     no array), and no decorator other than {@code @pub} (so not {@code @lock});
 *   - no other struct has it as a member type (by value, or as an array of it), and no static/global declaration uses an array of it
 *     or an {@code ALLOC_STATIC} of it;
 *   - no {@code raw} pointer to it appears anywhere, except the hidden destination pointer of a struct-returning call
 *     (an {@code ARG/PUSH $ret_dest}, a {@code RET} of it, and the address handed to a callee that declares one): unsafe code can
 *     reach a struct through a raw pointer by byte offset, and C interop sees the memory layout;
 *   - the front end did not fold its size into a static ({@code STRUCT_PIN Name}, emitted for {@code let static n = sizeof(Name)});
 *   - EVERY construction site of it was understood: a {@code PUSH <classId> imut_u64} followed by exactly the member units and
 *     padding locks its declaration predicts (a unit is pure: PUSH, arithmetic, compare and cast lines only, with the member's type),
 *     ended by an ASSIGN, a NEW or the next class-id push (array literal). A site that does not parse, or parses two ways, blocks the
 *     struct (a literal that happens to equal a class id can do this too; that only costs the optimisation);
 *   - the new layout is strictly smaller.
 *
 * Effect: the STRUCT_MEMBER / STRUCT_PADDING lines of the declaration are replaced by the new order (largest member first, equal sizes
 * keep their relative order, padding only at the end); each construction site is rewritten the same way (units reordered, padding locks
 * re-derived). Member expressions are evaluated in the new order; they are pure, so this only matters for the order of loads.
 * Static initialisers ({@code GLOBAL g.member ...}) name their members and need no change.
 *
 * Always, even with the switch off, the pass strips every {@code STRUCT_PIN} line (it must never reach the Lower Order Generator).
 */
public class StructMemberReorderingPass {

    private final boolean enabled;

    public StructMemberReorderingPass() {
        this(false);
    }

    public StructMemberReorderingPass(boolean enabled) {
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- model

    // ------------------------------------------------------------- helpers

    private final Set<String> pinnedEver = new HashSet<>();

    private static boolean isMn(BytecodeStatement l, String m) {
        return !l.isEmpty() && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.equals(m);
    }

    private static BytecodeToken tok(BytecodeToken near, String text) {
        return new BytecodeToken(text, near.file, near.line, BytecodeToken.Kind.CODE);
    }

    private static String stripMut(String t) {
        for (String m : new String[] {"mut_", "imut_", "indeterminate_"}) {
            if (t.startsWith(m)) {
                return t.substring(m.length());
            }
        }
        return t;
    }

    /** Byte size of a plain scalar base type, or -1 when the type is anything else. */
    private static int scalarSize(String type) {
        String b = stripMut(type);
        switch (b) {
            case "u8": case "s8": case "bool": case "char":
                return 1;
            case "u16": case "s16":
                return 2;
            case "u32": case "s32": case "f32":
                return 4;
            case "u64": case "s64": case "f64":
                return 8;
            default:
                return -1;
        }
    }

    private static final Set<String> BINARY = new HashSet<>(java.util.List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "SAR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(java.util.List.of(
            "NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV", "NEG_FLOAT"));

    /** Stack effect of one member-expression line, or Integer.MIN_VALUE when the line is not allowed in one. */
    private static int effect(BytecodeStatement l) {
        if (l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE) {
            return Integer.MIN_VALUE;
        }
        String m = l.get(0).text;
        if (m.equals("PUSH") && l.size() == 3) {
            return 1;
        }
        if (BINARY.contains(m) && l.size() == 4) {
            return -1;
        }
        if (UNARY.contains(m)) {
            return 0;
        }
        return Integer.MIN_VALUE;
    }

    private static String resultType(BytecodeStatement l) {
        return l.get(l.size() - 1).text;
    }

    // ------------------------------------------------------------------ run

    public PassResult run(BytecodeProgram lines) {
        // STRUCT_PIN lines: read, then always removed.
        Set<String> pinned = pinnedEver; // the pass instance lives across outer-loop rounds; the lines are stripped in round one, the pins must outlive them
        boolean stripped = false;
        BytecodeProgram work = lines;
        for (BytecodeStatement l : lines) {
            if (isMn(l, "STRUCT_PIN")) {
                stripped = true;
                break;
            }
        }
        if (stripped) {
            work = new BytecodeProgram(lines.size());
            for (BytecodeStatement l : lines) {
                if (isMn(l, "STRUCT_PIN")) {
                    if (l.size() >= 2) {
                        pinned.add(l.get(1).text);
                    }
                } else {
                    work.add(l);
                }
            }
        }
        if (!enabled) {
            return new PassResult(work, stripped);
        }
        PassResult r = reorder(work, pinned);
        return new PassResult(r.lines, r.changed || stripped);
    }

    private PassResult reorder(BytecodeProgram L, Set<String> pinned) {
        Map<String, StructMemberReorderingDecl> decls = readDecls(L);
        if (decls.isEmpty()) {
            return new PassResult(L, false);
        }
        readClassIds(L, decls);
        // structural eligibility
        for (StructMemberReorderingDecl d : decls.values()) {
            if (!d.plain || !d.hasType || !d.typeFirst || d.members.size() < 2 || d.classId < 0 || pinned.contains(d.name)) {
                d.blocked = true;
            }
        }
        blockByUses(L, decls);
        // the new layout, and only when it is strictly smaller
        for (StructMemberReorderingDecl d : decls.values()) {
            if (d.blocked) {
                continue;
            }
            long oldSize = 8;
            for (StructMemberReorderingEntry e : d.entries) {
                oldSize += e.pad ? e.padBytes : scalarSize(e.type);
            }
            List<StructMemberReorderingEntry> sorted = new ArrayList<>(d.members);
            sorted.sort((a, b) -> Integer.compare(scalarSize(b.type), scalarSize(a.type)));   // stable
            long sum = 8;
            for (StructMemberReorderingEntry e : sorted) {
                sum += scalarSize(e.type);
            }
            long total = (sum + 7) / 8 * 8;
            if (total >= oldSize) {
                d.blocked = true;
                continue;
            }
            List<StructMemberReorderingEntry> ne = new ArrayList<>(sorted);
            if (total > sum) {
                ne.add(new StructMemberReorderingEntry(true, total - sum, null, null, null));
            }
            d.newEntries = ne;
        }
        Map<Long, StructMemberReorderingDecl> byId = new HashMap<>();
        for (StructMemberReorderingDecl d : decls.values()) {
            if (d.classId >= 0) {
                byId.put(d.classId, d);
            }
        }
        // construction sites: every classId push of a still-eligible struct must parse
        List<StructMemberReorderingSite> sites = new ArrayList<>();
        for (int i = 0; i < L.size(); i++) {
            StructMemberReorderingDecl d = classIdPush(L.get(i), byId);
            if (d == null || d.blocked) {
                continue;
            }
            StructMemberReorderingSite s = parseSite(L, i, d, byId);
            if (s == null) {
                d.blocked = true;
            } else {
                sites.add(s);
            }
        }
        boolean any = false;
        for (StructMemberReorderingDecl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                any = true;
            }
        }
        if (!any) {
            return new PassResult(L, false);
        }
        // rewrite
        Map<Integer, StructMemberReorderingSite> siteAt = new HashMap<>();
        for (StructMemberReorderingSite s : sites) {
            if (!s.decl.blocked) {
                siteAt.put(s.start, s);
            }
        }
        Map<Integer, StructMemberReorderingDecl> declAt = new HashMap<>();
        for (StructMemberReorderingDecl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                declAt.put(d.start, d);
            }
        }
        BytecodeProgram out = new BytecodeProgram(L.size());
        for (int i = 0; i < L.size(); i++) {
            StructMemberReorderingDecl d = declAt.get(i);
            if (d != null) {
                out.addAll(d.head);
                for (StructMemberReorderingEntry e : d.newEntries) {
                    if (e.pad) {
                        BytecodeToken near = d.head.get(0).get(0);
                        BytecodeStatement pl = new BytecodeStatement();
                        pl.add(tok(near, "STRUCT_PADDING"));
                        pl.add(tok(near, Long.toString(e.padBytes)));
                        out.add(pl);
                    } else {
                        out.add(e.line);
                    }
                }
                out.add(L.get(d.end));
                i = d.end;
                continue;
            }
            StructMemberReorderingSite s = siteAt.get(i);
            if (s != null) {
                out.add(L.get(i));   // the class id push
                for (StructMemberReorderingEntry e : s.decl.newEntries) {
                    if (e.pad) {
                        BytecodeStatement pl = new BytecodeStatement();
                        pl.add(tok(L.get(i).get(0), "STACK_LOCK"));
                        pl.add(tok(L.get(i).get(0), Long.toString(e.padBytes)));
                        out.add(pl);
                    } else {
                        out.addAll(s.units.get(e.name));
                    }
                }
                i = s.end - 1;
                continue;
            }
            out.add(L.get(i));
        }
        return new PassResult(out, true);
    }

    // ---------------------------------------------------------- declarations

    private static Map<String, StructMemberReorderingDecl> readDecls(BytecodeProgram L) {
        Map<String, StructMemberReorderingDecl> out = new LinkedHashMap<>();
        StructMemberReorderingDecl cur = null;
        for (int i = 0; i < L.size(); i++) {
            BytecodeStatement l = L.get(i);
            if (isMn(l, "STRUCT_START") && l.size() == 2) {
                cur = new StructMemberReorderingDecl();
                cur.name = l.get(1).text;
                cur.start = i;
                cur.head.add(l);
            } else if (cur == null) {
                continue;
            } else if (isMn(l, "STRUCT_DECORATE")) {
                if (!(l.size() == 2 && l.get(1).text.equals("@pub"))) {
                    cur.plain = false;
                }
                cur.head.add(l);
            } else if (isMn(l, "STRUCT_MEMBER") && l.size() == 3) {
                String nm = l.get(1).text;
                String ty = l.get(2).text;
                if (nm.equals("___type")) {
                    cur.hasType = true;
                    cur.typeFirst = cur.entries.isEmpty();
                    cur.head.add(l);
                } else {
                    StructMemberReorderingEntry e = new StructMemberReorderingEntry(false, 0, nm, ty, l);
                    if (scalarSize(ty) < 0) {
                        cur.plain = false;
                    }
                    cur.entries.add(e);
                    cur.members.add(e);
                }
            } else if (isMn(l, "STRUCT_PADDING") && l.size() == 2) {
                long n;
                try {
                    n = Long.parseLong(l.get(1).text);
                } catch (NumberFormatException ex) {
                    cur.plain = false;
                    n = 0;
                }
                cur.entries.add(new StructMemberReorderingEntry(true, n, null, null, l));
            } else if (isMn(l, "STRUCT_END")) {
                cur.end = i;
                out.put(cur.name, cur);
                cur = null;
            } else {
                cur.plain = false;   // something we do not understand inside a declaration
            }
        }
        return out;
    }

    /** Class id of each struct, from the flat "Class" enum. */
    private static void readClassIds(BytecodeProgram L, Map<String, StructMemberReorderingDecl> decls) {
        for (BytecodeStatement l : L) {
            if (isMn(l, "ENUM") && l.size() >= 3 && l.get(1).text.equals("Class")) {
                for (int i = 2; i + 1 < l.size(); i += 2) {
                    StructMemberReorderingDecl d = decls.get(l.get(i).text);
                    if (d != null) {
                        try {
                            d.classId = Long.parseLong(l.get(i + 1).text);
                        } catch (NumberFormatException ex) {
                            d.classId = -1;
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- blockers

    /** Does the type text name struct s as its base, through an array suffix, mutability and storage prefixes? */
    private static boolean namesStruct(String text, String s) {
        String t = text;
        for (;;) {
            boolean cut = false;
            for (String p : new String[] {"owns_", "ref_", "raw_", "auto_", "static_", "some_", "mut_", "imut_", "indeterminate_", "atomic_"}) {
                if (t.startsWith(p)) {
                    t = t.substring(p.length());
                    cut = true;
                    break;
                }
            }
            if (!cut) {
                break;
            }
        }
        int br = t.indexOf('[');
        if (br > 0) {
            t = t.substring(0, br);
        }
        return t.equals(s);
    }

    private static boolean isRawTo(String text, String s) {
        if (!text.startsWith("raw_")) {
            return false;
        }
        return namesStruct(text, s);
    }

    private static void blockByUses(BytecodeProgram L, Map<String, StructMemberReorderingDecl> decls) {
        // struct used as a member of another struct (by value or array)
        for (StructMemberReorderingDecl d : decls.values()) {
            for (StructMemberReorderingEntry e : d.members) {
                for (StructMemberReorderingDecl o : decls.values()) {
                    if (namesStruct(e.type, o.name) && !e.type.startsWith("owns_") && !e.type.startsWith("ref_")
                            && !e.type.startsWith("auto_") && !e.type.startsWith("static_")) {
                        o.blocked = true;
                    }
                }
            }
            for (StructMemberReorderingEntry e : d.members) {
                for (StructMemberReorderingDecl o : decls.values()) {
                    if (namesStruct(e.type, o.name)) {
                        o.blocked = true;   // any use as a member type, pointer or not (a raw one is also caught below)
                    }
                }
            }
        }
        // functions that declare a hidden destination pointer
        Set<String> rvo = new HashSet<>();
        String fn = null;
        for (BytecodeStatement l : L) {
            if (isMn(l, "FUNC_START") && l.size() >= 2) {
                fn = l.get(1).text;
            } else if (isMn(l, "ARG") && l.size() == 3 && l.get(1).text.equals("$ret_dest") && fn != null) {
                rvo.add(fn);
            }
        }
        for (int i = 0; i < L.size(); i++) {
            BytecodeStatement l = L.get(i);
            if (l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE) {
                continue;
            }
            String m = l.get(0).text;
            boolean decl = m.startsWith("STRUCT_");
            if (decl) {
                continue;
            }
            for (StructMemberReorderingDecl d : decls.values()) {
                if (d.blocked) {
                    continue;
                }
                if ((m.equals("ALLOC_STATIC")) && lineNames(l, d.name)) {
                    d.blocked = true;
                    continue;
                }
                if ((m.equals("GLOBAL") || m.equals("ALLOC_STATIC")) && lineHasArrayOf(l, d.name)) {
                    d.blocked = true;
                    continue;
                }
                boolean raw = false;
                for (int k = 1; k < l.size(); k++) {
                    if (isRawTo(l.get(k).text, d.name)) {
                        raw = true;
                    }
                }
                if (!raw) {
                    continue;
                }
                boolean ok = false;
                if ((m.equals("ARG") || m.equals("PUSH")) && l.size() >= 2 && l.get(1).text.equals("$ret_dest")) {
                    ok = true;
                } else if (m.equals("RET")) {
                    ok = true;
                } else if ((m.equals("ADDR_OF") || (m.equals("POP") && l.size() >= 2 && l.get(1).text.equals("ARG0")))) {
                    // the address handed to a struct-returning callee: a CALL of one before the bracket closes
                    for (int j = i + 1; j < L.size() && j < i + 12; j++) {
                        BytecodeStatement n = L.get(j);
                        if (isMn(n, "CC_END")) {
                            break;
                        }
                        if (isMn(n, "CALL") && n.size() >= 2) {
                            ok = rvo.contains(n.get(1).text);
                            break;
                        }
                    }
                }
                if (!ok) {
                    d.blocked = true;
                }
            }
        }
    }

    private static boolean lineNames(BytecodeStatement l, String s) {
        for (int k = 1; k < l.size(); k++) {
            if (namesStruct(l.get(k).text, s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineHasArrayOf(BytecodeStatement l, String s) {
        for (int k = 1; k < l.size(); k++) {
            String t = l.get(k).text;
            if (t.indexOf('[') > 0 && namesStruct(t, s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------ construction sites

    private static StructMemberReorderingDecl classIdPush(BytecodeStatement l, Map<Long, StructMemberReorderingDecl> byId) {
        if (isMn(l, "PUSH") && l.size() == 3 && l.get(2).text.equals("imut_u64")) {
            try {
                return byId.get(Long.parseLong(l.get(1).text));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static final int MAX_UNIT = 64;

    private StructMemberReorderingSite parseSite(BytecodeProgram L, int i, StructMemberReorderingDecl d, Map<Long, StructMemberReorderingDecl> byId) {
        List<StructMemberReorderingEntry> order = d.entries;
        List<StructMemberReorderingSite> found = new ArrayList<>();
        walk(L, i + 1, order, d, byId, i, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * Backtracking search for the ways the literal's pushed values can be cut into the members of `order`, depth first, stopping
     * as soon as a second way is found. The choice points (where each member's value ends) are frames on an explicit stack.
     */
    private void walk(BytecodeProgram L, int j0, List<StructMemberReorderingEntry> order, StructMemberReorderingDecl d, Map<Long, StructMemberReorderingDecl> byId, int start, List<StructMemberReorderingSite> found) {
        List<StructMemberReorderingFrame> stack = new ArrayList<>();
        stack.add(new StructMemberReorderingFrame(j0, 0, 0, new ArrayList<>()));
        while (!stack.isEmpty()) {
            if (found.size() > 1) {
                return;
            }
            StructMemberReorderingFrame f = stack.get(stack.size() - 1);
            if (!f.entered) {
                f.entered = true;
                if (f.idx == order.size()) {
                    if (f.j < L.size()) {
                        BytecodeStatement t = L.get(f.j);
                        if (isMn(t, "ASSIGN") || isMn(t, "NEW") || classIdPush(t, byId) != null) {
                            StructMemberReorderingSite s = new StructMemberReorderingSite();
                            s.start = start;
                            s.end = f.j;
                            s.decl = d;
                            int k = 0;
                            for (StructMemberReorderingEntry e : order) {
                                if (!e.pad) {
                                    s.units.put(e.name, f.acc.get(k++));
                                }
                            }
                            found.add(s);
                        }
                    }
                    stack.remove(stack.size() - 1);
                    continue;
                }
                StructMemberReorderingEntry e = order.get(f.idx);
                if (e.pad) {
                    stack.remove(stack.size() - 1);
                    if (f.j < L.size() && isMn(L.get(f.j), "STACK_LOCK") && L.get(f.j).size() == 2
                            && L.get(f.j).get(1).text.equals(Long.toString(e.padBytes))) {
                        stack.add(new StructMemberReorderingFrame(f.j + 1, f.idx + 1, f.membersDone, f.acc));
                    }
                    continue;
                }
                f.depth = f.membersDone;
                f.unit = new BytecodeProgram();
                f.p = f.j;
            }
            StructMemberReorderingEntry e = order.get(f.idx);
            boolean descended = false;
            while (f.p < L.size() && f.unit.size() < MAX_UNIT) {
                BytecodeStatement l = L.get(f.p);
                int ef = effect(l);
                if (ef == Integer.MIN_VALUE) {
                    break;
                }
                if (ef < 0 && f.depth - f.membersDone < 2) {
                    break;   // a binary operator would eat the previous member's value
                }
                f.depth += ef;
                if (f.depth < f.membersDone + 1) {
                    break;
                }
                f.unit.add(l);
                f.p++;
                if (f.depth == f.membersDone + 1 && typeMatches(resultType(l), e.type)) {
                    List<BytecodeProgram> next = new ArrayList<>(f.acc);
                    next.add(new BytecodeProgram(f.unit));
                    stack.add(new StructMemberReorderingFrame(f.p, f.idx + 1, f.membersDone + 1, next));
                    descended = true;
                    break;
                }
            }
            if (descended) {
                continue;
            }
            stack.remove(stack.size() - 1);
        }
    }

    private static boolean typeMatches(String pushed, String member) {
        return stripMut(pushed).equals(stripMut(member));
    }
}
