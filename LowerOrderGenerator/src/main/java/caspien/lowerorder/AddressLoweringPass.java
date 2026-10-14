package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Last lowering stage: erases each named stack slot to its frame-relative offset and size ("PUSH imut_u64 x.y" ->
 * "PUSH 8 $32") and reduces most trailing type operands to byte sizes (or _INT/_FLOAT mnemonic splits). Runs once, after
 * every pass that can add ALLOCs (CloneGenerationPass, DropGlueGenerationPass), so offsets are final.
 * Per-mnemonic rewrite table, name resolution and the finished/untouched mnemonic lists:
 * see docs/AddressLoweringPass.md, "Mnemonic rewrites" and "Name resolution".
 */
public class AddressLoweringPass {

    private final CompilerConfig config;

    public AddressLoweringPass(CompilerConfig config) {
        this.config = config;
    }

    public PassResult run(BytecodeProgram lines) {
        StructTable structTable = StructTable.read(lines);
        AddressLoweringSizeCalculator sizes = new AddressLoweringSizeCalculator(structTable);
        EnumTable enumTable = EnumTable.read(lines);
        BytecodeProgram out = new BytecodeProgram();
        boolean changed = false;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            BytecodeStatement line = lines.get(i);
            if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                // Top-level decorator lines are dropped: metadata already consumed upstream (docs/AddressLoweringPass.md, "Dropped declarations").
                if (!line.isEmpty() && line.get(0).text.equals("STRUCT_DECORATE")) {
                    changed = true;
                    i++;
                    continue;
                }
                // STRUCT_START/STRUCT_MEMBER/STRUCT_END and ENUM lines are dropped: layouts and enum values were read up front into
                // structTable/enumTable and are already baked into the surviving instructions (docs/AddressLoweringPass.md, "Dropped declarations").
                if (!line.isEmpty() && (line.get(0).text.equals("STRUCT_START")
                        || line.get(0).text.equals("STRUCT_MEMBER") || line.get(0).text.equals("STRUCT_END")
                        || line.get(0).text.equals("ENUM"))) {
                    changed = true;
                    i++;
                    continue;
                }
                // A top-level GLOBAL declaration is a rewrite target of its own (ALLOC_STATIC is inside a function, handled in tryRewrite).
                BytecodeStatement staticRewrite = rewriteStaticOrGlobalDecl(line, sizes, enumTable);
                if (staticRewrite != null) {
                    out.add(staticRewrite);
                    changed = true;
                } else {
                    out.add(line);
                }
                i++;
                continue;
            }

            int start = i;
            int end = start;
            while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                end++;
            }
            end = Math.min(end, n - 1);

            // Assign every ALLOC in this function a size and a negative offset from the base pointer, in emission order (params first).
            // The cursor only grows more negative, so alignment rounds DOWN; any gap is absorbed into totalAllocSize.
            // See docs/AddressLoweringPass.md, "Frame layout".
            Map<String, Long> offsets = new HashMap<>();
            Map<String, String> localTypes = new LinkedHashMap<>();
            long cursor = 0;
            for (int k = start; k <= end; k++) {
                BytecodeStatement fl = lines.get(k);
                if (!fl.isEmpty() && fl.get(0).text.equals("ALLOC") && fl.size() >= 3) {
                    String varName = fl.get(1).text;
                    String varType = fl.get(2).text;
                    // The same name declared again in a sibling scope ("let key" as a u64 in one loop, as a u8 in the next) has one
                    // slot per NAME here, and every use of the name goes to the slot recorded last. A later, narrower declaration
                    // must not shrink it: the earlier scope's wider stores would then spill into the neighbouring slots (a loop
                    // counter, say). So a repeat reuses the existing slot when it is at least as big and as aligned, and takes a
                    // fresh, bigger one otherwise.
                    String earlierType = localTypes.get(varName);
                    if (earlierType != null && sizes.sizeOf(earlierType) >= sizes.sizeOf(varType)
                            && sizes.alignOf(earlierType) >= sizes.alignOf(varType)) {
                        localTypes.put(varName, varType);
                        continue;
                    }
                    long align = sizes.alignOf(varType);
                    long remainder = cursor % align; // Java's `%`: dividend's sign, so this is in (-align, 0] for cursor <= 0
                    if (remainder != 0) {
                        cursor -= (align + remainder); // move to the next multiple of `align` at or below the current cursor
                    }
                    cursor -= sizes.sizeOf(varType);
                    offsets.put(varName, cursor);
                    localTypes.put(varName, varType);
                }
            }
            // All ALLOCs of a function collapse into one reservation of the summed size (-cursor); ALLOC_STATIC/GLOBAL are not frame slots.
            long totalAllocSize = -cursor;
            // Round the frame UP to a multiple of 16 so rsp stays aligned after the prologue (never down: that could truncate a slot).
            long allocRemainder = totalAllocSize % 16;
            if (allocRemainder != 0) {
                totalAllocSize += 16 - allocRemainder;
            }

            CompilerConfigCallingConvention convention = resolveConvention(lines, start, end, config);

            // Resolve all PUSH ARGn/FARGn words up front: int and float words can interleave (see resolveArgWordSlots).
            Map<String, AddressLoweringArgWordSlot> argWordSlots = resolveArgWordSlots(lines, start, end, convention);

            // The preceding line in ORIGINAL (pre-rewrite) form: PUSH_FIELDNAME needs the struct type that an already-lowered
            // predecessor no longer carries.
            BytecodeStatement previousLine = null;
            boolean combinedAllocEmitted = false;
            Map<Integer, Integer> literalAssigns = findStructLiteralAssigns(lines, start, end, structTable);
            Map<Integer, Integer> arrayAssigns = findArrayLiteralAssigns(lines, start, end, sizes, structTable);
            for (int k = start; k <= end; k++) {
                BytecodeStatement fl = lines.get(k);
                // Every ALLOC line collapses into one combined "ALLOC totalAllocSize" emitted in place of the first; later ones are dropped.
                // previousLine still tracks the original ALLOC line (PUSH_FIELDNAME reads its type).
                if (!fl.isEmpty() && fl.get(0).text.equals("ALLOC") && fl.size() == 3) {
                    changed = true;
                    if (!combinedAllocEmitted) {
                        combinedAllocEmitted = true;
                        out.add(BytecodeStatement.fromText("ALLOC " + totalAllocSize, "<address-lowered>"));
                    }
                    previousLine = fl;
                    continue;
                }
                // "REGVAR name weight [f]" (from RegVarHintPass) -> "REGHINT offset size weight [f]"; an unknown name drops the hint.
                // RegisterFormPass consumes it; LowerOrderGenerator strips leftovers.
                if (!fl.isEmpty() && fl.get(0).text.equals("REGVAR") && (fl.size() == 3 || fl.size() == 4)) {
                    changed = true;
                    Long hintOff = offsets.get(fl.get(1).text);
                    String hintType = localTypes.get(fl.get(1).text);
                    if (hintOff != null && hintType != null) {
                        out.add(BytecodeStatement.fromText(
                                "REGHINT " + hintOff + " " + sizes.sizeOf(hintType) + " " + fl.get(2).text + (fl.size() == 4 ? " " + fl.get(3).text : ""), "<address-lowered>"));
                    }
                    continue;
                }
                // RETURNS is dropped: RET/RET_FLOAT carry the byte count (PUSH_RET keeps the full type at call sites).
                if (!fl.isEmpty() && fl.get(0).text.equals("RETURNS")) {
                    changed = true;
                    continue;
                }
                // FUNC_DECORATE/LOOP_DECORATE/FOR_DECORATE are dropped like STRUCT_DECORATE; previousLine is deliberately left unchanged.
                if (!fl.isEmpty() && (fl.get(0).text.equals("FUNC_DECORATE")
                        || fl.get(0).text.equals("LOOP_DECORATE") || fl.get(0).text.equals("FOR_DECORATE"))) {
                    changed = true;
                    continue;
                }
                // ADDR_OF is dropped as a no-op retag only for "ADDR_OF REF" on an owns variable and "ADDR_OF RAW" on a string literal.
                // Do not widen this to "any pointer-typed predecessor": "raw val" on a pointer-typed local needs the slot's real address.
                // See docs/AddressLoweringPass.md, "ADDR_OF". previousLine is still updated.
                if (!fl.isEmpty() && fl.get(0).text.equals("ADDR_OF") && fl.size() == 4 && previousLine != null
                        && !previousLine.isEmpty()
                        && isAlreadyCorrectPointerRetag(fl.get(1).text,
                                CanonicalType.parse(previousLine.get(previousLine.size() - 1).text).storage)) {
                    changed = true;
                    previousLine = fl;
                    continue;
                }
                BytecodeStatement rewritten = tryRewrite(fl, offsets, localTypes, sizes, structTable, enumTable,
                        convention, previousLine, argWordSlots);
                if (rewritten != null && literalAssigns.containsKey(k) && rewritten.size() == 4) {
                    // A stack struct literal's own top-level store: append the struct's physical layout (see the "NEW" rewrite
                    // for the token format), so Codegen can repack the pushed field words when the field values are not all
                    // plain pushes (see X86Backend.emitAssignRepack). Codegen ignores it on its packed fast path.
                    List<StructTableLayoutEntry> lit = structTable.layoutOf(CanonicalType.parse(fl.get(1).text).baseType);
                    if (lit != null) {
                        StringBuilder sb = new StringBuilder(fl.get(0).text);
                        for (int q = 1; q < 4; q++) {
                            sb.append(' ').append(rewritten.get(q).text);
                        }
                        sb.append(layoutTokens(lit, lines, literalAssigns.get(k), k, sizes, structTable, enumTable));
                        rewritten = BytecodeStatement.fromText(sb.toString(), "<address-lowered>");
                    }
                }
                if (rewritten != null && arrayAssigns.containsKey(k) && rewritten.size() == 4) {
                    String at = fl.get(1).text;
                    CanonicalType act = CanonicalType.parse(at);
                    String elemT = CanonicalType.fixedArrayElementTypeOf(act.baseType);
                    int arrN = CanonicalType.fixedArrayLengthOf(act.baseType);
                    List<Integer> units = splitValueUnits(lines, arrayAssigns.get(k), k, enumTable);
                    if (units != null && arrN > 0 && units.size() == arrN) {
                        boolean computed = false;
                        boolean wholeCopy = false;
                        for (int u : units) {
                            computed |= !lines.get(u).get(0).text.equals("PUSH");
                            wholeCopy |= lines.get(u).get(lines.get(u).size() - 1).text.equals(at);
                        }
                        if (computed && !wholeCopy) {
                            rewritten = BytecodeStatement.fromText(fl.get(0).text + " " + rewritten.get(1).text + " " + rewritten.get(2).text
                                    + " " + rewritten.get(3).text + " a" + sizes.sizeOf(elemT) + "x" + arrN, "<address-lowered>");
                        }
                    }
                }
                if (rewritten != null && fl.get(0).text.equals("NEW") && fl.size() == 2 && rewritten.size() > 2) {
                    // Heap struct literal: the same element-wise array handling as for a stack literal (see the ASSIGN case above).
                    int runStart = findNewRunStart(lines, start, k, structTable, enumTable);
                    List<StructTableLayoutEntry> nl = structTable.layoutOf(CanonicalType.parse(fl.get(1).text).baseType);
                    if (runStart >= 0 && nl != null) {
                        rewritten = BytecodeStatement.fromText("NEW " + rewritten.get(1).text
                                + layoutTokens(nl, lines, runStart, k, sizes, structTable, enumTable), "<address-lowered>");
                    }
                }
                if (rewritten != null) {
                    out.add(rewritten);
                    changed = true;
                } else {
                    out.add(fl);
                }
                previousLine = fl;
            }

            i = end + 1;
        }

        return new PassResult(out, changed);
    }

    /**
     * Indices (within [start, end]) of the ASSIGN lines that store a stack struct literal, each mapped to its literal's first line: the literal's own sequence is
     * "ADDR v T", "PUSH <digits> imut_u64" (the struct's ___type id), the member values, then "ASSIGN T T T". A whole-value copy
     * ("ADDR v T", "PUSH w T", "ASSIGN T T T") has no such type-id push and is not matched.
     */
    private static Map<Integer, Integer> findStructLiteralAssigns(BytecodeProgram lines, int start, int end, StructTable structTable) {
        Map<Integer, Integer> result = new HashMap<>();   // ASSIGN line index -> index of the literal's own "ADDR v T" line
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> pendingStart = new java.util.ArrayDeque<>();
        Map<String, Integer> untypedAddr = new HashMap<>();   // struct type -> latest "ADDR v T" of an '@untyped' struct (no class id push)
        for (int k = start; k <= end; k++) {
            BytecodeStatement l = lines.get(k);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR") && l.size() == 3 && k + 1 <= end) {
                BytecodeStatement nx = lines.get(k + 1);
                if (nx.size() == 3 && nx.get(0).text.equals("PUSH") && nx.get(2).text.equals("imut_u64")
                        && nx.get(1).text.matches("[0-9]+")) {
                    CanonicalType ct = CanonicalType.parse(l.get(2).text);
                    if (ct.storage == null && structTable.layoutOf(ct.baseType) != null) {
                        pending.push(l.get(2).text);
                        pendingStart.push(k);
                    }
                } else {
                    // An '@untyped' struct has no hidden class id, so its literal starts straight with the first member's value.
                    // Treat "ADDR v T" followed by anything but a single whole-value push of T as such a literal; the matching
                    // ASSIGN below confirms it (a whole-value copy is "ADDR v T / PUSH w T / ASSIGN T T T").
                    CanonicalType ct = CanonicalType.parse(l.get(2).text);
                    List<StructTableLayoutEntry> lay = ct.storage == null ? structTable.layoutOf(ct.baseType) : null;
                    if (lay != null && !lay.isEmpty() && lay.get(0).member != null && !lay.get(0).member.name.equals("___type")) {
                        untypedAddr.put(l.get(2).text, k);
                    }
                }
            } else if (m.equals("PUSH") && l.size() == 3 && l.get(1).text.equals("$ret_dest") && l.get(2).text.startsWith("raw_") && k + 1 <= end) {
                // Return value optimisation: the literal is stored through the hidden destination pointer
                // ("PUSH $ret_dest raw_mut_S / classId / members / ASSIGN mut_S indeterminate_S mut_S"). The pointer value sits where an
                // "ADDR v" would, so the store is the same repack.
                BytecodeStatement nx = lines.get(k + 1);
                // (the type id is a literal, or -- in a generated clone routine -- read off the source copy's own `.___type`)
                if (nx.size() == 3 && nx.get(0).text.equals("PUSH") && nx.get(2).text.equals("imut_u64")
                        && (nx.get(1).text.matches("[0-9]+") || nx.get(1).text.endsWith(".___type"))) {
                    CanonicalType ct = CanonicalType.parse(l.get(2).text.substring(4));
                    if (ct.storage == null && structTable.layoutOf(ct.baseType) != null) {
                        pending.push("RVO:" + l.get(2).text.substring(4));
                        pendingStart.push(k);
                    }
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && pending.peek().startsWith("RVO:")) {
                String t = pending.peek().substring(4);
                if (l.get(1).text.equals(t) && l.get(3).text.equals(t)) {
                    pending.pop();
                    result.put(k, pendingStart.pop());
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && l.get(1).text.equals(pending.peek()) && l.get(2).text.equals(pending.peek()) && l.get(3).text.equals(pending.peek())) {
                pending.pop();
                result.put(k, pendingStart.pop());
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && l.get(1).text.equals(l.get(2).text)
                    && l.get(2).text.equals(l.get(3).text) && untypedAddr.containsKey(l.get(1).text)) {
                // an '@untyped' struct literal's store -- unless it is a whole-value copy (one push of the struct itself)
                int startIdx = untypedAddr.remove(l.get(1).text);
                boolean wholeCopy = k == startIdx + 2 && lines.get(startIdx + 1).get(0).text.equals("PUSH")
                        && lines.get(startIdx + 1).get(lines.get(startIdx + 1).size() - 1).text.equals(l.get(1).text);
                if (!wholeCopy && k > startIdx + 1) {
                    result.put(k, startIdx);
                }
            }
        }
        return result;
    }

    /**
     * The ASSIGN lines that store a stack array literal of scalar elements narrower than 8 bytes (`let a: u16[3] = [c, c + 1, 7]`), mapped to the
     * literal's "ADDR v T" line. Each element is pushed as its own whole word; when one of them is computed Codegen cannot treat the run as a packed
     * image, so the ASSIGN gets an "a<elemBytes>x<n>" token (same repack as an array member of a struct literal).
     */
    private static Map<Integer, Integer> findArrayLiteralAssigns(BytecodeProgram lines, int start, int end, AddressLoweringSizeCalculator sizes,
            StructTable structTable) {
        Map<Integer, Integer> result = new HashMap<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> pendingStart = new java.util.ArrayDeque<>();
        for (int k = start; k <= end; k++) {
            BytecodeStatement l = lines.get(k);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR") && l.size() == 3) {
                CanonicalType ct = CanonicalType.parse(l.get(2).text);
                String elem = ct.storage == null ? CanonicalType.fixedArrayElementTypeOf(ct.baseType) : null;
                if (elem != null && elem.indexOf('[') < 0 && structTable.layoutOf(elem) == null && sizes.sizeOf(elem) < 8) {
                    pending.push(l.get(2).text);
                    pendingStart.push(k);
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && l.get(1).text.equals(pending.peek()) && l.get(2).text.equals(pending.peek()) && l.get(3).text.equals(pending.peek())) {
                pending.pop();
                result.put(k, pendingStart.pop());
            }
        }
        return result;
    }

    private static final Set<String> VALUE_BINARY_OPS = new HashSet<>(java.util.Arrays.asList(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "ROTL", "ROTR", "BITS_OR", "BITS_AND", "BITS_XOR", "BITS_LEFT", "BITS_RIGHT",
            "AND", "OR", "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "LOOKUP"));
    private static final Set<String> VALUE_UNARY_OPS = new HashSet<>(java.util.Arrays.asList("NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV"));

    /**
     * Splits the postfix lines strictly between `from` and `to` (a struct literal's member values, its type-id push first) into their top-level
     * values, by simulating the operand stack: pushes add an entry, binary operators merge two, calls leave one value at PUSH_RET, a nested heap
     * literal collapses at its NEW. Returns, for each top-level value in order, the index of the line that produced it (its last token is the
     * value's type); null if any line is not understood, in which case the caller falls back to counting.
     */
    private static List<Integer> splitValueUnits(BytecodeProgram lines, int from, int to, EnumTable enumTable) {
        java.util.ArrayList<int[]> stack = new java.util.ArrayList<>();   // {producer line, 1 if a struct type-id push}
        for (int j = from + 1; j < to; j++) {
            BytecodeStatement l = lines.get(j);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.startsWith("@")) {
                continue;   // label
            }
            switch (m) {
                case "PUSH":
                    stack.add(new int[] {j, (l.size() == 3 && l.get(2).text.equals("imut_u64") && l.get(1).text.matches("[0-9]+")) ? 1 : 0});
                    break;
                case "PUSH_LABEL":
                case "ADDR":
                case "PUSH_RET":
                    stack.add(new int[] {j, 0});
                    break;
                case "DUP_TOP":   // the out-of-memory check after a nested `new`: DUP_TOP / PUSH null / EQ / CMP / JMP, then GT_REGISTER
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.add(new int[] {j, 0});
                    break;
                case "CMP":
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    break;
                case "JMP":
                case "GT_REGISTER":
                case "STACK_LOCK":
                case "CC_START":
                case "CC_END":
                case "CALL":
                case "RECURSIVE_CALL":
                case "EXTERN_CALL":
                case "VARARGS_XMM_COUNT":
                    break;
                case "POP":
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    break;
                case "ASSIGN":
                case "ATOMIC_ASSIGN":
                    if (stack.size() < 2) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    stack.remove(stack.size() - 1);
                    break;
                case "NEW": {
                    // The nested literal starts at the nearest type-id push carrying this struct's own class id (an inline struct literal, e.g. an
                    // array element, has its own type-id push inside it and must not be taken for the start).
                    Long wanted = l.size() >= 2 ? classIdOf(CanonicalType.parse(l.get(1).text).baseType, enumTable) : null;
                    int q = stack.size() - 1;
                    while (q >= 0 && (stack.get(q)[1] == 0 || (wanted != null && !typeIdValue(lines.get(stack.get(q)[0])).equals(wanted)))) {
                        q--;
                    }
                    if (q < 0) {
                        return null;
                    }
                    while (stack.size() > q) {
                        stack.remove(stack.size() - 1);
                    }
                    stack.add(new int[] {j, 0});
                    break;
                }
                default:
                    if (VALUE_BINARY_OPS.contains(m) && l.size() == 4 && stack.size() >= 2) {
                        stack.remove(stack.size() - 1);
                        stack.remove(stack.size() - 1);
                        stack.add(new int[] {j, 0});
                    } else if (VALUE_UNARY_OPS.contains(m) && !stack.isEmpty()) {
                        stack.set(stack.size() - 1, new int[] {j, 0});
                    } else {
                        return null;
                    }
            }
        }
        List<Integer> units = new ArrayList<>();
        for (int[] e : stack) {
            units.add(e[0]);
        }
        return units;
    }

    /**
     * The layout descriptor tokens (each preceded by a space): "m<bytes>" member, "p<bytes>" padding, "a<elemBytes>x<n>" for a fixed array of
     * scalars narrower than 8 bytes that this literal writes element by element (n whole 8-byte words on the stack, one per element).
     * An array taken from an existing variable is pushed as one block instead (ceil(size/8) words) and stays "m<bytes>".
     *
     * Which of the two each array member is comes from splitting the literal's values (splitValueUnits): a member's value is a block when
     * the line that produced it has an array type, otherwise it is n element values. If the lines cannot be split, it falls back to counting
     * array-typed value lines; that is only decisive when no array type is shared by two members, otherwise the literal is rejected.
     */
    private static String layoutTokens(List<StructTableLayoutEntry> layout, BytecodeProgram lines, int from, int to,
            AddressLoweringSizeCalculator sizes, StructTable structTable, EnumTable enumTable) {
        List<Integer> units = splitValueUnits(lines, from, to, enumTable);
        StringBuilder sb = new StringBuilder();
        if (units != null) {
            List<String> tokens = new ArrayList<>();
            int[] u = {0};
            if (layoutFromUnits(layout, lines, units, u, tokens, sizes, structTable) && u[0] == units.size()) {
                for (String tk : tokens) {
                    sb.append(' ').append(tk);
                }
                return sb.toString();
            }
        }
        Map<String, Integer> arrayValueLines = countArrayValueLines(lines, from, to);
        for (StructTableLayoutEntry entry : layout) {
            if (entry.member == null) {
                sb.append(" p").append(entry.paddingBytes);
                continue;
            }
            String elementwise = elementwiseArrayToken(entry.member.canonicalType, layout, arrayValueLines, sizes, structTable);
            sb.append(' ').append(elementwise != null ? elementwise : "m" + sizes.sizeOf(entry.member.canonicalType));
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern ARRAY_TYPE = java.util.regex.Pattern.compile("^(.+)\\[([0-9]+)\\]$");

    /**
     * Appends the descriptor tokens for one struct layout, consuming the literal's top-level value units (u[0] is the next unit). Returns false
     * if the units do not fit the layout (the caller then falls back to counting).
     *
     * A plain (no storage) member is one value of type `base` -- a scalar, a fixed array or an inline struct -- as it sits in the literal's units.
     * A value whose producer already has exactly this type is one whole block (a variable): "m<size>". Otherwise an array is its elements one
     * after another (a narrow scalar array is the single token "a<elemBytes>x<n>", n words) and a struct literal is its own layout (type id,
     * members, padding), nested to any depth. Worked with an explicit to-do stack, front of the work first, so no recursion.
     */
    private static boolean layoutFromUnits(List<StructTableLayoutEntry> layout, BytecodeProgram lines, List<Integer> units, int[] u,
            List<String> tokens, AddressLoweringSizeCalculator sizes, StructTable structTable) {
        final int LAYOUT = 0;  // {kind, layout, next entry index}
        final int VALUE = 1;   // {kind, base type}
        final int REPEAT = 2;  // {kind, element type, repetitions left}
        List<Object[]> todo = new ArrayList<>();
        todo.add(new Object[] {LAYOUT, layout, 0});
        while (!todo.isEmpty()) {
            Object[] task = todo.remove(todo.size() - 1);
            int kind = (Integer) task[0];
            if (kind == LAYOUT) {
                @SuppressWarnings("unchecked")
                List<StructTableLayoutEntry> entries = (List<StructTableLayoutEntry>) task[1];
                int i = (Integer) task[2];
                while (i < entries.size()) {
                    StructTableLayoutEntry entry = entries.get(i++);
                    if (entry.member == null) {
                        tokens.add("p" + entry.paddingBytes);
                        continue;
                    }
                    CanonicalType ct = CanonicalType.parse(entry.member.canonicalType);
                    if (ct.storage != null || (!ARRAY_TYPE.matcher(ct.baseType).matches() && structTable.layoutOf(ct.baseType) == null)) {
                        if (u[0] >= units.size()) {
                            return false;
                        }
                        tokens.add("m" + sizes.sizeOf(entry.member.canonicalType));
                        u[0]++;
                        continue;
                    }
                    todo.add(new Object[] {LAYOUT, entries, i}); // the rest of this layout, after the value below
                    todo.add(new Object[] {VALUE, ct.baseType});
                    break;
                }
            } else if (kind == REPEAT) {
                long left = (Long) task[2];
                if (left > 0) {
                    todo.add(new Object[] {REPEAT, task[1], left - 1});
                    todo.add(new Object[] {VALUE, task[1]});
                }
            } else {
                String base = (String) task[1];
                if (u[0] >= units.size()) {
                    return false;
                }
                BytecodeStatement producer = lines.get(units.get(u[0]));
                String last = producer.get(producer.size() - 1).text;
                boolean isArray = ARRAY_TYPE.matcher(base).matches();
                List<StructTableLayoutEntry> structLayout = isArray ? null : structTable.layoutOf(base);
                if ((isArray || structLayout != null) && CanonicalType.parse(last).baseType.equals(base)) {
                    tokens.add("m" + sizes.sizeOf("mut_" + base));
                    u[0]++;
                } else if (structLayout != null) {
                    todo.add(new Object[] {LAYOUT, structLayout, 0});
                } else if (isArray) {
                    java.util.regex.Matcher mt = ARRAY_TYPE.matcher(base);
                    mt.matches();
                    String elem = mt.group(1);
                    long n = Long.parseLong(mt.group(2));
                    boolean nested = ARRAY_TYPE.matcher(elem).matches() || structTable.layoutOf(elem) != null;
                    if (!nested) {
                        String narrow = narrowElementToken(elem, n, sizes, structTable);
                        tokens.add(narrow != null ? narrow : "m" + sizes.sizeOf("mut_" + base));
                        u[0] += (int) n;
                        if (u[0] > units.size()) {
                            return false;
                        }
                    } else {
                        todo.add(new Object[] {REPEAT, elem, n});
                    }
                } else {
                    tokens.add("m" + sizes.sizeOf("mut_" + base));
                    u[0]++;
                }
            }
        }
        return true;
    }

    /** "a<elemBytes>x<n>" if `elem` is a plain scalar type narrower than 8 bytes, else null. */
    private static String narrowElementToken(String elem, long n, AddressLoweringSizeCalculator sizes, StructTable structTable) {
        if (!elem.matches("[A-Za-z0-9_]+") || structTable.layoutOf(elem) != null || n <= 0) {
            return null;
        }
        long e = sizes.sizeOf("mut_" + elem);
        return e > 0 && e < 8 ? "a" + e + "x" + n : null;
    }

    /**
     * Index of the line just before a heap struct literal's own type-id push ("PUSH <digits> imut_u64") for the "NEW" at `newIdx`, walking
     * back and skipping nested literals (each nested "NEW" has its own type-id push); -1 if it is not found within [start, newIdx).
     */
    private static int findNewRunStart(BytecodeProgram lines, int start, int newIdx, StructTable structTable, EnumTable enumTable) {
        String self = lines.get(newIdx).size() >= 2 ? CanonicalType.parse(lines.get(newIdx).get(1).text).baseType : null;
        Long wanted = self == null ? null : classIdOf(self, enumTable);
        int depth = 1;
        for (int j = newIdx - 1; j >= start; j--) {
            BytecodeStatement l = lines.get(j);
            if (l.isEmpty()) {
                continue;
            }
            if (l.get(0).text.equals("NEW")) {
                // with a known class id only a nested literal of the same struct can own a type-id push that matches; otherwise every NEW counts
                if (wanted == null || (l.size() >= 2 && self.equals(CanonicalType.parse(l.get(1).text).baseType))) {
                    depth++;
                }
            } else if (l.size() == 3 && l.get(0).text.equals("PUSH") && l.get(2).text.equals("imut_u64") && l.get(1).text.matches("[0-9]+")) {
                if (wanted != null && !typeIdValue(l).equals(wanted)) {
                    continue;   // the type-id push of an inline struct literal (an array element, an inline member), not this literal's own
                }
                depth--;
                if (depth == 0) {
                    return j - 1;   // the value-lines range starts after this line; j-1 keeps the exclusive lower bound below the push itself
                }
            }
        }
        return -1;
    }

    /** The struct's class id (the value of its `___type` push), or null if the Class enum does not list it. */
    private static Long classIdOf(String structBaseType, EnumTable enumTable) {
        return enumTable == null ? null : enumTable.variantValue("Class", structBaseType);
    }

    private static Long typeIdValue(BytecodeStatement pushLine) {
        try {
            return pushLine.size() >= 2 ? Long.valueOf(pushLine.get(1).text) : Long.valueOf(-1);
        } catch (NumberFormatException e) {
            return Long.valueOf(-1);
        }
    }

    /** Number of lines in (from, to) whose last token is an array type, keyed by that array's base type text (a whole array value pushed as one block). */
    private static Map<String, Integer> countArrayValueLines(BytecodeProgram lines, int from, int to) {
        Map<String, Integer> counts = new HashMap<>();
        for (int j = from + 1; j < to; j++) {
            BytecodeStatement l = lines.get(j);
            if (l.size() < 2) {
                continue;
            }
            String last = l.get(l.size() - 1).text;
            if (last.endsWith("]")) {
                counts.merge(CanonicalType.parse(last).baseType, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Descriptor token "a<elemBytes>x<n>" for a narrow-scalar fixed-array member written element by element (n words); null otherwise.
     * Fallback when the literal's values cannot be split; mixed shapes among same-typed members are rejected.
     * See docs/AddressLoweringPass.md, "NEW layout descriptor".
     */
    private static String elementwiseArrayToken(String memberType, List<StructTableLayoutEntry> layout, Map<String, Integer> arrayValueLines,
            AddressLoweringSizeCalculator sizes, StructTable structTable) {
        CanonicalType ct = CanonicalType.parse(memberType);
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^([A-Za-z0-9_]+)\\[([0-9]+)\\]$").matcher(ct.baseType);
        if (ct.storage != null || !mt.matches() || structTable.layoutOf(mt.group(1)) != null) {
            return null;
        }
        long elem = sizes.sizeOf("mut_" + mt.group(1));
        long n = Long.parseLong(mt.group(2));
        if (elem >= 8 || elem <= 0 || n <= 0) {
            return null;
        }
        int blockValues = arrayValueLines.getOrDefault(ct.baseType, 0);
        int sameType = 0;
        for (StructTableLayoutEntry e : layout) {
            if (e.member != null && CanonicalType.parse(e.member.canonicalType).baseType.equals(ct.baseType)) {
                sameType++;
            }
        }
        if (blockValues > 0 && sameType > 1) {
            throw new RuntimeException("cannot tell how the array members of type " + memberType + " in a struct literal are written (some as element lists, some taken"
                    + " from a variable) -- write them all the same way");
        }
        return blockValues == 0 ? "a" + elem + "x" + n : null;
    }

    /**
     * This function's calling convention: its "FUNC_DECORATE @call_convention(name)" line, else the config default (same rule as
     * TypeChecker.resolveCallConvention). See docs/AddressLoweringPass.md, "Calling convention and parameter words".
     */
    private CompilerConfigCallingConvention resolveConvention(BytecodeProgram lines, int start, int end,
            CompilerConfig config) {
        String name = null;
        for (int k = start; k <= end && name == null; k++) {
            BytecodeStatement fl = lines.get(k);
            if (fl.isEmpty() || !fl.get(0).text.equals("FUNC_DECORATE") || fl.size() < 2) {
                continue;
            }
            String decorator = fl.get(1).text;
            String prefix = "@call_convention(";
            if (decorator.startsWith(prefix) && decorator.endsWith(")")) {
                name = unquoteOrBare(decorator.substring(prefix.length(), decorator.length() - 1));
            }
        }
        if (name == null) {
            name = config.defaultConvention;
        }
        return config.callingConventions.get(name);
    }

    /** s8/s16/s32/s64: the signed integer base types (a signed compare/divide is a different machine operation). */
    private static boolean isSignedIntBaseType(String baseType) {
        return baseType.equals("s8") || baseType.equals("s16") || baseType.equals("s32") || baseType.equals("s64");
    }

    /** f32/f64 only: decides RET vs RET_FLOAT, PUSH_RET_INT/_FLOAT and the _INT/_FLOAT compares. */
    private static boolean isFloatBaseType(String baseType) {
        return "f32".equals(baseType) || "f64".equals(baseType);
    }

    /**
     * "LOOKUP_ARRAY" (fixed array, string, unsafe dynarray: base + index*width), "LOOKUP_DYN" (safe dynarray: extra header offset) or null
     * (line left untouched). Classifies the base type, storage ignored. See docs/AddressLoweringPass.md, "LOOKUP addressing shapes".
     */
    private static String lookupMnemonicFor(String targetType) {
        String baseType = CanonicalType.parse(targetType).baseType;
        if (baseType.equals("string") || CanonicalType.fixedArrayElementTypeOf(baseType) != null
                || CanonicalType.unsafeDynArrayElementTypeOf(baseType) != null) {
            return "LOOKUP_ARRAY";
        }
        if (CanonicalType.dynArrayElementTypeOf(baseType) != null) {
            return "LOOKUP_DYN";
        }
        return null;
    }

    private static String unquoteOrBare(String text) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** Parses "ARGn" into n, or -1 (hand-scanned). Parameter-loading "PUSH ARGn" only; call-site "POP ARGn" never comes here. */
    private static int parseArgIndex(String token) {
        if (!token.startsWith("ARG") || token.length() <= 3) {
            return -1;
        }
        for (int k = 3; k < token.length(); k++) {
            char c = token.charAt(k);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(token.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The float-bank counterpart of `parseArgIndex` -- "FARG" followed by one or more digits, `-1` otherwise. Never collides with `parseArgIndex` itself: "FARG3" doesn't start with "ARG" (it starts with "F"), so a token is recognized by at most one of the two. */
    /** Float-bank counterpart of parseArgIndex ("FARGn"); a token matches at most one of the two. */
    private static int parseFargIndex(String token) {
        if (!token.startsWith("FARG") || token.length() <= 4) {
            return -1;
        }
        for (int k = 4; k < token.length(); k++) {
            char c = token.charAt(k);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(token.substring(4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Resolves every parameter-loading ARGn/FARGn word of one function into a register index or stack offset, keyed by token text.
     * Needs the whole list because int and float words interleave and overflow words share one stack sequence.
     * See docs/AddressLoweringPass.md, "Calling convention and parameter words".
     */
    private Map<String, AddressLoweringArgWordSlot> resolveArgWordSlots(BytecodeProgram lines, int start, int end,
            CompilerConfigCallingConvention convention) {
        Map<String, AddressLoweringArgWordSlot> slots = new HashMap<>();
        int intCount = convention.argumentRegisters.size();
        int floatCount = convention.argumentRegistersFloat.size();
        boolean shared = convention.sharedArgumentPosition;
        int sharedIdx = 0;
        int intIdx = 0;
        int floatIdx = 0;
        int stackWordsSoFar = 0;
        for (int k = start; k <= end; k++) {
            BytecodeStatement fl = lines.get(k);
            if (fl.size() != 3 || !fl.get(0).text.equals("PUSH")) {
                continue;
            }
            String token = fl.get(1).text;
            boolean isFloatWord = parseFargIndex(token) >= 0;
            boolean isIntWord = !isFloatWord && parseArgIndex(token) >= 0;
            if (!isFloatWord && !isIntWord) {
                continue;
            }
            int idx;
            if (shared) {
                idx = sharedIdx;
                sharedIdx++;
            } else if (isFloatWord) {
                idx = floatIdx;
                floatIdx++;
            } else {
                idx = intIdx;
                intIdx++;
            }
            int cutoff = isFloatWord ? floatCount : intCount;
            if (idx < cutoff) {
                slots.put(token, AddressLoweringArgWordSlot.register(idx));
            } else {
                long offset = 16L + convention.shadowStack + (long) stackWordsSoFar * 8;
                slots.put(token, AddressLoweringArgWordSlot.stack(offset));
                stackWordsSoFar++;
            }
        }
        return slots;
    }

    /**
     * "ALLOC_STATIC|GLOBAL name type [value]" -> "... name size [value]"; the name is left alone (a real symbol). Standalone because a
     * top-level GLOBAL sits outside every function and never reaches tryRewrite; the value goes through resolveLiteralOrEnumValue.
     * See docs/AddressLoweringPass.md, "Static and global declarations".
     */
    private static BytecodeStatement rewriteStaticOrGlobalDecl(BytecodeStatement line, AddressLoweringSizeCalculator sizes,
            EnumTable enumTable) {
        if (line.isEmpty()) {
            return null;
        }
        String mnemonic = line.get(0).text;
        if (!(mnemonic.equals("ALLOC_STATIC") || mnemonic.equals("GLOBAL"))
                || (line.size() != 3 && line.size() != 4)) {
            return null;
        }
        String staticName = line.get(1).text;
        long size = sizes.sizeOf(line.get(2).text);
        String rewritten = mnemonic + " " + staticName + " " + size
                + (line.size() == 4 ? " " + resolveLiteralOrEnumValue(line.get(3).text, enumTable) : "");
        return BytecodeStatement.fromText(rewritten, "<address-lowered>");
    }

    /**
     * The only two ADDR_OF shapes that are already correct retags and may be dropped: REF of an "owns" value, RAW of a "static" string literal.
     * A broader "any storage" test caused a real bug (see the call site). See docs/AddressLoweringPass.md, "Pointer retags".
     */
    private static boolean isAlreadyCorrectPointerRetag(String opText, String precedingStorage) {
        if ("REF".equals(opText)) {
            return "owns".equals(precedingStorage);
        }
        if ("RAW".equals(opText)) {
            return "static".equals(precedingStorage);
        }
        return false;
    }

    /**
     * Rewrites one in-function line by erasing type operands to byte sizes (and splitting mnemonics where the runtime meaning differs);
     * null leaves the line alone. See docs/AddressLoweringPass.md, "Per-mnemonic rewrites".
     */
    private BytecodeStatement tryRewrite(BytecodeStatement line, Map<String, Long> offsets,
            Map<String, String> localTypes, AddressLoweringSizeCalculator sizes, StructTable structTable, EnumTable enumTable,
            CompilerConfigCallingConvention convention, BytecodeStatement previousLine,
            Map<String, AddressLoweringArgWordSlot> argWordSlots) {
        if (line.isEmpty()) {
            return null;
        }
        String mnemonic = line.get(0).text;

        // ATOMIC_ASSIGN: same size erasure as ASSIGN; the mnemonic is kept so codegen can tell it apart.
        if ((mnemonic.equals("ASSIGN") || mnemonic.equals("ATOMIC_ASSIGN")) && line.size() == 4) {
            StringBuilder sb = new StringBuilder(mnemonic);
            for (int k = 1; k < 4; k++) {
                sb.append(' ').append(sizes.sizeOf(line.get(k).text));
            }
            return BytecodeStatement.fromText(sb.toString(), "<address-lowered>");
        }

        // RET type -> RET size / RET_FLOAT size (integer vs float return register).
        if (mnemonic.equals("RET") && line.size() == 2) {
            String retType = line.get(1).text;
            long size = sizes.sizeOf(retType);
            String outMnemonic = isFloatBaseType(CanonicalType.parse(retType).baseType) ? "RET_FLOAT" : "RET";
            return BytecodeStatement.fromText(outMnemonic + " " + size, "<address-lowered>");
        }

        // PUSH_RET type -> PUSH_RET_INT|PUSH_RET_FLOAT size (caller-side mirror of RET; never emitted for void calls).
        if (mnemonic.equals("PUSH_RET") && line.size() == 2) {
            String retType = line.get(1).text;
            long size = sizes.sizeOf(retType);
            String suffix = isFloatBaseType(CanonicalType.parse(retType).baseType) ? "_FLOAT" : "_INT";
            return BytecodeStatement.fromText("PUSH_RET" + suffix + " " + size, "<address-lowered>");
        }

        // NEW TypeName -> NEW size [layout tokens] (see docs/AddressLoweringPass.md, "NEW layout descriptor").
        if (mnemonic.equals("NEW") && line.size() == 2) {
            long size = sizes.sizeOf(line.get(1).text);
            // Layout descriptor: "m<bytes>" per member, "p<bytes>" per padding gap; used by codegen only when the field pushes are not one clean run.
            StringBuilder lowered = new StringBuilder("NEW ").append(size);
            CanonicalType newType = CanonicalType.parse(line.get(1).text);
            List<StructTableLayoutEntry> newLayout = newType.storage == null ? structTable.layoutOf(newType.baseType) : null;
            if (newLayout != null) {
                for (StructTableLayoutEntry entry : newLayout) {
                    lowered.append(' ').append(entry.member != null
                            ? "m" + sizes.sizeOf(entry.member.canonicalType)
                            : "p" + entry.paddingBytes);
                }
            }
            return BytecodeStatement.fromText(lowered.toString(), "<address-lowered>");
        }

        // NEW_UDYN typeText count -> NEW_UDYN count*elemSize (headerless, so size alone is the contract).
        if (mnemonic.equals("NEW_UDYN") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.unsafeDynArrayElementTypeOf(baseType);
            long elementSize = sizes.sizeOf(elementBaseType);
            long count = Long.parseLong(line.get(2).text);
            long size = elementSize * count;
            return BytecodeStatement.fromText("NEW_UDYN " + size, "<address-lowered>");
        }

        // NEW_DYN typeText count -> NEW_DYN size count; size excludes the length header (added by the allocator); count stays on the line.
        if (mnemonic.equals("NEW_DYN") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.dynArrayElementTypeOf(baseType);
            long elementSize = sizes.sizeOf(elementBaseType);
            long count = Long.parseLong(line.get(2).text);
            long size = elementSize * count;
            return BytecodeStatement.fromText("NEW_DYN " + size + " " + count, "<address-lowered>");
        }

        // RESIZE dynArrType countType fillType -> RESIZE size; only the fill type's size (= element size) carries information.
        if (mnemonic.equals("RESIZE") && line.size() == 4) {
            // A pointer fill (owns/ref/raw/auto) is one 8-byte element whatever it points at: size the canonical type, not just its base.
            CanonicalType fillType = CanonicalType.parse(line.get(3).text);
            long size = fillType.storage != null ? 8 : sizes.sizeOf(fillType.baseType);
            return BytecodeStatement.fromText("RESIZE " + size, "<address-lowered>");
        }

        // URESIZE dynArrType countType -> URESIZE elemSize. The unsafe form is tried first, then the safe one: CloneGenerationPass.buildCloneLoop
        // synthesizes this 2-operand shape against a safe dynarray. See docs/AddressLoweringPass.md, "Per-mnemonic rewrites".
        if (mnemonic.equals("URESIZE") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.unsafeDynArrayElementTypeOf(baseType);
            if (elementBaseType == null) {
                elementBaseType = CanonicalType.dynArrayElementTypeOf(baseType);
            }
            long size = sizes.sizeOf(elementBaseType);
            return BytecodeStatement.fromText("URESIZE " + size, "<address-lowered>");
        }

        // STACK_LOCK structName -> STACK_LOCK size = struct size - 8 (if it has ___type) - 8 (the lock word); the rest is what the instruction reserves.
        // See docs/AddressLoweringPass.md, "STACK_LOCK".
        if (mnemonic.equals("STACK_LOCK") && line.size() == 2) {
            String operand = line.get(1).text;
            // An operand that is already digits is a padding gap size from emitInstantiate: already lowered.
            if (operand.chars().allMatch(Character::isDigit)) {
                return line;
            }
            String structName = operand;
            long size = sizes.sizeOf(structName);
            List<StructTableMember> members = structTable.membersOf(structName);
            // ___type, when present, is the FIRST member (index 0).
            boolean hasClassId = members != null && !members.isEmpty()
                    && members.get(0).name.equals("___type");
            if (hasClassId) {
                size -= 8;
            }
            size -= 8; // the lock field's own value, always one enum word
            return BytecodeStatement.fromText("STACK_LOCK " + size, "<address-lowered>");
        }

        // LOOKUP targetType indexType returnType -> LOOKUP_ARRAY|LOOKUP_DYN size.
        // See docs/AddressLoweringPass.md, "LOOKUP addressing shapes"; unrecognised targets are left untouched.
        if (mnemonic.equals("LOOKUP") && line.size() == 4) {
            String targetType = line.get(1).text;
            String returnType = line.get(3).text;
            String newMnemonic = lookupMnemonicFor(targetType);
            if (newMnemonic != null) {
                long size = sizes.sizeOf(returnType);
                // t8: a by-value 8-byte fixed array looks like a pointer to Codegen, so the token marks it as the array's own bytes.
                CanonicalType tt = CanonicalType.parse(targetType);
                String total = "";
                if (newMnemonic.equals("LOOKUP_ARRAY") && tt.storage == null && CanonicalType.fixedArrayElementTypeOf(tt.baseType) != null
                        && sizes.sizeOf(targetType) == 8) {
                    total = " t8";
                }
                CanonicalType rt = CanonicalType.parse(returnType);
                if (newMnemonic.equals("LOOKUP_ARRAY") && rt.storage == null && CanonicalType.fixedArrayElementTypeOf(rt.baseType) != null
                        && sizes.sizeOf(returnType) < 8) {
                    total += " ra";   // the element is itself a small fixed array held by value: Codegen tags it for the next lookup
                }
                return BytecodeStatement.fromText(newMnemonic + " " + size + total, "<address-lowered>");
            }
        }

        // LOOKUP_LHS -> LOOKUP_ARRAY_LHS|LOOKUP_DYN_LHS size: same split as LOOKUP, but the result is an address, not a value.
        if (mnemonic.equals("LOOKUP_LHS") && line.size() == 4) {
            String targetType = line.get(1).text;
            String returnType = line.get(3).text;
            String newMnemonic = lookupMnemonicFor(targetType);
            if (newMnemonic != null) {
                long size = sizes.sizeOf(returnType);
                return BytecodeStatement.fromText(newMnemonic + "_LHS " + size, "<address-lowered>");
            }
        }

        // GT/LT/GT_EQ/LT_EQ/EQ/NEQ -> <op>_INT|<op>_FLOAT size (size of the left operand); signed ints get an S prefix except EQ/NEQ.
        if ((mnemonic.equals("GT") || mnemonic.equals("LT") || mnemonic.equals("GT_EQ") || mnemonic.equals("LT_EQ")
                || mnemonic.equals("EQ") || mnemonic.equals("NEQ")) && line.size() == 4) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            String signedPrefix = (suffix.equals("_INT") && isSignedIntBaseType(CanonicalType.parse(leftType).baseType)
                    && !mnemonic.equals("EQ") && !mnemonic.equals("NEQ")) ? "S" : "";
            return BytecodeStatement.fromText(signedPrefix + mnemonic + suffix + " " + size, "<address-lowered>");
        }

        // AND/OR -> AND|OR size: width-independent bitwise op, no int/float split.
        if ((mnemonic.equals("AND") || mnemonic.equals("OR")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(1).text);
            return BytecodeStatement.fromText(mnemonic + " " + size, "<address-lowered>");
        }

        // SHL/SHR/ROTL/ROTR/BITS_OR/BITS_AND/BITS_XOR -> mnemonic + size of the left operand (integers only, so no float split).
        // A signed SHR becomes SAR. A one-operand "BITS_AND 8" made later by StrengthReductionPass is the same instruction at size 8.
        if ((mnemonic.equals("SHL") || mnemonic.equals("SHR") || mnemonic.equals("ROTL") || mnemonic.equals("ROTR") || mnemonic.equals("BITS_OR")
                || mnemonic.equals("BITS_AND") || mnemonic.equals("BITS_XOR")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(1).text);
            // bits_right on a signed operand is an arithmetic shift (SAR); unsigned stays logical (SHR).
            String outMnemonic = (mnemonic.equals("SHR")
                    && isSignedIntBaseType(CanonicalType.parse(line.get(1).text).baseType)) ? "SAR" : mnemonic;
            return BytecodeStatement.fromText(outMnemonic + " " + size, "<address-lowered>");
        }

        // BITS_NOT leftType returnType -> BITS_NOT size (complement of the low `size` bytes; backend re-truncates). Not the logical NOT below.
        if (mnemonic.equals("BITS_NOT") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
            return BytecodeStatement.fromText("BITS_NOT " + size, "<address-lowered>");
        }

        // NOT leftType returnType -> NOT size (bool or integer only, so no float split).
        if (mnemonic.equals("NOT") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
            return BytecodeStatement.fromText("NOT " + size, "<address-lowered>");
        }

        // CLONE argType returnType -> CLONE size, where size is the POINTEE's size (argType is a pointer, so strip the storage first, never
        // sizeOf(argType) directly). Only reached for flat pointees; CloneGenerationPass already rewrote owns-bearing ones.
        // See docs/AddressLoweringPass.md, "CLONE".
        if (mnemonic.equals("CLONE") && line.size() == 3) {
            String pointeeBaseType = CanonicalType.parse(line.get(1).text).baseType;
            String dynElemText = CanonicalType.dynArrayElementTypeOf(pointeeBaseType);
            if (dynElemText != null) {
                // a safe dynarray is a 16-byte header plus length*elemSize bytes: the size is only known at run time
                long elemSize = sizes.sizeOf(dynElemText);
                return BytecodeStatement.fromText("CLONE_DYN " + elemSize, "<address-lowered>");
            }
            long size = sizes.sizeOf(pointeeBaseType);
            return BytecodeStatement.fromText("CLONE " + size, "<address-lowered>");
        }

        // "DEREF pointeeType" -> "DEREF size". The pointee type rides on the DEREF line itself (not inferred
        // from the preceding PUSH, whose pointer type is always 8 bytes). sizeOf handles both storage-free and
        // storage-bearing operands. See docs/AddressLoweringPass.md, "Size-erasing rewrites".
        if (mnemonic.equals("DEREF") && line.size() == 2) {
            long size = sizes.sizeOf(line.get(1).text);
            return BytecodeStatement.fromText("DEREF " + size, "<address-lowered>");
        }

        // "NEG leftType returnType" -> "NEG size"; leftType alone suffices (unary minus never changes byte width).
        // See docs/AddressLoweringPass.md, "Size-erasing rewrites".
        if (mnemonic.equals("NEG") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
        // A float operand gets NEG_FLOAT (sign-bit flip); integer NEG would corrupt the IEEE-754 pattern.
            String negSuffix = isFloatBaseType(CanonicalType.parse(line.get(1).text).baseType) ? "_FLOAT" : "";
            return BytecodeStatement.fromText("NEG" + negSuffix + " " + size, "<address-lowered>");
        }

        // SEXT/ZEXT/TRUNC/FCONV keep both source and destination sizes (they genuinely differ).
        // See docs/AddressLoweringPass.md, "Size-erasing rewrites".
        if ((mnemonic.equals("SEXT") || mnemonic.equals("ZEXT") || mnemonic.equals("TRUNC") || mnemonic.equals("FCONV")) && line.size() == 3) {
            long sourceSize = sizes.sizeOf(line.get(1).text);
            long destSize = sizes.sizeOf(line.get(2).text);
            return BytecodeStatement.fromText(mnemonic + " " + sourceSize + " " + destSize, "<address-lowered>");
        }

        // "ATOMIC_SWAP l r ret" -> "ATOMIC_SWAP size", read from the LAST operand: leftType carries an
        // "atomic_" segment CanonicalType.parse does not know, which would size it as 8.
        // See docs/AddressLoweringPass.md, "Size-erasing rewrites".
        if (mnemonic.equals("ATOMIC_SWAP") && line.size() == 4) {
            long size = sizes.sizeOf(line.get(3).text);
            return BytecodeStatement.fromText("ATOMIC_SWAP " + size, "<address-lowered>");
        }

        // "ADD/SUB/MUL/DIV/MOD l r ret" -> "<op>_INT|_FLOAT size" from leftType only.
        // See docs/AddressLoweringPass.md, "Size-erasing rewrites".
        if ((mnemonic.equals("ADD") || mnemonic.equals("SUB") || mnemonic.equals("MUL")
                || mnemonic.equals("DIV") || mnemonic.equals("MOD")) && line.size() == 4) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            // Signed division/modulo are different machine operations (idiv, not div): own mnemonics.
            String signedPrefix = (suffix.equals("_INT") && isSignedIntBaseType(CanonicalType.parse(leftType).baseType)
                    && (mnemonic.equals("DIV") || mnemonic.equals("MOD"))) ? "S" : "";
            return BytecodeStatement.fromText(signedPrefix + mnemonic + suffix + " " + size, "<address-lowered>");
        }

        // "INC/DEC l ret" -> "INC_INT|INC_FLOAT size" (same _INT/_FLOAT split as the arithmetic ops).
        if ((mnemonic.equals("INC") || mnemonic.equals("DEC")) && line.size() == 3) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            return BytecodeStatement.fromText(mnemonic + suffix + " " + size, "<address-lowered>");
        }

        // "RECURSIVE_CALL f" -> "CALL f": a bare rename. AWAIT_CALL/PAR_CALL stay (different runtime mechanism).
        // See docs/AddressLoweringPass.md, "Call rewrites".
        if (mnemonic.equals("RECURSIVE_CALL") && line.size() == 2) {
            return BytecodeStatement.fromText("CALL " + line.get(1).text, "<address-lowered>");
        }

        // "EXTERN_CALL name argCount" -> "CALL name argCount": argCount is kept (varargs need it).
        // See docs/AddressLoweringPass.md, "Call rewrites".
        if (mnemonic.equals("EXTERN_CALL") && line.size() == 3) {
            return BytecodeStatement.fromText("CALL " + line.get(1).text + " " + line.get(2).text, "<address-lowered>");
        }

        // "POP name type" -> "POP $offset size"; ARGn names are not in offsets and stay verbatim.
        // See docs/AddressLoweringPass.md, "POP".
        if (mnemonic.equals("POP") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(2).text);
            Long popAddress = resolveAddress(line.get(1).text, offsets, localTypes, sizes, structTable);
            String nameOperand = popAddress != null ? "$" + popAddress : line.get(1).text;
            return BytecodeStatement.fromText("POP " + nameOperand + " " + size, "<address-lowered>");
        }

        // "PUSH_FIELDNAME f type" -> "PUSH_FIELDNAME memberOffset size". The struct comes from the previous
        // line in its ORIGINAL (not yet lowered) form; left untouched if it cannot be resolved.
        // See docs/AddressLoweringPass.md, "PUSH_FIELDNAME and DOT".
        if (mnemonic.equals("PUSH_FIELDNAME") && line.size() == 3) {
            if (previousLine == null || previousLine.isEmpty()) {
                return null;
            }
            String structType = previousLine.get(previousLine.size() - 1).text;
            String structBaseType = CanonicalType.parse(structType).baseType;
            String fieldName = line.get(1).text;
            String fieldType = line.get(2).text;
            AddressLoweringMemberLoc loc = memberLocOf(structBaseType, fieldName, sizes, structTable);
            if (loc == null) {
                return null;
            }
            long size = sizes.sizeOf(fieldType);
            return BytecodeStatement.fromText("PUSH_FIELDNAME " + loc.offset + " " + size, "<address-lowered>");
        }

        // "DOT/DOT_LHS l ret ret" -> "DOT size": the field size (returnType); leftType is dropped.
        // See docs/AddressLoweringPass.md, "PUSH_FIELDNAME and DOT".
        if ((mnemonic.equals("DOT") || mnemonic.equals("DOT_LHS")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(2).text);
                    // A by-value fixed array field of at most 8 bytes gets the extra token "ra" (Codegen assembles it from its
                    // one or two words and tags the pushed word as a small array for the next lookup).
            String smallArray = "";
            CanonicalType ft = CanonicalType.parse(line.get(2).text);
            if (mnemonic.equals("DOT") && ft.storage == null && CanonicalType.fixedArrayElementTypeOf(ft.baseType) != null && size <= 8) {
                smallArray = " ra";
            }
            return BytecodeStatement.fromText(mnemonic + " " + size + smallArray, "<address-lowered>");
        }

        // "ADDR_OF opText l ret" -> "ADDR_OF opText $address", when the previous line is a plain not-yet-lowered
        // PUSH name type (auto x / addressable raw x). Untouched if the shape differs or the name is unresolved.
        // See docs/AddressLoweringPass.md, "ADDR_OF".
        if (mnemonic.equals("ADDR_OF") && line.size() == 4 && previousLine != null && previousLine.size() == 3
                && (previousLine.get(0).text.equals("PUSH") || previousLine.get(0).text.equals("ATOMIC_PUSH"))) {
            Long address = resolveAddress(previousLine.get(1).text, offsets, localTypes, sizes, structTable);
            if (address == null) {
                return null;
            }
            return BytecodeStatement.fromText("ADDR_OF " + line.get(1).text + " $" + address, "<address-lowered>");
        }

        // "MEMCOPY d c s ret" -> bare "MEMCOPY": all four operands are always 8 bytes, so no information is lost.
        // See docs/AddressLoweringPass.md, "MEMCOPY and NEW_FROM_STRING".
        if (mnemonic.equals("MEMCOPY") && line.size() == 5) {
            return BytecodeStatement.fromText("MEMCOPY", "<address-lowered>");
        }

        // "NEW_FROM_STRING dyn src" -> bare "NEW_FROM_STRING" (same reasoning as MEMCOPY).
        // See docs/AddressLoweringPass.md, "MEMCOPY and NEW_FROM_STRING".
        if (mnemonic.equals("NEW_FROM_STRING") && line.size() == 3) {
            // an unsafe dynarray is headerless (see Codegen NEW_UDYN): its own mnemonic
            boolean unsafeTarget = line.get(1).text.contains("unsafe_dynarray(");
            return BytecodeStatement.fromText(unsafeTarget ? "NEW_FROM_USTRING" : "NEW_FROM_STRING", "<address-lowered>");
        }

        // "GT_DESTRUCT name" -> "GT_DESTRUCT $offset"; name may be a dotted chain. Untouched when unresolved.
        if (mnemonic.equals("GT_DESTRUCT") && line.size() == 2) {
            Long address = resolveAddress(line.get(1).text, offsets, localTypes, sizes, structTable);
            if (address == null) {
                return null;
            }
            return BytecodeStatement.fromText("GT_DESTRUCT $" + address, "<address-lowered>");
        }

        // ALLOC_STATIC / GLOBAL declarations: see rewriteStaticOrGlobalDecl (also called from run() for top-level GLOBAL).
        BytecodeStatement staticRewrite = rewriteStaticOrGlobalDecl(line, sizes, enumTable);
        if (staticRewrite != null) {
            return staticRewrite;
        }

        if (line.size() != 3) {
            return null;
        }
        String firstOperand = line.get(1).text;
        String typeOperand = line.get(2).text;

        // No in-function ALLOC case: run() collapses those into one combined ALLOC before this method is reached.

        // ATOMIC_PUSH is treated like PUSH; rebuilding lines from `mnemonic` keeps the ATOMIC_ prefix.
        if (!mnemonic.equals("PUSH") && !mnemonic.equals("ADDR") && !mnemonic.equals("ATOMIC_PUSH")) {
            return null;
        }
        long size = sizes.sizeOf(typeOperand);

        if (mnemonic.equals("PUSH") || mnemonic.equals("ATOMIC_PUSH")) {
            AddressLoweringArgWordSlot slot = argWordSlots.get(firstOperand);
            if (slot != null) {
                if (!slot.isRegister) {
                    String rewritten = mnemonic + " " + size + " $" + slot.stackOffset;
                    return BytecodeStatement.fromText(rewritten, "<address-lowered>");
                }
                        // Register-transferred: rewrite the digit to the per-bank register index (a no-op for win64;
                        // SysV/arm64 count int and float words independently). See resolveArgWordSlots.
                boolean isFloatWord = parseFargIndex(firstOperand) >= 0;
                firstOperand = (isFloatWord ? "FARG" : "ARG") + slot.registerIndex;
            }
        }

        Long address = resolveAddress(firstOperand, offsets, localTypes, sizes, structTable);
        String secondOperand;
        if (address != null) {
            secondOperand = "$" + address;
        } else {
                    // Unresolved name: a function-local ALLOC_STATIC or a top-level GLOBAL (ADDR is never given a
                    // dotted chain). Reduced to "size name" exactly like PUSH.
                    // See docs/AddressLoweringPass.md, "PUSH / ADDR tail".
                    // Unresolved names, "null" and global labels pass through; boolean and enum-variant literals resolve here.
            secondOperand = resolveLiteralOrEnumValue(firstOperand, enumTable);
        }
        String rewrittenText = mnemonic + " " + size + " " + secondOperand;
        return BytecodeStatement.fromText(rewrittenText, "<address-lowered>");
    }

    /** "true"/"false" -> "1"/"0"; "Enum.Variant" -> its integer value; anything else unchanged. See docs/AddressLoweringPass.md, "Literal and enum resolution". */
    private static String resolveLiteralOrEnumValue(String text, EnumTable enumTable) {
        if (text.equals("true")) {
            return "1";
        }
        if (text.equals("false")) {
            return "0";
        }
        int dot = text.indexOf('.');
        if (dot > 0 && dot < text.length() - 1) {
            String enumName = text.substring(0, dot);
            String variantName = text.substring(dot + 1);
            Long value = enumTable.variantValue(enumName, variantName);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return text;
    }

    /** Resolves a name or dotted chain to a flat base-pointer offset; null if any segment cannot be resolved. See docs/AddressLoweringPass.md, "Address resolution". */
    private Long resolveAddress(String nameChain, Map<String, Long> offsets, Map<String, String> localTypes,
            AddressLoweringSizeCalculator sizes, StructTable structTable) {
        int dot = nameChain.indexOf('.');
        String root = (dot < 0) ? nameChain : nameChain.substring(0, dot);
        Long base = offsets.get(root);
        if (base == null) {
            return null;
        }
        long address = base;
        String currentType = localTypes.get(root);
        String remainder = (dot < 0) ? "" : nameChain.substring(dot + 1);
        while (!remainder.isEmpty()) {
            CanonicalType t = CanonicalType.parse(currentType);
            if (t.storage != null) {
                return null; // pointer indirection -- defensive fallback, no longer expected to fire; see this pass's own header
            }
            int nextDot = remainder.indexOf('.');
            String segment = (nextDot < 0) ? remainder : remainder.substring(0, nextDot);
            String arrayElem = t.fixedArrayElementType();
            if (arrayElem != null && segment.matches("[0-9]+")) {
                // A numeric segment into a fixed array ("name.hs.1.w", as the drop/clone glue passes write them): element stride * index.
                address += Long.parseLong(segment) * sizes.sizeOf(arrayElem);
                currentType = arrayElem;
                remainder = (nextDot < 0) ? "" : remainder.substring(nextDot + 1);
                continue;
            }
            AddressLoweringMemberLoc loc = memberLocOf(t.baseType, segment, sizes, structTable);
            if (loc == null) {
                return null;
            }
            address += loc.offset;
            currentType = loc.type;
            remainder = (nextDot < 0) ? "" : remainder.substring(nextDot + 1);
        }
        return address;
    }

    /** Member byte offset and declared type within baseType's padded layout (walks LayoutEntry so padding is counted); null if absent. See docs/AddressLoweringPass.md, "Address resolution". */
    private AddressLoweringMemberLoc memberLocOf(String baseType, String memberName, AddressLoweringSizeCalculator sizes, StructTable structTable) {
        if (memberName.equals("___type")) {
            // ___type is always the first member (offset 0, imut_u64), resolved structure-agnostically so it also works when baseType is an interface.
            return new AddressLoweringMemberLoc(0, "imut_u64");
        }
        List<StructTableLayoutEntry> layout = pseudoLayoutOf(baseType, structTable);
        if (layout == null) {
            return null;
        }
        AddressLoweringMemberLoc direct = walkLayoutFor(layout, memberName, sizes);
        if (direct != null) {
            return direct;
        }
        return null;
    }

    private static AddressLoweringMemberLoc walkLayoutFor(List<StructTableLayoutEntry> layout, String memberName,
            AddressLoweringSizeCalculator sizes) {
        long offset = 0;
        for (StructTableLayoutEntry entry : layout) {
            if (entry.member == null) {
                offset += entry.paddingBytes;
                continue;
            }
            if (entry.member.name.equals(memberName)) {
                return new AddressLoweringMemberLoc(offset, entry.member.canonicalType);
            }
            offset += sizes.sizeOf(entry.member.canonicalType);
        }
        return null;
    }

    /** Fixed layout for a range (start, end: two u64) and otherwise the struct table's layout. */
    private List<StructTableLayoutEntry> pseudoLayoutOf(String baseType, StructTable structTable) {
        if (baseType.equals("range") || baseType.startsWith("range(")) {
            List<StructTableLayoutEntry> m = new ArrayList<>();
            m.add(StructTableLayoutEntry.ofMember(new StructTableMember("start", "indeterminate_u64")));
            m.add(StructTableLayoutEntry.ofMember(new StructTableMember("end", "indeterminate_u64")));
            return m;
        }
        return structTable.layoutOf(baseType);
    }

}
