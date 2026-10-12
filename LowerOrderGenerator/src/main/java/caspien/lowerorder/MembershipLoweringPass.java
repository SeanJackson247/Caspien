package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Final one-shot lowering (outside both fixed-point loops, like DropGlueGenerationPass) of IN, WITHIN,
 * INSTANCEOF, IMPLEMENTS, IS_BASE, IN_SCAN and LEN_SCAN into plain comparison/logic primitives.
 * Operands that are not simple named pushes are popped into "$in_tmpN" temps; unmatched shapes are
 * left as the original bytecode.
 * See docs/MembershipLoweringPass.md.
 */
public class MembershipLoweringPass implements OptimizationPass {


    @Override
    public String name() {
        return "membership-lowering";
    }

    @Override
    public PassResult run(BytecodeProgram lines) {
        return new Worker(StructTable.read(lines), EnumTable.read(lines)).process(lines);
    }

    private static final class Worker {

        private final StructTable structTable;
        private final EnumTable enumTable;

        // Reset per function by rewriteFunctionBody.
        private int allocInsertIndex;
        private int tempCounter;

        // Never reset: labels must stay unique across the whole program (used by the two scan loops).
        private int labelCounter;

        Worker(StructTable structTable, EnumTable enumTable) {
            this.structTable = structTable;
            this.enumTable = enumTable;
        }

        PassResult process(BytecodeProgram lines) {
            BytecodeProgram rewritten = new BytecodeProgram();
            boolean changed = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                BytecodeStatement line = lines.get(i);
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

                BytecodeProgram body = new BytecodeProgram();
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
            final BytecodeProgram lines;
            final boolean changed;
            RewriteOutcome(BytecodeProgram lines, boolean changed) {
                this.lines = lines;
                this.changed = changed;
            }
        }

        /** Walks one function's own line range looking for a rewritable IN/INSTANCEOF/IMPLEMENTS line, three lines at a time (the operator plus its two preceding operand pushes). */
        private RewriteOutcome rewriteFunctionBody(BytecodeProgram body) {
            BytecodeProgram out = new BytecodeProgram();
            boolean changed = false;
            int k = 0;
            int n = body.size();

            // Index where a new temp's ALLOC goes: after FUNC_START/EXPORT/FUNC_DECORATE/RETURNS/ARG/ALLOC lines.
            // ARG and EXPORT must be skipped: ArgToAllocLoweringPass runs later and stops collecting at the first
            // non-ARG line. See docs/MembershipLoweringPass.md, "Temp declaration".
            allocInsertIndex = 0;
            while (allocInsertIndex < n) {
                BytecodeStatement pl = body.get(allocInsertIndex);
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
                BytecodeStatement line = body.get(k);
                String head = line.isEmpty() ? "" : line.get(0).text;

                if (head.equals("IN") && line.size() == 4 && out.size() >= 2) {
                    InMatch match = tryRewriteIn(line, out);
                    if (match != null) {
                        // Drop each operand push unless the match materialized it (a kept push is essential bytecode).
                        // Right is removed first so the left index stays valid.
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN line itself was consumed from `body` beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("WITHIN") && line.size() == 4 && out.size() >= 2) {
                    // Same push bookkeeping as IN; see tryRewriteWithin.
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
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the WITHIN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if ((head.equals("INSTANCEOF") || head.equals("IMPLEMENTS")) && line.size() == 2
                        && k >= 1 && out.size() >= 2) {
                    // The left operand's type is written on the operator line itself, not inferred from the
                    // preceding line. See docs/MembershipLoweringPass.md, "INSTANCEOF and IMPLEMENTS".
                    String leftType = line.get(1).text;
                    BytecodeStatement targetPushLine = body.get(k - 1);
                    InstanceofMatch match = tryRewriteInstanceofOrImplements(head, targetPushLine, leftType, out);
                    if (match != null) {
                        // The target-name push is always one bare line, so it is always dropped; the left push is dropped
                        // only if it was read by name (a materialized left stays, followed by its POP).
                        out.remove(out.size() - 1);
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare INSTANCEOF/IMPLEMENTS line
                        continue;
                    }
                } else if (head.equals("IS_BASE") && line.size() == 2 && out.size() >= 1) {
                    // IS_BASE has exactly one operand push; line.get(1) is the range operand's type.
                    // See docs/MembershipLoweringPass.md, "IS_BASE".
                    String rangeOperandType = line.get(1).text;
                    InstanceofMatch match = tryRewriteIsBase(rangeOperandType, out);
                    if (match != null) {
                        // The only operand push; dropped unless materialized.
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare IS_BASE line
                        continue;
                    }
                } else if (head.equals("IN_SCAN") && line.size() == 4 && out.size() >= 2) {
                    // Same push bookkeeping as IN/WITHIN; see tryRewriteInScan.
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
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN_SCAN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("LEN_SCAN") && line.size() == 3 && out.size() >= 2) {
                    // LEN_SCAN carries only two type tokens (target, terminator); the result type is always
                    // indeterminate_u64 and is hardcoded in tryRewriteLenScan.
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
                        out.addAll(BytecodeStatement.fromTexts(match.lines, "<generated-membership-lowering>"));
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

        /** Replacement text plus whether each trailing operand push (left, right) must stay because it was materialized into a temp. */
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
         * `inLine` is "IN t1 t2 t3"; the last two entries of `outSoFar` are the operand pushes.
         * Returns the replacement, or null for a shape this pass does not lower.
         * See docs/MembershipLoweringPass.md, "IN shapes".
         */
        private InMatch tryRewriteIn(BytecodeStatement inLine, BytecodeProgram outSoFar) {
            String t1 = inLine.get(1).text;
            String t2 = inLine.get(2).text;
            String t3 = inLine.get(3).text;

            BytecodeStatement rightPush = outSoFar.get(outSoFar.size() - 1);
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 2);

            // Shape 1: u64 in GuaranteedEnum (right side is a bare "PUSH EnumName").
            List<Map.Entry<String, Long>> enumVariants = enumTable.valueVariantsOf(t2);
            if (!enumVariants.isEmpty() && isBarePush(rightPush) && rightPush.get(1).text.equals(t2)
                    && isSimplePush(leftPush)) {
                String leftName = leftPush.get(1).text;
                return new InMatch(buildOrChain(leftName, t1, enumVariants, t3), false, false);
            }

            // Shape 2/3: plain u64 left against a range or dynarray right. A range-typed left is Shape 4;
            // anything else is left as the original bytecode.
            CanonicalType leftCanonical = CanonicalType.parse(t1);
            boolean leftIsPlainU64 = leftCanonical.storage == null && leftCanonical.baseType.equals("u64");
            if (leftIsPlainU64) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                String rightBaseType = rightCanonical.baseType;
                // A bare "range" counts as a range too: buildRangeCheck reads .start/.end at run time.
                boolean rightIsRange = rightBaseType.equals("range") || rightBaseType.startsWith("range(");
                boolean rightIsDynArray = rightBaseType.startsWith("dynarray(");
                if (rightIsRange || rightIsDynArray) {
                    // Materialize non-simple operands into temps. Right is popped first (top of stack); if right is not
                    // simple, left is always materialized too. See docs/MembershipLoweringPass.md, "Operand materialization".
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

                    // A pointer-wrapped right (raw range/dynarray) is dereferenced into a storage-free temp first.
                    // The gate is isGenuinePointer, not storage != null. See docs/MembershipLoweringPass.md, "Pointer-wrapped operands".
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

            // Shape 4: range-shaped left (or pointer to one) against a range or dynarray right.
            // See docs/MembershipLoweringPass.md, "IN shapes". A bare "range" counts as a range here too.
            boolean leftIsRangeShaped = leftCanonical.baseType.equals("range") || leftCanonical.baseType.startsWith("range(");
            if (leftIsRangeShaped) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                boolean rightIsRangeShaped = rightCanonical.baseType.equals("range") || rightCanonical.baseType.startsWith("range(");
                boolean rightIsDynArrayShaped = rightCanonical.baseType.startsWith("dynarray(");
                if (rightIsRangeShaped || rightIsDynArrayShaped) {
                    // Same materialization as Shape 2/3.
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

                    // Unlike Shape 2/3, the left can be pointer-wrapped too, so each side is dereferenced independently.
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
         * "WITHIN t1 t2 t3": checkWithin guarantees both operands are range-shaped, so there is one shape.
         * Pointer-wrapped sides are dereferenced as in IN's Shape 4. See docs/MembershipLoweringPass.md, "WITHIN".
         */
        private InMatch tryRewriteWithin(BytecodeStatement withinLine, BytecodeProgram outSoFar) {
            String t1 = withinLine.get(1).text;
            String t2 = withinLine.get(2).text;
            String t3 = withinLine.get(3).text;

            BytecodeStatement rightPush = outSoFar.get(outSoFar.size() - 1);
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 2);

            CanonicalType leftCanonical = CanonicalType.parse(t1);
            CanonicalType rightCanonical = CanonicalType.parse(t2);
            // A bare "range" is accepted as well as "range(...)".
            if ((!leftCanonical.baseType.equals("range") && !leftCanonical.baseType.startsWith("range("))
                    || (!rightCanonical.baseType.equals("range") && !rightCanonical.baseType.startsWith("range("))) {
                // Cannot happen while checkWithin guarantees range-shaped operands; leave the original bytecode.
                return null;
            }

            // Materialize as for IN's range-vs-range shape.
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

            // Dereference whichever side is pointer-wrapped.
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
         * "r is base" (match condition): true when the range's start has reached its end. One operand push,
         * no right side. `leftType` comes from the IS_BASE line itself, because an inline range literal is two
         * scalar pushes. See docs/MembershipLoweringPass.md, "IS_BASE".
         */
        private InstanceofMatch tryRewriteIsBase(String leftType, BytecodeProgram outSoFar) {
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 1);
            boolean leftSimple = isSimplePush(leftPush);
            CanonicalType leftCanonical = CanonicalType.parse(leftType);
            // Accept bare "range" as well as "range(...)" (a @recursive range parameter is declared bare).
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

        /** "r is base" -- r.start >= r.end (GT_EQ rather than EQ, so an overshot range still counts). */
        private List<String> buildIsBaseCheck(String rangeName) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + rangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            return lines;
        }

        /**
         * "u64 in string": a byte-by-byte run-time scan to the NUL terminator (see buildStringScanCheck).
         * Operand handling as in IN Shape 2/3. See docs/MembershipLoweringPass.md, "IN_SCAN and LEN_SCAN".
         */
        private InMatch tryRewriteInScan(BytecodeStatement inScanLine, BytecodeProgram outSoFar) {
            String t1 = inScanLine.get(1).text;
            String t2 = inScanLine.get(2).text;
            String t3 = inScanLine.get(3).text;

            BytecodeStatement rightPush = outSoFar.get(outSoFar.size() - 1);
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 2);

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

            // Not isGenuinePointer: a string is pointer-like at every storage keyword, so only raw/ref need a
            // DEREF. See docs/MembershipLoweringPass.md, "Pointer-wrapped operands".
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
         * Scan loop: LOOKUP the byte at counter; at '\0' push false; else compare the zero-extended byte with
         * the left value (true on a match); otherwise increment and loop. The unchecked index is fine because
         * this is compiler-fabricated bytecode. See docs/MembershipLoweringPass.md, "IN_SCAN and LEN_SCAN".
         */
        private List<String> buildStringScanCheck(String leftName, String leftType, String rightName,
                String rightType, String resultType, BytecodeProgram outSoFar) {
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
         * "len(unsafeDynArr, terminator)": like IN_SCAN, but the terminator is a run-time operand of the element
         * type and the result is the index of the first match. No widening or dereference is needed.
         * See docs/MembershipLoweringPass.md, "IN_SCAN and LEN_SCAN".
         */
        private InMatch tryRewriteLenScan(BytecodeStatement lenScanLine, BytecodeProgram outSoFar) {
            String t1 = lenScanLine.get(1).text; // the unsafe dynarray target's own type
            String t2 = lenScanLine.get(2).text; // the terminator's own type

            BytecodeStatement rightPush = outSoFar.get(outSoFar.size() - 1); // the terminator -- pushed last, sits on top
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 2); // the target -- pushed first

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

        /** Walks forward comparing each element with the terminator; the index at the match is the length. */
        private List<String> buildLenScanCheck(String targetName, String targetType, String termName,
                String termType, BytecodeProgram outSoFar) {
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
         * Dereferences pointer-typed local `name` into a fresh storage-free temp ("PUSH / DEREF derefType / POP").
         * Returns {derefName, derefType}. DEREF carries the pointee type because it copies that many bytes.
         * See docs/MembershipLoweringPass.md, "Pointer-wrapped operands".
         */
        private String[] derefInto(String name, String type, CanonicalType canonical,
                BytecodeProgram outSoFar, List<String> lines) {
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
         * Declares a fresh "$in_tmpN" local, inserting its ALLOC at allocInsertIndex. A "$" name cannot clash
         * with a source identifier. See docs/MembershipLoweringPass.md, "Temp declaration".
         */
        private String declareTemp(BytecodeProgram out, String type) {
            String name = "$in_tmp" + (++tempCounter);
            out.add(allocInsertIndex,
                    BytecodeStatement.fromText("ALLOC " + name + " " + type, "<generated-membership-lowering>"));
            allocInsertIndex++;
            return name;
        }

        /** As InMatch.keepLeftPush: the left operand was materialized, so its original bytecode stays. */
        private static final class InstanceofMatch {
            final List<String> lines;
            final boolean keepLeftPush;
            InstanceofMatch(List<String> lines, boolean keepLeftPush) {
                this.lines = lines;
                this.keepLeftPush = keepLeftPush;
            }
        }

        /**
         * INSTANCEOF / IMPLEMENTS lowering. The target is always a bare one-line "PUSH name", so outSoFar's
         * second-to-last entry is the left operand's last line. A non-simple left is materialized (declareTemp
         * plus POP) only after every bail-out gate has passed, so no stray ALLOC is left. `leftType` is read from
         * the operator line. See docs/MembershipLoweringPass.md, "INSTANCEOF and IMPLEMENTS".
         */
        private InstanceofMatch tryRewriteInstanceofOrImplements(String mnemonic, BytecodeStatement targetPushLine,
                String leftType, BytecodeProgram outSoFar) {
            if (!isBarePush(targetPushLine)) {
                return null;
            }
            String targetName = targetPushLine.get(1).text;

            // outSoFar's last entry is the target push; the one before it is the left operand's final line.
            BytecodeStatement leftPush = outSoFar.get(outSoFar.size() - 2);
            boolean leftSimple = isSimplePush(leftPush);
            String structName = CanonicalType.parse(leftType).baseType;
            // An interface-typed left (members == null) is allowed: ___type is at offset 0 of every struct.
            // hasTypeField is only a defensive guard.
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
                    // No implementer anywhere in the program: provably false. The left is still consumed so its side
                    // effects run; the type checker, not this pass, warns about it.
                    lines.add("PUSH false imut_bool");
                    return new InstanceofMatch(lines, !leftSimple);
                }
                // Left's class id is only needed for a real OR-chain.
                String typeFieldPath = typeFieldPathFor(leftName, leftType, outSoFar, lines);
                lines.addAll(buildValueOrChain(typeFieldPath, implementerIds));
                return new InstanceofMatch(lines, !leftSimple);
            }
        }

        /**
         * Returns the left operand's name: a simple push's own name, or a fresh temp (declareTemp, with the
         * "POP" appended to `lines`) for a non-simple one.
         */
        private String resolveLeftName(boolean leftSimple, BytecodeStatement leftPush, String leftType,
                BytecodeProgram outSoFar, List<String> lines) {
            if (leftSimple) {
                return leftPush.get(1).text;
            }
            String leftName = declareTemp(outSoFar, leftType);
            lines.add("POP " + leftName + " " + leftType);
            return leftName;
        }

        /**
         * Builds "leftName.___type", dereferencing first whenever leftType has any storage prefix (plain
         * `storage != null`, not isGenuinePointer): AddressLoweringPass.resolveAddress leaves a dotted name
         * rooted in a storage-qualified local unresolved. See docs/MembershipLoweringPass.md, "INSTANCEOF and IMPLEMENTS".
         */
        private String typeFieldPathFor(String leftName, String leftType, BytecodeProgram outSoFar,
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
         * True only for a pointer wrapping a value (raw/ref...), not for "owns" storage, which is a
         * dynarray's own representation. See docs/MembershipLoweringPass.md, "Pointer-wrapped operands".
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
         * True for a token that is a real addressable name (identifier optionally prefixed with "$", dotted
         * chain, hoisted string id) rather than a literal (digits, quoted char, true/false/null). Syntactic
         * because this pass runs before AddressLoweringPass builds any symbol table. "$" must be accepted:
         * for-loop holders are "$for_range_N". See docs/MembershipLoweringPass.md, "Name tokens".
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

        /** A plain "PUSH name" (no type): how a bare type/interface/enum name is pushed. */
        private static boolean isBarePush(BytecodeStatement line) {
            return line.size() == 2 && line.get(0).text.equals("PUSH") && isRealNameToken(line.get(1).text);
        }

        /** A plain "PUSH name type": the whole emission of a variable reference, dotted field access or hoisted string id. Bare literals of the same shape are excluded (see isRealNameToken). */
        private static boolean isSimplePush(BytecodeStatement line) {
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

        /** "x in dynarray" -- "x < len(dynarray)"; the lower bound is always true for a u64 and is not emitted. */
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
         * "r1 in r2" -- "r1.start >= r2.start && r1.end <= r2.end", the non-strict subset test. The upper bound
         * is LT_EQ (not LT as in buildRangeCheck): a range may reach the other's exclusive end.
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
         * "r1 within r2" -- the non-strict subset test AND NOT (both bounds equal). Built from GT_EQ/LT_EQ/EQ/AND/NOT;
         * each dotted bound is re-read every time it is needed.
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
         * "r in dynarray" -- "r.end <= LEN(dynarray)". LT_EQ, not LT: the range 0..N is exactly the full valid span.
         * The lower bound is always true for a u64 range and is not emitted.
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
