package caspien.optimizer;

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
public class StructMemberReorderingPass implements OptimizationPass {

    private final boolean enabled;

    public StructMemberReorderingPass() {
        this(false);
    }

    public StructMemberReorderingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "struct-member-reordering";
    }

    // ---------------------------------------------------------------- model

    private static final class Entry {
        final boolean pad;
        final long padBytes;
        final String name;
        final String type;
        final List<BytecodeToken> line;   // the original declaration line

        Entry(boolean pad, long padBytes, String name, String type, List<BytecodeToken> line) {
            this.pad = pad;
            this.padBytes = padBytes;
            this.name = name;
            this.type = type;
            this.line = line;
        }
    }

    private static final class Decl {
        String name;
        int start;                       // index of STRUCT_START
        int end;                         // index of STRUCT_END
        List<List<BytecodeToken>> head = new ArrayList<>();   // STRUCT_START, STRUCT_DECORATE..., the ___type member
        List<Entry> entries = new ArrayList<>();              // everything after ___type, in order
        boolean hasType;
        boolean typeFirst;
        boolean plain = true;            // scalar members only, no odd decorators
        List<Entry> members = new ArrayList<>();
        long classId = -1;
        boolean blocked;
        List<Entry> newEntries;          // the reordered layout (members and padding)
    }

    private static final class Site {
        int start;
        int end;                         // exclusive
        Decl decl;
        Map<String, List<List<BytecodeToken>>> units = new HashMap<>();   // member name -> its lines
    }

    // ------------------------------------------------------------- helpers

    private final Set<String> pinnedEver = new HashSet<>();

    private static boolean isMn(List<BytecodeToken> l, String m) {
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
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "SAR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(java.util.List.of(
            "NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV", "NEG_FLOAT"));

    /** Stack effect of one member-expression line, or Integer.MIN_VALUE when the line is not allowed in one. */
    private static int effect(List<BytecodeToken> l) {
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

    private static String resultType(List<BytecodeToken> l) {
        return l.get(l.size() - 1).text;
    }

    // ------------------------------------------------------------------ run

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        // STRUCT_PIN lines: read, then always removed.
        Set<String> pinned = pinnedEver; // the pass instance lives across outer-loop rounds; the lines are stripped in round one, the pins must outlive them
        boolean stripped = false;
        List<List<BytecodeToken>> work = lines;
        for (List<BytecodeToken> l : lines) {
            if (isMn(l, "STRUCT_PIN")) {
                stripped = true;
                break;
            }
        }
        if (stripped) {
            work = new ArrayList<>(lines.size());
            for (List<BytecodeToken> l : lines) {
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

    private PassResult reorder(List<List<BytecodeToken>> L, Set<String> pinned) {
        Map<String, Decl> decls = readDecls(L);
        if (decls.isEmpty()) {
            return new PassResult(L, false);
        }
        readClassIds(L, decls);
        // structural eligibility
        for (Decl d : decls.values()) {
            if (!d.plain || !d.hasType || !d.typeFirst || d.members.size() < 2 || d.classId < 0 || pinned.contains(d.name)) {
                d.blocked = true;
            }
        }
        blockByUses(L, decls);
        // the new layout, and only when it is strictly smaller
        for (Decl d : decls.values()) {
            if (d.blocked) {
                continue;
            }
            long oldSize = 8;
            for (Entry e : d.entries) {
                oldSize += e.pad ? e.padBytes : scalarSize(e.type);
            }
            List<Entry> sorted = new ArrayList<>(d.members);
            sorted.sort((a, b) -> Integer.compare(scalarSize(b.type), scalarSize(a.type)));   // stable
            long sum = 8;
            for (Entry e : sorted) {
                sum += scalarSize(e.type);
            }
            long total = (sum + 7) / 8 * 8;
            if (total >= oldSize) {
                d.blocked = true;
                continue;
            }
            List<Entry> ne = new ArrayList<>(sorted);
            if (total > sum) {
                ne.add(new Entry(true, total - sum, null, null, null));
            }
            d.newEntries = ne;
        }
        Map<Long, Decl> byId = new HashMap<>();
        for (Decl d : decls.values()) {
            if (d.classId >= 0) {
                byId.put(d.classId, d);
            }
        }
        // construction sites: every classId push of a still-eligible struct must parse
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < L.size(); i++) {
            Decl d = classIdPush(L.get(i), byId);
            if (d == null || d.blocked) {
                continue;
            }
            Site s = parseSite(L, i, d, byId);
            if (s == null) {
                d.blocked = true;
            } else {
                sites.add(s);
            }
        }
        boolean any = false;
        for (Decl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                any = true;
            }
        }
        if (!any) {
            return new PassResult(L, false);
        }
        // rewrite
        Map<Integer, Site> siteAt = new HashMap<>();
        for (Site s : sites) {
            if (!s.decl.blocked) {
                siteAt.put(s.start, s);
            }
        }
        Map<Integer, Decl> declAt = new HashMap<>();
        for (Decl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                declAt.put(d.start, d);
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(L.size());
        for (int i = 0; i < L.size(); i++) {
            Decl d = declAt.get(i);
            if (d != null) {
                out.addAll(d.head);
                for (Entry e : d.newEntries) {
                    if (e.pad) {
                        BytecodeToken near = d.head.get(0).get(0);
                        List<BytecodeToken> pl = new ArrayList<>();
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
            Site s = siteAt.get(i);
            if (s != null) {
                out.add(L.get(i));   // the class id push
                for (Entry e : s.decl.newEntries) {
                    if (e.pad) {
                        List<BytecodeToken> pl = new ArrayList<>();
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

    private static Map<String, Decl> readDecls(List<List<BytecodeToken>> L) {
        Map<String, Decl> out = new LinkedHashMap<>();
        Decl cur = null;
        for (int i = 0; i < L.size(); i++) {
            List<BytecodeToken> l = L.get(i);
            if (isMn(l, "STRUCT_START") && l.size() == 2) {
                cur = new Decl();
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
                    Entry e = new Entry(false, 0, nm, ty, l);
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
                cur.entries.add(new Entry(true, n, null, null, l));
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
    private static void readClassIds(List<List<BytecodeToken>> L, Map<String, Decl> decls) {
        for (List<BytecodeToken> l : L) {
            if (isMn(l, "ENUM") && l.size() >= 3 && l.get(1).text.equals("Class")) {
                for (int i = 2; i + 1 < l.size(); i += 2) {
                    Decl d = decls.get(l.get(i).text);
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

    private static void blockByUses(List<List<BytecodeToken>> L, Map<String, Decl> decls) {
        // struct used as a member of another struct (by value or array)
        for (Decl d : decls.values()) {
            for (Entry e : d.members) {
                for (Decl o : decls.values()) {
                    if (namesStruct(e.type, o.name) && !e.type.startsWith("owns_") && !e.type.startsWith("ref_")
                            && !e.type.startsWith("auto_") && !e.type.startsWith("static_")) {
                        o.blocked = true;
                    }
                }
            }
            for (Entry e : d.members) {
                for (Decl o : decls.values()) {
                    if (namesStruct(e.type, o.name)) {
                        o.blocked = true;   // any use as a member type, pointer or not (a raw one is also caught below)
                    }
                }
            }
        }
        // functions that declare a hidden destination pointer
        Set<String> rvo = new HashSet<>();
        String fn = null;
        for (List<BytecodeToken> l : L) {
            if (isMn(l, "FUNC_START") && l.size() >= 2) {
                fn = l.get(1).text;
            } else if (isMn(l, "ARG") && l.size() == 3 && l.get(1).text.equals("$ret_dest") && fn != null) {
                rvo.add(fn);
            }
        }
        for (int i = 0; i < L.size(); i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE) {
                continue;
            }
            String m = l.get(0).text;
            boolean decl = m.startsWith("STRUCT_");
            if (decl) {
                continue;
            }
            for (Decl d : decls.values()) {
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
                        List<BytecodeToken> n = L.get(j);
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

    private static boolean lineNames(List<BytecodeToken> l, String s) {
        for (int k = 1; k < l.size(); k++) {
            if (namesStruct(l.get(k).text, s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineHasArrayOf(List<BytecodeToken> l, String s) {
        for (int k = 1; k < l.size(); k++) {
            String t = l.get(k).text;
            if (t.indexOf('[') > 0 && namesStruct(t, s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------ construction sites

    private static Decl classIdPush(List<BytecodeToken> l, Map<Long, Decl> byId) {
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

    private Site parseSite(List<List<BytecodeToken>> L, int i, Decl d, Map<Long, Decl> byId) {
        List<Entry> order = d.entries;
        List<Site> found = new ArrayList<>();
        walk(L, i + 1, order, 0, 0, new ArrayList<>(), d, byId, i, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private void walk(List<List<BytecodeToken>> L, int j, List<Entry> order, int idx, int membersDone,
            List<List<List<BytecodeToken>>> acc, Decl d, Map<Long, Decl> byId, int start, List<Site> found) {
        if (found.size() > 1) {
            return;
        }
        if (idx == order.size()) {
            if (j < L.size()) {
                List<BytecodeToken> t = L.get(j);
                if (isMn(t, "ASSIGN") || isMn(t, "NEW") || classIdPush(t, byId) != null) {
                    Site s = new Site();
                    s.start = start;
                    s.end = j;
                    s.decl = d;
                    int k = 0;
                    for (Entry e : order) {
                        if (!e.pad) {
                            s.units.put(e.name, acc.get(k++));
                        }
                    }
                    found.add(s);
                }
            }
            return;
        }
        Entry e = order.get(idx);
        if (e.pad) {
            if (j < L.size() && isMn(L.get(j), "STACK_LOCK") && L.get(j).size() == 2
                    && L.get(j).get(1).text.equals(Long.toString(e.padBytes))) {
                walk(L, j + 1, order, idx + 1, membersDone, acc, d, byId, start, found);
            }
            return;
        }
        int depth = membersDone;
        List<List<BytecodeToken>> unit = new ArrayList<>();
        for (int p = j; p < L.size() && unit.size() < MAX_UNIT; p++) {
            List<BytecodeToken> l = L.get(p);
            int ef = effect(l);
            if (ef == Integer.MIN_VALUE) {
                return;
            }
            if (ef < 0 && depth - membersDone < 2) {
                return;   // a binary operator would eat the previous member's value
            }
            depth += ef;
            if (depth < membersDone + 1) {
                return;
            }
            unit.add(l);
            if (depth == membersDone + 1 && typeMatches(resultType(l), e.type)) {
                List<List<List<BytecodeToken>>> next = new ArrayList<>(acc);
                next.add(new ArrayList<>(unit));
                walk(L, p + 1, order, idx + 1, membersDone + 1, next, d, byId, start, found);
                if (found.size() > 1) {
                    return;
                }
            }
        }
    }

    private static boolean typeMatches(String pushed, String member) {
        return stripMut(pushed).equals(stripMut(member));
    }
}
