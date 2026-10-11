package caspien.bytecodeemitter;
import caspien.typechecker.TypeChecker;
import caspien.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterStatements: static allocs, hoisting, statements, loops, expression dispatch (part 2 of 4; see README.md in this folder). */
abstract class BytecodeEmitterStatements extends BytecodeEmitterCore {
    // methods defined in later parts of the chain
    abstract public String declareHiddenLocal(String type);
    abstract public String literalValueOf(Token node);
    abstract public String qualifiedDotName(Token node);
    abstract public Token singleBuiltinArg(Token op);
    abstract public Token unwrapMutWrappers(Token t);
    abstract public boolean isOwnsTemporary(Token value);
    abstract public boolean isQualifiedNameableDot(Token node);
    abstract public boolean isThenVariant(Token node);
    abstract public void collectCommaArgs(Token node, List<Token> out);
    abstract public void emitAddressOf(Token op);
    abstract public void emitAs(Token op);
    abstract public void emitAssign(Token op);
    abstract public void emitBinaryArith(Token op);
    abstract public void emitCallOrBuiltin(Token op);
    abstract public void emitComparison(Token op);
    abstract public void emitCompoundAssign(Token op);
    abstract public void emitDecrement(Token op);
    abstract public void emitDot(Token op);
    abstract public void emitFits(Token node);
    abstract public void emitIncrement(Token op);
    abstract public void emitInstantiate(Token op);
    abstract public void emitLogical(Token op, String name);
    abstract public void emitLogicalNot(Token op);
    abstract public void emitLookup(Token op);
    abstract public void emitNew(Token op);
    abstract public void emitOwnsTemporaryViaLocal(Token value, List<String> tempNames);
    abstract public void emitShortCircuit(Token node, String falseLabel, String trueLabel);
    abstract public void emitStructRvoForwardCall(Token callOp, String ptrType);
    abstract public void emitSwap(Token op);
    abstract public void emitUnaryMinus(Token op);



    /**
     * A compile-time-constant `let static` / global: the folded value is part of the allocation line. Scalar: "MNEMONIC name type value". Struct: a
     * container line "MNEMONIC name imut_Struct" then one "MNEMONIC name.member type value" per member (nested: "name.outer.inner"). Array:
     * container then "name.0", "name.1"... Range: container, "name.start", "name.end". MNEMONIC is ALLOC_STATIC (local) or GLOBAL.
     */
    public void emitStaticAlloc(String mnemonic, String name, String resolvedType, Token rhs) {
        Token unwrapped = unwrapStaticExpr(rhs);
        String structName = staticStructNameOf(unwrapped);
        if (structName != null) {
            line(mnemonic + " " + name + " imut_" + structName);
            TypeChecker.StructInfo structInfo = checker.getStructs().get(structName);
            // Hidden ___type (classId) first, as emitInstantiate pushes it; absent for @untyped. Without it a classId-bearing static was left uninitialised.
            Map<String, Token> valueByMember = new HashMap<>();
            for (Token lineTok : unwrapped.right.childs) {
                Token assignNode = lineTok.childs.get(0);
                valueByMember.put(assignNode.left.text, assignNode.right);
            }
            // Walk the real layout (___type, members, padding): the backend places each "name.member" line at the running sum of the sizes before it,
            // so padding gaps must be lines too ("name.$padN").
            int padCount = 0;
            for (StructLayoutEntry entry : computeStructLayout(structInfo).entries) {
                if (entry.paddingBytes > 0) {
                    line(mnemonic + " " + name + ".$pad" + (padCount++) + " imut_u8[" + entry.paddingBytes + "]");
                } else if (entry.memberName.equals("___type")) {
                    line(mnemonic + " " + name + ".___type imut_u64 " + structInfo.classId);
                } else {
                    emitStaticAlloc(mnemonic, name + "." + entry.memberName, entry.canonicalType, valueByMember.get(entry.memberName));
                }
            }
            return;
        }
        List<Token> arrayElements = staticArrayElements(unwrapped);
        if (arrayElements != null) {
            // Fixed-array literal: a no-value container line, then "name.0", "name.1", ... per element.
            line(mnemonic + " " + name + " " + resolvedType);
            String elemType = staticArrayElementType(resolvedType, unwrapped);
            for (int i = 0; i < arrayElements.size(); i++) {
                emitStaticAlloc(mnemonic, name + "." + i, elemType, arrayElements.get(i));
            }
            return;
        }
        if (isRangeType(resolvedType)) {
            // Range: a no-value container line, then "name.start" and "name.end".
            long[] bounds = rangeBoundValues(unwrapped);
            line(mnemonic + " " + name + " " + resolvedType);
            line(mnemonic + " " + name + ".start imut_u64 " + bounds[0]);
            line(mnemonic + " " + name + ".end imut_u64 " + bounds[1]);
            return;
        }
        line(mnemonic + " " + name + " " + resolvedType + " " + foldStaticConstant(rhs));
    }

    /** Strips (mut|imut) wrappers, 'as' casts and grouping parens from a static initializer down to the node that has a runtime shape. */
    public Token unwrapStaticExpr(Token node) {
        while (true) {
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                node = node.childs.get(0);
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("as")) {
                node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary
                    && (node.text.equals("mut") || node.text.equals("imut"))) {
                node = node.left;
                continue;
            }
            return node;
        }
    }

    /** The element expressions if `rhs` is an array literal ("[" node), else null. */
    public List<Token> staticArrayElements(Token rhs) {
        if (rhs.type != TokenType.DELINEATOR || !rhs.text.equals("[")) {
            return null;
        }
        List<Token> elements = new ArrayList<>();
        collectCommaArgs(rhs.childs.get(0), elements);
        return elements;
    }

    /** Strips the trailing "[N]" of a static array type: "mut_u64[3]" -> "mut_u64". */
    public String staticArrayElementType(String resolvedType, Token at) {
        int bracket = resolvedType.lastIndexOf('[');
        if (bracket < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "expected an array type here, got '" + resolvedType + "'");
        }
        return resolvedType.substring(0, bracket);
    }

    /** The struct name if `rhs` is a struct-literal ("INSTANTIATE") node, else null (a plain scalar). */
    public String staticStructNameOf(Token rhs) {
        if (rhs.type == TokenType.OPERATOR && rhs.text.equals("INSTANTIATE")) {
            return rhs.left.text;
        }
        return null;
    }

    /** True when a static/global's canonical type is a range (after one optional storage and one mutability prefix, "range(" follows). */
    public boolean isRangeType(String resolvedType) {
        String t = resolvedType;
        for (String storage : new String[]{"raw_", "owns_", "ref_", "auto_", "static_"}) {
            if (t.startsWith(storage)) {
                t = t.substring(storage.length());
                break;
            }
        }
        for (String mutability : new String[]{"mut_", "imut_", "indeterminate_"}) {
            if (t.startsWith(mutability)) {
                t = t.substring(mutability.length());
                break;
            }
        }
        return t.startsWith("range(");
    }

    /** The two bounds of a range static: a literal "a..b" (each side folded) or a range-valued enum variant, whose bounds are embedded in its type text ("imut_range(0,10)"). */
    public long[] rangeBoundValues(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals("..")) {
            return new long[]{Long.parseLong(foldStaticConstant(node.left)),
                    Long.parseLong(foldStaticConstant(node.right))};
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && isQualifiedNameableDot(node)) {
            return parseEmbeddedRangeBounds(node.resolvedType, node);
        }
        throw new CompilerException("bytecode", node.file, node.line,
                "a 'static' range initializer must be a literal 'a..b' range or a reference to a "
                        + "range-valued enum variant, not '" + node.text + "'");
    }

    /** Hand-scans "...range(A,B)" out of a resolved range type string -- no regex, per this project's standing rule. */
    public long[] parseEmbeddedRangeBounds(String type, Token at) {
        int open = type.indexOf("range(");
        if (open < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "expected a literal-bound range type here, got '" + type + "'");
        }
        int start = open + "range(".length();
        int comma = type.indexOf(',', start);
        int close = comma < 0 ? -1 : type.indexOf(')', comma);
        if (comma < 0 || close < 0) {
            throw new CompilerException("bytecode", at.file, at.line,
                    "malformed range type string '" + type + "'");
        }
        long a = Long.parseLong(type.substring(start, comma));
        long b = Long.parseLong(type.substring(comma + 1, close));
        return new long[]{a, b};
    }

    /**
     * Folds a compile-time-constant expression to the bytecode text of one literal: literals pass through (literalValueOf), 'as'/mut/imut/static/atomic
     * wrappers are transparent, integer + - * / % and unary - are evaluated (the restricted shape 'dup' counts use, see DupExpander), a plain enum
     * variant folds to its dotted name, a global may reference an earlier global. Enum-variant defaults beyond that are not handled.
     */
    public String foldStaticConstant(Token node) {
        if (node.sizeofStruct != null && node.left != null && node.left.type == TokenType.VARREF
                && node.left.text.equals("sizeof")) {
            // "let static n = sizeof(Struct)": the initializer is a data line, so the Optimizer never resolves it; fold the laid-out size here and emit
            // STRUCT_PIN so StructMemberReorderingPass cannot change that size (the pass always strips the line).
            line("STRUCT_PIN " + node.sizeofStruct);
            return Long.toString(computeStructLayout(checker.getStructs().get(node.sizeofStruct)).size);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
            return Long.toString(node.builtinConstantValue);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return foldStaticConstant(node.childs.get(0));
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("as")) {
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary
                && (node.text.equals("mut") || node.text.equals("imut"))) {
            // The RHS is virtually always wrapped in mut/imut (a bare literal is indeterminate); the wrapper has no runtime representation.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("static")) {
            // 'static' on a string/char literal has no runtime representation either (emitAddressOf emits just the operand).
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("atomic")) {
            // 'atomic' is a pure type modifier; without this case the operator text "atomic" itself became the folded initial value of every atomic static/global.
            return foldStaticConstant(node.left);
        }
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && isQualifiedNameableDot(node)) {
            // Plain enum-variant reference ("let static c = Color.GREEN"; range-valued ones are routed by emitStaticAlloc's isRangeType check): folds to the
            // same dotted name an ordinary PUSH uses (qualifiedDotName).
            return qualifiedDotName(node);
        }
        if (node.type == TokenType.OPERATOR && !node.unary) {
            long left = Long.parseLong(foldStaticConstant(node.left));
            long right = Long.parseLong(foldStaticConstant(node.right));
            switch (node.text) {
                case "+": return Long.toString(left + right);
                case "-": return Long.toString(left - right);
                case "*": return Long.toString(left * right);
                case "/": return Long.toString(left / right);
                case "%": return Long.toString(left % right);
                default:
                    break;
            }
        }
        if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("-")) {
            String folded = foldStaticConstant(node.left);
            if (folded.contains(".") || folded.contains("e") || folded.contains("E")) {
                // Float constant (negative literal or array element): negate the text; parseLong would throw.
                return folded.startsWith("-") ? folded.substring(1) : "-" + folded;
            }
            return Long.toString(-Long.parseLong(folded));
        }
        if (node.type == TokenType.VARREF && checker.getGlobals().containsKey(node.text)) {
            // A global may reference an earlier global: fold through to its value.
            return foldStaticConstant(checker.getGlobalRhs().get(node.text));
        }
        return literalValueOf(node);
    }

    /** Returns the merged 'let' token if this statement is a `let` declaration, else null. */
    public Token letDeclarationOf(Token stmt) {
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=")
                && stmt.left.type == TokenType.KEYWORD && stmt.left.text.equals("let")) {
            return stmt.left;
        }
        return null;
    }

    // ---- whole-function ALLOC hoisting -----------------------------------

    /**
     * Walks the whole function body (every nested block, any depth) collecting one Runnable per ALLOC/ALLOC_STATIC, in program order, without
     * emitting. emitFuncUnderName runs the static ones, then the gt_routine_address slot, then the ordinary ones; the initializing ASSIGN stays at
     * the declaration's position. See docs/BytecodeEmitter.md, "ALLOC hoisting".
     */
    public void collectHoistedAllocs(List<Token> lines, List<Runnable> staticAllocs, List<Runnable> ordinaryAllocs) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token letTok = letDeclarationOf(stmt);
            if (letTok != null) {
                Token nameTok = letTok.childs.get(0);
                if (letTok.isStatic) {
                    Token assignNode = stmt;
                    staticAllocs.add(() -> emitStaticAlloc("ALLOC_STATIC", nameTok.text, letTok.resolvedType,
                            assignNode.right));
                } else {
                    ordinaryAllocs.add(() -> line("ALLOC " + nameTok.text + " " + letTok.resolvedType));
                }
                // A 'let' right-hand side can itself be a "try...catch(e){...}": hoist its catch parameter and body locals as well.
                Token tryNodeUnderLet = tryNodeOf(stmt);
                if (tryNodeUnderLet != null) {
                    collectTryHoistedAllocs(tryNodeUnderLet, staticAllocs, ordinaryAllocs);
                }
                continue;
            }
            // "let x = try..." (above), "x = try..." and a bare "try...catch(e){...}" were once never hoisted (the switch below only sees KEYWORD
            // statements); tryNodeOf recognises all three shapes, so checking it here closes the gap.
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collectTryHoistedAllocs(tryNode, staticAllocs, ordinaryAllocs);
                continue;
            }
            collectHoistedAllocsFromStatement(stmt, staticAllocs, ordinaryAllocs);
        }
    }

    /** Shared by both `collectHoistedAllocs` call sites that find a "try" node (under a 'let', or standalone): hoists the catch's own bound parameter (`ALLOC <catchParamName> static_imut_string`, initialized later by `emitHoistedCatchBlocks`'s own one-time snapshot write) plus, recursively, anything the catch body itself declares. */
    public void collectTryHoistedAllocs(Token tryNode, List<Runnable> staticAllocs, List<Runnable> ordinaryAllocs) {
        functionCatchParams.add(tryNode.catchParamName);
        ordinaryAllocs.add(() -> line("ALLOC " + tryNode.catchParamName + " static_imut_string"));
        if (tryNode.catchUsesStackTrace) {
            ordinaryAllocs.add(() -> line("ALLOC " + tryNode.catchParamName + "$trace mut_u64[" + (TraceConfig.depth + 1) + "]"));
        }
        collectHoistedAllocs(tryNode.childs, staticAllocs, ordinaryAllocs);
    }

    /** The non-'let' half of `collectHoistedAllocs` -- recurses into whichever nested block(s), if any, this statement carries, plus the two implicit shapes ('for's hidden range/loop variables, a 'loop's own preLoopInit) that declare a local without going through an ordinary 'let' line at all. */
    public void collectHoistedAllocsFromStatement(Token stmt, List<Runnable> staticAllocs,
            List<Runnable> ordinaryAllocs) {
        if (stmt.type != TokenType.KEYWORD) {
            return;
        }
        switch (stmt.text) {
            case "if":
            case "match":
                for (Token b = stmt; b != null; b = b.right) {
                    collectHoistedAllocs(b.childs, staticAllocs, ordinaryAllocs);
                }
                return;
            case "loop":
                if (stmt.preLoopInit != null) {
                    for (Token assign : stmt.preLoopInit) {
                        Token letTok = assign.left;
                        Token nameTok = letTok.childs.get(0);
                        ordinaryAllocs.add(() -> line("ALLOC " + nameTok.text + " " + letTok.resolvedType));
                    }
                }
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            case "for": {
                Token rangeAssign = stmt.sub.get(0);
                Token loopVarAssign = stmt.sub.get(1);
                ordinaryAllocs.add(() -> line(
                        "ALLOC " + rangeVarNameOf(rangeAssign) + " " + rangeAssign.left.resolvedType));
                ordinaryAllocs.add(() -> line("ALLOC " + loopVarAssign.left.childs.get(0).text + " "
                        + loopVarAssign.left.resolvedType));
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            }
            case "unsafe":
            case "safe":
            case "lock":
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            case "assume":
                if (stmt.hasBlock) {
                    collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                }
                return;
            case "try":
                // Only the plain "try { ... }" block form arrives here (a throwing try...catch is intercepted by tryNodeOf); it emits nothing of its own.
                collectHoistedAllocs(stmt.childs, staticAllocs, ordinaryAllocs);
                return;
            default:
                return;
        }
    }

    // ---- whole-function catch-block hoisting -------------------------------

    /**
     * Emits each real try/catch's catch body once, at the top of the function behind a JMP over them (a catch body is plain
     * code that must end in return/throw, so no resume label exists). Labels are assigned before any statement is emitted
     * so call sites can redirect unwinds into them. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public void emitHoistedCatchBlocks(TypeChecker.FuncInfo info) {
        List<Token> tryNodes = new ArrayList<>();
        collectTryCatchNodes(info.funcToken.childs, tryNodes);
        if (tryNodes.isEmpty()) {
            return;
        }
        for (Token tryNode : tryNodes) {
            tryCatchLabels.put(tryNode, newLabel("catch"));
        }
        String skipLabel = newLabel("end_of_catches__" + currentFuncMangledName);
        line("JMP " + skipLabel);
        for (Token tryNode : tryNodes) {
            line(tryCatchLabels.get(tryNode) + ":");
            // copy of this frame's gt_error_message into the catch parameter (an independent local, never an alias)
            line("ADDR " + tryNode.catchParamName + " static_imut_string");
            line("PUSH gt_error_message static_imut_string");
            line("ASSIGN static_imut_string static_imut_string static_imut_string");
            if (tryNode.catchUsesStackTrace) {
                // handler-local copy of the trace: the staging array belongs to the root and a later throw would overwrite it
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                line("ADDR " + tryNode.catchParamName + "$trace " + arr);
                line("PUSH gt_trace_ptr " + traceArrayPointerType());
                line("DEREF " + arr);
                line("ASSIGN " + arr + " " + arr + " " + arr);
            }
            emitBlock(tryNode.childs);
            // no trailing JMP/destruct list: the body ends in return/throw, which emit their own
        }
        line(skipLabel + ":");
    }

    /** Recurses into whichever nested block(s), if any, each statement in `lines` carries -- the identical traversal shape `collectHoistedAllocsFromStatement` already uses -- collecting every "try" token found, in program order. */
    public void collectTryCatchNodes(List<Token> lines, List<Token> collected) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collected.add(tryNode);
                // a catch body can itself contain a nested try
                collectTryCatchNodes(tryNode.childs, collected);
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    for (Token b = stmt; b != null; b = b.right) {
                        collectTryCatchNodes(b.childs, collected);
                    }
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    collectTryCatchNodes(stmt.childs, collected);
                    break;
                case "try":
                    // plain "try { }" block form only (the throwing form was taken by tryNodeOf); recurse so nested try/catch is found
                    collectTryCatchNodes(stmt.childs, collected);
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        collectTryCatchNodes(stmt.childs, collected);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Walks the function body once, giving every plain "try { }" block a label (tryBlockLabels) and resolving every 'continue' to
     * its nearest enclosing try-block label (continueTargetLabels) before any code is emitted: a 'continue' inside a hoisted
     * catch body is emitted before its try block is reached. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public void collectTryBlockLabels(List<Token> lines, Deque<Token> enclosingTryBlocks) {
        for (Token lineTok : lines) {
            Token stmt = lineTok.childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collectTryBlockLabels(tryNode.childs, enclosingTryBlocks);
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    for (Token b = stmt; b != null; b = b.right) {
                        collectTryBlockLabels(b.childs, enclosingTryBlocks);
                    }
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    break;
                case "try":
                    // plain "try { }" only; label assigned before recursing so a nested catch's 'continue' can resolve it
                    tryBlockLabels.put(stmt, newLabel("after_try_block"));
                    enclosingTryBlocks.push(stmt);
                    collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    enclosingTryBlocks.pop();
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        collectTryBlockLabels(stmt.childs, enclosingTryBlocks);
                    }
                    break;
                case "continue":
                    if (!enclosingTryBlocks.isEmpty()) {
                        continueTargetLabels.put(stmt, tryBlockLabels.get(enclosingTryBlocks.peek()));
                    }
                    // empty stack: already rejected by TypeChecker
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * The throwing "try" token a statement carries (bare statement, "let/x = [mut|imut] try ..", or "return [mut|imut] try ..");
     * null if none. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public Token tryNodeOf(Token stmt) {
        // a plain "try { }" block shares the KEYWORD text but is a different construct; excluded
        if (stmt.type == TokenType.KEYWORD && stmt.text.equals("try") && !stmt.isTryBlock) {
            return stmt;
        }
        if (stmt.type == TokenType.OPERATOR && stmt.text.equals("=") && stmt.right != null) {
            Token rhs = stmt.right;
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try") && !rhs.isTryBlock) {
                return rhs;
            }
        }
        // "return ?f(x)" / "return try f(x) catch(e){...}": the returned expression is the try (through any mut/imut wrapper).
        // Without this the catch was never hoisted or labelled, and the call was emitted as a plain one (the catch silently ignored).
        if (stmt.type == TokenType.KEYWORD && stmt.text.equals("return") && stmt.sub != null && stmt.sub.size() == 1) {
            Token rhs = stmt.sub.get(0);
            while (rhs.type == TokenType.OPERATOR && (rhs.text.equals("mut") || rhs.text.equals("imut"))
                    && rhs.left != null) {
                rhs = rhs.left;
            }
            if (rhs.type == TokenType.KEYWORD && rhs.text.equals("try") && !rhs.isTryBlock) {
                return rhs;
            }
        }
        return null;
    }

    // ---- statements -----------------------------------------------------

    public void emitStatement(Token stmt) {
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    emitIfChain(stmt);
                    return;
                case "loop":
                    emitLoop(stmt);
                    return;
                case "for":
                    emitForLoop(stmt);
                    return;
                case "match":
                    emitMatchChain(stmt);
                    return;
                case "unsafe":
                case "safe":
                    // compile-time safety markers only; emit the statements, no wrapper
                    emitBlock(stmt.childs);
                    emitDestructList(stmt.destructOnExit, stmt);
                    return;
                case "assume":
                    // no runtime presence: the block form emits its statements; the no-block form has no children (later siblings belong to the enclosing block)
                    if (stmt.hasBlock) {
                        emitBlock(stmt.childs);
                        emitDestructList(stmt.destructOnExit, stmt);
                    } else if (stmt.assumeFieldVariants == null && stmt.sub != null && !stmt.sub.isEmpty()) {
                        emitAssumeResolves(stmt.sub.get(0));   // a nullable ref proven by `assume match Some(..)`: resolve it once
                    }
                    return;
                case "return":
                    emitReturn(stmt);
                    return;
                case "throw":
                    emitThrow(stmt);
                    return;
                case "try":
                    // Two constructs share this KEYWORD (Token.isTryBlock). A plain "try { }" block emits its statements plus a label
                    // precomputed by collectTryBlockLabels (a hoisted catch's 'continue' jumps to it). Otherwise it is the bare
                    // "try call() catch(e){..}" statement; the call's result, if any, stays on the stack.
                    if (stmt.isTryBlock) {
                        emitBlock(stmt.childs);
                        emitDestructList(stmt.destructOnExit, stmt);
                        line(tryBlockLabels.get(stmt) + ":");
                        return;
                    }
                    emitTryGuardedCall(stmt);
                    return;
                case "break":
                    emitBreak(stmt);
                    return;
                case "continue":
                    emitContinue(stmt);
                    return;
                case "yield":
                    // bare mnemonic, no operands
                    line("YIELD");
                    return;
                case "ASM":
                    // named: emitted at each invocation site (see the "ASM" case in emit()); unnamed: only here
                    if (stmt.sub.isEmpty()) {
                        emitAsm(stmt.resolvedAsmContent);
                    }
                    return;
                default:
                    throw new IllegalStateException("unexpected statement keyword: " + stmt.text);
            }
        }
        if (stmt.type == TokenType.VARREF && stmt.resolvedAsmContent != null) {
            // "asm_name;" invocation (resolvedAsmContent is set only by TypeChecker, only for genuine ones)
            emitAsm(stmt.resolvedAsmContent);
            return;
        }
        // plain expression statement: the result, if any, is left on the stack (no POP)
        emitExpr(stmt);
    }

    public void emitIfChain(Token ifTok) {
        String endLabel = newLabel("end_of_if");
        List<Token> branches = new ArrayList<>();
        for (Token b = ifTok; b != null; b = b.right) {
            branches.add(b);
        }

        for (int i = 0; i < branches.size(); i++) {
            Token branch = branches.get(i);
            boolean isLast = (i == branches.size() - 1);
            String nextLabel = isLast ? endLabel : newLabel("if_branch");
            boolean hasCondition = !branch.sub.isEmpty();

            if (hasCondition) {
                Token cond = branch.sub.get(0);
                if (isThenVariant(cond)) {
                    // "&&then"/"||then" root: cascading jumps (see emitShortCircuit)
                    emitShortCircuit(cond, nextLabel, null);
                } else {
                    emitExpr(cond);
                    line("CMP");
                    line("JMP " + nextLabel);
                }
            }
            emitBlock(branch.childs);
            emitDestructList(branch.destructOnExit, branch);
            line("JMP " + endLabel);
            if (!isLast) {
                line(nextLabel + ":");
            }
        }
        line(endLabel + ":");
    }

    /** A match is an if-chain: one CMP/JMP per branch (conditions via emitMatchCondition), shared end label; a bare 'else' branch falls into its block. */
    public void emitMatchChain(Token matchTok) {
        String endLabel = newLabel("end_of_match");
        List<Token> branches = new ArrayList<>();
        for (Token b = matchTok; b != null; b = b.right) {
            branches.add(b);
        }

        for (int i = 0; i < branches.size(); i++) {
            Token branch = branches.get(i);
            boolean isLast = (i == branches.size() - 1);
            String nextLabel = isLast ? endLabel : newLabel("match_branch");
            boolean hasCondition = !branch.sub.isEmpty();

            if (hasCondition) {
                emitMatchCondition(branch.sub.get(0));
                line("CMP");
                line("JMP " + nextLabel);
            }
            emitBlock(branch.childs);
            emitDestructList(branch.destructOnExit, branch);
            line("JMP " + endLabel);
            if (!isLast) {
                line(nextLabel + ":");
            }
        }
        line(endLabel + ":");
    }

    /** Emits a match condition's bool result onto the stack -- ordinary operator emission for instanceof/implements/in, plus GT_ALIVE_CHECK for the bare form, recursing through "and" so every leaf (whichever kind) gets the right treatment before being combined. */
    public void emitMatchCondition(Token node) {
        if (node.type == TokenType.OPERATOR && node.text.equals("and")) {
            emitMatchCondition(node.left);
            emitMatchCondition(node.right);
            line("AND " + node.left.resolvedType + " " + node.right.resolvedType + " " + node.resolvedType);
            return;
        }
        if (node.someIndexElementExpr != null) {
            // "match Some(i) in/into x": one combined bool (bounds IN, then element alive) gating the single block.
            // node.left is the Some(..) call; only its index argument is pushed for the IN.
            Token indexExpr = singleBuiltinArg(node.left);
            emitExpr(indexExpr);
            emitExpr(node.right);
            line("IN " + indexExpr.resolvedType + " " + node.right.resolvedType + " " + node.resolvedType);
            if (inGtSuppressedContext) {
                throw new CompilerException("type", node.file, node.line,
                        "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                + "function is a ghost-table-decorated function, or is reachable "
                                + "from one, and ghost-table instructions can never be generated "
                                + "in that whole reachable set");
            }
            if (node.someIndexElementExpr.someOnRef) {
                emitSomeOnRef(node.someIndexElementExpr, node.someIndexElementExpr, node.someIndexElementExpr.someProofKey);
            } else {
                requireGhostTableFunctionPresent("gt_alive_check", node);
                emitExpr(node.someIndexElementExpr);
                line("GT_ALIVE_CHECK");
            }
            line("AND " + node.resolvedType + " indeterminate_bool " + node.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR
                && (node.text.equals("instanceof") || node.text.equals("implements") || node.text.equals("in")
                        || node.text.equals("within") || node.text.equals("into"))) {
            // "within" as a match condition emits exactly like "within"
            // within/into emit exactly like in as expressions; the proof is recorded separately by the type checker
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                && node.left.text.equals("Some")) {
            // emitExpr already emits GT_ALIVE_CHECK for Some(..); the generic fallback below would duplicate it
            emitExpr(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
            emitFits(node);
            return;
        }
        if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
            // "PUSH x / IS_BASE <leftType>": only the left side is pushed. The type is written out because for a composite
            // left operand (e.g. a range literal = two scalar pushes) the preceding line does not carry it.
            // See docs/BytecodeEmitter.md, "Operator emission".
            emitExpr(node.left);
            line("IS_BASE " + node.left.resolvedType);
            return;
        }
        if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                && node.left.type == TokenType.VARREF && node.right.type == TokenType.INTEGER
                && node.right.text.equals("0")) {
            // division-safety proof shape: an ordinary comparison, never a GT_ALIVE_CHECK
            emitExpr(node);
            return;
        }
        // unreachable: checkMatchCondition rejects every other shape
        throw new IllegalStateException(
                "internal error: unrecognized match condition shape reached bytecode emission: " + node.text);
    }

    public void noteLoopLabel(Token loopTok, String kind, String startLabel) {
        if (curHobFn != null && loopTok.file != null) {
            sourcePos.put(curHobFn + "|" + startLabel, shownPos(loopTok));
            termSites.add(new String[]{curHobFn, startLabel, loopTok.file, String.valueOf(loopTok.line), kind});
        }
        DocComments.Doc td = DocComments.find(loopTok.file, loopTok.line, "termination", kind);
        if (td != null && curHobFn != null) {
            loopTermLabels.put(curHobFn + "|" + startLabel, td);
        }
    }

    public void emitLoop(Token loopTok) {
        String startLabel = newLabel("loop");
        noteLoopLabel(loopTok, "loop", startLabel);
        if (loopTok.lockSpinBound != 0 && curHobFn != null) {
            spinBounds.put(curHobFn + "|" + startLabel, loopTok.lockSpinBound);
        }
        String endLabel = newLabel("loop_end");
        loopEndLabels.add(endLabel);
        loopContinueTargets.add(new String[] { startLabel });
        emitDecorators("LOOP_DECORATE", loopTok.decorators);
        emitPreLoopInit(loopTok.preLoopInit);
        line(startLabel + ":");
        if (loopTok.isLockSpinLoop) {
            lockRetryLabels.add(startLabel);
        }
        emitBlock(loopTok.childs);
        if (loopTok.isLockSpinLoop) {
            lockRetryLabels.remove(lockRetryLabels.size() - 1);
        }
        emitDestructList(loopTok.destructOnExit, loopTok);
        line("JMP " + startLabel);
        line(endLabel + ":");
        loopEndLabels.remove(loopEndLabels.size() - 1);
        loopContinueTargets.remove(loopContinueTargets.size() - 1);
    }

    /**
     * Pre-loop assigns of a lowered "match @lock" loop that uses a "CLOSED:default" policy ($lm_tries = 0 and the policy's
     * parameters), run once before the start label and not through emitBlock. Their ALLOCs are hoisted by collectHoistedAllocs.
     */
    public void emitPreLoopInit(List<Token> preLoopInit) {
        if (preLoopInit == null) {
            return;
        }
        for (Token assign : preLoopInit) {
            emitAssign(assign);
        }
    }

    /**
     * Emits a 'for' that TypeChecker.checkForLoop desugared into four nodes (range let, loop-variable let, 'in' condition, '++')
     * through the ordinary emission paths, plus the loop control flow (check at top, continue label before the step, jump back).
     * The hidden variables' ALLOCs are hoisted by collectHoistedAllocs.
     */
    public void emitForLoop(Token forTok) {
        Token rangeAssign = forTok.sub.get(0);
        Token loopVarAssign = forTok.sub.get(1);
        Token conditionIn = forTok.sub.get(2);
        Token increment = forTok.sub.get(3);

        emitAssign(rangeAssign);
        emitAssign(loopVarAssign);

        String startLabel = newLabel("for");
        noteLoopLabel(forTok, "for", startLabel);
        String endLabel = newLabel("for_end");
        loopEndLabels.add(endLabel);
        String[] continueTarget = new String[] { null };
        loopContinueTargets.add(continueTarget);
        emitDecorators("FOR_DECORATE", forTok.decorators, true);
        line(startLabel + ":");
        emitExpr(conditionIn);
        line("CMP");
        line("JMP " + endLabel);
        emitBlock(forTok.childs);
        emitDestructList(forTok.destructOnExit, forTok);
        if (continueTarget[0] != null) {
            line(continueTarget[0] + ":");
        }
        emitExpr(increment);
        line("JMP " + startLabel);
        line(endLabel + ":");
        loopEndLabels.remove(loopEndLabels.size() - 1);
        loopContinueTargets.remove(loopContinueTargets.size() - 1);
    }

    public String rangeVarNameOf(Token rangeAssign) {
        return rangeAssign.left.childs.get(0).text;
    }

    /** 'break': destruct/unlock lists, then a jump to the enclosing loop's end label. An empty stack means TypeChecker failed. */
    public void emitBreak(Token breakTok) {
        if (loopEndLabels.isEmpty()) {
            throw new IllegalStateException(
                    "internal error: 'break' reached bytecode gen outside any loop "
                            + "(should have been caught by TypeChecker)");
        }
        emitDestructList(breakTok.destructOnExit, breakTok);
        emitUnlockList(breakTok.unlockOnExit, breakTok);
        line("JMP " + loopEndLabels.get(loopEndLabels.size() - 1));
    }

    /**
     * 'continue' in three forms: a user for/loop (jump to the step/start), the CLOSED case of a 'match @lock' (retry), or a
     * catch-body continue (jump to the label from continueTargetLabels, precomputed because the catch body is hoisted).
     * See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public void emitContinue(Token continueTok) {
        if (continueTok.isLoopContinue) {
            // 'continue' in a user 'for'/'loop': unwind like 'break', then jump to the loop's step / start.
            if (loopContinueTargets.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: loop 'continue' reached bytecode gen outside any loop");
            }
            String[] target = loopContinueTargets.get(loopContinueTargets.size() - 1);
            if (target[0] == null) {
                target[0] = newLabel("for_cont");
            }
            emitDestructList(continueTok.destructOnExit, continueTok);
            emitUnlockList(continueTok.unlockOnExit, continueTok);
            line("JMP " + target[0]);
            return;
        }
        if (continueTok.isLockRetryContinue) {
            // CLOSED case of a 'match @lock': retry at the spin loop's start (after the pre-loop init, so counters are kept); no lock is held
            if (lockRetryLabels.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: lock-retry 'continue' reached bytecode gen outside a 'match @lock' loop");
            }
            emitDestructList(continueTok.destructOnExit, continueTok);
            emitUnlockList(continueTok.unlockOnExit, continueTok);
            line("JMP " + lockRetryLabels.get(lockRetryLabels.size() - 1));
            return;
        }
        String targetLabel = continueTargetLabels.get(continueTok);
        if (targetLabel == null) {
            throw new IllegalStateException(
                    "internal error: 'continue' reached bytecode gen with no enclosing 'try { ... }' "
                            + "block (should have been caught by TypeChecker)");
        }
        emitDestructList(continueTok.destructOnExit, continueTok);
        emitUnlockList(continueTok.unlockOnExit, continueTok);
        line("JMP " + targetLabel);
    }

    public void emitReturn(Token returnTok) {
        if (returnTok.sub.isEmpty()) {
            emitDestructList(returnTok.destructOnExit, returnTok);
            emitUnlockList(returnTok.unlockOnExit, returnTok);
            line("RET " + returnTok.resolvedType);
            return;
        }
        Token expr = returnTok.sub.get(0);
        if (currentFuncInfo != null && isStructByValueReturn(currentFuncInfo.returnType)) {
            emitStructRvoReturn(expr, returnTok);
            return;
        }
        emitExpr(expr);
        // the value is evaluated before destructing; the returned value is never in the destruct list
        emitDestructList(returnTok.destructOnExit, returnTok);
        emitUnlockList(returnTok.unlockOnExit, returnTok);
        line("RET " + returnTok.resolvedType);
    }

    /** Callee half of struct RVO: writes the returned struct through "$ret_dest" and returns that pointer. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". */
    public void emitStructRvoReturn(Token expr, Token returnTok) {
        String structName = currentFuncInfo.returnType.baseType;
        String ptrType = "raw_mut_" + structName;
        String structType = "mut_" + structName;
        Token unwrapped = unwrapMutWrappers(expr);
        if (unwrapped.isStructRvoCall) {
            // "return foo()": forward $ret_dest (emitStructRvoForwardCall)
            emitStructRvoForwardCall(unwrapped, ptrType);
        } else {
            line("PUSH $ret_dest " + ptrType);
            emitExpr(expr);
            line("ASSIGN " + structType + " " + expr.resolvedType + " " + structType);
        }
        emitDestructList(returnTok.destructOnExit, returnTok);
        emitUnlockList(returnTok.unlockOnExit, returnTok);
        line("PUSH $ret_dest " + ptrType);
        line("RET " + ptrType);
    }

    /**
     * THROW marker line, then the write of the operand into this frame's gt_error_message slot, the per-throw-site destruct list
     * and the unwind/exit. See docs/BytecodeEmitter.md, "Throw".
     */
    public void emitThrow(Token throwTok) {
        Token expr = throwTok.sub.get(0);
        String operand = expr.type == TokenType.VARREF ? expr.text : literalValueOf(expr);
        line("THROW " + operand);
        if (inGtSuppressedContext) {
            // gt-suppressed function: no ghost-table instructions and no gt_routine slot exist here (checkThrow bans throw in the ghost-table functions)
            return;
        }
        // operand is a string literal id or a variable name (a catch parameter is re-propagated unchanged)
        if (!(expr.type == TokenType.VARREF && functionCatchParams.contains(expr.text))) {
            emitTraceCapture();   // a fresh throw starts a new trace; `throw e` of a catch parameter keeps the one already captured
        }
        line("ADDR gt_error_message static_imut_string");
        line("PUSH " + operand + " " + expr.resolvedType);
        line("ASSIGN static_imut_string " + expr.resolvedType + " static_imut_string");
        // per-throw-site destruct list; currentFuncInfo/currentFuncMangledName are the emitted (post-dundering) identity
        List<String> destructNames = throwTok.destructOnExit != null ? throwTok.destructOnExit
                : Collections.emptyList();
        if (!destructNames.isEmpty()) {
            requireGhostTableFunctionPresent("gt_destruct", throwTok);
        }
        for (String name : destructNames) {
            line("GT_DESTRUCT " + name);
        }
        emitUnlockList(throwTok.unlockOnExit, throwTok);
        if (currentFuncMangledName.equals("main")) {
            line("EXIT");
        } else if (currentFuncInfo != null && currentFuncInfo.isAsync) {
            line("EXIT_THREAD");
        } else {
            line(gtUnwindLine());
        }
    }

    public String newLabel(String prefix) {
        labelCounter++;
        return "@" + prefix + "_" + labelCounter;
    }

    // ---- expressions ------------------------------------------------------

    /**
     * Nullable `ref` values are 64-bit ids, `ref some` values and proven bindings are addresses (see Token.refToId). This wrapper
     * applies the three marks the TypeChecker left on an expression: a nullable `ref` that is the subject of an alive proof is read from
     * the proof's hidden address slot; an address stored where a nullable `ref` is expected becomes an id (GT_REF_ID); a nullable `ref`
     * handed to a `raw` slot is resolved to its current address (GT_REF_RESOLVE, null when dead).
     */
    public void emitExpr(Token node) {
        if (node.refProofIsId && node.refProofKey != null) {
            String slot = refAddrSlots.get(node.refProofKey);
            if (slot != null) {
                line("PUSH " + slot + " " + node.resolvedType);
                return;
            }
            // proven by `assume match` (nothing ran, no address slot): resolve the id here
            emitExprCore(node);
            requireRefHook("gt_ref_resolve", node);
            line("GT_REF_RESOLVE " + node.resolvedType);   // like every instruction, it names the type it leaves (a following PUSH_FIELDNAME reads the struct from it)
            return;
        }
        emitExprCore(node);
        if (node.refToId) {
            requireRefHook("gt_ref_id", node);
            line("GT_REF_ID");
        } else if (node.refToRaw) {
            requireRefHook("gt_ref_resolve", node);
            line("GT_REF_RESOLVE " + node.resolvedType);
        }
    }

    public void requireRefHook(String decorator, Token at) {
        if (inGtSuppressedContext) {
            throw new CompilerException("type", at.file, at.line,
                    "a nullable 'ref' (which needs '@" + decorator + "') can't be used here -- this function is a ghost-table-decorated "
                            + "function, or is reachable from one, and ghost-table instructions can never be generated in that whole reachable set");
        }
        requireGhostTableFunctionPresent(decorator, at);
    }

    /**
     * `Some(x)` on a nullable `ref` (an id): resolve it into the proof's hidden address slot (one slot per proof key and function), then
     * test that address against null. The match body reads the slot instead of the id (emitExpr, Token.refProofKey).
     */
    public void emitSomeOnRef(Token someCall, Token subject, String proofKey) {
        emitSomeOnRef(someCall, subject, proofKey, true);
    }

    /**
     * `assume match Some(r)` / `assume match <index> in v and Some(v[i].r)` on a nullable `ref`: the same one-time resolve into the proof's
     * address slot as a real `match Some`, but no null test and no branch (the programmer vouches), so the uses after it read the slot
     * instead of resolving the id again at every use.
     */
    public void emitAssumeResolves(Token c) {
        if (c == null) {
            return;
        }
        if (c.someOnRef && c.someProofKey != null) {
            emitSomeOnRef(c, singleBuiltinArg(c), c.someProofKey, false);
        } else if (c.someIndexElementExpr != null && c.someIndexElementExpr.someOnRef) {
            emitSomeOnRef(c.someIndexElementExpr, c.someIndexElementExpr, c.someIndexElementExpr.someProofKey, false);
        }
        emitAssumeResolves(c.left);
        emitAssumeResolves(c.right);
        if (c.sub != null) {
            for (Token t : c.sub) {
                emitAssumeResolves(t);
            }
        }
    }

    public void emitSomeOnRef(Token someCall, Token subject, String proofKey, boolean test) {
        requireRefHook("gt_ref_resolve", someCall);
        String type = subject.resolvedType;
        String slot = proofKey == null ? null : refAddrSlots.get(proofKey);
        if (slot == null) {
            slot = declareHiddenLocal(type);
            if (proofKey != null) {
                refAddrSlots.put(proofKey, slot);
            }
        }
        line("ADDR " + slot + " " + type);
        emitExpr(subject);
        line("GT_REF_RESOLVE " + type);
        line("ASSIGN " + type + " " + type + " " + type);
        if (test) {
            line("PUSH " + slot + " " + type);
            line("PUSH null null");
            line("NEQ " + type + " null indeterminate_bool");
        }
    }

    public void emitExprCore(Token node) {
        switch (node.type) {
            case STRING:
            case CHAR:
            case INTEGER:
            case FLOAT:
            case BOOL:
            case NULL:
                line("PUSH " + literalValueOf(node) + " " + node.resolvedType);
                return;
            case VARREF:
                line((isAtomicCanonical(node.resolvedType) ? "ATOMIC_PUSH " : "PUSH ") + node.text + " "
                        + node.resolvedType);
                return;
            case KEYWORD:
                if (node.text.equals("let")) {
                    Token nameTok = node.childs.get(0);
                    line("PUSH " + nameTok.text + " " + node.resolvedType);
                    return;
                }
                if (node.text.equals("try")) {
                    // expression-position "let x = try .. catch .."; emitAssign already emitted ADDR x
                    emitTryGuardedCall(node);
                    return;
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    // transparent grouping parens
                    if (!node.childs.isEmpty()) {
                        emitExpr(node.childs.get(0));
                    }
                    return;
                }
                if (node.text.equals("[")) {
                    // array literal: each element pushed in written order
                    List<Token> elements = new ArrayList<>();
                    collectCommaArgs(node.childs.get(0), elements);
                    for (Token element : elements) {
                        if (dynLiteralTempNames != null && isOwnsTemporary(element)) {
                            emitOwnsTemporaryViaLocal(element, dynLiteralTempNames);
                        } else {
                            emitExpr(element);
                        }
                    }
                    return;
                }
                break;
            case OPERATOR:
                emitOperator(node);
                return;
            default:
                break;
        }
        throw new IllegalStateException("don't know how to emit " + node.type + "(" + node.text + ")");
    }

    public void emitOperator(Token op) {
        switch (op.text) {
            case "=":
                emitAssign(op);
                return;
            case "+=": case "-=": case "*=": case "/=": case "%=":
                emitCompoundAssign(op);
                return;
            case "+": case "-": case "*": case "/": case "%":
                if (op.unary) {
                    emitUnaryMinus(op);
                } else {
                    emitBinaryArith(op);
                }
                return;
            case "!":
                emitLogicalNot(op);
                return;
            case "++":
                emitIncrement(op);
                return;
            case "--":
                emitDecrement(op);
                return;
            case "&&":
                emitLogical(op, "AND");
                return;
            case "||":
                emitLogical(op, "OR");
                return;
            case "<": case ">": case "<=": case ">=": case "==": case "!=":
                emitComparison(op);
                return;
            case "in": case "into":
                // dedicated IN opcode; "into" emits identically (the difference is only a type-checker proof kind)
                if (op.isGuaranteedEnumMembershipCheck) {
                    // right side is a bare enum type name, pushed by name; the backend derives the check from the ENUM line
                    emitExpr(op.left);
                    line("PUSH " + op.right.resolvedType);
                    line("IN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                    return;
                }
                if (op.isStringMembershipCheck) {
                    // a string has no stored length: runtime byte scan under its own mnemonic IN_SCAN
                    emitExpr(op.left);
                    emitExpr(op.right);
                    line("IN_SCAN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                    return;
                }
                emitExpr(op.left);
                emitExpr(op.right);
                line("IN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                return;
            case "within":
                // same shape as "in"; always WITHIN (pointerness is handled by MembershipLoweringPass from the operand types)
                emitExpr(op.left);
                emitExpr(op.right);
                line("WITHIN " + op.left.resolvedType + " " + op.right.resolvedType + " " + op.resolvedType);
                return;
            case "CALL":
                emitCallOrBuiltin(op);
                return;
            case "LOOKUP":
                emitLookup(op);
                return;
            case "as":
                emitAs(op);
                return;
            case ".":
                emitDot(op);
                return;
            case "INSTANTIATE":
                emitInstantiate(op);
                return;
            case "owns": case "ref": case "auto": case "raw": case "static":
                emitAddressOf(op);
                return;
            case "mut": case "imut":
                // compile-time mutability retag: nothing to emit
                emitExpr(op.left);
                return;
            case "new":
                emitNew(op);
                return;
            case "await":
                // no bytecode of its own; the wrapped CALL emits AWAIT_CALL
                emitExpr(op.left);
                return;
            case "par":
                // no bytecode of its own; the wrapped CALL emits PAR_CALL (its resolvedType was overwritten by checkPar)
                emitExpr(op.left);
                return;
            case "unsafe":
                // no bytecode of its own ("unsafe dyn(..)"); the wrapped call carries the corrected resolvedType
                emitExpr(op.left);
                return;
            case "atomic":
                // no bytecode of its own; the wrapped expression's resolvedType carries "atomic"
                emitExpr(op.left);
                return;
            case "swap":
                emitSwap(op);
                return;
            case "..":
                // a range is its two u64 bounds pushed back to back
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case ",":
                // no COMMA instruction, per spec -- just compile both sides
                emitExpr(op.left);
                emitExpr(op.right);
                return;
            case "instanceof": case "implements":
                // right operand is a bare type/interface name pushed by name. The left operand's type is a trailing operand
                // because a bare struct literal on the left leaves only its last member's push before this line.
                emitExpr(op.left);
                line("PUSH " + op.right.resolvedType);
                line(op.text.toUpperCase() + " " + op.left.resolvedType);
                return;
            case "and":
                // same bytecode as "&&"; the extra meaning is type-checker only
                emitLogical(op, "AND");
                return;
            case "FLOAT_CHECK":
                // compiler-internal operator (TypeChecker.lowerFloatMatchToIfChain); op.right.text holds the "finite|nan" chain string
                emitExpr(op.left);
                // an f64 operand carries its width (the f32 form is unchanged: "FLOAT_CHECK cats")
                line("FLOAT_CHECK " + op.right.text + (op.left.resolvedType != null && (op.left.resolvedType.equals("f64") || op.left.resolvedType.endsWith("_f64")) ? " 8" : ""));
                return;
            default:
                throw new IllegalStateException("unhandled operator in bytecode gen: '" + op.text + "'");
        }
    }

    /**
    /**
     * Pushes the base of an assignment target's '.'/LOOKUP chain: a no-storage bare variable gets ADDR instead of PUSH, a
     * no-storage '.'/LOOKUP root recurses into emitAssignTarget (so "grid[1][1] = x" computes nested addresses), and a
     * pointer-typed root or anything else goes through emitExpr. See docs/BytecodeEmitter.md, "Assignment targets".
     */
    public void emitDotLhsRoot(Token root) {
        if (root.type == TokenType.VARREF && !hasStorage(root.resolvedType)) {
            line("ADDR " + root.text + " " + root.resolvedType);
            return;
        }
        if (root.type == TokenType.OPERATOR && (root.text.equals(".") || root.text.equals("LOOKUP"))
                && !hasStorage(root.resolvedType)) {
            emitAssignTarget(root);
            return;
        }
        emitExpr(root);
    }

    public void emitAssignTarget(Token target) {
        if (target.type == TokenType.OPERATOR && target.text.equals(".")) {
            emitDotLhsRoot(target.left);
            line("PUSH_FIELDNAME " + target.right.text + " " + target.resolvedType);
            line("DOT_LHS " + target.left.resolvedType + " " + target.resolvedType + " " + target.resolvedType);
            return;
        }
        if (target.type == TokenType.OPERATOR && target.text.equals("LOOKUP")) {
            emitDotLhsRoot(target.left);
            Token indexExpr = target.right.childs.get(0);
            emitExpr(indexExpr);
            line("LOOKUP_LHS " + target.left.resolvedType + " " + indexExpr.resolvedType + " "
                    + target.resolvedType);
            return;
        }
        if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
            // fresh 'let': the name is the wrapper's first child
            Token nameTok = target.childs.get(0);
            line("ADDR " + nameTok.text + " " + target.resolvedType);
            return;
        }
        line("ADDR " + target.text + " " + target.resolvedType);
    }

    /** A synthesized `base.member` access, enough for `emitAssignTarget`/`DOT_LHS`. */
    public Token syntheticMember(Token base, String member, TypeChecker.TypeInfo memberType) {
        Token dot = new Token(TokenType.OPERATOR, ".", base.line, base.file);
        Token name = new Token(TokenType.VARREF, member, base.line, base.file);
        name.resolvedType = memberType.canonical();
        dot.left = base;
        dot.right = name;
        dot.resolvedType = memberType.canonical();
        return dot;
    }

    /** A synthesized `base[i]` with a literal index, enough for `emitAssignTarget`/`LOOKUP_LHS`. */
    public Token syntheticElement(Token base, int index, String elemCanonical) {
        Token look = new Token(TokenType.OPERATOR, "LOOKUP", base.line, base.file);
        Token idx = new Token(TokenType.INTEGER, Integer.toString(index), base.line, base.file);
        idx.resolvedType = "imut_u64";
        Token holder = new Token(TokenType.DELINEATOR, "[", base.line, base.file);
        holder.childs.add(idx);
        look.left = base;
        look.right = holder;
        look.resolvedType = elemCanonical;
        return look;
    }

    /** Nulls every owned member of the inline struct at `base` (recursing into inline owning members): what a move out of it, or its own drop, leaves behind. */
    public void emitInlineOwnsNullOut(Token base, String structName) {
        if (checker.isFixedArrayTypeText(structName)) {
            // A fixed array of inline owning structs: null every element's owned leaves (unrolled, the length is fixed).
            String elemName = structName.substring(0, structName.lastIndexOf('['));
            int count = Integer.parseInt(structName.substring(structName.lastIndexOf('[') + 1, structName.length() - 1));
            String elemCanon = base.resolvedType.substring(0, base.resolvedType.lastIndexOf(structName)) + elemName;
            for (int i = 0; i < count; i++) {
                emitInlineOwnsNullOut(syntheticElement(base, i, elemCanon), elemName);
            }
            return;
        }
        TypeChecker.StructInfo si = checker.getStructs().get(structName);
        for (Map.Entry<String, TypeChecker.TypeInfo> m : si.members.entrySet()) {
            if (m.getKey().equals("___type")) {
                continue;
            }
            TypeChecker.TypeInfo mt = m.getValue();
            if ("owns".equals(mt.storage)) {
                Token target = syntheticMember(base, m.getKey(), mt);
                emitAssignTarget(target);
                line("PUSH null " + mt.canonical());
                line("ASSIGN " + mt.canonical() + " " + mt.canonical() + " " + mt.canonical());
            } else if (checker.isInlineOwningStruct(mt)) {
                emitInlineOwnsNullOut(syntheticMember(base, m.getKey(), mt), mt.baseType);
            }
        }
    }

    /**
     * Nulls a moved-from owner right after the move (synthesized "x = null", only where isOwnershipMoveSource is set). For a call
     * argument it sits between "POP ARGn" and the CALL, so a backend must keep its scratch registers off pending ARG registers.
     * See docs/BytecodeEmitter.md, "Moves and owning assignment".
     */
    public void emitOwnershipMoveNullOut(Token valueExpr) {
        if (valueExpr.inlineOwnsStruct != null) {
            emitInlineOwnsNullOut(unwrapMutWrappers(valueExpr), valueExpr.inlineOwnsStruct);
            return;
        }
        Token source = unwrapMutWrappers(valueExpr); // `a = mut b`: the variable underneath the wrapper is what gets nulled
        emitAssignTarget(source);
        line("PUSH null " + source.resolvedType);
        line("ASSIGN " + source.resolvedType + " " + source.resolvedType + " " + source.resolvedType);
    }

    public boolean isFlatOwnsDestructTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.VARREF
                || (target.type == TokenType.OPERATOR && target.text.equals(".") && isQualifiedNameableDot(target));
    }

    /** An `owns` assignment target with no flat name: a field reached through a pointer, or an indexed element. */
    public boolean isPointerCrossingOwnsTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.resolvedType == null
                || !(target.resolvedType.startsWith("owns") || target.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.OPERATOR && (target.text.equals("LOOKUP")
                || (target.text.equals(".") && !isQualifiedNameableDot(target)));
    }
}
