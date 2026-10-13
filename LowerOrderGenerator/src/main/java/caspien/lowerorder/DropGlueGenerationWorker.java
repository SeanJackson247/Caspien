package caspien.lowerorder;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds this one run's own mutable state (the routine worklist, the
 * label counter) so the outer pass class itself stays a stateless,
 * reentrant OptimizationPass -- a fresh Worker per run() call, never
 * shared across calls.
 */
final class DropGlueGenerationWorker {

    private final StructTable structTable;
    private final Set<String> needed = new LinkedHashSet<>();
    private final Set<String> generated = new HashSet<>();
    private int labelCounter = 0;
    private int tempCounter = 0;

    private final CompilerConfig config;

    DropGlueGenerationWorker(StructTable structTable, CompilerConfig config) {
        this.structTable = structTable;
        this.config = config;
    }

    PassResult process(BytecodeProgram lines) {
        BytecodeProgram rewritten = new BytecodeProgram();
        boolean changedAnyCallSite = false;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            BytecodeStatement line = lines.get(i);
            if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                rewritten.add(line);
                i++;
                continue;
            }

            // A whole function's own line range, FUNC_START..FUNC_END
            // inclusive. Every `owns` local's own ALLOC is "hoisted to
            // the top of its enclosing scope" *conceptually* (per this
            // bytecode's own spec -- see BytecodeEmitter's own doc
            // comment on ALLOC), but not textually: a real compiled
            // function's own gt_routine unwind-path copy of its
            // GT_DESTRUCT list sits right after the prologue, *before*
            // the ordinary body's ALLOC lines appear in the text below
            // it (confirmed directly by running this pass against real
            // compiler output -- an early single forward-pass version
            // of this method missed exactly that copy). So every
            // ALLOC in the whole function is collected first, in a
            // pass of its own, before any GT_DESTRUCT in that same
            // function is resolved against it. Parameters need no
            // separate check here any more -- the sibling
            // `caspien-compiler` project's own "ARG-to-ALLOC lowering"
            // means a real parameter is now just an ordinary `ALLOC`
            // too (the old, now-extinct `ARG name type` declaration
            // line this used to also check for never appears in real
            // compiler output any more, and this pass's own generated
            // routines emit `ALLOC` for their own parameter as well --
            // see `generateRoutine`'s own doc comment).
            int start = i;
            int end = start;
            while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                end++;
            }
            end = Math.min(end, n - 1); // index of the FUNC_END line itself (or the last line, if malformed)

            Map<String, String> localTypes = new HashMap<>();
            for (int k = start; k <= end; k++) {
                BytecodeStatement fl = lines.get(k);
                if (!fl.isEmpty() && (fl.get(0).text.equals("ALLOC") || fl.get(0).text.equals("ARG")) && fl.size() >= 3) {
                    // `ARG name type` too: this pass runs BEFORE ArgToAllocLoweringPass, so a parameter is still an ARG line here;
                    // without it an owned parameter's destruct was a bare free (its owns members / elements leaked).
                    localTypes.put(fl.get(1).text, fl.get(2).text);
                }
            }

            for (int k = start; k <= end; k++) {
                BytecodeStatement fl = lines.get(k);
                if (!fl.isEmpty() && fl.get(0).text.equals("GT_DROP_ADDR") && fl.size() >= 2) {
                    // "GT_DROP_ADDR structType": the address of an inline owning struct is on top of the stack; drop its
                    // members (the struct itself stays where it is). Becomes a call to the struct's drop routine.
                    CanonicalType dt = CanonicalType.parse(fl.get(1).text);
                    if (structTable.hasStruct(dt.baseType) && structTable.isOwnsBearing(dt.baseType)) {
                        emit(rewritten, "CALL " + routineNameFor(dt.baseType));
                        enqueue(dt.baseType);
                    }
                    changedAnyCallSite = true;
                    continue;
                }
                if (!fl.isEmpty() && fl.get(0).text.equals("GT_DESTRUCT_TAIL") && fl.size() >= 4) {
                    // "GT_DESTRUCT_TAIL arrayLocal arrayType startLocal": a shrinking `resize` drops every element at
                    // index >= startLocal (the owns members of each) before the block is cut down. The line itself is
                    // replaced by the element-drop loop (nothing at all when the elements own nothing).
                    String dynElem = CanonicalType.dynArrayElementTypeOf(CanonicalType.parse(fl.get(2).text).baseType);
                    if (dynElem != null) {
                        emitDynArrayElementDrop(rewritten, fl.get(1).text, fl.get(2).text, dynElem, fl.get(3).text);
                    }
                    changedAnyCallSite = true;
                    continue;
                }
                if (!fl.isEmpty() && fl.get(0).text.equals("GT_DESTRUCT") && fl.size() >= 2) {
                    String targetName = fl.get(1).text;
                    String targetType = resolveType(targetName, localTypes);
                    if (targetType != null) {
                        CanonicalType t = CanonicalType.parse(targetType);
                        if (!t.isOwnsStorage() && t.storage == null && t.fixedArrayElementType() != null) {
                            // A fixed array of inline owning structs: drop every element (unrolled, the length is fixed).
                            int before = rewritten.size();
                            emitValueDestruct(rewritten, targetName, targetType);
                            if (rewritten.size() != before) {
                                changedAnyCallSite = true;
                                continue;
                            }
                        }
                        if (!t.isOwnsStorage() && t.storage == null && structTable.hasStruct(t.baseType)
                                && structTable.isOwnsBearing(t.baseType)) {
                            // An inline struct that owns memory (a local, or a member of one): there is no pointer to free,
                            // only its members to drop. The routine takes the struct's address; the original line is replaced.
                            emit(rewritten, "ADDR " + targetName + " " + targetType);
                            emit(rewritten, "CALL " + routineNameFor(t.baseType));
                            enqueue(t.baseType);
                            changedAnyCallSite = true;
                            continue;
                        }
                        if (t.isOwnsStorage() && structTable.isOwnsBearing(t.baseType)) {
                            // The local may already be null (moved out earlier, e.g. "x = f(x)"
                            // destructs x after f took it), and reading a null dynarray's length
                            // or a null struct's members would segfault: guard the descent.
                            String topSkip = newLabel("drop_skip");
                            emit(rewritten, "PUSH " + targetName + " " + targetType);
                            emit(rewritten, "PUSH null " + targetType);
                            emit(rewritten, "NEQ " + targetType + " " + targetType + " imut_bool");
                            emit(rewritten, "CMP");
                            emit(rewritten, "JMP " + topSkip);
                            String topLevelDynElem = CanonicalType.dynArrayElementTypeOf(t.baseType);
                            if (topLevelDynElem != null) {
                                // A bare, top-level owns local that is
                                // itself a dynarray (never a struct --
                                // `enqueue`/`generateRoutine` below are
                                // both struct-shaped, calling
                                // `structTable.membersOf` /
                                // requiring `structTable.hasStruct`,
                                // neither of which a dynarray baseType
                                // ever satisfies). This used to fall
                                // into the struct branch below anyway
                                // -- `isOwnsBearing` already recognizes
                                // a dynarray baseType as owns-bearing
                                // (recursing into its own element type,
                                // `StructTable.computeOwnsBearing`) --
                                // emitting a real "CALL
                                // __drop_dynarray_..." call site, but
                                // `enqueue`'s own `hasStruct` guard
                                // silently refused to ever add it to
                                // the generation worklist, so that
                                // routine was never actually generated
                                // at all: a call to a genuinely
                                // nonexistent function, caught directly
                                // against a real fixture
                                // (`clone_generation_manual_check_test`'s
                                // own top-level `arr`/`arrCloned`
                                // locals) while verifying this pass's
                                // own new dynarray-element loop
                                // generation. Fixed by handling this
                                // shape inline, the same way an
                                // owns-bearing dynarray *member*
                                // already is (`emitOwnsMemberDestruct`)
                                // -- no separate generated routine at
                                // all, just this local's own elements
                                // walked directly at the call site.
                                emitDynArrayElementDrop(rewritten, targetName, targetType, topLevelDynElem);
                            } else {
                                emit(rewritten, "PUSH " + targetName + " " + targetType);
                                emit(rewritten, "CALL " + routineNameFor(t.baseType));
                                enqueue(t.baseType);
                            }
                            emit(rewritten, topSkip + ":");
                            changedAnyCallSite = true;
                        }
                    }
                }
                rewritten.add(fl); // the original line always survives unchanged -- including the original GT_DESTRUCT itself, which still runs last, after any children
            }

            i = end + 1;
        }

        boolean generatedAnyRoutine = !needed.isEmpty();
        while (!needed.isEmpty()) {
            String next = needed.iterator().next();
            needed.remove(next);
            if (generated.contains(next)) {
                continue;
            }
            generated.add(next);
            rewritten.addAll(generateRoutine(next));
        }

        return new PassResult(rewritten, changedAnyCallSite || generatedAnyRoutine);
    }

    /** Chases a (possibly dotted) destruct target's declared type through ALLOC and struct-member lookups; null if any segment can't be resolved. */
    private String resolveType(String name, Map<String, String> localTypes) {
        int dot = name.indexOf('.');
        String head = (dot < 0) ? name : name.substring(0, dot);
        String currentType = localTypes.get(head);
        if (currentType == null) {
            return null;
        }
        String remainder = (dot < 0) ? "" : name.substring(dot + 1);
        while (!remainder.isEmpty()) {
            int nextDot = remainder.indexOf('.');
            String segment = (nextDot < 0) ? remainder : remainder.substring(0, nextDot);
            CanonicalType t = CanonicalType.parse(currentType);
            List<StructTableMember> members = structTable.membersOf(t.baseType);
            if (members == null) {
                return null;
            }
            String found = null;
            for (StructTableMember m : members) {
                if (m.name.equals(segment)) {
                    found = m.canonicalType;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            currentType = found;
            remainder = (nextDot < 0) ? "" : remainder.substring(nextDot + 1);
        }
        return currentType;
    }

    private void enqueue(String baseType) {
        if (structTable.hasStruct(baseType) && !generated.contains(baseType)) {
            needed.add(baseType);
        }
    }

    private static String routineNameFor(String baseType) {
        return "__drop_" + CanonicalType.sanitizeForLabel(baseType);
    }

    private String newLabel(String prefix) {
        labelCounter++;
        return "@" + prefix + "_" + labelCounter;
    }

    private static void emit(BytecodeProgram out, String text) {
        out.add(BytecodeStatement.fromText(text, "<generated-drop-glue>"));
    }

    /**
     * Declares a fresh, uniquely-named hidden local (the same
     * "$"-prefixed convention `MembershipLoweringPass`'s own
     * `declareTemp` and the compiler's own "$for_range_N" for-loop
     * desugaring both already establish) right where it's needed --
     * no separate hoisting-to-top step required, since
     * `AddressLoweringPass` already scans every `ALLOC` anywhere in
     * a function, in whatever order it appears, to assign stack
     * offsets (confirmed directly against its own `run` method), not
     * just ones sitting in a hoisted prologue block.
     */
    private String newTemp(BytecodeProgram out, String tag, String type) {
        tempCounter++;
        String name = "$" + tag + tempCounter;
        emit(out, "ALLOC " + name + " " + type);
        return name;
    }

    /**
     * Builds the full FUNC_START..FUNC_END block for `structName`'s
     * own drop-glue routine. Its one parameter is loaded per the
     * sibling `caspien-compiler` project's own "ARG-to-ALLOC
     * lowering" contract (see that project's CLAUDE.md) -- a real
     * `ALLOC`, "the first of the ALLOCs for that function" (trivially
     * true here: it's the *only* one), then loaded via `ADDR`/
     * `PUSH $16`/`ASSIGN` -- the same plain, pre-existing `PUSH` (no
     * new mnemonic) the compiler side uses for its own stack-passed
     * parameters -- since this routine's own call site (just below,
     * in `process`) is a bare `PUSH value` / `CALL name` with no
     * `CC_START`/`CC_END`/register-transfer bracket at all -- a
     * deliberately minimal, ad hoc calling shape distinct from an
     * ordinary, convention-driven call, under which the value never
     * touches a register and is simply left sitting on the stack,
     * exactly the "left as a plain PUSH" shape a real call's own
     * stack-passed arguments (beyond its convention's own register
     * count) already get -- so `p` is always stack word 0,
     * unconditionally, regardless of whatever real `@call_convention`
     * the rest of the program actually uses (this pass has no
     * `compiler.config` of its own to resolve one from). `16` is the
     * compiler side's own fixed saved-rbp-plus-return-address base
     * (`16 + shadowStack + stackWordIndex*8`), with `shadowStack=0`
     * and `stackWordIndex=0` here since this bare `PUSH`/`CALL` shape
     * has no real calling convention and always exactly one
     * stack-passed word.
     */
    private BytecodeProgram generateRoutine(String structName) {
        BytecodeProgram out = new BytecodeProgram();
        String paramType = "ref_mut_" + structName;
        emit(out, "FUNC_START " + routineNameFor(structName));
        emit(out, "FUNC_DECORATE @drop_glue");
        emit(out, "RETURNS imut_void");
        emit(out, "ALLOC p " + paramType);
        emit(out, "ADDR p " + paramType);
        emit(out, "PUSH $16 " + paramType);
        emit(out, "ASSIGN " + paramType + " " + paramType + " " + paramType);

        // `p` itself is genuinely pointer-wrapped (`ref`-storage) --
        // AddressLoweringPass.resolveAddress deliberately, by design,
        // refuses to resolve any dotted chain whose root (or any
        // segment) still carries non-null storage (its own "pointer
        // indirection -- known gap" bail), because real, type-checked
        // Caspien source can never produce that shape in the first
        // place (TypeChecker.checkDot's requiresAliveProof gate
        // rejects any '.' access rooted in a pointer outright). This
        // pass hand-writes its own bytecode after the compiler has
        // already run, bypassing that gate, so "p.member" paths built
        // directly off the raw pointer parameter would hit exactly
        // that unresolvable shape. Fixed the same way
        // MembershipLoweringPass's own derefInto already establishes
        // for the identical situation elsewhere in this codebase:
        // dereference the pointer once, into a fresh, storage-free
        // local, and dot into THAT instead -- every member path below
        // is now rooted in an ordinary, non-pointer name, exactly the
        // shape resolveAddress already resolves correctly for plain
        // (non-nested) owns locals and for ordinary compiled '.field'
        // access alike.
        CanonicalType paramCanonical = CanonicalType.parse(paramType);
        String derefType = paramCanonical.mutability != null
                ? paramCanonical.mutability + "_" + paramCanonical.baseType
                : paramCanonical.baseType;
        String root = newTemp(out, "drop_self", derefType);
        emit(out, "PUSH p " + paramType);
        emit(out, "DEREF " + derefType);
        emit(out, "POP " + root + " " + derefType);

        String hook = structTable.dropHookOf(structName);
        if (hook != null) {
            // The struct's `@drop` function runs first, while every member is still intact.
            String conv = config.defaultConvention;
            emit(out, "CC_START " + conv);
            emit(out, "PUSH p " + paramType);
            emit(out, "POP ARG0 " + paramType);
            emit(out, "CALL " + hook);
            emit(out, "CC_END " + conv);
        }
        for (StructTableMember member : structTable.membersOf(structName)) {
            emitValueDestruct(out, root + "." + member.name, member.canonicalType);
        }
        emit(out, "RET imut_void");
        emit(out, "FUNC_END");
        return out;
    }

    /**
     * Emits whatever destruction work `path` (an existing,
     * addressable operand -- a plain name or a dotted chain) needs
     * for a value of type `canonicalType`, exactly as if `path`
     * were an ordinary struct member being visited on the way to
     * freeing whatever owns it. Shared by every place this pass
     * needs "destruct whatever's reachable through here" -- a
     * struct's own top-level members, and each element of an
     * unrolled fixed array.
     */
    private void emitValueDestruct(BytecodeProgram out, String path, String canonicalType) {
        // Fixed-array elements are queued on an explicit to-do stack ({path, type}; the next one to emit is the last), not recursed into.
        List<String[]> todo = new ArrayList<>();
        todo.add(new String[] {path, canonicalType});
        while (!todo.isEmpty()) {
            String[] item = todo.remove(todo.size() - 1);
            emitValueDestructStep(out, item[0], item[1], todo);
        }
    }

    private void emitValueDestructStep(BytecodeProgram out, String path, String canonicalType, List<String[]> todo) {
        CanonicalType t = CanonicalType.parse(canonicalType);

        if (t.isOwnsStorage()) {
            emitOwnsMemberDestruct(out, path, canonicalType, t.baseType);
            return;
        }

        if (structTable.hasStruct(t.baseType)) {
            if (structTable.isOwnsBearing(t.baseType)) {
                emit(out, "ADDR " + path + " " + canonicalType);
                emit(out, "CALL " + routineNameFor(t.baseType));
                enqueue(t.baseType);
            }
            return;
        }

        String fixedElem = t.fixedArrayElementType();
        if (fixedElem != null) {
            CanonicalType elemT = CanonicalType.parse(fixedElem);
            boolean elemNeedsWork = elemT.isOwnsStorage()
                    || (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType))
                    || CanonicalType.fixedArrayElementTypeOf(elemT.baseType) != null;
            CanonicalType leafT = elemT;
            while (CanonicalType.fixedArrayElementTypeOf(leafT.baseType) != null) {
                leafT = CanonicalType.parse(CanonicalType.fixedArrayElementTypeOf(leafT.baseType));
            }
            if (elemNeedsWork && !leafT.isOwnsStorage() && leafT.storage == null && structTable.hasStruct(leafT.baseType)
                    && structTable.isOwnsBearing(leafT.baseType)) {
                // Elements are inline owning structs: address each one on the stack (LOOKUP_LHS chain) and call its drop routine.
                emitFixedArrayElementDrops(out, path, canonicalType, leafT.baseType);
                return;
            }
            if (elemNeedsWork) {
                int count = t.fixedArrayLength();
                for (int i = count - 1; i >= 0; i--) {
                    todo.add(new String[] {path + "." + i, fixedElem});
                }
            }
            return;
        }

        // A bare dynarray value can't reach this, inline branch --
        // "a dynarray value is always owns-storage" (see this
        // project's CLAUDE.md notes on dynarray codegen) -- so
        // dynarray handling only ever happens from the owns branch
        // above, via emitOwnsMemberDestruct's own GT_LOOP emission.
        // A plain scalar/non-owns-bearing struct needs nothing at
        // all -- falls through with no bytecode emitted.
    }

    /**
     * Calls the drop routine of `leafStruct` on every element of the fixed array at `basePath` (unrolled; nested dimensions are
     * stepped through like an odometer, last index fastest, which is the order nested loops would emit them).
     */
    private void emitFixedArrayElementDrops(BytecodeProgram out, String basePath, String arrCanonical, String leafStruct) {
        List<String> levels = new ArrayList<>();   // levels.get(k) = the array type indexed at nesting depth k
        String cur = arrCanonical;
        while (true) {
            levels.add(cur);
            String elem = CanonicalType.parse(cur).fixedArrayElementType();
            if (CanonicalType.fixedArrayElementTypeOf(CanonicalType.parse(elem).baseType) == null) {
                break;
            }
            cur = elem;
        }
        int depth = levels.size();
        int[] count = new int[depth];
        for (int k = 0; k < depth; k++) {
            count[k] = CanonicalType.parse(levels.get(k)).fixedArrayLength();
            if (count[k] <= 0) {
                return; // an empty dimension has no elements to drop
            }
        }
        int[] idx = new int[depth];
        while (true) {
            emit(out, "ADDR " + basePath + " " + arrCanonical);
            for (int k = 0; k < depth; k++) {
                String elemCanonical = k + 1 < depth ? levels.get(k + 1) : CanonicalType.parse(levels.get(k)).fixedArrayElementType();
                emit(out, "PUSH " + idx[k] + " mut_u64");
                emit(out, "LOOKUP_LHS " + levels.get(k) + " mut_u64 " + elemCanonical);
            }
            emit(out, "CALL " + routineNameFor(leafStruct));
            enqueue(leafStruct);
            int k = depth - 1;
            while (k >= 0 && ++idx[k] == count[k]) {
                idx[k] = 0;
                k--;
            }
            if (k < 0) {
                return;
            }
        }
    }

    /**
     * The owns-member case: null-guard the recursive descent into
     * this pointer's own children, then unconditionally GT_DESTRUCT
     * the pointer itself (safe even on null -- the runtime's own
     * gt_destruct is a documented no-op there).
     */
    private void emitOwnsMemberDestruct(BytecodeProgram out, String path, String canonicalType, String baseType) {
        String skipLabel = newLabel("drop_skip");

        emit(out, "PUSH " + path + " " + canonicalType);
        emit(out, "PUSH null " + canonicalType);
        emit(out, "NEQ " + canonicalType + " " + canonicalType + " imut_bool");
        emit(out, "CMP");
        emit(out, "JMP " + skipLabel);

        String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
        if (dynElem != null) {
            emitDynArrayElementDrop(out, path, canonicalType, dynElem);
        } else if (structTable.hasStruct(baseType) && structTable.isOwnsBearing(baseType)) {
            emit(out, "PUSH " + path + " " + canonicalType);
            emit(out, "CALL " + routineNameFor(baseType));
            enqueue(baseType);
        }
        // else: either a bare fixed array pointee (the documented
        // known gap above) or a shape with no owns content at all --
        // nothing further to recurse into.

        emit(out, skipLabel + ":");
        emit(out, "GT_DESTRUCT " + path);
    }

    /** The routine to call for a dynarray element of type `elemCanonicalType` (see `emitDynArrayElementDrop`), or the literal "NONE" when there's nothing to recurse into for that element shape. */
    private String elementRoutineFor(String elemCanonicalType) {
        CanonicalType elemT = CanonicalType.parse(elemCanonicalType);
        if (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType)) {
            enqueue(elemT.baseType);
            return routineNameFor(elemT.baseType);
        }
        return "NONE";
    }

    /**
     * Builds a real, generated JMP/CMP loop over `path`'s own
     * elements in place of the original "GT_LOOP dynarrayOperand
     * elemType elemRoutine" contract this pass used to hand off to a
     * not-yet-built codegen stage (see this pass's own header for
     * the full reasoning). `path` is already known to be null-guarded
     * by the caller (`emitOwnsMemberDestruct`), and its own
     * unconditional `GT_DESTRUCT` (freeing the dynarray's own
     * backing buffer) still happens right after this method returns,
     * unchanged -- this method only ever handles the *elements*.
     *
     * Built entirely out of primitives already fully specified
     * elsewhere in this pipeline: a hidden `mut_u64` counter,
     * initialized and incremented exactly the way the compiler's own
     * `for`/`loop` desugaring already does (`ADDR`/`PUSH`/`ASSIGN`
     * for init, `ADDR`/`PUSH`/`INC`/`ASSIGN` for the increment); the
     * loop condition is a plain `PUSH counter / PUSH dynarrayOperand
     * / LEN / LT / CMP / JMP end` -- the exact same bare-`LEN`
     * contract `MembershipLoweringPass`'s own "x in dynarray"
     * lowering already relies on, and in fact the same comparison a
     * real `for i in dynarrayExpr{...}` loop's own "i in dynarray"
     * eventually lowers to anyway.
     *
     * Per-element handling mirrors this pass's own non-loop member
     * handling (`emitOwnsMemberDestruct`/`emitValueDestruct`)
     * exactly, just reading through a computed index instead of a
     * static name:
     *   - `dynElem` itself owns-storage (a dynarray of owns
     *     pointers) -- each element is read (`LOOKUP`, a value read)
     *     into a fresh per-iteration hidden temp, purely so it has a
     *     *name* -- "GT_DESTRUCT name" is this bytecode's only
     *     existing "free this owns pointer" primitive, and (per this
     *     pass's own header) it has no confirmed "act on a bare
     *     stack value" form, unlike `CALL`, whose own operand is
     *     just a routine name, never a location. The temp is then
     *     null-guarded and (if `elementRoutineFor` found a routine)
     *     recursed into exactly like an ordinary owns member, then
     *     unconditionally `GT_DESTRUCT`ed -- safe even on null, the
     *     runtime's own documented no-op there. The temp itself
     *     needs no cleanup of its own: it's an ordinary local from
     *     this point on, reused (reassigned, not re-`ALLOC`'d) every
     *     iteration, gone the same way any other local already is
     *     once the function returns.
     *   - `dynElem` an inline (no-storage) owns-bearing composite
     *     value -- no temp needed at all: `LOOKUP_LHS` produces the
     *     element's own address directly as a bare stack value, and
     *     since this pass's own generated routines are already
     *     called through nothing more than "bare PUSH / CALL" (no
     *     named operand on `CALL` itself), that address can be
     *     handed straight to the routine with no name ever needed
     *     for it -- the identical "no guard, no destruct, just
     *     recurse" shape `emitValueDestruct`'s own inline-struct-
     *     member case already uses.
     *   - Neither of the above -- this method is never called at
     *     all (see the caller): nothing to do, exactly like an
     *     ordinary scalar/no-owns-bearing member.
     */
    private void emitDynArrayElementDrop(BytecodeProgram out, String path, String canonicalType,
            String dynElem) {
        emitDynArrayElementDrop(out, path, canonicalType, dynElem, null);
    }

    /** As above, walking from index `startLocal` (a `mut_u64` local) instead of 0 when it is non-null. */
    private void emitDynArrayElementDrop(BytecodeProgram out, String path, String canonicalType,
            String dynElem, String startLocal) {
        CanonicalType elemT = CanonicalType.parse(dynElem);
        boolean elemIsOwnsPointer = elemT.isOwnsStorage();
        boolean elemIsInlineOwnsStruct = !elemIsOwnsPointer
                && structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType);
        if (!elemIsOwnsPointer && !elemIsInlineOwnsStruct) {
            return; // a plain scalar element, or a non-owns-bearing struct element -- nothing to do
        }

        String counter = newTemp(out, "gt_loop_i", "mut_u64");
        emit(out, "ADDR " + counter + " mut_u64");
        if (startLocal == null) {
            emit(out, "PUSH 0 indeterminate_u64");
        } else {
            emit(out, "PUSH " + startLocal + " mut_u64");
        }
        emit(out, "ASSIGN mut_u64 mut_u64 mut_u64");

        String topLabel = newLabel("gt_loop");
        String endLabel = newLabel("gt_loop_end");
        emit(out, topLabel + ":");
        emit(out, "PUSH " + counter + " mut_u64");
        emit(out, "PUSH " + path + " " + canonicalType);
        emit(out, "LEN");
        emit(out, "LT mut_u64 indeterminate_u64 indeterminate_bool");
        emit(out, "CMP");
        emit(out, "JMP " + endLabel);

        if (elemIsOwnsPointer) {
            String elemTemp = newTemp(out, "gt_loop_elem", dynElem);
            emit(out, "ADDR " + elemTemp + " " + dynElem);
            emit(out, "PUSH " + path + " " + canonicalType);
            emit(out, "PUSH " + counter + " mut_u64");
            emit(out, "LOOKUP " + canonicalType + " mut_u64 " + dynElem);
            emit(out, "ASSIGN " + dynElem + " " + dynElem + " " + dynElem);

            String elemRoutine = elementRoutineFor(dynElem);
            if (!elemRoutine.equals("NONE")) {
                String skipRecurse = newLabel("gt_loop_elem_skip");
                emit(out, "PUSH " + elemTemp + " " + dynElem);
                emit(out, "PUSH null " + dynElem);
                emit(out, "NEQ " + dynElem + " " + dynElem + " imut_bool");
                emit(out, "CMP");
                emit(out, "JMP " + skipRecurse);
                emit(out, "PUSH " + elemTemp + " " + dynElem);
                emit(out, "CALL " + elemRoutine);
                emit(out, skipRecurse + ":");
            }
            emit(out, "GT_DESTRUCT " + elemTemp);
        } else {
            String elemRoutine = elementRoutineFor(dynElem); // guaranteed != "NONE" here, by elemIsInlineOwnsStruct
            emit(out, "PUSH " + path + " " + canonicalType);
            emit(out, "PUSH " + counter + " mut_u64");
            emit(out, "LOOKUP_LHS " + canonicalType + " mut_u64 " + dynElem);
            emit(out, "CALL " + elemRoutine);
        }

        emit(out, "ADDR " + counter + " mut_u64");
        emit(out, "PUSH " + counter + " mut_u64");
        emit(out, "INC mut_u64 indeterminate_u64");
        emit(out, "ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
        emit(out, "JMP " + topLabel);
        emit(out, endLabel + ":");
    }
}
