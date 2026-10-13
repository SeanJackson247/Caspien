package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerControlFlow: match, if, for, return, throw and try (part 6 of 11; see README.md in this folder). */
public final class TypeCheckerControlFlow {
    private final TypeChecker hub;

    TypeCheckerControlFlow(TypeChecker hub) {
        this.hub = hub;
    }

    /** "match e{ A:{..} B:{..} }" on an enum: records no proof; each case body is an ordinary child scope. See docs/TypeChecker.md, "Float and enum case matches". */
    public void checkCaseMatch(Token matchTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        Token conditionExpr = matchTok.condition();
        // "match x instanceof P.enum{..}" / "implements I.enum{..}" is detected structurally before resolveExprType rejects "P.enum".
        if (isEnumForMatchCondition(conditionExpr)) {
            checkEnumForMatch(matchTok, conditionExpr, scope, func, insideLoop);
            return;
        }
        TypeCheckerModel.TypeInfo conditionType = resolveExprType(conditionExpr, scope, func);
        TypeCheckerModel.EnumInfo enumInfo = hub.model.enums.get(conditionType.baseType);
        if (enumInfo != null) {
            checkEnumMatch(matchTok, conditionExpr, conditionType, enumInfo, scope, func, insideLoop);
            return;
        }
        if (TypeCheckerModel.isFloatType(conditionType.baseType)) {
            checkFloatMatch(matchTok, conditionExpr, conditionType, scope, func, insideLoop);
            return;
        }
        throw new CompilerException("type", conditionExpr.file, conditionExpr.line,
                "'match " + describeConditionForError(conditionExpr) + "{ LABEL:{...} ... }' requires an "
                        + "enum-typed or 'f32'/'f64' condition, got '" + conditionType.canonical() + "'");
    }

    /** "match f{ finite:.. infinite:.. nan:.. }" on a float: exhaustive over the type's allowed states, records a "float_state" proof per case, then lowers to an if-chain. See docs/TypeChecker.md, "Float and enum case matches". */
    public void checkFloatMatch(Token matchTok, Token conditionExpr, TypeCheckerModel.TypeInfo conditionType, TypeCheckerModel.Scope scope,
            TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        // Exhaustive over the type-level state set if it has one, else all three states.
        Set<String> required = conditionType.floatStates != null
                ? conditionType.floatStates : TypeCheckerModel.FLOAT_STATE_NAMES;
        Set<String> covered = new HashSet<>();
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        String conditionSlotKey = hub.expressions.slotKeyOf(conditionExpr);
        for (Token caseTok : matchTok.childs) {
            List<Token> labels = caseTok.sub;
            Set<String> seenInThisCase = new HashSet<>();
            for (Token label : labels) {
                String labelName = label.text;
                if (!hub.model.isFloatStateName(labelName)) {
                    // No default here: anything but a float state name is rejected.
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' isn't a valid float state -- expected 'finite', "
                                    + "'infinite', or 'nan'");
                }
                if (!required.contains(labelName)) {
                    // A state the type's modifier rules out is an error.
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is impossible here -- '" + conditionType.canonical()
                                    + "'s own state modifier already rules it out");
                }
                if (!seenInThisCase.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' repeated in the same '|'-chain");
                }
                if (!covered.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is already covered by an earlier case in this match");
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = new ArrayList<>(scope.activeMatchPatterns);
            if (conditionSlotKey != null) {
                branchPatterns.add(new Token.MatchPattern(conditionSlotKey, "float_state", null));
            }
            TypeCheckerModel.Scope branchScope = new TypeCheckerModel.Scope(scope, branchMoved, branchPatterns, false);
            hub.statements.checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String state : required) {
            if (!covered.contains(state)) {
                missing.add(state);
            }
        }
        if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + conditionType.canonical() + "' isn't exhaustive -- missing: "
                            + String.join(", ", missing));
        }
        hub.statements.finishBranchMerge(preState, union, survivorMoves, false);
        lowerFloatMatchToIfChain(matchTok, conditionExpr, scope, func);
    }

    /**
     * Float counterpart of lowerEnumMatchToIfChain: each non-last case becomes an if on a compiler-internal "FLOAT_CHECK"
     * node (result type set by hand; no resolveExprType case) whose right text is the alphabetically sorted chain
     * ("finite|nan"), built from a TreeSet so source order never matters. See docs/TypeChecker.md, "Float and enum case matches".
     */
    public void lowerFloatMatchToIfChain(Token matchTok, Token conditionExpr, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.chk.destructOnExit = caseTok.chk.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                java.util.TreeSet<String> chain = new java.util.TreeSet<>();
                for (Token label : caseTok.sub) {
                    chain.add(label.text);
                }
                // conditionExpr is reused, not cloned, in every case: it is fully checked and never mutated again.
                Token chainTok = new Token(TokenType.VARREF, String.join("|", chain), caseTok.line, caseTok.file);
                Token checkOp = new Token(TokenType.OPERATOR, "FLOAT_CHECK", caseTok.line, caseTok.file);
                checkOp.left = conditionExpr;
                checkOp.right = chainTok;
                checkOp.chk.resolvedType = "indeterminate_bool";
                branch.sub = new ArrayList<>();
                branch.sub.add(checkOp);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).next = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.chk.destructOnExit = first.chk.destructOnExit;
        matchTok.next = first.next;
    }

    public String labelsText(List<Token> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(labels.get(i).text);
        }
        return sb.toString();
    }

    public void checkEnumMatch(Token matchTok, Token conditionExpr, TypeCheckerModel.TypeInfo conditionType, TypeCheckerModel.EnumInfo enumInfo,
            TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        Set<String> covered = new HashSet<>();
        boolean sawDefault = false;
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (int i = 0; i < matchTok.childs.size(); i++) {
            Token caseTok = matchTok.childs.get(i);
            List<Token> labels = caseTok.sub;
            boolean isDefault = labels.size() == 1 && labels.get(0).text.equals("default");
            if (sawDefault) {
                throw new CompilerException("type", caseTok.file, caseTok.line,
                        "'default' must be the last case in the match -- '" + labelsText(labels)
                                + "' comes after it");
            }
            if (isDefault) {
                if (!enumInfo.isNonExhaustive) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'default' is only allowed matching an enum decorated '@non_exhaustive' -- '"
                                    + enumInfo.name + "' isn't; every variant must be covered by name "
                                    + "instead");
                }
                if (i != matchTok.childs.size() - 1) {
                    throw new CompilerException("type", caseTok.file, caseTok.line,
                            "'default' must be the last case in the match");
                }
                sawDefault = true;
            } else {
                for (Token label : labels) {
                    String labelName = label.text;
                    if (labelName.equals("default")) {
                        // "default" cannot share a "|"-chain with a variant name; it must stand alone as the final case.
                        throw new CompilerException("type", label.file, label.line,
                                "'default' can't be combined with another label in a '|'-chain -- it must "
                                        + "stand alone as its own, final case");
                    }
                    if (!enumInfo.variants.contains(labelName)) {
                        throw new CompilerException("type", label.file, label.line,
                                "'" + enumInfo.name + "' has no variant named '" + labelName + "'");
                    }
                    if (!covered.add(labelName)) {
                        throw new CompilerException("type", label.file, label.line,
                                "'" + labelName + "' is already covered by an earlier case in this match");
                    }
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = scope.activeMatchPatterns;
            List<String> caseVariants = isDefault
                    ? enumInfo.variants.stream().filter(v -> !covered.contains(v))
                            .collect(java.util.stream.Collectors.toList())
                    : labels.stream().map(l -> l.text).collect(java.util.stream.Collectors.toList());
            // Every "receiver.field" condition records the enum_field_variants proof that @lock(match self.x : X) qualifiers read.
            if (conditionExpr.type == TokenType.OPERATOR && conditionExpr.text.equals(".")) {
                String fieldSlot = hub.expressions.slotKeyOf(conditionExpr.left);
                if (fieldSlot != null) {
                    branchPatterns = new ArrayList<>(branchPatterns);
                    branchPatterns.add(new Token.MatchPattern(fieldSlot, "enum_field_variants",
                            conditionExpr.right.text, caseVariants));
                }
            }
            TypeCheckerModel.Scope branchScope = new TypeCheckerModel.Scope(scope, branchMoved, branchPatterns, false);
            hub.statements.checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String variant : enumInfo.variants) {
            if (!variant.equals("default") && !covered.contains(variant)) {
                missing.add(variant);
            }
        }
        if (enumInfo.isNonExhaustive) {
            if (sawDefault && missing.isEmpty()) {
                throw new CompilerException("type", matchTok.file, matchTok.line,
                        "'default' is unreachable: every variant of '" + enumInfo.name
                                + "' is already covered by name");
            }
            if (!sawDefault && !missing.isEmpty()) {
                throw new CompilerException("type", matchTok.file, matchTok.line,
                        "match on '@non_exhaustive' enum '" + enumInfo.name + "' needs a 'default' case "
                                + "as the last one -- uncovered: " + String.join(", ", missing));
            }
        } else if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + enumInfo.name + "' isn't exhaustive -- missing: "
                            + String.join(", ", missing));
        }
        hub.statements.finishBranchMerge(preState, union, survivorMoves, false);
        // Lowered to an if/else-if/else chain only now, after all validation ran on the real construct; the last case gets no
        // comparison. matchTok is mutated in place; emitIfChain ignores .text, only emitStatement needs "if". See docs/TypeChecker.md, "Float and enum case matches".
        lowerEnumMatchToIfChain(matchTok, conditionExpr, enumInfo, scope, func);
    }

    /** Lowering step of checkEnumMatch: one type-checked "conditionExpr == Enum.Variant" per case except the last, via the ordinary "==" dispatch. */
    public void lowerEnumMatchToIfChain(Token matchTok, Token conditionExpr, TypeCheckerModel.EnumInfo enumInfo, TypeCheckerModel.Scope scope,
            TypeCheckerModel.FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.chk.destructOnExit = caseTok.chk.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                // One "conditionExpr == Enum.Label" per label, OR-combined for "A|B:" chains.
                // conditionExpr is reused, not cloned (it is fully checked and never mutated again; re-resolving it is idempotent).
                Token combined = null;
                for (Token labelTok : caseTok.sub) {
                    Token enumNameTok = new Token(TokenType.VARREF, enumInfo.name, labelTok.line, labelTok.file);
                    Token variantTok = new Token(TokenType.VARREF, labelTok.text, labelTok.line, labelTok.file);
                    Token dotOp = new Token(TokenType.OPERATOR, ".", labelTok.line, labelTok.file);
                    dotOp.left = enumNameTok;
                    dotOp.right = variantTok;
                    Token eqOp = new Token(TokenType.OPERATOR, "==", labelTok.line, labelTok.file);
                    eqOp.left = conditionExpr;
                    eqOp.right = dotOp;
                    resolveExprType(eqOp, scope, func);
                    if (combined == null) {
                        combined = eqOp;
                    } else {
                        Token orOp = new Token(TokenType.OPERATOR, "||", labelTok.line, labelTok.file);
                        orOp.left = combined;
                        orOp.right = eqOp;
                        resolveExprType(orOp, scope, func);
                        combined = orOp;
                    }
                }
                branch.sub = new ArrayList<>();
                branch.sub.add(combined);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).next = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.chk.destructOnExit = first.chk.destructOnExit;
        matchTok.next = first.next;
    }

    /** True for exactly "x instanceof Owner.enum" / "x implements Owner.enum" ("enum" lexes as a VARREF after "."; Lexer.justSawDotOperator). */
    public boolean isEnumForMatchCondition(Token conditionExpr) {
        if (conditionExpr.type != TokenType.OPERATOR
                || !(conditionExpr.text.equals("instanceof") || conditionExpr.text.equals("implements"))) {
            return false;
        }
        Token right = conditionExpr.right;
        return right.type == TokenType.OPERATOR && right.text.equals(".")
                && right.left.type == TokenType.VARREF && right.right.type == TokenType.VARREF
                && right.right.text.equals("enum");
    }

    /**
     * "match x implements Iface.enum{ Impl1:{..} .. }": labels are the members of the synthesized "$enum_for_Iface" enum
     * (generateEnumForEnums) and must cover it exactly, no default. "instanceof Owner.enum" is always an error (structs have no
     * subclasses). Each case gets an "instanceof" proof. See docs/TypeChecker.md, "Float and enum case matches".
     */
    public void checkEnumForMatch(Token matchTok, Token conditionExpr, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func,
            boolean insideLoop) {
        boolean isInstanceof = conditionExpr.text.equals("instanceof");
        Token xExpr = conditionExpr.left;
        String ownerName = conditionExpr.right.left.text;
        List<String> memberNames;
        if (isInstanceof) {
            if (hub.model.interfaces.containsKey(ownerName)) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'instanceof " + ownerName + ".enum' is not a form: 'instanceof' takes a struct name. To match over "
                                + "the implementers of the interface '" + ownerName + "', write 'match x implements " + ownerName
                                + ".enum{ ... }'");
            }
            throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                    "'" + ownerName + ".enum' no longer exists: structs have no subclasses. "
                            + "Only an interface has an '.enum' (use 'x implements " + ownerName + "')");
        } else {
            if (!hub.model.interfaces.containsKey(ownerName)) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'" + ownerName + "' is not a known interface");
            }
            TypeCheckerModel.EnumInfo enumForInfo = hub.model.enums.get("$enum_for_" + ownerName);
            if (enumForInfo == null) {
                throw new CompilerException("type", conditionExpr.right.file, conditionExpr.right.line,
                        "'" + ownerName + "' has no known implementers -- '.enum' requires at least one");
            }
            memberNames = enumForInfo.variants;
        }
        // A throwaway "x instanceof FirstMember" runs checkInstanceof once on x (always instanceof: members are concrete structs).
        Token probe = new Token(TokenType.OPERATOR, "instanceof", conditionExpr.line, conditionExpr.file);
        probe.left = xExpr;
        probe.right = new Token(TokenType.VARREF, memberNames.get(0), conditionExpr.line, conditionExpr.file);
        resolveExprType(probe, scope, func);
        conditionExpr.chk.resolvedType = "imut_bool";
        Set<String> required = new LinkedHashSet<>(memberNames);
        Set<String> covered = new HashSet<>();
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        String xSlotKey = hub.expressions.slotKeyOf(xExpr);
        for (Token caseTok : matchTok.childs) {
            List<Token> labels = caseTok.sub;
            for (Token label : labels) {
                String labelName = label.text;
                if (labelName.equals("default")) {
                    throw new CompilerException("type", label.file, label.line,
                            "'default' is not valid here -- an enum-style match on '." + ownerName
                                    + ".enum' must be exhaustive over its exact membership");
                }
                if (!required.contains(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is not a known implementer of '" + ownerName + "'");
                }
                if (!covered.add(labelName)) {
                    throw new CompilerException("type", label.file, label.line,
                            "'" + labelName + "' is already covered by an earlier case in this match");
                }
            }
            Set<String> branchMoved = new HashSet<>(preState);
            List<Token.MatchPattern> branchPatterns = scope.activeMatchPatterns;
            if (xSlotKey != null && labels.size() == 1) {
                // Always an "instanceof" proof, even for the "implements" form: a label names a concrete implementer.
                branchPatterns = new ArrayList<>(branchPatterns);
                branchPatterns.add(new Token.MatchPattern(xSlotKey, "instanceof", labels.get(0).text));
            }
            TypeCheckerModel.Scope branchScope = new TypeCheckerModel.Scope(scope, branchMoved, branchPatterns, false);
            // Same instanceof shadowing as checkMatchChain (narrowInstanceofSlots), so "c.foo()" in an "A:" case resolves as A's method.
            hub.statements.narrowInstanceofSlots(branchPatterns, scope, branchScope);
            hub.statements.checkLinesInScope(caseTok.childs, branchScope, func, insideLoop);
            caseTok.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(caseTok.childs, branchScope, branchScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(caseTok.childs)) { survivorMoves.add(branchMoved); }
        }
        List<String> missing = new ArrayList<>();
        for (String member : required) {
            if (!covered.contains(member)) {
                missing.add(member);
            }
        }
        if (!missing.isEmpty()) {
            throw new CompilerException("type", matchTok.file, matchTok.line,
                    "match on '" + ownerName + ".enum' isn't exhaustive -- missing: " + String.join(", ", missing));
        }
        hub.statements.finishBranchMerge(preState, union, survivorMoves, false);
        lowerEnumForMatchToIfChain(matchTok, xExpr, scope, func);
    }

    /** Lowering step of checkEnumForMatch: like lowerEnumMatchToIfChain but each comparison is an INSTANCEOF node (OR-combined for "|" chains). */
    public void lowerEnumForMatchToIfChain(Token matchTok, Token xExpr, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        List<Token> cases = matchTok.childs;
        List<Token> branches = new ArrayList<>();
        // Always "instanceof": a case label is a concrete struct, even for the "implements" form.
        String opText = "instanceof";
        for (int i = 0; i < cases.size(); i++) {
            Token caseTok = cases.get(i);
            boolean isLast = i == cases.size() - 1;
            Token branch = new Token(TokenType.KEYWORD, isLast ? "else" : "if", caseTok.line, caseTok.file);
            branch.childs = caseTok.childs;
            branch.chk.destructOnExit = caseTok.chk.destructOnExit;
            if (isLast) {
                branch.sub = new ArrayList<>();
            } else {
                Token combined = null;
                for (Token labelTok : caseTok.sub) {
                    Token targetTok = new Token(TokenType.VARREF, labelTok.text, labelTok.line, labelTok.file);
                    Token checkOp = new Token(TokenType.OPERATOR, opText, labelTok.line, labelTok.file);
                    checkOp.left = xExpr;
                    checkOp.right = targetTok;
                    resolveExprType(checkOp, scope, func);
                    if (combined == null) {
                        combined = checkOp;
                    } else {
                        Token orOp = new Token(TokenType.OPERATOR, "||", labelTok.line, labelTok.file);
                        orOp.left = combined;
                        orOp.right = checkOp;
                        resolveExprType(orOp, scope, func);
                        combined = orOp;
                    }
                }
                branch.sub = new ArrayList<>();
                branch.sub.add(combined);
            }
            branches.add(branch);
        }
        for (int i = 0; i + 1 < branches.size(); i++) {
            branches.get(i).next = branches.get(i + 1);
        }
        Token first = branches.get(0);
        matchTok.text = "if";
        matchTok.isCaseMatch = false;
        matchTok.sub = first.sub;
        matchTok.childs = first.childs;
        matchTok.chk.destructOnExit = first.chk.destructOnExit;
        matchTok.next = first.next;
    }

    /**
     * Match condition grammar: left instanceof right, left implements right, left in right, a bare expression, chained with
     * "and". Each leaf is checked through resolveExprType and records one MatchPattern. See docs/TypeChecker.md, "Match chains".
     */
    public void checkMatchCondition(Token node, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, List<Token.MatchPattern> out) {
        if (node.type == TokenType.OPERATOR && node.text.equals("and")) {
            checkMatchCondition(node.left, scope, func, out);
            checkMatchCondition(node.right, scope, func, out);
            // "and" has no resolveExprType case; its result type is set here (always bool).
            node.chk.resolvedType = "imut_bool";
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                && (node.left.type == TokenType.VARREF || (hub.model.assumeKeyMode && hub.expressions.exprKeyOf(node.left) != null))
                && node.right.type == TokenType.INTEGER
                && node.right.text.equals("0")) {
            // "match rht != 0 and lft != 0{ .. }": proves an integer nonzero, which makes / % /= %= legal in safe code.
            // Never falls through to the bare-expression "alive" case (an alive check on the comparison's bool result is meaningless).
            TypeCheckerModel.TypeInfo leftType = resolveExprType(node.left, scope, func);
            boolean isInteger = TypeCheckerModel.INTEGER_TYPES.contains(leftType.baseType);
            if (!isInteger) {
                // Not an integer after all: let the ordinary comparison check report its own error.
                resolveExprType(node, scope, func);
                out.add(new Token.MatchPattern(hub.expressions.slotKeyOf(node), "alive", null));
                return;
            }
            // A literal 0 fits every integer type, but resolveExprType types it u64 and checkComparison needs equal base types, so
            // the comparison's types are set by hand here (literal coerced to the left side's type).
            node.right.chk.resolvedType = leftType.canonical();
            node.chk.resolvedType = "imut_bool";
            boolean isUnsigned = TypeCheckerModel.UNSIGNED_TO_SIGNED.containsKey(leftType.baseType);
            // "!= 0" suffices only for unsigned types (a signed divisor could still be -1: TYPE_MIN / -1 traps); "> 0" always suffices.
            boolean sufficientForDivision = node.text.equals(">") || (node.text.equals("!=") && isUnsigned);
            if (sufficientForDivision) {
                out.add(new Token.MatchPattern(hub.expressions.proofKeyOf(node.left), "nonzero", null));
            }
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("instanceof")) {
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(hub.expressions.slotKeyOf(node.left), "instanceof", node.right.chk.resolvedType));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("implements")) {
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(hub.expressions.slotKeyOf(node.left), "implements", node.right.chk.resolvedType));
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("in") || node.text.equals("into"))
                && node.left.type == TokenType.OPERATOR && node.left.text.equals("CALL")
                && node.left.left.type == TokenType.VARREF && node.left.left.text.equals("Some")) {
            // "match Some(i) in x" / "into x": sugar form handled separately (the ordinary in/into cases would reject "Some(i)").
            checkSomeIndexBoundsCondition(node, node.text, scope, func, out);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("in")) {
            resolveExprType(node, scope, func);
            // "u64 in GuaranteedEnum" is never a valid match condition: the right side is a type name, so no bounds proof can target it.
            if (node.chk.isGuaranteedEnumMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 in " + node.right.chk.resolvedType + "' is not valid as a match condition -- "
                                + "it's only usable as an ordinary bool expression");
            }
            // "u64 in string" is a byte-membership scan, not a bounds check: likewise never a match condition.
            if (node.chk.isStringMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 in <string>' is not valid as a match condition -- "
                                + "it's only usable as an ordinary bool expression");
            }
            out.add(new Token.MatchPattern(hub.accessAndAsync.boundsSlotKeyOf(node.left), "in", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("into")) {
            // "match i into arr{..}": like "in" (checkIn, called by hand), but recorded as an "into" proof, which also
            // legalizes writes at that exact index (isProvenWritable). See docs/TypeChecker.md, "Match chains".
            TypeCheckerModel.TypeInfo intoCondType = hub.checkIn(node, scope, func);
            node.chk.resolvedType = intoCondType.canonical();
            if (node.chk.isGuaranteedEnumMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 into " + node.right.chk.resolvedType + "' is not valid -- 'into' only makes "
                                + "sense against a range/dynarray target, never a guaranteed enum");
            }
            if (node.chk.isStringMembershipCheck) {
                throw new CompilerException("type", node.file, node.line,
                        "'u64 into <string>' is not valid -- 'into' only makes sense against a "
                                + "range/dynarray target, never a string");
            }
            out.add(new Token.MatchPattern(hub.accessAndAsync.boundsSlotKeyOf(node.left), "into", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("within")) {
            // "within" is checked like "in" and recorded under the same "in" proof kind, so every consumer of an "in" proof treats it alike.
            resolveExprType(node, scope, func);
            out.add(new Token.MatchPattern(hub.accessAndAsync.boundsSlotKeyOf(node.left), "in", null, boundsTargetSlotOf(node.right)));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
            // "match x fits u8{..}": inside the branch x is proven representable in u8, so "x as u8" is legal; a later write to x kills it.
            TypeCheckerModel.TypeInfo fitsLeft = resolveExprType(node.left, scope, func);
            if (fitsLeft.storage != null || !TypeCheckerModel.INTEGER_TYPES.contains(fitsLeft.baseType)) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'fits' requires a plain integer value on the left, got '" + fitsLeft.canonical() + "'");
            }
            if (node.right.type != TokenType.VARREF || !TypeCheckerModel.INTEGER_TYPES.contains(node.right.text)) {
                throw new CompilerException("type", node.right.file, node.right.line,
                        "'fits' requires an integer type name on the right (u8, s32, ...)");
            }
            String fitsKey = hub.expressions.slotKeyOf(node.left);
            if (fitsKey == null) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'fits' needs a variable or a field on the left, so the proof has a slot to attach to");
            }
            node.chk.fitsSourceBase = fitsLeft.baseType;
            node.chk.fitsTargetBase = node.right.text;
            node.chk.resolvedType = "imut_bool";
            out.add(new Token.MatchPattern(fitsKey, "fits", node.right.text));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
            // "match RANGE_PARAM is base{..}": reserved form, left must be a range, right literally "base" (range empty, tested at run time by
            // IS_BASE). Like "and", it has no resolveExprType case, so its result type is set here.
            TypeCheckerModel.TypeInfo leftType = resolveExprType(node.left, scope, func);
            if (!hub.isRangeType(leftType)) {
                throw new CompilerException("type", node.left.file, node.left.line,
                        "'is' requires a 'range' on the left, got '" + leftType.canonical() + "'");
            }
            if (node.right.type != TokenType.VARREF || !node.right.text.equals("base")) {
                throw new CompilerException("type", node.right.file, node.right.line,
                        "'is' only supports 'base' on the right");
            }
            node.chk.resolvedType = "imut_bool";
            out.add(new Token.MatchPattern(hub.expressions.slotKeyOf(node.left), "is_base", null));
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                && node.left.text.equals("Some")) {
            // "match Some(x){..}": the bytecode is a plain GT_ALIVE_CHECK; resolveExprType dispatches to checkSomeBuiltin and this adds
            // the "alive" proof for x's slot.
            ProofKills.suppressReads++;       // the alive check itself reads the pointer; that read is what renews the proof
            try {
                resolveExprType(node, scope, func);
            } finally {
                ProofKills.suppressReads--;
            }
            Token argExpr = hub.calls.collectSingleBuiltinArg(node, "Some", scope, func).get(0);
            node.chk.someProofKey = hub.expressions.proofKeyOf(argExpr);
            Token.MatchPattern someProof = new Token.MatchPattern(hub.expressions.proofKeyOf(argExpr), "alive", null);
            someProof.onId = node.chk.someOnRef;
            out.add(someProof);
            return;
        }
        // A bare value expression is no longer a match condition; "match Some(x)" is the only way to prove a pointer alive.
        throw new CompilerException("type", node.file, node.line,
                "a 'match' condition must be 'Some(x)', 'x in y', 'x within y', 'x into y', 'x instanceof Y', "
                        + "'x implements Y', 'x fits T' (for an integer x), 'x != 0'/'x > 0' (for an integer x), 'x is base' (for a range x), or "
                        + "an 'and'-chain of these -- a bare value expression is no longer accepted on "
                        + "its own; write 'match Some(" + describeConditionForError(node) + ")' if you "
                        + "meant to prove it isn't null");
    }

    /** Best-effort text for the "did you mean Some(..)" error: the variable name, else "...". */
    public String describeConditionForError(Token node) {
        return node.type == TokenType.VARREF ? node.text : "...";
    }

    /**
     * "match Some(i) in/into x{..}" = "match i in/into x{ match Some(x[i]){..} }": grants the index bounds proof and the
     * element-alive proof for x[i] at once. `i` must be a variable or a "."-chain. Also used by the "for match Some(i)" header
     * (checkForLoop) and the @lock(match Some(i) in self.field) qualifier. See docs/TypeChecker.md, "Match chains".
     */
    public void checkSomeIndexBoundsCondition(Token node, String kind, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func,
            List<Token.MatchPattern> out) {
        Token someCall = node.left;
        Token indexExpr = hub.calls.collectSingleBuiltinArg(someCall, "Some", scope, func).get(0);
        if (indexExpr.type != TokenType.VARREF
                && !(indexExpr.type == TokenType.OPERATOR && indexExpr.text.equals(".")
                        && indexExpr.right.type == TokenType.VARREF)) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "'Some(...) " + kind + "' requires a bare variable or a '.'-chain inside 'Some(...)', "
                            + "not an arbitrary expression");
        }
        // The bounds check reuses checkIn on a synthetic node.
        Token boundsOp = new Token(TokenType.OPERATOR, kind, node.line, node.file);
        boundsOp.left = indexExpr;
        boundsOp.right = node.right;
        TypeCheckerModel.TypeInfo condType = hub.checkIn(boundsOp, scope, func);
        node.chk.resolvedType = condType.canonical();
        someCall.chk.resolvedType = "imut_bool";
        if (boundsOp.chk.isGuaranteedEnumMembershipCheck) {
            throw new CompilerException("type", node.file, node.line,
                    "'Some(...) " + kind + " " + boundsOp.right.chk.resolvedType + "' is not valid -- "
                            + "'Some(...) " + kind + "' only makes sense against a range/dynarray "
                            + "target, never a guaranteed enum");
        }
        String indexSlot = hub.expressions.slotKeyOf(indexExpr);
        String targetSlot = boundsTargetSlotOf(node.right);
        Token.MatchPattern boundsPattern = new Token.MatchPattern(indexSlot, kind, null, targetSlot);
        out.add(boundsPattern);

        // Element-alive proof for x[i]: a throwaway LOOKUP on the "range(origin)"-unwrapped target (boundsTargetExprOf), checked
        // under a scope that already has the bounds proof.
        Token originExpr = boundsTargetExprOf(node.right);
        Token bracket = new Token(TokenType.DELINEATOR, "[", node.line, node.file);
        bracket.childs.add(indexExpr);
        Token lookupNode = new Token(TokenType.OPERATOR, "LOOKUP", node.line, node.file);
        lookupNode.left = originExpr;
        lookupNode.right = bracket;
        List<Token.MatchPattern> withBounds = new ArrayList<>(scope.activeMatchPatterns);
        withBounds.add(boundsPattern);
        TypeCheckerModel.Scope probeScope = new TypeCheckerModel.Scope(scope, withBounds, false);
        TypeCheckerModel.TypeInfo elemType = resolveExprType(lookupNode, probeScope, func);
        if (elemType.storage == null) {
            throw new CompilerException("type", indexExpr.file, indexExpr.line,
                    "'Some(...)' requires the element type to be a pointer, got '" + elemType.canonical()
                            + "'");
        }
        Token.MatchPattern elemProof = new Token.MatchPattern(hub.expressions.slotKeyOf(lookupNode), "alive", null);
        out.add(elemProof);
        lookupNode.chk.someOnRef = "ref".equals(elemType.storage) && !elemType.isSome;
        elemProof.onId = lookupNode.chk.someOnRef;
        lookupNode.chk.someProofKey = hub.expressions.slotKeyOf(lookupNode);
        // Lets BytecodeEmitter emit the GT_ALIVE_CHECK for this resolved lookup directly.
        node.chk.someIndexElementExpr = lookupNode;
    }

    /** Slot a bounds proof applies to: the range variable itself, or the origin argument of "range(origin)". Null if untrackable. */
    public String boundsTargetSlotOf(Token rightExpr) {
        if (rightExpr.type == TokenType.OPERATOR && rightExpr.text.equals("CALL")
                && rightExpr.left.type == TokenType.VARREF && rightExpr.left.text.equals("range")
                && !rightExpr.right.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            hub.locksAndProofs.collectCommaArgs(rightExpr.right.childs.get(0), args);
            if (args.size() == 1) {
                return hub.accessAndAsync.boundsSlotKeyOf(args.get(0));
            }
        }
        return hub.accessAndAsync.boundsSlotKeyOf(rightExpr);
    }

    /** Like boundsTargetSlotOf but returns the unwrapped expression Token, so a synthetic "origin[i]" LOOKUP can be built. */
    public Token boundsTargetExprOf(Token rightExpr) {
        if (rightExpr.type == TokenType.OPERATOR && rightExpr.text.equals("CALL")
                && rightExpr.left.type == TokenType.VARREF && rightExpr.left.text.equals("range")
                && !rightExpr.right.childs.isEmpty()) {
            List<Token> args = new ArrayList<>();
            hub.locksAndProofs.collectCommaArgs(rightExpr.right.childs.get(0), args);
            if (args.size() == 1) {
                return args.get(0);
            }
        }
        return rightExpr;
    }

    /**
     * Enforces generic bounds ("func f<T: Iface>") at each instantiation site only (templates are never checked unbound).
     * Runs right after collectDeclarations so structImplementsInterface is reliable; GenericsExpander recorded the checks in
     * Token.pendingGenericBoundChecks. See docs/TypeChecker.md, "Generic bounds".
     */
    public void validateGenericBounds(List<Token> rootLines) {
        for (Token lineTok : rootLines) {
            Token entry = lineTok.childs.get(0);
            List<Token.GenericBoundCheck> checks = entry.pendingGenericBoundChecks;
            if (checks == null) {
                continue;
            }
            for (Token.GenericBoundCheck check : checks) {
                if (!structImplementsInterface(check.concreteTypeName, check.interfaceName)) {
                    throw new CompilerException("type", entry.file, entry.line,
                            "'" + check.concreteTypeName + "' does not implement '" + check.interfaceName
                                    + "' -- required by the bound on type parameter '" + check.typeParamName
                                    + "'");
                }
            }
        }
    }

    /** True if the struct implements the interface directly or through an interface that extends it, including standalone "impl X for Y" blocks (implsByConcreteType). */
    public boolean structImplementsInterface(String structName, String interfaceName) {
        TypeCheckerModel.StructInfo info = hub.model.structs.get(structName);
        if (info == null) {
            return false;
        }
        for (String directIface : info.implementsNames) {
            if (interfaceExtendsOrIs(directIface, interfaceName)) {
                return true;
            }
        }
        List<TypeCheckerModel.ImplInfo> impls = hub.model.implsByConcreteType.get(structName);
        if (impls != null) {
            for (TypeCheckerModel.ImplInfo impl : impls) {
                if (impl.interfaceName != null && interfaceExtendsOrIs(impl.interfaceName, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean interfaceExtendsOrIs(String actualInterfaceName, String targetInterfaceName) {
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.push(actualInterfaceName);
        while (!work.isEmpty()) {
            String name = work.pop();
            if (name.equals(targetInterfaceName)) {
                return true;
            }
            TypeCheckerModel.InterfaceInfo info = hub.model.interfaces.get(name);
            if (info != null && seen.add(name)) {
                info.extendsNames.forEach(work::push);
            }
        }
        return false;
    }

    /** If chain: each branch is checked against its own copy of the pre-if moved set; the shared set becomes their union. See docs/TypeChecker.md, "Branch merge and partly-moved slots". */
    public void checkIfChain(Token ifTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        Set<String> preState = scope.movedSlots;
        Set<String> union = new HashSet<>();
        List<Set<String>> survivorMoves = new ArrayList<>();
        for (Token branch = ifTok; branch != null; branch = branch.next) {
            if (!branch.sub.isEmpty()) {
                Token condNode = branch.condition();
                // The one place insideShortCircuitEligiblePosition is seeded true: a branch condition is the top level where &&then / ||then is legal.
                boolean previousEligible = hub.model.insideShortCircuitEligiblePosition;
                hub.model.insideShortCircuitEligiblePosition = true;
                TypeCheckerModel.TypeInfo condType;
                try {
                    condType = resolveExprType(condNode, scope, func);
                } finally {
                    hub.model.insideShortCircuitEligiblePosition = previousEligible;
                }
                requireBoolOrNumericCondition(condType, condNode);
            }
            Set<String> branchMoved = new HashSet<>(preState);
            TypeCheckerModel.Scope branchScope = new TypeCheckerModel.Scope(scope, branchMoved);
            // if / else do not change loop context.
            TypeCheckerModel.Scope branchBodyScope = hub.statements.checkLines(branch.childs, branchScope, func, insideLoop);
            // A branch's natural end is not a function/loop boundary: only its own locals are destructed.
            branch.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(branch.childs, branchBodyScope, branchBodyScope);
            union.addAll(branchMoved);
            if (!hub.declarations.closedCaseTerminates(branch.childs)) { survivorMoves.add(branchMoved); }
        }
        hub.statements.finishBranchMerge(preState, union, survivorMoves, !hub.statements.chainHasElse(ifTok));
    }

    /** An if/elseif condition must be bool or a numeric type (integer, f32, char; C-style "zero is false"); no truthy pointers or collections. See docs/TypeChecker.md, "Conditions". */
    public void requireBoolOrNumericCondition(TypeCheckerModel.TypeInfo t, Token at) {
        // Storage-less only: a pointer to a bool/number is not a condition (null checks use "match Some(x)").
        if (t.storage == null && (t.baseType.equals("bool") || t.baseType.equals("char")
                || TypeCheckerModel.NUMERIC_TYPES.contains(t.baseType))) {
            return;
        }
        throw new CompilerException("type", at.file, at.line,
                "an 'if'/'elseif' condition must be 'bool' or a numeric type (an integer, 'f32', or 'char') "
                        + "-- matching C's own \"any scalar, zero is false\" convention -- but this one resolved "
                        + "to '" + t.canonical() + "'");
    }

    /**
     * "for x in range{..}" desugars into real AST nodes: let $range = ..; let x = $range.start; condition "x in $range"; step x++.
     * The "in" condition is byte-for-byte what an ordinary "in" expression emits. See docs/TypeChecker.md, "For loop desugaring".
     */
    public void checkForLoop(Token forTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        boolean rootIsIn = !forTok.sub.isEmpty() && forTok.sub.get(0).type == TokenType.OPERATOR
                && forTok.sub.get(0).text.equals("in");
        boolean rootIsInto = !forTok.sub.isEmpty() && forTok.sub.get(0).type == TokenType.OPERATOR
                && forTok.sub.get(0).text.equals("into");
        if (!rootIsIn && !rootIsInto) {
            throw new CompilerException("type", forTok.file, forTok.line,
                    "'for' requires 'varname in range { ... }'");
        }
        if (rootIsInto && !forTok.isForMatch) {
            // A plain "for i into arr" is rejected: only "for match i into arr" grants the write proof.
            throw new CompilerException("type", forTok.file, forTok.line,
                    "'into' in a 'for' header requires 'for match', not a plain 'for' -- write 'for match "
                            + forTok.sub.get(0).left.text + " into ...{...}', or use plain 'in' instead");
        }
        Token originalIn = forTok.sub.get(0);
        Token loopVarTok = originalIn.left;
        Token rangeExpr = originalIn.right;
        // "for match Some(i) in/into x{..}": detected before the plain-variable check; the body is wrapped in a synthetic
        // "match Some(x[i])" further down.
        boolean isSomeIndexForm = loopVarTok.type == TokenType.OPERATOR && loopVarTok.text.equals("CALL")
                && loopVarTok.left.type == TokenType.VARREF && loopVarTok.left.text.equals("Some");
        if (isSomeIndexForm) {
            // Needs "for match", otherwise the proofs have nowhere to go.
            if (!forTok.isForMatch) {
                throw new CompilerException("type", originalIn.file, originalIn.line,
                        "'for Some(...) " + originalIn.text + " ...{...}' requires 'for match', not a "
                                + "plain 'for'");
            }
            Token indexExpr = hub.calls.collectSingleBuiltinArg(loopVarTok, "Some", scope, func).get(0);
            if (indexExpr.type != TokenType.VARREF) {
                throw new CompilerException("type", indexExpr.file, indexExpr.line,
                        "'for match Some(...) " + originalIn.text + "' requires a bare, new variable name "
                                + "inside 'Some(...)', not an arbitrary expression");
            }
            loopVarTok = indexExpr;
        }
        if (loopVarTok.type != TokenType.VARREF) {
            throw new CompilerException("type", originalIn.file, originalIn.line,
                    "'for' requires a plain, new variable name on the left of 'in', not an existing "
                            + "expression");
        }

        TypeCheckerModel.TypeInfo rangeType = resolveExprType(rangeExpr, scope, func);
        if (hub.isUnsafeDynArrayType(rangeType)) {
            // Banned: an unsafe dynarray has no compiler-known length; iterate by hand with loop{} in unsafe code.
            throw new CompilerException("type", rangeExpr.file, rangeExpr.line,
                    "'for ... in/into' cannot be used on an 'unsafe dynarray' -- it has no compiler-known "
                            + "length to loop against; iterate by hand with a bare 'loop{}' inside 'unsafe' "
                            + "code instead");
        }
        if (hub.isArrayType(rangeType.baseType)) {
            // "for i in arr" / "for match i in arr" on a fixed array: sugar for
            // "for i in range(arr)" (see checkIn's same rewrite).
            rangeExpr = hub.wrapFixedArrayAsRange(rangeExpr);
            rangeType = resolveExprType(rangeExpr, scope, func);
        }
        boolean isDynArraySource = hub.isDynArrayType(rangeType);
        if (!hub.isRangeType(rangeType) && !isDynArraySource) {
            throw new CompilerException("type", rangeExpr.file, rangeExpr.line,
                    "'for ... in' requires a range or a dynarray on the right, got '"
                            + rangeType.canonical() + "'");
        }
        // Dynarray source: the loop variable starts at a literal 0 (no .start); the body gets no proof about dynArrExpr[x] itself
        // (it needs its own "match x in .."; entries may be null).

        hub.model.forVarCounter++;
        String rangeVarName = "$for_range_" + hub.model.forVarCounter;
        TypeCheckerModel.Scope forScope = new TypeCheckerModel.Scope(scope);

        // let $range = <rangeExpr>; for a dynarray "let $range = mut ref <rangeExpr>" so the owns source is borrowed, not moved.
        Token rangeSourceExpr = rangeExpr;
        if (isDynArraySource) {
            Token refWrap = new Token(TokenType.OPERATOR, "ref", forTok.line, forTok.file);
            refWrap.left = rangeExpr;
            // "ref X" is indeterminate-mutability, so it needs a mut wrapper to bind to a let.
            Token mutRefWrap = new Token(TokenType.OPERATOR, "mut", forTok.line, forTok.file);
            mutRefWrap.left = refWrap;
            rangeSourceExpr = mutRefWrap;
        }
        Token rangeNameTok = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
        Token rangeLetTok = new Token(TokenType.KEYWORD, "let", forTok.line, forTok.file);
        rangeLetTok.childs = new ArrayList<>();
        rangeLetTok.childs.add(rangeNameTok);
        Token rangeAssign = new Token(TokenType.OPERATOR, "=", forTok.line, forTok.file);
        rangeAssign.left = rangeLetTok;
        rangeAssign.right = rangeSourceExpr;
        TypeCheckerModel.TypeInfo rangeAssignType = hub.expressions.checkAssign(rangeAssign, forScope, func);
        rangeAssign.chk.resolvedType = rangeAssignType.canonical();

        // let x = $range.start (a dynarray starts at literal 0).
        Token loopVarLetTok = new Token(TokenType.KEYWORD, "let", forTok.line, forTok.file);
        loopVarLetTok.childs = new ArrayList<>();
        loopVarLetTok.childs.add(loopVarTok);
        Token loopVarAssign = new Token(TokenType.OPERATOR, "=", forTok.line, forTok.file);
        loopVarAssign.left = loopVarLetTok;
        Token loopVarInitialValue;
        if (isDynArraySource) {
            loopVarInitialValue = new Token(TokenType.INTEGER, "0", forTok.line, forTok.file);
        } else {
            Token rangeRefForStart = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
            Token startNameTok = new Token(TokenType.VARREF, "start", forTok.line, forTok.file);
            Token startDot = new Token(TokenType.OPERATOR, ".", forTok.line, forTok.file);
            startDot.left = rangeRefForStart;
            startDot.right = startNameTok;
            loopVarInitialValue = startDot;
        }
        // The loop variable is mut (the synthesized ++ writes it); .start / literal 0 are indeterminate, so wrapping in mut is legal.
        Token mutStartDot = new Token(TokenType.OPERATOR, "mut", forTok.line, forTok.file);
        mutStartDot.left = loopVarInitialValue;
        loopVarAssign.right = mutStartDot;
        TypeCheckerModel.TypeInfo loopVarAssignType = hub.expressions.checkAssign(loopVarAssign, forScope, func);
        loopVarAssign.chk.resolvedType = loopVarAssignType.canonical();

        // per-iteration condition: x in $range (a real 'in' node)
        Token loopVarRefForCond = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
        Token rangeRefForCond = new Token(TokenType.VARREF, rangeVarName, forTok.line, forTok.file);
        Token conditionIn = new Token(TokenType.OPERATOR, "in", forTok.line, forTok.file);
        conditionIn.left = loopVarRefForCond;
        conditionIn.right = rangeRefForCond;
        TypeCheckerModel.TypeInfo condType = hub.checkIn(conditionIn, forScope, func);
        conditionIn.chk.resolvedType = condType.canonical();

        // increment: x++ (a real postfix '++' node)
        Token loopVarRefForInc = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
        Token increment = new Token(TokenType.OPERATOR, "++", forTok.line, forTok.file);
        increment.unary = true;
        increment.left = loopVarRefForInc;
        TypeCheckerModel.TypeInfo incType = hub.expressions.checkIncrement(increment, forScope, func);
        increment.chk.resolvedType = incType.canonical();

        // "for match i in x{..}": same bytecode, but the body scope also carries the "i in x" proof, built against the ORIGINAL
        // rangeExpr (not $range) so indexing in the body names the same slot; wrapped in a fresh Scope, forScope stays untouched.
        TypeCheckerModel.Scope forScopeForBody = forScope;
        if (forTok.isForMatch) {
            String iSlotKey = hub.expressions.slotKeyOf(loopVarTok);
            if (iSlotKey != null) {
                List<Token.MatchPattern> extended = new ArrayList<>(forScope.activeMatchPatterns);
                // "into" also grants everything "in" does; only the recorded kind differs.
                String kind = rootIsInto ? "into" : "in";
                extended.add(new Token.MatchPattern(iSlotKey, kind, null, boundsTargetSlotOf(rangeExpr)));
                forScopeForBody = new TypeCheckerModel.Scope(forScope, forScope.movedSlots, extended, false);
            }
        }
        // The loop variable and the source slot are immutable inside the body (all for forms). A fresh scope is wrapped even without
        // for-match so the synthesized pieces above, which do reassign the variable, are unaffected.
        if (forScopeForBody == forScope) {
            forScopeForBody = new TypeCheckerModel.Scope(forScope);
        }
        Set<String> immutableHere = new HashSet<>();
        immutableHere.add(loopVarTok.text);
        String arrSlotKey = hub.expressions.slotKeyOf(rangeExpr);
        if (arrSlotKey != null) {
            immutableHere.add(arrSlotKey);
        }
        forScopeForBody.ownLoopImmutableSlots = immutableHere;
        if (isSomeIndexForm) {
            // Wrap the body in a real "match Some(origin[i]){..}" statement so existing match checking and emission handle it unchanged.
            Token originExpr = boundsTargetExprOf(rangeExpr);
            Token idxRef = new Token(TokenType.VARREF, loopVarTok.text, forTok.line, forTok.file);
            Token bracket = new Token(TokenType.DELINEATOR, "[", forTok.line, forTok.file);
            bracket.childs.add(idxRef);
            Token lookupNode = new Token(TokenType.OPERATOR, "LOOKUP", forTok.line, forTok.file);
            lookupNode.left = originExpr;
            lookupNode.right = bracket;
            Token someArgs = new Token(TokenType.DELINEATOR, "(", forTok.line, forTok.file);
            someArgs.childs.add(lookupNode);
            Token someVarRef = new Token(TokenType.VARREF, "Some", forTok.line, forTok.file);
            Token someCall = new Token(TokenType.OPERATOR, "CALL", forTok.line, forTok.file);
            someCall.left = someVarRef;
            someCall.right = someArgs;
            Token syntheticMatch = new Token(TokenType.KEYWORD, "match", forTok.line, forTok.file);
            syntheticMatch.sub = new ArrayList<>();
            syntheticMatch.sub.add(someCall);
            syntheticMatch.childs = forTok.childs;
            // LINE wrapper shape (Parser.wrapAsLine): statement lists hold LINE tokens with the statement in childs.get(0).
            Token lineTok = new Token(TokenType.LINE, "line", forTok.line, forTok.file);
            lineTok.childs.add(syntheticMatch);
            forTok.childs = new ArrayList<>();
            forTok.childs.add(lineTok);
        }
        hub.statements.collectLoopMoves(forTok.childs, forScope, forScope.movedSlots);
        TypeCheckerModel.Scope forBodyScope = hub.statements.checkLinesAsLoopBody(forTok.childs, forScopeForBody, func, isDynArraySource);
        // Per-iteration scope exit, as for "loop".
        forTok.chk.destructOnExit = hub.statements.collectNaturalEndDestruct(forTok.childs, forBodyScope, forBodyScope);
        // Desugared pieces for BytecodeEmitter, stored in forTok.sub: [rangeAssign, loopVarAssign, conditionIn, increment].
        forTok.sub = new ArrayList<>();
        forTok.sub.add(rangeAssign);
        forTok.sub.add(loopVarAssign);
        forTok.sub.add(conditionIn);
        forTok.sub.add(increment);
    }

    public void checkReturn(Token returnTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        if (returnTok.sub.isEmpty()) {
            if (!func.returnType.baseType.equals("void")) {
                throw new CompilerException("type", returnTok.file, returnTok.line,
                        "empty 'return' in a function declared to return '"
                                + func.returnType.canonical() + "'");
            }
            returnTok.chk.resolvedType = func.returnType.canonical();
            returnTok.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
            returnTok.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
            return;
        }
        Token previousDirectReturnExprNode = hub.model.directReturnExprNode;
        hub.model.directReturnExprNode = returnTok.operand();
        // 'return foo()' forwards this function's $ret_dest into a struct-returning callee (third legal RVO position).
        // See docs/TypeChecker.md, "Struct return values (RVO)".
        boolean previousRvoEligible = hub.model.insideStructRvoDestinationPosition;
        hub.model.insideStructRvoDestinationPosition = hub.isStructByValueReturn(func.returnType);
        TypeCheckerModel.TypeInfo exprType;
        try {
            exprType = resolveExprType(returnTok.operand(), scope, func);
        } finally {
            hub.model.directReturnExprNode = previousDirectReturnExprNode;
            hub.model.insideStructRvoDestinationPosition = previousRvoEligible;
        }
        if (func.returnType.isSome && !exprType.isSome && hub.locksAndProofs.isProvenAlive(returnTok.operand(), scope)) {
            // A return expression proven alive by an active `match Some` counts as 'some' for a 'some' return type.
            // A bare `new` never does (malloc can fail). See docs/TypeChecker.md, "Returning owns some".
            exprType = exprType.withSome(true);
            returnTok.operand().chk.resolvedType = exprType.canonical();
        }
        if (!hub.acceptsAdaptingLiteral(func.returnType, exprType, returnTok.operand())) {
            throw new CompilerException("type", returnTok.file, returnTok.line,
                    "return type mismatch: function returns '" + func.returnType.canonical()
                            + "' but this statement returns '" + exprType.canonical() + "'");
        }
        if (func.isAsync && hub.locksAndProofs.containsPointerAnywhere(func.returnType) && !hub.declarations.unsafeBypass(scope, "async")) {
            throw new CompilerException("type", returnTok.file, returnTok.line,
                    "returning '" + func.returnType.canonical() + "' (a pointer, or a struct with one "
                            + "nested somewhere inside it) from an '@async' function requires 'unsafe' "
                            + "code");
        }
        // Struct-by-value return: only a fresh struct literal, a readable value chain of the same struct, or a tail call.
        // See docs/TypeChecker.md, "Struct return values (RVO)".
        if (hub.isStructByValueReturn(func.returnType)) {
            Token retExpr = returnTok.operand();
            boolean isFreshLiteral = retExpr.type == TokenType.OPERATOR && retExpr.text.equals("INSTANTIATE");
            boolean isReadableExpr = hub.isReadableValueChain(retExpr);
            // checkCall sets isStructRvoCall only for an eligible position, so testing the flag is enough.
            Token unwrapped = retExpr;
            while (unwrapped.type == TokenType.OPERATOR
                    && (unwrapped.text.equals("mut") || unwrapped.text.equals("imut"))) {
                unwrapped = unwrapped.left;
            }
            boolean isTailForwardCall = unwrapped.chk.isStructRvoCall;
            if (!isFreshLiteral && !isReadableExpr && !isTailForwardCall) {
                throw new CompilerException("type", returnTok.file, returnTok.line,
                        "a function returning '" + func.returnType.baseType + "' by value may only "
                                + "'return' a fresh struct-literal expression (e.g. 'return "
                                + func.returnType.baseType + "{...}'), an existing variable, field, or "
                                + "array/dynarray/string element (or any chain of those, e.g. 'return "
                                + func.returnType.baseType.toLowerCase() + "Var', 'return self.field', "
                                + "'return arr[i]', 'return self.arr[i]', 'return arr[i].field'), or a "
                                + "direct call to another function returning the same struct type by "
                                + "value (e.g. 'return otherFunc()') -- not any other kind of computed "
                                + "expression");
            }
        }
        // Annotate with the declared return type (differs from the expression's when returning 'null' for a pointer type).
        returnTok.chk.resolvedType = func.returnType.canonical();
        // The slot named directly in the return expression passes to the caller and is exempt from destruction.
        String preservedSlotKey = hub.expressions.slotKeyOf(returnTok.operand());
        if (preservedSlotKey != null) {
            hub.model.fnEverMoved.add(preservedSlotKey);
        }
        returnTok.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, preservedSlotKey);
        returnTok.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
    }

    /**
         * `throw <string>`: unconditional termination that unwinds to the nearest `try`/`catch`, destructing the owns locals
         * alive at this throw site (no preserved slot). See docs/TypeChecker.md, "throw, try and catch".
         */
    public void checkThrow(Token throwTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        if (throwTok.sub.isEmpty()) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a string expression, e.g. 'throw \"message\"'");
        }
        // 'throw' is safe code but needs '@throws' on the function; checked first so the message is never masked.
        if (hub.impls.getDecorator(func.funcToken.decorators, "throws") == null) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'" + func.name + "' uses 'throw' but is not decorated '@throws' -- add '@throws' to "
                            + "this function's declaration");
        }
        // Ghost-table functions have no gt_routine of their own, so 'throw' would name a label that is never emitted.
        for (String decoratorName : Arrays.asList("gt_init", "gt_alive_check", "gt_destruct", "gt_register", "gt_moved", "gt_ref_id", "gt_ref_resolve")) {
            if (hub.impls.getDecorator(func.funcToken.decorators, decoratorName) != null) {
                throw new CompilerException("type", throwTok.file, throwTok.line,
                        "'throw' cannot be used inside '@" + decoratorName + "' -- a ghost-table function "
                                + "has no 'gt_routine' of its own to unwind into");
            }
        }
        if (hub.impls.isPureFunc(func)) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'" + func.name + "' is '@pure' and cannot 'throw' -- an unconditional program "
                            + "termination can never be verified at compile time");
        }
        hub.model.rethrowOperand = throwTok.operand();   // `throw e` of a catch parameter is the one bare use that stays legal
        TypeCheckerModel.TypeInfo exprType;
        try {
            exprType = resolveExprType(throwTok.operand(), scope, func);
        } finally {
            hub.model.rethrowOperand = null;
        }
        TypeCheckerModel.TypeInfo requiredType = new TypeCheckerModel.TypeInfo("static", "imut", "string");
        if (!hub.typesCompatible(requiredType, exprType)) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a 'static imut string' -- got '" + exprType.canonical() + "'");
        }
        // Any valid `throw` needs the per-throw-site destruct-and-unwind sequence to exist in the bytecode.
        hub.model.usesThrow = true;
        func.sawThrow = true;
        hub.model.throwSites.add(new TypeCheckerModel.ThrowSite(func, throwTok));
        // Per-throw-site destruct list and held-lock releases (a throw out of an OPEN case must not leave the lock held).
        throwTok.chk.destructOnExit = hub.statements.collectOwnsToDestruct(scope, scope.functionRootScope, null);
        throwTok.chk.unlockOnExit = hub.statements.collectLockReleasesToBoundary(scope, scope.functionRootScope);
        // THROW takes one operand: a string literal (templates are already reduced) or a bare variable.
        Token expr = throwTok.operand();
        if (expr.type != TokenType.STRING && expr.type != TokenType.VARREF) {
            throw new CompilerException("type", throwTok.file, throwTok.line,
                    "'throw' requires a string literal or a bare variable -- not a more complex "
                            + "expression");
        }
        throwTok.chk.resolvedType = exprType.canonical();
    }

    /**
         * `try CALL catch(e){..}`: wraps exactly one call, `new`, `par`/`await` or `dyn`/`resize`/`clone`, type-checks it as usual
         * and checks the catch body as a nested block. See docs/TypeChecker.md, "throw, try and catch".
         */
    public TypeCheckerModel.TypeInfo checkTry(Token tryTok, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func, boolean insideLoop) {
        if (tryTok.sub == null || tryTok.sub.isEmpty()) {
            throw new CompilerException("type", tryTok.file, tryTok.line,
                    "'try' requires an expression (a call to a '@throws' function) before 'catch'");
        }
        Token callNode = tryTok.operand();
        // Shapes `try` may wrap: a CALL (including the dyn/resize/clone builtins), `new`, `par`/`await`.
        // `new` and the builtins enforce their own wrapping rule; a CALL is enforced by requireThrowsWrapping.
        boolean isAsyncNode = callNode.type == TokenType.OPERATOR
                && (callNode.text.equals("par") || callNode.text.equals("await"));
        boolean isNewNode = callNode.type == TokenType.OPERATOR && callNode.text.equals("new");
        boolean isThrowingBuiltinCall = callNode.type == TokenType.OPERATOR && callNode.text.equals("CALL")
                && callNode.left.type == TokenType.VARREF
                && (callNode.left.text.equals("dyn") || callNode.left.text.equals("resize")
                        || callNode.left.text.equals("clone"));
        if (!(callNode.type == TokenType.OPERATOR && callNode.text.equals("CALL")) && !isNewNode && !isAsyncNode) {
            throw new CompilerException("type", callNode.file, callNode.line,
                    "'try' can only wrap a direct call to a '@throws' function, a 'new' expression, a "
                            + "'par'/'await' call, or a 'dyn'/'resize'/'clone' builtin call, not a more general expression");
        }
        callNode.chk.insideTry = true;
        // A nested allocation cannot get its own `try`, so coverage is tracked by the insideThrowingAllocContext flag
        // for the whole descent, not by the per-token insideTry.
        boolean previousInsideThrowingAllocContext = hub.model.insideThrowingAllocContext;
        if (isNewNode || isThrowingBuiltinCall || isAsyncNode) {
            hub.model.insideThrowingAllocContext = true;
        }
        TypeCheckerModel.TypeInfo resultType;
        try {
            resultType = resolveExprType(callNode, scope, func);
        } finally {
            hub.model.insideThrowingAllocContext = previousInsideThrowingAllocContext;
        }
        // Declare the catch parameter in a scope around the catch body. A bare use is rejected in the VARREF case
        // (`e.msg`, `e.stack_trace`, or `throw e`).
        TypeCheckerModel.Scope catchBodyScope = new TypeCheckerModel.Scope(scope);
        catchBodyScope.vars.put(tryTok.catchParamName, new TypeCheckerModel.TypeInfo("static", "imut", "string"));
        Token previousActiveCatchTry = hub.model.activeCatchTries.put(tryTok.catchParamName, tryTok);
        // insideCatchBody / catchEnclosingLoopBoundary are saved and restored around the catch body, so a `continue`
        // at any depth inside it is recognised, and a nested catch restores the outer values.
        boolean previousInsideCatchBody = hub.model.insideCatchBody;
        TypeCheckerModel.Scope previousCatchLoopBoundary = hub.model.catchEnclosingLoopBoundary;
        hub.model.insideCatchBody = true;
        hub.model.catchEnclosingLoopBoundary = scope.loopBoundaryScope;
        try {
            hub.statements.checkLines(tryTok.childs, catchBodyScope, func, insideLoop);
        } finally {
            hub.model.insideCatchBody = previousInsideCatchBody;
            hub.model.catchEnclosingLoopBoundary = previousCatchLoopBoundary;
            if (previousActiveCatchTry == null) {
                hub.model.activeCatchTries.remove(tryTok.catchParamName);
            } else {
                hub.model.activeCatchTries.put(tryTok.catchParamName, previousActiveCatchTry);
            }
        }
        // No destructOnExit: a catch body never reaches its natural end (it must terminate, checked below).
        tryTok.chk.resolvedType = resultType.canonical();
        // A catch body must end every path in return, throw or continue: a caught throw jumps straight into it,
        // never back through the call's return path. See docs/TypeChecker.md, "throw, try and catch".
        if (!hub.statements.definitelyReturns(tryTok.childs)) {
            throw new CompilerException("type", tryTok.file, tryTok.line,
                    "'catch' must be terminating -- every path through its body must end in a 'return', "
                            + "a 'throw', or a 'continue' (a caught throw is redirected straight into this "
                            + "block, never back through the wrapped call's own normal return path, so "
                            + "there is no well-defined way to fall out of 'catch' and resume normal "
                            + "execution -- 'continue' is only legal here when this 'catch' is itself "
                            + "inside an enclosing 'try { ... }' block, which is exactly where control "
                            + "would resume)");
        }
        return resultType;
    }

    /** The two-way @throws contract, checked at each call-resolution site once the callee is known: an '@throws' callee must be in try/`?`, a non-throwing one must not be. */
    public void requireThrowsWrapping(TypeCheckerModel.FuncInfo candidate, Token op) {
        if (candidate.isThrows && !op.chk.insideTry && !op.chk.coveredByThrowingNew) {
            throw new CompilerException("type", op.file, op.line,
                    "call to '" + candidate.name + "', which is decorated '@throws', must be wrapped in "
                            + "'try ... catch { ... }'");
        }
        if (!candidate.isThrows && op.chk.insideTry) {
            throw new CompilerException("type", op.file, op.line,
                    "'try' wraps a call to '" + candidate.name + "', which is not decorated '@throws' -- "
                            + "'try'/'catch' is only needed (and only allowed) around a call to a "
                            + "'@throws' function");
        }
    }

    // ---- expression type resolution -----------------------------------

    public TypeCheckerModel.TypeInfo resolveExprType(Token node, TypeCheckerModel.Scope scope, TypeCheckerModel.FuncInfo func) {
        TypeCheckerModel.TypeInfo result = hub.expressions.resolveExprTypeInner(node, scope, func);
        if (node.chk.resolvedCallTarget != null && node.type == TokenType.OPERATOR && "CALL".equals(node.text)) {
            ProofKills.call(scope.activeMatchPatterns, node, node.chk.resolvedCallTarget);
        }
        node.chk.resolvedType = result.canonical();
        if (func != null && hub.impls.isPureRtFunc(func)) {
            hub.impls.requirePureRtNoPointer(result, node, func, "in an expression");
        }
        return result;
    }
}
