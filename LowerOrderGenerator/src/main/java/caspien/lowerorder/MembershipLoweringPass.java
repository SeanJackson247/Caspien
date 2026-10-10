package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lowers the four "type-overloaded-by-membership-kind" operators --
 * "IN" (its ordinary range/dynarray shape, and its guaranteed-enum
 * shape), "WITHIN", "INSTANCEOF", and "IMPLEMENTS" -- into sequences of
 * ordinary comparison/logic primitives this bytecode already needs
 * regardless (GE/LT/EQ-shaped comparisons, AND/OR/NOT, and fixed-offset
 * dotted-name reads), per the design settled on directly: "so the plan
 * is to compile them all out in to more primitive instructions" --
 * confirmed yes. Runs once, as a final, one-shot stage
 * (BytecodeOptimizer.optimize), the same "not part of either fixed-point
 * loop" placement DropGlueGenerationPass already established for a
 * lowering step that only ever needs to see the program once its shape
 * has settled.
 *
 * "WITHIN" was originally left deliberately unhandled here (see the old
 * "Known gaps" entry this replaces), on the theory that its own strict-
 * subset semantics were "genuinely more complex than a single bounds
 * check." Once "in"'s own range-vs-range subset test (tryRewriteIn's
 * Shape 4, and the range-vs-dynarray shape right after it) was built,
 * that turned out to be wrong: "within" needs exactly the same
 * materialize/dereference machinery plus one small variation on the
 * same comparison (see tryRewriteWithin, buildRangeStrictSubsetCheck) --
 * not a genuinely different mechanism, just a different final formula.
 * Scrapped and rebuilt on top of "in"'s own machinery accordingly,
 * confirmed directly: "within is very similar to in... i think instead
 * scrap what we currently do with within and build it off of what we do
 * with in." This also retired the compiler-side "PTR_WITHIN" mnemonic
 * (see caspien-compiler's own CLAUDE.md) -- it existed only because,
 * before this pass had its own pointer-dereference mechanism, the
 * compiler itself had to pick a mnemonic based on operand pointerness at
 * emission time; "in" never needed a "PTR_IN" for the identical reason
 * this pass's own dereference step already handles it downstream, and
 * "within" doesn't need "PTR_WITHIN" for the same reason once it reuses
 * that same step.
 *
 * Most rewrites below repeat an *already emitted* "PUSH name type" line
 * (or read a dotted field off that same name) -- that covers whichever
 * operand needs to be read more than once (the left of "in EnumName"/
 * "in range", or the struct-typed left of "instanceof"/"implements")
 * whenever it was itself emitted as a single, bare "PUSH name type"
 * line immediately before the operator -- i.e. a plain variable
 * reference, a dotted field access on one, or a bare literal, the
 * shapes this bytecode compresses down to one PUSH line (see
 * BytecodeEmitter.emitDot's qualified-name fast path, and emitExpr's
 * ordinary VARREF/literal handling).
 *
 * A genuinely complex left operand of "in" (the result of a CALL, an
 * arithmetic expression, a LOOKUP into something not itself a bare
 * name, ...) never ends its own emission with a solitary "PUSH" line --
 * it ends with whatever instruction actually produced it (ADD, CALL's
 * own PUSH_RET, DOT, ...). Confirmed directly -- "the instruction
 * should have expectation based on what it presumes is on the stack,
 * not what from a most recent instruction type thing... its ok for
 * this to mean making a temporary variable or position on the stack" --
 * this is since handled too, for "in"'s left operand specifically (see
 * tryRewriteIn's own "materialize the left operand" branch): a fresh,
 * uniquely-named temp local ("$in_tmpN", declareTemp) is declared, and
 * the already-computed value already guaranteed to be sitting on the
 * stack at that point (by the compiler's own left-then-right emission
 * order -- no backward tracing of the left operand's own arbitrary
 * bytecode needed at all, its own emission is left completely
 * untouched) is popped straight into it via a widened "POP name type"
 * (AddressLoweringPass's own POP rewrite, previously hardcoded to only
 * ever resolve call-argument "ARGn" slots, now also resolves a real,
 * ALLOC-declared local through the identical `resolveAddress` lookup
 * PUSH/ADDR already use). `ASSIGN` couldn't do this instead -- it needs
 * its destination address pushed *before* the value being stored, which
 * would mean inserting a line ahead of an already-emitted expression
 * whose start was never (and still isn't) located, exactly the
 * backward-tracing this rewrite avoids by construction; `POP name type`
 * needs nothing pushed beforehand, so it drops in cleanly right after
 * the value it's popping, wherever that value already ends. The temp
 * needs no separate cleanup -- it's an ordinary local from that point
 * on, gone the same way any other local already is once the function
 * returns.
 *
 * The right operand gets the identical treatment when it isn't a simple
 * push either (e.g. "x in someFunc()"), popped *before* left since it's
 * the one sitting on top of the stack. This surfaced a real, subtler
 * point worth flagging: outSoFar's second-to-last entry only reliably
 * *is* left's own final line when right's own span is known to be
 * exactly one line (a simple push) -- once right can be an arbitrary,
 * multi-line expression too (a CALL sequence is four lines), that
 * assumption breaks, and inspecting that position as "left's push"
 * blindly read the wrong line entirely the first time this was tried.
 * The fix: whenever right isn't simple, left is unconditionally
 * materialized too, regardless of what that position actually holds --
 * never inspected, never removed -- relying only on the same
 * stack-order guarantee everything else here leans on already.
 *
 * Runtime semantics assumed for the range case (confirmed by inference
 * from TypeChecker.checkForLoop's own desugaring -- a for-loop's own
 * per-iteration "x in $range" condition is the loop's "keep going" test,
 * and the loop variable starts at $range.start and is compared against
 * $range.end as its stopping point): a range's bounds are half-open,
 * start inclusive, end exclusive -- "x in range" lowers to
 * "x >= range.start && x < range.end".
 *
 * Known gaps, deliberately left as original, unmodified IN/WITHIN/
 * INSTANCEOF/IMPLEMENTS bytecode (per the reassessment settled on
 * directly before this pass was written):
 *   - A *pointer*-typed right operand of "u64 in range"/"dynarray"
 *     ("raw range(...)", "raw dynarray(...)", legally constructible in
 *     unsafe code and legally accepted by checkIn, which only checks
 *     baseType text, never storage) is since handled by dereferencing
 *     first: "PUSH name type" / "DEREF derefType" / "POP derefTemp
 *     derefType", reusing the identical declareTemp/widened-POP machinery
 *     this pass already has, landing the pointee's own value in a fresh,
 *     storage-free temp before handing it to buildRangeCheck/
 *     buildDynArrayCheck -- confirmed the correct minimal fix since
 *     `deref()` already exists end-to-end (TypeChecker.checkDerefBuiltin,
 *     BytecodeEmitter's "deref" case: "PUSH pointer" / "DEREF
 *     pointeeType") and the dereferenced temp's own type has storage
 *     stripped (mutability_baseType, matching checkDerefBuiltin's own
 *     TypeInfo), so its ".start"/".end" resolves in AddressLoweringPass
 *     exactly like an ordinary, non-pointer range already does. (`DEREF`
 *     itself used to carry no operand at all -- a real, separate,
 *     later-found gap, fixed alongside a real dereference genuinely being
 *     a copy that needs its own byte count; see `derefInto`'s own doc
 *     comment and this project's own CLAUDE.md for the full story.)
 *
 *     **A real, separate correctness bug, found and fixed directly:**
 *     "does this operand need dereferencing" is NOT the same question as
 *     "is `canonical.storage` non-null" -- an ordinary dynarray value
 *     *always* carries "owns" storage itself (the same "buffer pointer
 *     plus bookkeeping" handle LEN/LOOKUP_DYN etc. already read
 *     directly, no dereference needed -- CanonicalType.isOwnsStorage),
 *     so the plain `storage != null` version of this check wrongly
 *     dereferenced *every* ordinary, non-pointer "x in dynarray" too,
 *     not just the genuinely pointer-wrapped ("raw dynarray(...)") ones
 *     it was meant for -- confirmed directly against a real compiled
 *     fixture using a plain local dynarray (no pointer, no materialized
 *     call result) as the right operand, the first fixture in this
 *     pass's own history to actually exercise that exact shape. Fixed
 *     by `isGenuinePointer` (`storage != null && !isOwnsStorage()`),
 *     used everywhere this pass decides whether an operand needs
 *     dereferencing, in place of the bare storage-null check.
 *   - A range-typed left operand of "in" against a range-shaped *or*
 *     dynarray-shaped right operand is since handled too:
 *       - "r1 in r2" -- the ordinary *non-strict* subset test,
 *         "r1.start >= r2.start && r1.end <= r2.end" (allows r1 == r2;
 *         WITHIN is the separate *strict* variant, built the same way,
 *         that additionally excludes the equal-bounds case -- see
 *         tryRewriteWithin/buildRangeStrictSubsetCheck above).
 *       - "r in dynarray" -- "is the whole range a valid index span into
 *         the dynarray," i.e. `r.end <= LEN(dynarray)` (the lower bound
 *         `r.start >= 0` is trivially true for a u64 range, the same
 *         reasoning already used to drop it for a scalar `u64` left).
 *         Deliberately `<=`, not `<`: a dynarray of length N has valid
 *         indices `0..N-1`, and the range `0..N` (`end == N`) is
 *         *exactly* that full valid span, so it must test as fully "in"
 *         the dynarray -- confirmed directly, "N < N" would wrongly
 *         reject it, "N <= N" is correct.
 *     Both sides get the identical pointer-dereference treatment
 *     described just above, independently -- a range-shaped left can
 *     genuinely be pointer-wrapped here (unlike Shape 2/3's scalar
 *     "u64" left, which checkIn itself never lets be a pointer), so
 *     "r1 in r2"/"r in dynarray" and every combination of either side
 *     being a raw pointer to its own operand all lower correctly, each
 *     independently materialized into a temp first when needed, same as
 *     Shape 2/3. Originally, *any* left operand shape reaching this
 *     rewrite at all was firing the plain-`u64` logic unconditionally --
 *     a real, separate bug found and fixed directly: no left-operand-
 *     shape guard existed at first, so a range-typed left (16 bytes) got
 *     compared via a same-sized GT_EQ_INT/LT_INT against a scalar bound,
 *     producing genuinely wrong bytecode (confirmed against real
 *     compiled fixtures, "in_operator_range_range_test") before the
 *     guard -- and later the two dedicated subset/dynarray-check
 *     builders -- were added.
 *   - "u64 in string" -- already compiled to its own dedicated
 *     "IN_SCAN" mnemonic by the compiler itself (BytecodeEmitter), never
 *     plain "IN" -- this pass never sees it.
 *   - An "instanceof" target that is not a struct in the flat "Class" enum, or an "implements" target with
 *     no "$enum_for_" table: the match fails and the original bytecode is left alone. "x instanceof S" (x
 *     interface-typed, S a struct) lowers to one class-id equality test; "x implements I" (x struct-typed)
 *     lowers to an OR-chain over I's implementers' class ids.
 *   - A non-simple-push left *or* right operand of a "u64 in range"/
 *     "dynarray" is since handled (see the design note above) --
 *     each is independently materialized into its own temp when it
 *     isn't a simple named push, so e.g. "x in someFunc()" and
 *     "(3+4) in someFunc()" both lower correctly now. The guaranteed-
 *     enum shape ("u64 in EnumName") is untouched by this -- its right
 *     side is a bare type-name label, not a runtime value, so
 *     materializing it wouldn't mean anything. "instanceof"/
 *     "implements"'s own struct-typed left operand also still must be a
 *     simple push -- its own materialization wasn't asked for or
 *     attempted here, though the identical mechanism would extend to it
 *     the same way if that's ever wanted.
 */
public class MembershipLoweringPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "membership-lowering";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        return new Worker(StructTable.read(lines), EnumTable.read(lines)).process(lines);
    }

    private static final class Worker {

        private final StructTable structTable;
        private final EnumTable enumTable;

        // Both reset once per function by rewriteFunctionBody -- see
        // declareTemp and tryRewriteIn's own "materialize the left
        // operand" branch.
        private int allocInsertIndex;
        private int tempCounter;

        // Never reset per function -- unlike tempCounter/allocInsertIndex,
        // labels have to stay unique across the *whole* program, the
        // identical "one Worker-lifetime counter, never zeroed" precedent
        // BytecodeEmitter.labelCounter and CloneGenerationPass.Worker's own
        // labelCounter already establish. Only tryRewriteInScan (below)
        // needs this at all -- every other rewrite in this pass produces
        // straight-line replacement text, never a real loop.
        private int labelCounter;

        Worker(StructTable structTable, EnumTable enumTable) {
            this.structTable = structTable;
            this.enumTable = enumTable;
        }

        PassResult process(List<List<BytecodeToken>> lines) {
            List<List<BytecodeToken>> rewritten = new ArrayList<>();
            boolean changed = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                List<BytecodeToken> line = lines.get(i);
                if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                    rewritten.add(line);
                    i++;
                    continue;
                }

                int start = i;
                int end = start;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                end = Math.min(end, n - 1);

                List<List<BytecodeToken>> body = new ArrayList<>();
                for (int k = start; k <= end; k++) {
                    body.add(lines.get(k));
                }
                RewriteOutcome outcome = rewriteFunctionBody(body);
                rewritten.addAll(outcome.lines);
                changed |= outcome.changed;

                i = end + 1;
            }

            return new PassResult(rewritten, changed);
        }

        private static final class RewriteOutcome {
            final List<List<BytecodeToken>> lines;
            final boolean changed;
            RewriteOutcome(List<List<BytecodeToken>> lines, boolean changed) {
                this.lines = lines;
                this.changed = changed;
            }
        }

        /** Walks one function's own line range looking for a rewritable IN/INSTANCEOF/IMPLEMENTS line, three lines at a time (the operator plus its two preceding operand pushes). */
        private RewriteOutcome rewriteFunctionBody(List<List<BytecodeToken>> body) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            boolean changed = false;
            int k = 0;
            int n = body.size();

            // Where a freshly-needed temp local's own "ALLOC name type"
            // line belongs -- right after this function's existing ALLOC
            // prologue block (see declareTemp, and tryRewriteIn's own
            // "materialize the left operand" branch), the same "ALLOC/
            // RETURNS prologue, then real instructions" layout every real
            // function already follows. Computed once, up front, against
            // `body` rather than `out` -- nothing before this point is
            // ever itself rewritten by this pass, so the two stay
            // index-aligned for as long as this scan cares about.
            //
            // `ARG`/`EXPORT` are real, ordinary header lines at THIS
            // pass's own stage -- `ArgToAllocLoweringPass` (which turns
            // `ARG` into a real `ALLOC` plus a load sequence) doesn't run
            // until later, so a real function's header here is always
            // "FUNC_START, [EXPORT], FUNC_DECORATE*, RETURNS, ARG*,
            // ALLOC*" -- both were missing from this skip-set, a real,
            // found-and-fixed bug: whenever a temp actually needed
            // inserting in a function that also declared real parameters,
            // this loop stopped at the *first* `ARG` line instead of
            // skipping past all of them, landing `allocInsertIndex` mid-
            // way through the parameter list -- splitting it in two
            // (some `ARG` lines before the newly-inserted temp `ALLOC`,
            // some after). `ArgToAllocLoweringPass`'s own header scan
            // (`lowerFunction`) stops collecting `ARG` lines the moment it
            // hits anything else, so it silently gave up after collecting
            // only the first few, treating the split-off remainder as
            // ordinary body text and leaving it completely unlowered --
            // confirmed directly: this is exactly the failure mode that
            // surfaced once `isRealNameToken` (below) started correctly
            // recognizing a `for` loop's own compiler-synthesized
            // "$for_range_N" range holder as a real name (fixing one bug)
            // and, for the first time, stopped needlessly materializing
            // it -- which had been the ONLY reason a temp was ever
            // getting inserted into these particular ARG-bearing stdlib
            // functions at all; a *different* future change that made a
            // temp genuinely necessary in an ARG-bearing function would
            // have hit this identical, independent gap regardless. Both
            // are fixed here together so the second one can't resurface
            // silently the next time something else needs to materialize
            // a value in a function that also takes real parameters.
            allocInsertIndex = 0;
            while (allocInsertIndex < n) {
                List<BytecodeToken> pl = body.get(allocInsertIndex);
                String ph = pl.isEmpty() ? "" : pl.get(0).text;
                if (ph.equals("FUNC_START") || ph.equals("EXPORT") || ph.equals("FUNC_DECORATE")
                        || ph.equals("RETURNS") || ph.equals("ARG") || ph.equals("ALLOC")) {
                    allocInsertIndex++;
                } else {
                    break;
                }
            }
            tempCounter = 0;

            while (k < n) {
                List<BytecodeToken> line = body.get(k);
                String head = line.isEmpty() ? "" : line.get(0).text;

                if (head.equals("IN") && line.size() == 4 && out.size() >= 2) {
                    InMatch match = tryRewriteIn(line, out);
                    if (match != null) {
                        // Each operand push is dropped only if this match
                        // says it's safe to (a *simple* push -- the
                        // replacement text below just re-derives it fresh
                        // by name, so the original line is now redundant);
                        // a push this match had to *materialize* instead
                        // (see tryRewriteIn's own "materialize the left
                        // operand" branch) is essential, real bytecode and
                        // is left standing exactly where it already was.
                        // Removed by explicit index, right push first --
                        // it's the tail, so removing it first never
                        // disturbs the left push's own index, whichever of
                        // the two (or neither, or both) actually gets
                        // removed.
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN line itself was consumed from `body` beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("WITHIN") && line.size() == 4 && out.size() >= 2) {
                    // Identical operand-push bookkeeping to "IN" above --
                    // see tryRewriteWithin's own header for why this is
                    // always the range-vs-range shape now.
                    InMatch match = tryRewriteWithin(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the WITHIN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if ((head.equals("INSTANCEOF") || head.equals("IMPLEMENTS")) && line.size() == 2
                        && k >= 1 && out.size() >= 2) {
                    // line.get(1) is the left operand's own real type,
                    // written directly onto this line by BytecodeEmitter
                    // (see its own "instanceof"/"implements" case) -- not
                    // inferred from whatever bytecode line happens to sit
                    // just before this one, which is unreliable the
                    // moment the left operand is a multi-push composite
                    // with no consolidating instruction (a bare, non-
                    // `new` struct literal -- see tryRewriteInstanceofOrImplements's
                    // own header for the real, confirmed bug this closes).
                    String leftType = line.get(1).text;
                    List<BytecodeToken> targetPushLine = body.get(k - 1);
                    InstanceofMatch match = tryRewriteInstanceofOrImplements(head, targetPushLine, leftType, out);
                    if (match != null) {
                        // The target/interface-name push (outSoFar's very
                        // last entry) is always exactly one line, always a
                        // bare compile-time name -- checkInstanceof/
                        // checkImplementsOperator both require a bare
                        // VARREF on the right, so BytecodeEmitter only
                        // ever emits it as a single, invariant "PUSH name"
                        // line -- never a computed value, so it's always
                        // safe to drop unconditionally, whether or not the
                        // left operand below needed materializing.
                        //
                        // The left operand (outSoFar's second-to-last
                        // entry, i.e. its own final line however many
                        // lines produced it) is dropped only when it was a
                        // simple push read fresh by name in the
                        // replacement text -- a materialized (kept) left
                        // operand's own original bytecode is essential
                        // (real computation, possibly with side effects)
                        // and is left standing exactly where it already
                        // was, with a "POP" already appended right after
                        // it to consume its value into the fresh temp the
                        // replacement text reads instead. Unlike "IN", the
                        // right/target operand here can never be multi-
                        // line, so -- unlike the miswiring "IN" had to
                        // guard against -- outSoFar.size() - 2 always
                        // reliably names the left operand's own last line,
                        // regardless of whether it turns out to be simple.
                        out.remove(out.size() - 1);
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare INSTANCEOF/IMPLEMENTS line
                        continue;
                    }
                } else if (head.equals("IS_BASE") && line.size() == 2 && out.size() >= 1) {
                    // "r is base" -- BytecodeEmitter.emitMatchCondition's
                    // own "PUSH x / IS_BASE -- instead of push base and
                    // then IS" shape: unlike INSTANCEOF/IMPLEMENTS (always
                    // two preceding pushes, left operand plus a bare
                    // target name) or IN/WITHIN (always two preceding
                    // pushes, left and right operands), IS_BASE has
                    // exactly one preceding operand push -- there's no
                    // "right side" at all, "base" isn't a real value
                    // anything gets pushed for.
                    // line.get(1) is the range operand's own real type,
                    // written directly onto this line by BytecodeEmitter
                    // (see its own "is" case) -- not inferred from
                    // whatever line precedes IS_BASE, which is unreliable
                    // the moment that operand is an inline range literal
                    // (two raw scalar pushes, no consolidating
                    // instruction -- see tryRewriteIsBase's own header
                    // for the real, confirmed bug this closes).
                    String rangeOperandType = line.get(1).text;
                    InstanceofMatch match = tryRewriteIsBase(rangeOperandType, out);
                    if (match != null) {
                        // outSoFar's very last entry is the range
                        // operand's own final line -- the only operand
                        // IS_BASE has -- dropped only when it was a
                        // simple push read fresh by name in the
                        // replacement text, exactly the same "materialize
                        // vs. simple" bookkeeping every other shape in
                        // this pass already follows.
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare IS_BASE line
                        continue;
                    }
                } else if (head.equals("IN_SCAN") && line.size() == 4 && out.size() >= 2) {
                    // "u64 in string" -- identical operand-push bookkeeping
                    // to "IN"/"WITHIN" above (always two preceding pushes,
                    // left then right) -- see tryRewriteInScan's own header.
                    InMatch match = tryRewriteInScan(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN_SCAN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("LEN_SCAN") && line.size() == 3 && out.size() >= 2) {
                    // "len(unsafeDynArr, terminator)" -- unlike every
                    // other shape here (including IN_SCAN), LEN_SCAN's
                    // own raw line is only 3 tokens (mnemonic + two
                    // operand types, no trailing resultType -- see
                    // BytecodeEmitter's own "line(\"LEN_SCAN \" +
                    // targetArg.resolvedType + \" \" +
                    // termArg.resolvedType)"); the result type is always,
                    // unconditionally, exactly "indeterminate_u64"
                    // (checkLenBuiltin's own fixed `TypeInfo(null,
                    // "indeterminate", "u64")` for this shape), so
                    // tryRewriteLenScan hardcodes it rather than reading a
                    // fourth token that doesn't exist -- see
                    // tryRewriteInScan's own header for the identical
                    // "operand-push bookkeeping" shape otherwise (two
                    // preceding pushes, target then terminator).
                    InMatch match = tryRewriteLenScan(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the LEN_SCAN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                }

                out.add(line);
                k++;
            }
            return new RewriteOutcome(out, changed);
        }

        /** A rewrite's replacement text for "IN", plus whether each of `outSoFar`'s trailing operand-push lines (left, then right) must be left standing rather than dropped -- true when that operand had to be materialized into a temp instead of being a simple named push (see tryRewriteIn's own "materialize the left/right operand" handling): its own original bytecode is essential and is never dropped, unlike a simple push's now-redundant original line. */
        private static final class InMatch {
            final List<String> lines;
            final boolean keepLeftPush;
            final boolean keepRightPush;
            InMatch(List<String> lines, boolean keepLeftPush, boolean keepRightPush) {
                this.lines = lines;
                this.keepLeftPush = keepLeftPush;
                this.keepRightPush = keepRightPush;
            }
        }

        /**
         * `inLine` is "IN t1 t2 t3". `outSoFar` is every already-rewritten
         * line of this function emitted before it -- its last two entries
         * are the original left/right operand pushes (unless this "IN"
         * doesn't qualify for lowering at all, checked as each shape is
         * tried). Returns the raw replacement text, or null if this
         * particular "IN" doesn't match any of the shapes this pass
         * knows how to lower.
         */
        private InMatch tryRewriteIn(List<BytecodeToken> inLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = inLine.get(1).text;
            String t2 = inLine.get(2).text;
            String t3 = inLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            // Shape 1: "u64 in GuaranteedEnumName" -- the right side is a
            // bare enum name, pushed as "PUSH EnumName" (2 tokens, no
            // type -- see BytecodeEmitter's own guaranteed-enum "in"
            // case), never a real, type-carrying operand push.
            List<Map.Entry<String, Long>> enumVariants = enumTable.valueVariantsOf(t2);
            if (!enumVariants.isEmpty() && isBarePush(rightPush) && rightPush.get(1).text.equals(t2)
                    && isSimplePush(leftPush)) {
                String leftName = leftPush.get(1).text;
                return new InMatch(buildOrChain(leftName, t1, enumVariants, t3), false, false);
            }

            // Shape 2/3: the ordinary bounds/length comparison -- right
            // side is a real range or dynarray value.
            // buildRangeCheck/buildDynArrayCheck both assume a scalar u64 left operand
            // (comparing it directly against range bounds / treating it
            // as a plain value against LEN), so this branch only fires
            // when the left operand is confirmed to be a plain "u64" --
            // no storage, exact base-type match, the same isPlainU64
            // gate the compiler's own TypeChecker already uses for this
            // same distinction. checkIn legally also permits a "range"
            // left operand -- firing this rewrite for that blindly
            // produced genuinely wrong bytecode (a 16-byte range
            // compared via a same-sized GT_EQ_INT/LT_INT against a
            // scalar bound), confirmed directly against real compiled
            // fixtures. Per the call made on this directly -- "if its
            // not a u64 on the left it should remain unhandled" -- bail
            // out and leave the original IN bytecode untouched, joining
            // the same "left as original, unmodified" category a
            // range-left operand still occupies, rather than attempting
            // real range-left subset-check semantics here.
            CanonicalType leftCanonical = CanonicalType.parse(t1);
            boolean leftIsPlainU64 = leftCanonical.storage == null && leftCanonical.baseType.equals("u64");
            if (leftIsPlainU64) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                String rightBaseType = rightCanonical.baseType;
                // Bare "range" (bounds unknown to the compiler -- a
                // parameter, or a value like `range(dynarray)` whose
                // length is a genuine runtime read) is exactly as real a
                // range as a literal-bounds "range(0,5)" -- buildRangeCheck
                // just below reads ".start"/".end" as ordinary runtime
                // dotted-field accesses either way, never the type text's
                // own bounds, so there was never a reason to exclude it
                // here. This exact narrow-check bug was already found and
                // fixed once, at this file's own "r is base" rewrite (see
                // its own doc comment) -- missed here and at the two
                // "within" gates just below when that fix landed, until
                // caught again, directly, against real "for i in
                // range(dynarray)" output.
                boolean rightIsRange = rightBaseType.equals("range") || rightBaseType.startsWith("range(");
                boolean rightIsDynArray = rightBaseType.startsWith("dynarray(");
                if (rightIsRange || rightIsDynArray) {
                    // Either operand's own name (an ordinary, already-
                    // addressable local -- "PUSH name type" -- so
                    // buildRangeCheck/buildDynArrayCheck can just re-push
                    // it fresh, as many times as they need to read it), or,
                    // when it wasn't a simple push at all (e.g. "(3+4) in
                    // r", or "u64 in someFunc()") -- confirmed directly,
                    // "the instruction should have expectation based on
                    // what it presumes is on the stack, not what from a
                    // most recent instruction type thing" -- a freshly
                    // declared temp local that a new "POP name type" pops
                    // the already-computed value into right where it
                    // already sits, so buildRangeCheck/buildDynArrayCheck
                    // can re-read *that* by name instead. Left completely
                    // alone otherwise -- this rewrite never touches,
                    // relocates, or duplicates either operand's own
                    // original bytecode (however long it is), it only ever
                    // consumes the single value each is already guaranteed
                    // (by the compiler's own left-then-right emission
                    // order) to have left on the stack.
                    //
                    // Right is materialized (popped) *before* left, since
                    // it's the one sitting on top of the stack (pushed
                    // last) -- popping it first is what makes left's own
                    // value reachable at all when left also needs popping.
                    //
                    // `leftPush` (outSoFar's second-to-last entry) is only
                    // trustworthy as "left's own last line" when right's
                    // own span is known to be exactly the single line at
                    // outSoFar's very last entry -- i.e. when right is a
                    // simple push. The moment right *isn't* (a CALL's own
                    // "CC_START .../CALL .../CC_END .../PUSH_RET ..." block
                    // is several lines, not one), "outSoFar.size() - 2"
                    // no longer reliably points at left's own last line at
                    // all -- it could be mid-way through right's own
                    // multi-line span instead (confirmed directly: this
                    // produced a real, wrong double-POP the first time
                    // this was tried, materializing garbage instead of
                    // `x` for "x in makeArr()"). So whenever right isn't
                    // simple, left is *always* materialized too,
                    // regardless of what `leftPush` actually looks like --
                    // never inspected, never removed -- relying only on
                    // the same stack-order guarantee: once right's own
                    // value has just been popped off, whatever's left
                    // underneath is unconditionally left's own value,
                    // however many lines produced it.
                    List<String> lines = new ArrayList<>();
                    boolean rightSimple = isSimplePush(rightPush);
                    String rightName;
                    if (rightSimple) {
                        rightName = rightPush.get(1).text;
                    } else {
                        rightName = declareTemp(outSoFar, t2);
                        lines.add("POP " + rightName + " " + t2);
                    }
                    boolean leftSimple = rightSimple && isSimplePush(leftPush);
                    String leftName;
                    if (leftSimple) {
                        leftName = leftPush.get(1).text;
                    } else {
                        leftName = declareTemp(outSoFar, t1);
                        lines.add("POP " + leftName + " " + t1);
                    }

                    // A pointer-typed right operand ("raw range(...)",
                    // "raw dynarray(...)", ...) -- legally accepted by
                    // checkIn (isRangeType/isDynArrayType only look at
                    // baseType text, never storage), but wrong to hand
                    // straight to buildRangeCheck/buildDynArrayCheck as-is:
                    // those functions read the name directly ("PUSH name
                    // type", "PUSH name.start ...") assuming it's already
                    // the range/dynarray value itself, not a pointer *to*
                    // one -- confirmed directly against real compiled
                    // fixtures ("PUSH p.start"/"LEN" on a raw pointer's own
                    // address, never the pointee). Fixed here by
                    // dereferencing first, using the identical `deref()`
                    // builtin the compiler itself already lowers to
                    // "PUSH pointer / DEREF pointeeType" (BytecodeEmitter's
                    // own "deref" case) -- reusing this pass's existing
                    // declareTemp/widened-POP machinery to land the
                    // dereferenced value in a fresh, storage-free temp:
                    // "PUSH rightName t2" / "DEREF derefType" / "POP
                    // derefName derefType" (see derefInto's own doc
                    // comment for why DEREF carries that operand at all) --
                    // and the resulting temp's type has no storage of its
                    // own (mutability_
                    // baseType, storage stripped, exactly like TypeChecker.
                    // checkDerefBuiltin's own TypeInfo), so its ".start"/
                    // ".end" access resolves in AddressLoweringPass exactly
                    // like an ordinary, non-pointer range already does.
                    //
                    // isGenuinePointer -- NOT plain `storage != null` --
                    // is the right gate here: a real, correctness bug
                    // found and fixed directly. An ordinary dynarray value
                    // *always* carries "owns" storage itself (that's just
                    // how a dynarray's own runtime handle is represented,
                    // the identical "buffer pointer plus bookkeeping"
                    // shape LEN/LOOKUP_DYN etc. already read directly, no
                    // prior DEREF needed), so "owns" storage on a dynarray
                    // means "this is a plain dynarray value," never "this
                    // is a pointer wrapping one" -- unlike "raw"/"ref",
                    // which genuinely do mean the latter. The plain
                    // `storage != null` version of this check wrongly
                    // dereferenced *every* ordinary "x in dynarray" (any
                    // bare, non-pointer dynarray local), not just the
                    // genuinely pointer-wrapped ones -- confirmed directly
                    // against a real compiled fixture using a plain local
                    // dynarray (not a pointer, not a materialized call
                    // result) as the right operand, the first fixture in
                    // this pass's own history to actually exercise that
                    // exact shape.
                    if (isGenuinePointer(rightCanonical)) {
                        String[] deref = derefInto(rightName, t2, rightCanonical, outSoFar, lines);
                        rightName = deref[0];
                        t2 = deref[1];
                    }

                    if (rightIsRange) {
                        lines.addAll(buildRangeCheck(leftName, t1, rightName, t3));
                    } else {
                        lines.addAll(buildDynArrayCheck(leftName, t1, rightName, t2, t3));
                    }
                    return new InMatch(lines, !leftSimple, !rightSimple);
                }
            }

            // Shape 4: a *range*-shaped left operand (bare "range(...)"
            // or a pointer to one -- checkIn's own isRangeType only looks
            // at baseType text, never storage, so both are legally
            // accepted, same as the right side already was) against
            // either a range-shaped or a dynarray-shaped right operand:
            //   - "r1 in r2" -- the ordinary non-strict subset test,
            //     "r1.start >= r2.start && r1.end <= r2.end" -- confirmed
            //     directly as the right pairing with "in" (as opposed to
            //     "within", which is the separate, still-unhandled
            //     *strict* subset variant that additionally excludes the
            //     case where both sides carry identical bounds, "1..2 is
            //     within 0..3 but 0..3 is not within 0..3" -- see this
            //     pass's own header).
            //   - "r in dynarray" -- "is the whole range a valid index
            //     span into the dynarray," i.e. "r.end <= LEN(dynarray)"
            //     (the lower bound "r.start >= 0" is trivially true for a
            //     u64 range, the same reasoning buildDynArrayCheck already
            //     uses to drop it for a scalar u64 left). Confirmed
            //     directly this must be "<=", not "<": a dynarray of
            //     length 5 has valid indices 0..4, and the range "0..5"
            //     (start=0, end=5) is *exactly* that full valid span, so
            //     it must test as fully "in" the dynarray -- "5 < 5" is
            //     false (would wrongly reject the range that exactly
            //     covers the whole array), "5 <= 5" is true (correct).
            //     Consistent with the range-subset rule just above,
            //     treating the dynarray as an implicit "0..LEN" range and
            //     dropping the always-true lower bound the identical way
            //     the scalar case already does.
            // See the identical widening (and its own doc comment) just
            // above, in Shape 2/3's own "rightIsRange" -- bare "range" is
            // exactly as real a range as a literal-bounds one here too.
            boolean leftIsRangeShaped = leftCanonical.baseType.equals("range") || leftCanonical.baseType.startsWith("range(");
            if (leftIsRangeShaped) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                boolean rightIsRangeShaped = rightCanonical.baseType.equals("range") || rightCanonical.baseType.startsWith("range(");
                boolean rightIsDynArrayShaped = rightCanonical.baseType.startsWith("dynarray(");
                if (rightIsRangeShaped || rightIsDynArrayShaped) {
                    // Identical materialize-into-temp treatment as Shape
                    // 2/3 above -- right popped before left (it's the one
                    // sitting on top of the stack), left unconditionally
                    // materialized too whenever right isn't a simple push
                    // (same "outSoFar's second-to-last entry isn't
                    // trustworthy once right spans more than one line"
                    // reasoning documented above).
                    List<String> lines = new ArrayList<>();
                    boolean rightSimple = isSimplePush(rightPush);
                    String rightName;
                    String rightType = t2;
                    if (rightSimple) {
                        rightName = rightPush.get(1).text;
                    } else {
                        rightName = declareTemp(outSoFar, t2);
                        lines.add("POP " + rightName + " " + t2);
                    }
                    boolean leftSimple = rightSimple && isSimplePush(leftPush);
                    String leftName;
                    String leftType = t1;
                    if (leftSimple) {
                        leftName = leftPush.get(1).text;
                    } else {
                        leftName = declareTemp(outSoFar, t1);
                        lines.add("POP " + leftName + " " + t1);
                    }

                    // Unlike Shape 2/3 (where the left operand is always
                    // a plain scalar "u64", never a pointer -- checkIn
                    // itself rejects a pointer-typed "u64" left outright),
                    // a range-shaped left can genuinely be pointer-wrapped
                    // here too, so both sides get the identical
                    // dereference-into-a-fresh-temp treatment, each
                    // independently, whichever side(s) actually need it.
                    // isGenuinePointer, not plain `storage != null` -- see
                    // the identical fix and its "owns" explanation just
                    // above in Shape 2/3; matters here specifically for a
                    // dynarray-shaped right operand (an ordinary dynarray
                    // value's own "owns" storage must never trigger a
                    // dereference). A range-shaped operand's own storage
                    // is always null by construction (TypeChecker.
                    // checkRange), so this makes no difference for the
                    // range-vs-range pairing -- only for range-vs-dynarray.
                    if (isGenuinePointer(rightCanonical)) {
                        String[] deref = derefInto(rightName, rightType, rightCanonical, outSoFar, lines);
                        rightName = deref[0];
                        rightType = deref[1];
                    }
                    if (isGenuinePointer(leftCanonical)) {
                        String[] deref = derefInto(leftName, leftType, leftCanonical, outSoFar, lines);
                        leftName = deref[0];
                    }

                    if (rightIsRangeShaped) {
                        lines.addAll(buildRangeSubsetCheck(leftName, rightName, t3));
                    } else {
                        lines.addAll(buildRangeInDynArrayCheck(leftName, rightName, rightType, t3));
                    }
                    return new InMatch(lines, !leftSimple, !rightSimple);
                }
            }

            return null; // some other shape this pass doesn't lower
        }

        /**
         * `withinLine` is "WITHIN t1 t2 t3". `checkWithin` (the compiler's
         * own type rule) guarantees both operands are always range-shaped
         * (bare "range(...)" or a pointer to one) -- unlike "in", `within`
         * never legally accepts a dynarray on either side, and a plain
         * "u64" is never legal on the left either (only a range being
         * compared against another range is meaningful for "within" --
         * "a single point has nothing to be within"). So there's only
         * ever this one shape to handle, not a per-shape dispatch the way
         * `tryRewriteIn` needs -- `outSoFar`'s last two entries are the
         * original left/right operand pushes, exactly as for "in".
         *
         * The compiler no longer emits a separate "PTR_WITHIN" mnemonic
         * for a pointer-wrapped operand (see this project's own CLAUDE.md,
         * "Membership-operator lowering" -- that split predates this
         * pass's own pointer-dereference mechanism and is now redundant
         * with it, the identical "IN never needed a PTR_IN either" logic
         * the sibling caspien-compiler project's own CLAUDE.md records):
         * a genuinely pointer-wrapped operand is detected here, by
         * `isGenuinePointer`, exactly the same way "in"'s own range-left
         * shape (`tryRewriteIn`'s Shape 4) already does, and dereferenced
         * into a fresh temp before the comparison, independently per
         * side.
         */
        private InMatch tryRewriteWithin(List<BytecodeToken> withinLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = withinLine.get(1).text;
            String t2 = withinLine.get(2).text;
            String t3 = withinLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            CanonicalType leftCanonical = CanonicalType.parse(t1);
            CanonicalType rightCanonical = CanonicalType.parse(t2);
            // Bare "range" accepted alongside "range(...)" here too -- see
            // the identical widening (and its own doc comment) in
            // tryRewriteIn's Shape 2/3 "rightIsRange" above. This was the
            // one real, previously-documented gap this narrow check
            // caused ("WITHIN's incomplete-range case," flagged in
            // caspien-codegen's own X86Backend class comment as needing a
            // fix "one layer up in caspien-lowerordergenerator itself") --
            // fixed at the actual root here, not patched around downstream.
            if ((!leftCanonical.baseType.equals("range") && !leftCanonical.baseType.startsWith("range("))
                    || (!rightCanonical.baseType.equals("range") && !rightCanonical.baseType.startsWith("range("))) {
                // Should never actually happen -- checkWithin's own type
                // rule guarantees both sides are range-shaped -- but the
                // same "let the pattern fail rather than guess" fallback
                // every other gap in this pass already relies on, in case
                // that rule is ever loosened later without this pass
                // being revisited.
                return null;
            }

            // Identical materialize-into-temp treatment as "in"'s own
            // range-vs-range shape -- right popped before left (it's the
            // one sitting on top of the stack), left unconditionally
            // materialized too whenever right isn't a simple push (see
            // tryRewriteIn's own header for the full "outSoFar's second-
            // to-last entry isn't trustworthy once right spans more than
            // one line" reasoning).
            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String rightName;
            if (rightSimple) {
                rightName = rightPush.get(1).text;
            } else {
                rightName = declareTemp(outSoFar, t2);
                lines.add("POP " + rightName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String leftName;
            if (leftSimple) {
                leftName = leftPush.get(1).text;
            } else {
                leftName = declareTemp(outSoFar, t1);
                lines.add("POP " + leftName + " " + t1);
            }

            // Dereference whichever side is genuinely pointer-wrapped --
            // `isGenuinePointer`, not plain `storage != null` (see that
            // helper's own doc for why -- irrelevant here in practice,
            // since a range's own storage is always null by construction,
            // but kept consistent with every other call site in this
            // pass rather than special-cased).
            if (isGenuinePointer(rightCanonical)) {
                String[] deref = derefInto(rightName, t2, rightCanonical, outSoFar, lines);
                rightName = deref[0];
            }
            if (isGenuinePointer(leftCanonical)) {
                String[] deref = derefInto(leftName, t1, leftCanonical, outSoFar, lines);
                leftName = deref[0];
            }

            lines.addAll(buildRangeStrictSubsetCheck(leftName, rightName, t3));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "r is base" -- "match RANGE_PARAM is base{...}," confirmed
         * directly: a reserved, special-purpose match condition (never a
         * general "is" operator -- `TypeChecker`'s own dedicated "is"
         * case, not a `resolveExprType` dispatch entry), true exactly
         * when the range's own bounds have already met ("its start has
         * reached its end," checked purely at runtime since a range's
         * bounds aren't necessarily known at compile time). Structurally
         * closest to `tryRewriteInstanceofOrImplements` -- a single
         * preceding operand push, no second operand at all (unlike
         * IN/WITHIN's own always-two-pushes shape) -- reusing the
         * identical `InstanceofMatch`/`resolveLeftName`/`isGenuinePointer`/
         * `derefInto` machinery rather than inventing parallel plumbing
         * for what's otherwise the same "materialize a non-simple
         * operand, dereference a genuinely pointer-wrapped one" shape
         * every other rewrite in this pass already follows.
         *
         * `checkIs`'s own `isRangeType` check (TypeChecker) only looks at
         * `baseType` text, exactly like `checkIn`'s range-left shape --
         * never at storage -- so a pointer-wrapped range ("raw
         * range(...)") is just as legal here as a bare one, and gets the
         * identical dereference treatment `tryRewriteIn`'s own Shape 4
         * and `tryRewriteWithin` already established for a range-typed
         * operand.
         *
         * "DEBUG can be removed... I mean the full thing, no special
         * debug() keyword or builtin in the language," confirmed
         * directly and done separately from this method (BytecodeEmitter/
         * TypeChecker/Lexer/Parser no longer know the word "debug" at
         * all) -- noted here only because IS_BASE was originally
         * investigated alongside a leftover DEBUG mnemonic search; the
         * two features are otherwise unrelated.
         *
         * `leftType` is read directly off the `IS_BASE leftType` line
         * itself (`BytecodeEmitter`'s own "is" case), not inferred from
         * whatever bytecode line happens to precede it -- a real,
         * confirmed bug, fixed directly: the earlier version of this
         * method read `leftPush`'s own trailing token as "the operand's
         * type," which is correct only when `leftPush` really is the
         * operand's one and only line (a bare, named range reference).
         * The moment the left operand is instead an inline range
         * *literal* ("match (2..5) is base{...}" -- legal; confirmed by
         * actually compiling it -- `checkMatchCondition`'s "is" case only
         * requires `isRangeType(leftType)`, never that `node.left` itself
         * be a bare VARREF), `BytecodeEmitter` pushes its two bounds as
         * two separate, ordinary scalar "PUSH n u64" lines with no
         * consolidating instruction -- so `leftPush` is only ever the
         * upper bound's own bare push, and its own trailing token is
         * "u64", never "range(...)". No amount of "materialize into a
         * temp of the right type" fixes that on its own, because the
         * *type itself* was never recoverable from `leftPush`'s own shape
         * to begin with -- the only fix that actually closes this is
         * reading it from where it still reliably exists, the `IS_BASE`
         * line's own now-real operand.
         */
        private InstanceofMatch tryRewriteIsBase(String leftType, List<List<BytecodeToken>> outSoFar) {
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 1);
            boolean leftSimple = isSimplePush(leftPush);
            CanonicalType leftCanonical = CanonicalType.parse(leftType);
            // `TypeChecker.isRangeType`'s own two-part check, not just
            // `startsWith("range(")` -- a "@recursive" function's own
            // final range parameter is legally declared as plain, bare
            // "range" (no known "start"/"end" field names at that scope,
            // "an incomplete range pointer" -- TypeChecker's own
            // isCompleteRange distinction), not necessarily a concrete
            // "range(x,y)". `AddressLoweringPass.pseudoMembersOf` already
            // handles both spellings identically for ".start"/".end"
            // resolution, so there's no reason for this rewrite to
            // recognize only the concrete one -- confirmed directly this
            // was the actual reason the very first "r is base" fixture
            // tried against this rewrite silently failed to match at all
            // (BytecodeEmitter emits the range parameter's own push as
            // bare "PUSH r imut_range", never a concrete "imut_range(...)"
            // -- only a *local* range variable's own declared type gets
            // to know its field names).
            if (!leftCanonical.baseType.equals("range") && !leftCanonical.baseType.startsWith("range(")) {
                return null; // should never actually happen -- checkIs's own isRangeType check guarantees a range-typed left
            }

            List<String> lines = new ArrayList<>();
            String rangeName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
            if (isGenuinePointer(leftCanonical)) {
                String[] deref = derefInto(rangeName, leftType, leftCanonical, outSoFar, lines);
                rangeName = deref[0];
            }
            lines.addAll(buildIsBaseCheck(rangeName));
            return new InstanceofMatch(lines, !leftSimple);
        }

        /**
         * "r is base" -- "r.start >= r.end," confirmed directly: the
         * range is exhausted once its own start has caught up to (or
         * somehow passed) its end. Deliberately `GT_EQ`, not `EQ` --
         * matching this pass's own established "don't assume a range's
         * bounds can only ever move one specific way" caution
         * (`buildRangeSubsetCheck`'s own bounds comparisons make no
         * assumption about how a range got to whatever state it's in
         * either), so a range that somehow overshot its own end (rather
         * than landing exactly on it) is still correctly reported as
         * "base" rather than silently falling through as neither
         * "before" nor "at" the end.
         */
        private List<String> buildIsBaseCheck(String rangeName) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + rangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            return lines;
        }

        /**
         * "u64 in string" -- lowered to a real byte-by-byte runtime scan
         * (see `buildStringScanCheck`), the first rewrite in this whole
         * pass that needs actual control flow (a loop) rather than a
         * single straight-line replacement -- confirmed directly: "so
         * that should be lowered to using strlen()," refined to a plain
         * forward scan with no `strlen()` call and no length known
         * upfront at all, since a raw scan to the null terminator carries
         * exactly the same semantics -- and exactly the same single
         * caveat (both stop at the very first '\0' byte) -- strlen()
         * itself already has; nothing about "weird"/non-ASCII byte values
         * introduces any additional risk, since every comparison here is
         * a plain numeric byte compare, never a text/locale-aware
         * operation.
         *
         * `checkIn`'s own "u64 in string" branch (this pass's own header,
         * "Known gaps" entry now retired) guarantees the left operand is
         * always exactly a plain "u64" (`isPlainU64` -- no storage, so
         * always exactly 8 bytes, never itself a pointer) and the right
         * operand's `baseType` is always exactly "string" -- though, like
         * every other shape in this pass, only `baseType` text is ever
         * checked there, never storage, so a genuinely pointer-wrapped
         * string ("raw string") is legally possible and gets the
         * identical `isGenuinePointer`/`derefInto` treatment every other
         * operand here already gets. Identical materialize-into-temp
         * bookkeeping to `tryRewriteIn`'s own Shape 2/3 otherwise -- right
         * popped before left, left unconditionally materialized too
         * whenever right isn't a simple push (see `tryRewriteIn`'s own
         * header for the full reasoning).
         */
        private InMatch tryRewriteInScan(List<BytecodeToken> inScanLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = inScanLine.get(1).text;
            String t2 = inScanLine.get(2).text;
            String t3 = inScanLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            CanonicalType leftCanonical = CanonicalType.parse(t1);
            CanonicalType rightCanonical = CanonicalType.parse(t2);
            if (leftCanonical.storage != null || !leftCanonical.baseType.equals("u64")
                    || !rightCanonical.baseType.equals("string")) {
                return null; // should never actually happen -- checkIn's own "u64 in string" branch guarantees this shape
            }

            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String rightName;
            String rightType = t2;
            if (rightSimple) {
                rightName = rightPush.get(1).text;
            } else {
                rightName = declareTemp(outSoFar, t2);
                lines.add("POP " + rightName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String leftName;
            if (leftSimple) {
                leftName = leftPush.get(1).text;
            } else {
                leftName = declareTemp(outSoFar, t1);
                lines.add("POP " + leftName + " " + t1);
            }

            // NOT `isGenuinePointer` here -- a real, found-and-fixed bug,
            // confirmed directly against a real compiled+optimized probe
            // ("code in \"hello\""): a string literal's own canonical type
            // is always "static_some_imut_string" (see `CanonicalType`'s
            // own doc comment, fixed alongside this), and `isGenuinePointer`
            // (storage != null && !isOwnsStorage()) treats any non-"owns"
            // storage as a wrapping indirection needing a `DEREF` first --
            // correct for a *struct*-typed operand (where "raw"/"ref"/
            // "auto"/"static" all genuinely point *at* a boxed value sitting
            // elsewhere), but wrong for `string`: `string` is this
            // language's one and only `pointerLikeBaseType` (confirmed
            // directly, `TypeChecker.pointerLikeBaseTypes`), meaning its
            // value already *is* the pointer, at every one of the five
            // storage keywords -- "static" is a string's own intrinsic,
            // already-addressable representation the exact same way "owns"
            // already is for a dynarray's own runtime handle (this pass's
            // own `isGenuinePointer` doc comment), not a second indirection
            // layer. Naively dereferencing a "static"-storage string
            // produced genuinely wrong bytecode ("PUSH string_id1 / DEREF"
            // on a bare string-literal push, confirmed against the real
            // optimized probe output) before this fix. Only a genuinely
            // *further*-wrapping storage on a string ("raw"/"ref", a
            // pointer to a string sitting elsewhere -- legally possible per
            // `checkIn`'s own "only baseType is checked, never storage"
            // comment) still needs dereferencing here.
            boolean rightNeedsDeref = rightCanonical.storage != null && !"static".equals(rightCanonical.storage);
            if (rightNeedsDeref) {
                String[] deref = derefInto(rightName, rightType, rightCanonical, outSoFar, lines);
                rightName = deref[0];
                rightType = deref[1];
            }

            lines.addAll(buildStringScanCheck(leftName, t1, rightName, rightType, t3, outSoFar));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "leftValue in rightString" -- a real byte-by-byte runtime scan,
         * walking forward from index 0 until either a matching byte is
         * found (pushes `true`) or the null terminator is hit first
         * (pushes `false`) -- see `tryRewriteInScan`'s own header for why
         * this needs no `strlen()` call and no length known upfront.
         *
         * Each loop iteration:
         *   1. Reads the string byte at `counter` via an ordinary
         *      `LOOKUP` (`AddressLoweringPass.lookupMnemonicFor` maps a
         *      string's own baseType to `LOOKUP_ARRAY`, exactly like a
         *      fixed array -- direct, fixed-width addressing). This is a
         *      genuinely unchecked, un-bounds-proven index read -- fine
         *      here specifically because `checkLookup`'s source-level
         *      "index must be a literal or have a live bounds-proof" gate
         *      only ever binds bytecode the *source* compiles through
         *      `TypeChecker`, never bytecode an optimizer pass fabricates
         *      directly (this pass already relies on that exact
         *      distinction implicitly everywhere else it synthesizes
         *      PUSH/POP/comparison sequences) -- and a forward scan that's
         *      guaranteed to stop at the first '\0' byte is exactly the
         *      same trust model `strlen`/`strchr` already rely on in C.
         *   2. Compares that byte against the literal `'\0'` first, at the
         *      true single-byte width both sides share (`imut_char`,
         *      confirmed 1 byte -- `SizeCalculator`) -- matters because
         *      `AddressLoweringPass`'s GT/LT/EQ family sizes a comparison
         *      from its *first* operand's type alone, so both pushed
         *      values must actually be the same width at runtime.
         *   3. Compares that same byte, widened to a full `u64` via `ZEXT`
         *      (always a genuine 1-to-8-byte zero-extend here, never a
         *      same-width no-op), against `leftName` -- both sides now
         *      genuinely 8 bytes wide, matching that same "first operand's
         *      type decides the whole comparison's width" rule.
         *   4. On no match, increments `counter` and loops back -- the
         *      identical `ADDR`/`PUSH`/`INC`/`ASSIGN` shape
         *      `CloneGenerationPass.buildCloneLoop`'s own counter
         *      increment already establishes, reused verbatim rather than
         *      inventing a second convention for the same operation.
         * The two divergent exits (found vs. hit-the-terminator) each push
         * their own boolean literal and fall straight through to the same
         * next instruction -- an ordinary stack-machine merge, no
         * different from how an if/else's own two branches already
         * converge with no explicit "phi" needed.
         */
        private List<String> buildStringScanCheck(String leftName, String leftType, String rightName,
                String rightType, String resultType, List<List<BytecodeToken>> outSoFar) {
            List<String> lines = new ArrayList<>();

            String counter = declareTemp(outSoFar, "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String charTemp = declareTemp(outSoFar, "imut_char");

            String topLabel = newLabel("in_scan_loop");
            String notFoundLabel = newLabel("in_scan_not_found");
            String incrementLabel = newLabel("in_scan_increment");
            String endLabel = newLabel("in_scan_end");

            lines.add(topLabel + ":");
            lines.add("PUSH " + rightName + " " + rightType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP " + rightType + " mut_u64 imut_char");
            lines.add("POP " + charTemp + " imut_char");

            lines.add("PUSH " + charTemp + " imut_char");
            lines.add("PUSH '\\0' imut_char");
            lines.add("EQ imut_char imut_char imut_bool");
            lines.add("CMP");
            lines.add("JMP " + notFoundLabel);

            lines.add("PUSH " + charTemp + " imut_char");
            lines.add("ZEXT imut_char indeterminate_u64");
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("EQ indeterminate_u64 " + leftType + " imut_bool");
            lines.add("CMP");
            lines.add("JMP " + incrementLabel);

            lines.add("PUSH true " + resultType);
            lines.add("JMP " + endLabel);

            lines.add(incrementLabel + ":");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);

            lines.add(notFoundLabel + ":");
            lines.add("PUSH false " + resultType);

            lines.add(endLabel + ":");

            return lines;
        }

        private String newLabel(String prefix) {
            labelCounter++;
            return "@" + prefix + "_" + labelCounter;
        }

        /**
         * "len(unsafeDynArr, terminator)" -- the identical "no stored
         * length, must scan" situation `IN_SCAN` already had for a
         * string, just parameterized: the terminator is a real runtime
         * operand (any value, not hardcoded to `'\0'`), and the element
         * type is whatever the unsafe dynarray was declared with, not
         * fixed to `char`. Confirmed directly, right after `IN_SCAN`
         * shipped: "or base + 8 + index*width if 8 is for the length
         * stored at the beginning, if its safe one or an unsafe one...
         * this needs to be comprehensively fixed" -- the `LOOKUP`/
         * `LOOKUP_DYN` addressing fix that unblocked this (see this
         * project's own CLAUDE.md, "LOOKUP_DYN's 'handle indirection' was
         * a design bug") is what makes an ordinary `LOOKUP` into the
         * unsafe dynarray's own elements resolve correctly at all.
         *
         * Genuinely *simpler* than `buildStringScanCheck` in one respect:
         * `checkLenBuiltin`'s own 2-arg branch requires the terminator's
         * `baseType` to match the unsafe dynarray's element `baseType`
         * exactly, so both sides of the per-iteration comparison are
         * always already the same width -- no `ZEXT`/widening step is
         * ever needed here, unlike `IN_SCAN`'s fixed 1-byte-`char`-vs-
         * 8-byte-`u64` mismatch. And *no dereference handling at all* is
         * needed for the target: `TypeInfo`'s own `unsafeDynArray`
         * constructor unconditionally forces `"owns"` storage the moment
         * an "unsafe dynarray(T)" type annotation is parsed (rejecting
         * any other storage keyword outright,
         * `TypeChecker`'s own explicit check) -- so, unlike a string or
         * an ordinary dynarray, a genuinely pointer-wrapped unsafe
         * dynarray target can never legally exist in the first place;
         * `isGenuinePointer`/`derefInto` would never have anything to do
         * here.
         *
         * Unlike `IN_SCAN` (a boolean membership test), this is a real
         * *count*: the loop's own running index, at the moment the
         * terminator is found, IS the answer (the terminator itself is
         * never counted) -- so the loop's exit path pushes the counter's
         * own current value, not a `true`/`false` literal.
         */
        private InMatch tryRewriteLenScan(List<BytecodeToken> lenScanLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = lenScanLine.get(1).text; // the unsafe dynarray target's own type
            String t2 = lenScanLine.get(2).text; // the terminator's own type

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1); // the terminator -- pushed last, sits on top
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2); // the target -- pushed first

            CanonicalType targetCanonical = CanonicalType.parse(t1);
            if (CanonicalType.unsafeDynArrayElementTypeOf(targetCanonical.baseType) == null) {
                return null; // should never actually happen -- checkLenBuiltin's own 2-arg branch guarantees an unsafe dynarray target
            }

            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String termName;
            if (rightSimple) {
                termName = rightPush.get(1).text;
            } else {
                termName = declareTemp(outSoFar, t2);
                lines.add("POP " + termName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String targetName;
            if (leftSimple) {
                targetName = leftPush.get(1).text;
            } else {
                targetName = declareTemp(outSoFar, t1);
                lines.add("POP " + targetName + " " + t1);
            }

            lines.addAll(buildLenScanCheck(targetName, t1, termName, t2, outSoFar));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "len(targetName, termName)" -- walk forward from index 0,
         * comparing each element against the terminator, until a match is
         * found; the index at that point is the length. See
         * `tryRewriteLenScan`'s own header for why no widening and no
         * dereference are ever needed here, unlike `buildStringScanCheck`.
         */
        private List<String> buildLenScanCheck(String targetName, String targetType, String termName,
                String termType, List<List<BytecodeToken>> outSoFar) {
            List<String> lines = new ArrayList<>();

            String counter = declareTemp(outSoFar, "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String elemTemp = declareTemp(outSoFar, termType);

            String topLabel = newLabel("len_scan_loop");
            String incrementLabel = newLabel("len_scan_increment");
            String endLabel = newLabel("len_scan_end");

            lines.add(topLabel + ":");
            lines.add("PUSH " + targetName + " " + targetType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP " + targetType + " indeterminate_u64 " + termType);
            lines.add("POP " + elemTemp + " " + termType);

            lines.add("PUSH " + elemTemp + " " + termType);
            lines.add("PUSH " + termName + " " + termType);
            lines.add("EQ " + termType + " " + termType + " imut_bool");
            lines.add("CMP");
            lines.add("JMP " + incrementLabel);

            lines.add("PUSH " + counter + " indeterminate_u64");
            lines.add("JMP " + endLabel);

            lines.add(incrementLabel + ":");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);

            lines.add(endLabel + ":");

            return lines;
        }

        /**
         * Dereferences a pointer-typed operand already sitting in a
         * named local (`name`, of canonical type `type`/`canonical`) into
         * a fresh, storage-free temp: "PUSH name type" / "DEREF derefType"
         * / "POP derefTemp derefType", appended to `lines`, reusing this
         * pass's existing `declareTemp`/widened-`POP` machinery. The
         * returned temp's own type has storage stripped (`mutability_
         * baseType`, matching `TypeChecker.checkDerefBuiltin`'s own
         * `TypeInfo`), so a dotted `.start`/`.end`/`.<field>` access on it
         * resolves in `AddressLoweringPass` exactly like an ordinary,
         * non-pointer value of that same shape already does. Returns
         * `{derefName, derefType}`. Only ever called when
         * `canonical.storage != null` -- callers check that themselves,
         * since "was this pointer-wrapped at all" also decides whether the
         * *type text* handed to a later comparison builder needs to
         * change to the dereferenced one.
         *
         * `DEREF` now carries its own pointee-type operand -- a real,
         * found-and-fixed gap, confirmed directly: "a deref is
         * essentially a copy," and on this bytecode's own byte-precise
         * stack, a copy needs to know how many bytes to move, which the
         * old bare "DEREF" (matching `BytecodeEmitter`'s own former
         * shape, before this same fix) never carried anywhere recoverable
         * -- the preceding "PUSH name type" line's own erased width is
         * always exactly 8 (every pointer is one machine word), which
         * describes the *pointer's* size, never the *pointee's*.
         * `derefType` (already computed here, for the exact same reason
         * the following `POP` line needs it) is exactly the operand
         * `DEREF` itself needs too, so this already-correct call site
         * needed no separate lookup -- just passing the same value
         * through to one more line.
         */
        private String[] derefInto(String name, String type, CanonicalType canonical,
                List<List<BytecodeToken>> outSoFar, List<String> lines) {
            String derefType = canonical.mutability != null
                    ? canonical.mutability + "_" + canonical.baseType
                    : canonical.baseType;
            String derefName = declareTemp(outSoFar, derefType);
            lines.add("PUSH " + name + " " + type);
            lines.add("DEREF " + derefType);
            lines.add("POP " + derefName + " " + derefType);
            return new String[]{derefName, derefType};
        }

        /**
         * Declares a fresh, uniquely-named scratch local ("$in_tmpN") for
         * materializing an "in" operand that wasn't a simple named push
         * (see tryRewriteIn's own "materialize the left operand" branch)
         * -- inserts its own "ALLOC name type" line into `out` right after
         * this function's existing ALLOC prologue block (tracked by
         * allocInsertIndex, computed once per function in
         * rewriteFunctionBody). A "$"-prefixed name can never collide with
         * a real source identifier (the same convention EnumTable's own
         * "$enum_for_" synthetic names already establish), and it needs no
         * separate cleanup of any kind -- it's an ordinary local from this
         * point on, gone the same way any other local already is once the
         * function returns.
         */
        private String declareTemp(List<List<BytecodeToken>> out, String type) {
            String name = "$in_tmp" + (++tempCounter);
            out.add(allocInsertIndex,
                    PARSER.parse(Collections.singletonList("ALLOC " + name + " " + type),
                            "<generated-membership-lowering>").get(0));
            allocInsertIndex++;
            return name;
        }

        /**
         * `keepLeftPush` mirrors `InMatch`'s own field of the same name --
         * true exactly when the left operand had to be materialized into a
         * fresh temp rather than being a simple named push (see
         * `tryRewriteInstanceofOrImplements`'s own "materialize the left
         * operand" handling, below), meaning its own original bytecode is
         * essential (real computation, possibly with side effects) and
         * must be left standing in `out` rather than dropped.
         */
        private static final class InstanceofMatch {
            final List<String> lines;
            final boolean keepLeftPush;
            InstanceofMatch(List<String> lines, boolean keepLeftPush) {
                this.lines = lines;
                this.keepLeftPush = keepLeftPush;
            }
        }

        /**
         * Originally required the struct-typed left operand of
         * "instanceof"/"implements" to already be a simple named push
         * ("PUSH name type"), unconditionally bailing (leaving the
         * original INSTANCEOF/IMPLEMENTS bytecode untouched) for anything
         * else -- e.g. "makeThing() instanceof Foo", where the left
         * operand is a multi-line CALL sequence ending in its own
         * "PUSH_RET type" line, never a bare "PUSH name type". This was
         * never a correctness bug the way "in"'s own analogous gap once
         * was (a *complex left operand of "in"* materialization mistake
         * this pass's own header documents at length) -- confirmed
         * directly by compiling and lowering a real "makeSub() instanceof
         * Base" fixture: the pass correctly detected the non-simple shape
         * and left the original bytecode standing, unmodified but
         * perfectly correct, just unoptimized. But it *was* an
         * incompleteness this pass simply hadn't gotten around to yet --
         * "this is because we did the implements/instanceof before the IN
         * work... the implements/instanceof just doesn't reflect that,"
         * confirmed directly -- so it's fixed here the same way "in"'s own
         * left operand was: materialize into a fresh temp via
         * `declareTemp`/a "POP name type" right after the operand's own
         * (untouched) emission, then read the temp back by name exactly
         * like a simple push would have been.
         *
         * Unlike "in", there is no analogous "right operand might be
         * multi-line, so don't trust outSoFar's second-to-last-entry
         * position" risk to guard against here at all: `checkInstanceof`/
         * `checkImplementsOperator` both require the right/target operand
         * to be a bare `VARREF` naming a real struct/interface (never a
         * computed value), so `BytecodeEmitter` only ever emits it as a
         * single, invariant "PUSH name" line (`isBarePush`, already
         * checked via `targetPushLine`). That means `outSoFar`'s second-
         * to-last entry is *always* reliably the left operand's own final
         * line, however many lines produced it -- no unconditional-
         * materialize-both-sides workaround (the "in" fix's own
         * safety net for its genuinely multi-line right operand) is
         * needed at all here.
         *
         * The left operand's own type (needed both to confirm it's a
         * classId-bearing struct at all, and to declare a correctly-typed
         * temp for it) is read off that same final line's own trailing
         * type token when it isn't a simple push -- true of every
         * terminal, single-value-producing instruction this bytecode
         * emits ("PUSH_RET type" for a CALL result, "LOOKUP ... resultType"
         * for an array/dynarray element, an ordinary "PUSH name type" for
         * a simple push, ...): the result type is always the line's very
         * last token, the same convention `declareTemp`'s own callers
         * already rely on elsewhere in this file. A genuinely typeless
         * final line (a bare, operand-free "DEREF", say) has no such
         * token at all -- `leftPush.size() < 2` catches that and bails,
         * joining the same "pattern doesn't match, leave it alone"
         * category every other unrecognized shape in this pass already
         * falls into, rather than guessing.
         *
         * Every bail-out that doesn't depend on the left operand's own
         * name (the struct/`___type`-field check, `INSTANCEOF`'s
         * `classRangeOf` lookup) happens *before* any temp is declared, so
         * a rewrite that ultimately can't proceed never leaves a stray,
         * unused "ALLOC"/orphaned "POP" behind -- `declareTemp` itself,
         * which mutates `outSoFar` immediately, is only ever called once
         * every other gate has already passed and the rewrite is
         * genuinely committed to succeeding.
         *
         * The zero-implementer `IMPLEMENTS` case (see the "PUSH false"
         * branch below) still materializes a non-simple left operand
         * exactly the same way, even though the constant-false result
         * never actually reads the temp back -- the original left
         * operand's own bytecode (and any side effects it has, e.g. a
         * real function call) must still run and have its value properly
         * consumed off the stack either way; only whether the generated
         * comparison text goes on to *reference* that temp differs.
         *
         * `leftType` is read directly off the `INSTANCEOF leftType`/
         * `IMPLEMENTS leftType` line itself (`BytecodeEmitter`'s own
         * "instanceof"/"implements" case), not inferred from whatever
         * bytecode line happens to precede it -- a real, confirmed bug,
         * fixed directly: the earlier version of this method read
         * `leftPush`'s own trailing token as "the operand's type," which
         * is correct only when `leftPush` really is the operand's one and
         * only line. `checkInstanceof` allows a bare, non-storage struct
         * value on the left (`requiresAliveProof`'s own carve-out for "an
         * inline (no-storage)... struct value"), and a bare, non-`new`
         * struct *literal* ("Point{x=1} instanceof Point" -- confirmed
         * legal by actually compiling it) is exactly that: emitted by
         * `emitInstantiate`'s non-`new` path as classId plus every member
         * pushed back-to-back with no consolidating instruction, so
         * `leftPush` in that case is only ever the *last member's own*
         * scalar push, whose trailing token is that member's own type,
         * never the struct's. Reading `leftType` off this operator's own
         * line instead closes that gap the same way `tryRewriteIsBase`'s
         * identical fix does.
         */
        private InstanceofMatch tryRewriteInstanceofOrImplements(String mnemonic, List<BytecodeToken> targetPushLine,
                String leftType, List<List<BytecodeToken>> outSoFar) {
            if (!isBarePush(targetPushLine)) {
                return null;
            }
            String targetName = targetPushLine.get(1).text;

            // outSoFar's last entry mirrors the target-name push itself
            // (already inspected separately, above, as `targetPushLine`
            // -- both refer to the same original line); the one before
            // it is the struct-typed left operand's own final line,
            // reliably so regardless of how many lines produced it (see
            // this method's own doc comment for why the target's
            // guaranteed single-line shape makes this safe, unlike "in").
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);
            boolean leftSimple = isSimplePush(leftPush);
            String structName = CanonicalType.parse(leftType).baseType;
            // An interface-typed left (`members == null` -- an interface
            // carries no STRUCT_START/STRUCT_MEMBER block of its own in
            // StructTable at all) is deliberately NOT rejected here
            // anymore -- confirmed directly: "the code generator
            // shouldn't care if the lefthand is struct-typed or
            // interface-typed, it's just a pointer to get the ___type
            // from." Whatever an interface reference actually points to
            // at runtime is always some real, concrete struct, and every
            // real struct's own hidden "___type" field is always at a
            // fixed offset (0) regardless of which static type (the
            // concrete struct itself, or an interface with no layout of
            // its own) was used to reach it -- see
            // AddressLoweringPass.memberLocOf, which now resolves
            // "___type" this same structure-agnostic way. `checkInstanceof`/
            // `checkImplementsOperator` already guarantee `structName`
            // names a real struct or a real interface by the time this
            // bytecode exists, so `members == null` here means
            // "interface," never "unresolvable name." The
            // `hasTypeField` check is kept, but now only as a defensive
            // guard against a real, *registered* struct somehow missing
            // its own "___type" field (should never happen, per the
            // compiler's own "___type is always first" invariant).
            List<StructTable.Member> members = structTable.membersOf(structName);
            if (members != null && !hasTypeField(members)) {
                return null; // a real, registered struct missing "___type" -- shouldn't happen, defensive bail
            }

            if (mnemonic.equals("INSTANCEOF")) {
                Long classId = enumTable.variantValue("Class", targetName);
                if (classId == null) {
                    return null; // targetName isn't a struct in the flat "Class" enum
                }
                List<String> lines = new ArrayList<>();
                String leftName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
                String typeFieldPath = typeFieldPathFor(leftName, leftType, outSoFar, lines);
                // flat class ids: "x instanceof S" is one class-id equality test
                lines.addAll(buildValueOrChain(typeFieldPath, java.util.Collections.singletonList(classId)));
                return new InstanceofMatch(lines, !leftSimple);
            } else {
                List<Long> implementerIds = enumTable.implementerClassIdsOf(targetName);
                List<String> lines = new ArrayList<>();
                String leftName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
                if (implementerIds.isEmpty()) {
                    // "x implements SomeInterface" where SomeInterface has
                    // no registered implementers anywhere in the whole
                    // compilation unit (never a source-order thing: every
                    // "impl X for Y" block is collected before any function
                    // body is checked, so this is a genuinely whole-program
                    // fact, not "none found yet") -- legal source (the
                    // interface itself is real, `checkImplementsOperator`
                    // never requires it to have an implementer at all), but
                    // provably always false: there is no possible runtime
                    // classId `left.___type` could ever hold that would
                    // satisfy it. Confirmed directly, reachable from real
                    // source (a declared-but-unimplemented interface),
                    // unlike `INSTANCEOF`'s own empty-range case just above
                    // (never actually reachable -- every legal `instanceof`
                    // target is a real struct, and every real struct is
                    // listed in the flat "Class" enum). Lowered to the same "single constant
                    // value" shape a real, non-empty chain would ultimately
                    // leave on the stack (a bare "imut_bool", matching
                    // `checkImplementsOperator`'s own result type) --
                    // `left`'s own push (or, when materialized, its own
                    // POP-consumed temp) is still dropped/discarded by the
                    // caller like any other rewrite here, since nothing
                    // about `left`'s actual runtime value is needed to
                    // know the answer -- only that whatever produced it
                    // (including any side effects) still actually ran.
                    // Deliberately not a compiler-side error or even a
                    // rejection here -- this pass's whole job is silent
                    // lowering; a human-facing diagnostic about a
                    // pointless/always-false check belongs at the type-
                    // checker level instead (see `checkImplementsOperator`'s
                    // own warning for this exact shape).
                    lines.add("PUSH false imut_bool");
                    return new InstanceofMatch(lines, !leftSimple);
                }
                // Only reached once there's a real, non-empty OR-chain to
                // build (so left's own runtime classId is actually
                // needed) -- unlike the "PUSH false" branch just above,
                // which never dereferences a pointer-typed left at all
                // since its result never depends on left's value.
                String typeFieldPath = typeFieldPathFor(leftName, leftType, outSoFar, lines);
                lines.addAll(buildValueOrChain(typeFieldPath, implementerIds));
                return new InstanceofMatch(lines, !leftSimple);
            }
        }

        /**
         * Resolves the left operand's own name for `typeFieldPath`
         * construction -- a simple push's own name, read fresh (no side
         * effect), or, for a non-simple left operand, the point where this
         * rewrite commits to materializing it: `declareTemp` (which
         * mutates `outSoFar` immediately, inserting a real "ALLOC" line)
         * is only ever called here, after every other gate that could
         * still bail this rewrite out has already passed, and the
         * resulting "POP name type" line -- which actually consumes the
         * left operand's already-computed value off the stack, preserving
         * whatever real computation (and any side effects) produced it --
         * is appended to `lines` before anything else.
         */
        private String resolveLeftName(boolean leftSimple, List<BytecodeToken> leftPush, String leftType,
                List<List<BytecodeToken>> outSoFar, List<String> lines) {
            if (leftSimple) {
                return leftPush.get(1).text;
            }
            String leftName = declareTemp(outSoFar, leftType);
            lines.add("POP " + leftName + " " + leftType);
            return leftName;
        }

        /**
         * Builds "leftName.___type" for `buildRangeMembershipCheck`/
         * `buildValueOrChain` -- dereferencing first, via the identical
         * `derefInto` machinery `tryRewriteIn`'s own pointer-typed range/
         * dynarray handling already uses, whenever `leftType` itself
         * carries a storage prefix. Found and fixed directly while
         * verifying the "materialize a non-simple left operand" work
         * above: a struct-returning function can *only* return a
         * storage-qualified value ("owns"/"ref"/"raw"/"auto"/"static" --
         * TypeChecker rejects returning a struct "by value" outright), so
         * the very shape that fix exists for ("makeThing() instanceof
         * Foo") is *always* storage-qualified -- but this pre-existing
         * gap turns out to affect the already-shipped *simple*-push case
         * identically: confirmed directly against a real compiled fixture
         * ("let sAuto = mut auto s; sAuto instanceof Base", no call
         * involved at all) that a bare "auto"-storage simple local hit
         * the exact same problem before either of today's fixes existed.
         * `AddressLoweringPass.resolveAddress`'s own dotted-chain
         * resolution unconditionally bails ("return null // pointer
         * indirection -- known gap") the moment any segment's own type
         * carries a storage prefix -- so emitting "leftName.___type"
         * directly against a storage-qualified `leftName` doesn't fail
         * loudly, it just leaves that exact dotted text sitting
         * unresolved, verbatim, in the supposedly-fully-lowered final
         * bytecode (confirmed directly: "PUSH 8 sAuto.___type" survived
         * all the way to the end of the pipeline, untouched, next to
         * ordinary already-resolved "$-N" addresses around it) --
         * silently wrong, not a clean bail. Unlike `isGenuinePointer`
         * (this pass's own dynarray-specific carve-out, which treats
         * "owns" as a dynarray value's own intrinsic representation, not
         * a wrapping indirection needing a further deref), a struct's own
         * storage-qualified local is *never* the inline struct sitting
         * directly at that address -- `resolveAddress`'s gate is
         * unconditional on any non-null storage, "owns" included, with no
         * such carve-out anywhere in `AddressLoweringPass` -- so the
         * right check here is plain `canonical.storage != null`, not
         * `isGenuinePointer`.
         */
        private String typeFieldPathFor(String leftName, String leftType, List<List<BytecodeToken>> outSoFar,
                List<String> lines) {
            CanonicalType canonical = CanonicalType.parse(leftType);
            if (canonical.storage != null) {
                String[] deref = derefInto(leftName, leftType, canonical, outSoFar, lines);
                return deref[0] + ".___type";
            }
            return leftName + ".___type";
        }

        // ---- shape checks -------------------------------------------------

        /**
         * True only for a genuine pointer *wrapping* a value ("raw X",
         * "ref X", ...) -- deliberately NOT true for "owns" storage,
         * which is a dynarray's (or an owns-allocated struct's) own
         * intrinsic representation, not a wrapping indirection. Plain
         * `canonical.storage != null` looks right but is a real, found-
         * and-fixed bug: every ordinary dynarray value already carries
         * "owns" storage itself (the same "buffer pointer plus
         * bookkeeping" handle LEN/LOOKUP_DYN already read directly, no
         * dereference needed), so that plain check wrongly dereferenced
         * *every* "x in dynarray" using a bare, non-pointer dynarray
         * local -- not just the genuinely pointer-wrapped ones this
         * pass's own dereference step exists for.
         */
        private static boolean isGenuinePointer(CanonicalType canonical) {
            // A `ref` to a dynarray is the same block pointer the owner
            // holds (`len(r)` and indexing read it directly), so it is a
            // dynarray value here, never a pointer to one; only `raw`
            // (the address of the variable) needs the dereference.
            if ("ref".equals(canonical.storage) && canonical.baseType.startsWith("dynarray(")) {
                return false;
            }
            return canonical.storage != null && !canonical.isOwnsStorage();
        }

        /**
         * True only for a token this compiler's own emission rules could
         * ever have produced as a *real, addressable reference* -- a bare
         * variable/parameter/temp name, a dotted chain rooted in one
         * ("p.x", "r.start"), or a hoisted string literal's own synthetic
         * id ("string_id1", a genuine, separately-declared name a later
         * stage resolves like any other global) -- as opposed to a bare
         * literal *value* that merely happens to sit in the same "PUSH X
         * type" text position: a raw integer/float digit sequence, a
         * quoted char literal, or one of the fixed literal keywords
         * (`true`/`false`/`null`).
         *
         * This is the real, root-cause fix for a bug found and reported
         * directly: `isSimplePush`/`isBarePush` used to check *shape*
         * only (token count + "PUSH"), which cannot tell a genuine single-
         * line reference apart from a bare literal that happens to have
         * the identical shape -- confirmed to actually misfire on an
         * inline range literal ("2..5"), which `BytecodeEmitter` emits as
         * two raw, back-to-back scalar pushes ("PUSH 2 type" / "PUSH 5
         * type") with no consolidating instruction: the *second* of those
         * two lines is indistinguishable, by shape alone, from a genuine
         * one-line "PUSH r type" reference to a whole range variable, so
         * every rewrite here that grabbed "the line right before this op"
         * and trusted it to be a real, dottable reference was silently
         * wrong whenever that operand was actually an inline range
         * literal instead.
         *
         * Why a syntactic check, not a symbol-table lookup: this pass runs
         * *first* in the fixed lowering pipeline (`MembershipLoweringPass`,
         * `CloneGenerationPass`, `DropGlueGenerationPass`,
         * `ArgToAllocLoweringPass`, `AddressLoweringPass` -- see this
         * project's own CLAUDE.md), strictly before `AddressLoweringPass`
         * ever builds the real name -> offset table that could answer "is
         * this actually a declared storage location" with certainty. There
         * is no such table here to consult, and forcing one into existence
         * this early would be a much larger, unjustified restructuring for
         * a fact that's already fully decidable without it: every real
         * name this compiler's own `BytecodeEmitter` ever writes into a
         * `PUSH` line's name position (a `VARREF`'s own text, `emitDot`'s
         * qualified-dot-name, a hoisted string id, or a compiler-
         * synthesized hidden local such as a `for` loop's own
         * `$for_range_N` range holder) is, by construction, a legal
         * identifier optionally prefixed with `$` -- and every literal
         * value shape it ever writes there instead (an `INTEGER`/`FLOAT`
         * token's digits, a quoted `CHAR`, or the fixed `true`/`false`/
         * `null` keywords) provably never starts either way. So "is this
         * syntactically shaped like a real name" is exactly as reliable a
         * signal, at this pass's own stage, as an actual symbol-table
         * lookup would be one stage later -- it isn't a weaker heuristic
         * standing in for the real check, it's the same fact, checked the
         * only way it's actually available yet.
         *
         * **`$` is a real name prefix here, not just a post-address-
         * lowering artifact** -- a genuine bug, found and fixed directly:
         * the first version of this check required a name to start with a
         * letter or underscore, missing that this compiler's own
         * `BytecodeEmitter` *already* synthesizes hidden locals with a
         * literal `$` prefix at the higher-order-bytecode stage this pass
         * itself runs at -- `for x in range{...}`'s own hidden range
         * holder is declared and pushed as a perfectly ordinary single
         * line, "PUSH $for_range_1 imut_range(...)" (confirmed directly
         * against real compiled `for`-loop output), and this pass's own
         * `declareTemp` uses the identical `$in_tmp` convention for
         * exactly the same reason. Rejecting a leading `$` wrongly
         * treated that whole, genuinely single-line reference as "not
         * simple," forcing an unneeded materialization -- which surfaced
         * immediately and loudly the first time a real stdlib function
         * with actual `ARG` parameters ahead of its `ALLOC` block (`for`
         * loops appear throughout `stdlib/gt/ghost_table.caspien`/
         * `gt_alive_check.caspien`/etc.) contained a `for` loop at all,
         * colliding with a second, independent, previously-latent gap in
         * `allocInsertIndex`'s own computation (see its own doc comment,
         * just below, for that half of the fix).
         */
        private static boolean isRealNameToken(String text) {
            if (text.isEmpty()) {
                return false;
            }
            if (text.equals("true") || text.equals("false") || text.equals("null")) {
                return false; // the fixed literal keywords -- identifier-shaped, but never a real name
            }
            char first = text.charAt(0);
            if (!(Character.isLetter(first) || first == '_' || first == '$')) {
                return false; // every literal shape (digits, a signed digit, a quoted char) fails this; every real name -- including a "$"-prefixed compiler-synthesized one -- passes it
            }
            for (int i = 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) {
                    return false; // '.' allowed for a dotted qualified name ("p.x", "r.start")
                }
            }
            return true;
        }

        /** A plain "PUSH name" with no type operand -- the shape a bare type/interface/enum name is pushed as (never a real value read). Both of this pass's own call sites additionally compare the pushed text against a real, known-at-compile-time enum/class/interface name, so a literal could never satisfy either check anyway -- the `isRealNameToken` guard is added here too only for defense-in-depth/consistency with `isSimplePush`, not because either call site was actually found vulnerable. */
        private static boolean isBarePush(List<BytecodeToken> line) {
            return line.size() == 2 && line.get(0).text.equals("PUSH") && isRealNameToken(line.get(1).text);
        }

        /** A plain "PUSH name type" -- the *entire* emission of a bare variable reference, a dotted field access rooted at one, or a hoisted string id -- see this pass's own header for why that's the only shape it ever reuses, and `isRealNameToken`'s own doc comment for why a bare literal value that happens to share this shape (e.g. one half of an inline range literal) is deliberately excluded rather than mistaken for one. */
        private static boolean isSimplePush(List<BytecodeToken> line) {
            return line.size() == 3 && line.get(0).text.equals("PUSH") && isRealNameToken(line.get(1).text);
        }

        private static boolean hasTypeField(List<StructTable.Member> members) {
            for (StructTable.Member m : members) {
                if (m.name.equals("___type")) {
                    return true;
                }
            }
            return false;
        }

        // ---- code generation -----------------------------------------------

        /** "x in range" -- "x >= range.start && x < range.end" (half-open, see this pass's own header). */
        private List<String> buildRangeCheck(String leftName, String leftType, String rangeName, String resultType) {
            List<String> lines = new ArrayList<>();
            if (rangeName.startsWith("$for_range_")) {
                // The per-iteration test of a compiler-generated `for` loop: the counter starts at
                // `range.start`, is immutable in the body (requireNotLoopImmutable) and only ever
                // incremented by the loop itself, and each increment is taken only after `i < end`
                // held, so it cannot wrap. `i >= start` is therefore always true; drop it.
                lines.add("PUSH " + leftName + " " + leftType);
                lines.add("PUSH " + rangeName + ".end indeterminate_u64");
                lines.add("LT " + leftType + " indeterminate_u64 " + resultType);
                return lines;
            }
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + rangeName + ".start indeterminate_u64");
            lines.add("GT_EQ " + leftType + " indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("LT " + leftType + " indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool " + resultType);
            return lines;
        }

        /** "x in dynarray" -- "x < len(dynarray)" -- the lower bound ("x >= 0") is always true for a plain u64 (checkIn requires it), so it's never emitted at all, per "the whole point of all of this is to limit the amount of work done, not more." */
        private List<String> buildDynArrayCheck(String leftName, String leftType, String dynName, String dynType,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + dynName + " " + dynType);
            lines.add("LEN");
            lines.add("LT " + leftType + " indeterminate_u64 " + resultType);
            return lines;
        }

        /**
         * "r1 in r2" -- "r1.start >= r2.start && r1.end <= r2.end", the
         * ordinary *non-strict* subset test (allows `r1 == r2`) -- unlike
         * `WITHIN`'s own still-unhandled *strict* variant, which
         * additionally excludes the equal-bounds case. Both bounds are
         * read as ordinary dotted fields off each already-named range
         * operand, the identical mechanism `buildRangeCheck` already uses
         * for a scalar left operand's own comparisons against a range's
         * bounds -- no new instruction needed, just two dotted reads
         * compared against two more dotted reads instead of against a
         * bare scalar push. The upper-bound comparison is `LT_EQ`, not
         * `buildRangeCheck`'s own strict `LT` -- a real, deliberate
         * difference: "x in range" excludes `x == range.end` (half-open,
         * `end` is exclusive), but a *range* is legitimately still a
         * subset of another range whose own `.end` it exactly reaches
         * (`r1.end == r2.end` is fine; only `r1.end > r2.end` fails).
         */
        private List<String> buildRangeSubsetCheck(String leftRangeName, String rightRangeName, String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool " + resultType);
            return lines;
        }

        /**
         * "r1 within r2" -- the *strict* subset test `buildRangeSubsetCheck`
         * just above deliberately isn't: "r1.start >= r2.start && r1.end
         * <= r2.end" (the same non-strict subset "in" uses) AND NOT both
         * bounds being exactly equal -- "1..2 is within 0..3 but 0..3 is
         * not within 0..3," confirmed directly. Built as the non-strict
         * subset test, ANDed with the negation of a separate "are both
         * bounds exactly equal" check, rather than inventing a new
         * comparison primitive -- every piece here (`GT_EQ`/`LT_EQ`/`EQ`/
         * `AND`/`NOT`) is already emitted elsewhere in this pass or the
         * compiler itself. Each dotted `.start`/`.end` field is re-read
         * fresh every time it's needed (matching this pass's own
         * established convention throughout -- see `buildRangeCheck`'s
         * own repeated pushes), rather than trying to reuse a
         * still-on-the-stack value across the two separate boolean
         * chains.
         */
        private List<String> buildRangeStrictSubsetCheck(String leftRangeName, String rightRangeName,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool"); // non-strict subset
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool"); // both bounds exactly equal
            lines.add("NOT imut_bool imut_bool"); // ... and NOT equal
            lines.add("AND imut_bool imut_bool " + resultType); // non-strict subset AND NOT equal
            return lines;
        }

        /**
         * "r in dynarray" -- "r.end <= LEN(dynarray)": the whole range is
         * a valid index span into the dynarray. The lower bound
         * ("r.start >= 0") is never emitted at all -- always trivially
         * true for a u64 range, the identical reasoning `buildDynArrayCheck`
         * already uses to drop it for a scalar `u64` left. Deliberately
         * `LT_EQ`, not `LT` -- a dynarray of length N has valid indices
         * `0..N-1`, and the range `0..N` (`end == N`) is *exactly* that
         * full valid span, so it must test as fully "in": `N <= N` is
         * true, `N < N` would wrongly reject it.
         */
        private List<String> buildRangeInDynArrayCheck(String rangeName, String dynName, String dynType,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("PUSH " + dynName + " " + dynType);
            lines.add("LEN");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 " + resultType);
            return lines;
        }

        /** "u64 in GuaranteedEnum" -- an OR-chain of "leftValue == variantValue" per explicit-valued variant. */
        private List<String> buildOrChain(String leftName, String leftType, List<Map.Entry<String, Long>> variants,
                String resultType) {
            List<String> lines = new ArrayList<>();
            boolean lone = variants.size() == 1;
            for (int i = 0; i < variants.size(); i++) {
                long value = variants.get(i).getValue();
                lines.add("PUSH " + leftName + " " + leftType);
                lines.add("PUSH " + value + " indeterminate_u64");
                lines.add("EQ " + leftType + " indeterminate_u64 " + (lone ? resultType : "imut_bool"));
                if (i > 0) {
                    lines.add("OR imut_bool imut_bool " + (i == variants.size() - 1 ? resultType : "imut_bool"));
                }
            }
            return lines;
        }

        /** "value in [lo,hi]" -- "value >= lo && value <= hi", inclusive both ends (a "Class" hierarchy range, unlike an ordinary user-facing range, is inclusive on both bounds -- see TypeChecker.walkClassHierarchy's own startIdx/endIdx construction). */
        private List<String> buildRangeMembershipCheck(String valuePath, long lo, long hi) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + valuePath + " imut_u64");
            lines.add("PUSH " + lo + " indeterminate_u64");
            lines.add("GT_EQ imut_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + valuePath + " imut_u64");
            lines.add("PUSH " + hi + " indeterminate_u64");
            lines.add("LT_EQ imut_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool");
            return lines;
        }

        /** "value in {v0, v1, ...}" -- an OR-chain of "value == vi", one per implementer. */
        private List<String> buildValueOrChain(String valuePath, List<Long> values) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                lines.add("PUSH " + valuePath + " imut_u64");
                lines.add("PUSH " + values.get(i) + " indeterminate_u64");
                lines.add("EQ imut_u64 indeterminate_u64 imut_bool");
                if (i > 0) {
                    lines.add("OR imut_bool imut_bool imut_bool");
                }
            }
            return lines;
        }
    }
}
