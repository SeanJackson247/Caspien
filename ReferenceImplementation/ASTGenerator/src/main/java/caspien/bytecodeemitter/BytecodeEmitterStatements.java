package caspien.bytecodeemitter;
import caspien.config.CompilerException;
import caspien.config.DocComments;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.config.TraceConfig;
import caspien.typechecker.TypeCheckerModel;

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
import caspien.typechecker.FuncInfo;
import caspien.typechecker.StructInfo;
import caspien.typechecker.TypeInfo;
import caspien.config.DocComment;

/**
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterStatements: static allocs, hoisting, statements, loops, expression dispatch (part 2 of 4; see README.md in this folder). */
final class BytecodeEmitterStatements {
    private final BytecodeEmitter hub;

    BytecodeEmitterStatements(BytecodeEmitter hub) {
        this.hub = hub;
    }

    /**
     * A compile-time-constant `let static` / global: the folded value is part of the allocation line. Scalar: "MNEMONIC name type value". Struct: a
     * container line "MNEMONIC name imut_Struct" then one "MNEMONIC name.member type value" per member (nested: "name.outer.inner"). Array:
     * container then "name.0", "name.1"... Range: container, "name.start", "name.end". MNEMONIC is ALLOC_STATIC (local) or GLOBAL.
     */
    public void emitStaticAlloc(String mnemonic, String name, String resolvedType, Token rhs) {
        // Explicit stack of pending pieces (a nested member/element is a piece of its own, a data line is a ready line), in output order.
        Deque<StaticAllocPiece> work = new ArrayDeque<>();
        work.push(new StaticAllocPiece(null, name, resolvedType, rhs));
        while (!work.isEmpty()) {
            StaticAllocPiece piece = work.pop();
            if (piece.line != null) {
                hub.core.line(piece.line);
            } else {
                expandStaticAlloc(mnemonic, piece, work);
            }
        }
    }



    private void expandStaticAlloc(String mnemonic, StaticAllocPiece piece, Deque<StaticAllocPiece> work) {
        String name = piece.name;
        String resolvedType = piece.type;
        Token rhs = piece.rhs;
        Token unwrapped = unwrapStaticExpr(rhs);
        String structName = staticStructNameOf(unwrapped);
        if (structName != null) {
            hub.core.line(mnemonic + " " + name + " imut_" + structName);
            StructInfo structInfo = hub.core.checker.declarations.getStructs().get(structName);
            // Hidden ___type (classId) first, as emitInstantiate pushes it; absent for @untyped. Without it a classId-bearing static was left uninitialised.
            Map<String, Token> valueByMember = new HashMap<>();
            for (Token lineTok : unwrapped.right.childs) {
                Token assignNode = lineTok.childs.get(0);
                valueByMember.put(assignNode.left.text, assignNode.right);
            }
            // Walk the real layout (___type, members, padding): the backend places each "name.member" line at the running sum of the sizes before it,
            // so padding gaps must be lines too ("name.$padN").
            List<StaticAllocPiece> pieces = new ArrayList<>();
            int padCount = 0;
            for (StructLayoutEntry entry : hub.core.computeStructLayout(structInfo).entries) {
                if (entry.paddingBytes > 0) {
                    pieces.add(new StaticAllocPiece(mnemonic + " " + name + ".$pad" + (padCount++) + " imut_u8[" + entry.paddingBytes + "]", null, null, null));
                } else if (entry.memberName.equals("___type")) {
                    pieces.add(new StaticAllocPiece(mnemonic + " " + name + ".___type imut_u64 " + structInfo.classId, null, null, null));
                } else {
                    pieces.add(new StaticAllocPiece(null, name + "." + entry.memberName, entry.canonicalType, valueByMember.get(entry.memberName)));
                }
            }
            for (int i = pieces.size() - 1; i >= 0; i--) {
                work.push(pieces.get(i));
            }
            return;
        }
        List<Token> arrayElements = staticArrayElements(unwrapped);
        if (arrayElements != null) {
            // Fixed-array literal: a no-value container line, then "name.0", "name.1", ... per element.
            hub.core.line(mnemonic + " " + name + " " + resolvedType);
            String elemType = staticArrayElementType(resolvedType, unwrapped);
            for (int i = arrayElements.size() - 1; i >= 0; i--) {
                work.push(new StaticAllocPiece(null, name + "." + i, elemType, arrayElements.get(i)));
            }
            return;
        }
        if (isRangeType(resolvedType)) {
            // Range: a no-value container line, then "name.start" and "name.end".
            long[] bounds = rangeBoundValues(unwrapped);
            hub.core.line(mnemonic + " " + name + " " + resolvedType);
            hub.core.line(mnemonic + " " + name + ".start imut_u64 " + bounds[0]);
            hub.core.line(mnemonic + " " + name + ".end imut_u64 " + bounds[1]);
            return;
        }
        hub.core.line(mnemonic + " " + name + " " + resolvedType + " " + foldStaticConstant(rhs));
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
        hub.operations.collectCommaArgs(rhs.childs.get(0), elements);
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
        if (node.type == TokenType.OPERATOR && node.text.equals(".") && hub.isQualifiedNameableDot(node)) {
            return parseEmbeddedRangeBounds(node.chk.resolvedType, node);
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
    public String foldStaticConstant(Token start) {
        // Explicit stack: a wrapper replaces its node in place (tail), a binary / unary-minus node waits for its operand results.
        Deque<FoldStep> stack = new ArrayDeque<>();
        stack.push(new FoldStep(start));
        String ret = null;
        while (!stack.isEmpty()) {
            FoldStep f = stack.peek();
            Token node = f.node;
            if (f.state == 1) {
                f.left = Long.parseLong(ret);
                f.state = 2;
                stack.push(new FoldStep(node.right));
                continue;
            }
            if (f.state == 2) {
                long left = f.left;
                long right = Long.parseLong(ret);
                stack.pop();
                switch (node.text) {
                    case "+": ret = Long.toString(left + right); break;
                    case "-": ret = Long.toString(left - right); break;
                    case "*": ret = Long.toString(left * right); break;
                    case "/": ret = Long.toString(left / right); break;
                    case "%": ret = Long.toString(left % right); break;
                    default: ret = hub.literalValueOf(node); break;
                }
                continue;
            }
            if (f.state == 3) {
                stack.pop();
                String folded = ret;
                if (folded.contains(".") || folded.contains("e") || folded.contains("E")) {
                    // Float constant (negative literal or array element): negate the text; parseLong would throw.
                    ret = folded.startsWith("-") ? folded.substring(1) : "-" + folded;
                } else {
                    ret = Long.toString(-Long.parseLong(folded));
                }
                continue;
            }
            if (node.chk.sizeofStruct != null && node.left != null && node.left.type == TokenType.VARREF
                    && node.left.text.equals("sizeof")) {
                // "let static n = sizeof(Struct)": the initializer is a data line, so the Optimizer never resolves it; fold the laid-out size here and emit
                // STRUCT_PIN so StructMemberReorderingPass cannot change that size (the pass always strips the line).
                hub.core.line("STRUCT_PIN " + node.chk.sizeofStruct);
                ret = Long.toString(hub.core.computeStructLayout(hub.core.checker.declarations.getStructs().get(node.chk.sizeofStruct)).size);
                stack.pop();
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left != null
                    && node.left.type == TokenType.VARREF && node.left.text.equals("sizeof")) {
                ret = Long.toString(node.chk.builtinConstantValue);
                stack.pop();
                continue;
            }
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                f.node = node.childs.get(0);
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals("as")) {
                f.node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary
                    && (node.text.equals("mut") || node.text.equals("imut"))) {
                // The RHS is virtually always wrapped in mut/imut (a bare literal is indeterminate); the wrapper has no runtime representation.
                f.node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("static")) {
                // 'static' on a string/char literal has no runtime representation either (emitAddressOf emits just the operand).
                f.node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("atomic")) {
                // 'atomic' is a pure type modifier; without this case the operator text "atomic" itself became the folded initial value of every atomic static/global.
                f.node = node.left;
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.text.equals(".") && hub.isQualifiedNameableDot(node)) {
                // Plain enum-variant reference ("let static c = Color.GREEN"; range-valued ones are routed by emitStaticAlloc's isRangeType check): folds to the
                // same dotted name an ordinary PUSH uses (qualifiedDotName).
                ret = hub.qualifiedDotName(node);
                stack.pop();
                continue;
            }
            if (node.type == TokenType.OPERATOR && !node.unary) {
                f.state = 1;
                stack.push(new FoldStep(node.left));
                continue;
            }
            if (node.type == TokenType.OPERATOR && node.unary && node.text.equals("-")) {
                f.state = 3;
                stack.push(new FoldStep(node.left));
                continue;
            }
            if (node.type == TokenType.VARREF && hub.core.checker.declarations.getGlobals().containsKey(node.text)) {
                // A global may reference an earlier global: fold through to its value.
                f.node = hub.core.checker.declarations.getGlobalRhs().get(node.text);
                continue;
            }
            ret = hub.literalValueOf(node);
            stack.pop();
        }
        return ret;
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



    /** Pushes the statement lists of an `if`/`match` chain so the first branch is walked first. */
    private static void pushBranchBodies(Deque<LineCursor> work, Token chainHead) {
        List<Token> branches = new ArrayList<>();
        for (Token b = chainHead; b != null; b = b.next) {
            branches.add(b);
        }
        for (int i = branches.size() - 1; i >= 0; i--) {
            work.push(new LineCursor(branches.get(i).childs, false));
        }
    }

    /**
     * Walks the whole function body (every nested block, any depth) collecting one HoistedAlloc per ALLOC/ALLOC_STATIC, in program order, without
     * emitting. emitFuncUnderName emits the static ones, then the gt_routine_address slot, then the ordinary ones; the initializing ASSIGN stays at
     * the declaration's position. See docs/BytecodeEmitter.md, "ALLOC hoisting".
     */
    public void collectHoistedAllocs(List<Token> lines, List<HoistedAlloc> staticAllocs, List<HoistedAlloc> ordinaryAllocs) {
        Deque<LineCursor> work = new ArrayDeque<>();
        work.push(new LineCursor(lines, false));
        while (!work.isEmpty()) {
            LineCursor cursor = work.peek();
            if (cursor.index >= cursor.lines.size()) {
                work.pop();
                continue;
            }
            Token lineTok = cursor.lines.get(cursor.index++);
            Token stmt = lineTok.childs.get(0);
            Token letTok = letDeclarationOf(stmt);
            if (letTok != null) {
                Token nameTok = letTok.childs.get(0);
                if (letTok.isStatic) {
                    staticAllocs.add(new HoistedAlloc(nameTok.text, letTok.chk.resolvedType, stmt.right));
                } else {
                    ordinaryAllocs.add(new HoistedAlloc(nameTok.text, letTok.chk.resolvedType, null));
                }
                // A 'let' right-hand side can itself be a "try...catch(e){...}": hoist its catch parameter and body locals as well.
                Token tryNodeUnderLet = tryNodeOf(stmt);
                if (tryNodeUnderLet != null) {
                    hoistTryCatchParam(tryNodeUnderLet, ordinaryAllocs, work);
                }
                continue;
            }
            // "let x = try..." (above), "x = try..." and a bare "try...catch(e){...}" were once never hoisted (the switch below only sees KEYWORD
            // statements); tryNodeOf recognises all three shapes, so checking it here closes the gap.
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                hoistTryCatchParam(tryNode, ordinaryAllocs, work);
                continue;
            }
            hoistFromStatement(stmt, ordinaryAllocs, work);
        }
    }

    /** Hoists the catch's own bound parameter (`ALLOC <catchParamName> static_imut_string`, initialized later by `emitHoistedCatchBlocks`'s own one-time snapshot write), then queues the catch body for the walk. */
    private void hoistTryCatchParam(Token tryNode, List<HoistedAlloc> ordinaryAllocs, Deque<LineCursor> work) {
        hub.core.functionCatchParams.add(tryNode.catchParamName);
        ordinaryAllocs.add(new HoistedAlloc(tryNode.catchParamName, "static_imut_string", null));
        if (tryNode.chk.catchUsesStackTrace) {
            ordinaryAllocs.add(new HoistedAlloc(tryNode.catchParamName + "$trace", "mut_u64[" + (TraceConfig.depth + 1) + "]", null));
        }
        work.push(new LineCursor(tryNode.childs, false));
    }

    /** The non-'let' half of `collectHoistedAllocs` -- queues whichever nested block(s), if any, this statement carries, plus the two implicit shapes ('for's hidden range/loop variables, a 'loop's own preLoopInit) that declare a local without going through an ordinary 'let' line at all. */
    private void hoistFromStatement(Token stmt, List<HoistedAlloc> ordinaryAllocs, Deque<LineCursor> work) {
        if (stmt.type != TokenType.KEYWORD) {
            return;
        }
        switch (stmt.text) {
            case "if":
            case "match":
                pushBranchBodies(work, stmt);
                return;
            case "loop":
                if (stmt.chk.preLoopInit != null) {
                    for (Token assign : stmt.chk.preLoopInit) {
                        Token letTok = assign.left;
                        Token nameTok = letTok.childs.get(0);
                        ordinaryAllocs.add(new HoistedAlloc(nameTok.text, letTok.chk.resolvedType, null));
                    }
                }
                work.push(new LineCursor(stmt.childs, false));
                return;
            case "for": {
                Token rangeAssign = stmt.sub.get(0);
                Token loopVarAssign = stmt.sub.get(1);
                ordinaryAllocs.add(new HoistedAlloc(rangeVarNameOf(rangeAssign), rangeAssign.left.chk.resolvedType, null));
                ordinaryAllocs.add(new HoistedAlloc(loopVarAssign.left.childs.get(0).text, loopVarAssign.left.chk.resolvedType, null));
                work.push(new LineCursor(stmt.childs, false));
                return;
            }
            case "unsafe":
            case "safe":
            case "lock":
                work.push(new LineCursor(stmt.childs, false));
                return;
            case "assume":
                if (stmt.hasBlock) {
                    work.push(new LineCursor(stmt.childs, false));
                }
                return;
            case "try":
                // Only the plain "try { ... }" block form arrives here (a throwing try...catch is intercepted by tryNodeOf); it emits nothing of its own.
                work.push(new LineCursor(stmt.childs, false));
                return;
            default:
                return;
        }
    }

    /** Emits one collected allocation: `ALLOC name type`, or the folded ALLOC_STATIC lines of a constant `let static`. */
    public void emitHoistedAlloc(HoistedAlloc alloc) {
        if (alloc.staticValue != null) {
            emitStaticAlloc("ALLOC_STATIC", alloc.name, alloc.type, alloc.staticValue);
        } else {
            hub.core.line("ALLOC " + alloc.name + " " + alloc.type);
        }
    }

    // ---- whole-function catch-block hoisting -------------------------------

    /**
     * Emits each real try/catch's catch body once, at the top of the function behind a JMP over them (a catch body is plain
     * code that must end in return/throw, so no resume label exists). Labels are assigned before any statement is emitted
     * so call sites can redirect unwinds into them. See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public void emitHoistedCatchBlocks(FuncInfo info) {
        List<Token> tryNodes = new ArrayList<>();
        collectTryCatchNodes(info.funcToken.childs, tryNodes);
        if (tryNodes.isEmpty()) {
            return;
        }
        for (Token tryNode : tryNodes) {
            hub.core.tryCatchLabels.put(tryNode, newLabel("catch"));
        }
        String skipLabel = newLabel("end_of_catches__" + hub.core.currentFuncMangledName);
        hub.core.line("JMP " + skipLabel);
        for (Token tryNode : tryNodes) {
            hub.core.line(hub.core.tryCatchLabels.get(tryNode) + ":");
            // copy of this frame's gt_error_message into the catch parameter (an independent local, never an alias)
            hub.core.line("ADDR " + tryNode.catchParamName + " static_imut_string");
            hub.core.line("PUSH gt_error_message static_imut_string");
            hub.core.line("ASSIGN static_imut_string static_imut_string static_imut_string");
            if (tryNode.chk.catchUsesStackTrace) {
                // handler-local copy of the trace: the staging array belongs to the root and a later throw would overwrite it
                String arr = "mut_u64[" + (TraceConfig.depth + 1) + "]";
                hub.core.line("ADDR " + tryNode.catchParamName + "$trace " + arr);
                hub.core.line("PUSH gt_trace_ptr " + BytecodeEmitterCore.traceArrayPointerType());
                hub.core.line("DEREF " + arr);
                hub.core.line("ASSIGN " + arr + " " + arr + " " + arr);
            }
            hub.core.runBlock(tryNode.childs, false);
            // no trailing JMP/destruct list: the body ends in return/throw, which emit their own
        }
        hub.core.line(skipLabel + ":");
    }

    /** Visits whichever nested block(s), if any, each statement in `lines` carries -- the identical traversal shape `collectHoistedAllocs` uses -- collecting every "try" token found, in program order. */
    public void collectTryCatchNodes(List<Token> lines, List<Token> collected) {
        Deque<LineCursor> work = new ArrayDeque<>();
        work.push(new LineCursor(lines, false));
        while (!work.isEmpty()) {
            LineCursor cursor = work.peek();
            if (cursor.index >= cursor.lines.size()) {
                work.pop();
                continue;
            }
            Token stmt = cursor.lines.get(cursor.index++).childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                collected.add(tryNode);
                // a catch body can itself contain a nested try
                work.push(new LineCursor(tryNode.childs, false));
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    pushBranchBodies(work, stmt);
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    work.push(new LineCursor(stmt.childs, false));
                    break;
                case "try":
                    // plain "try { }" block form only (the throwing form was taken by tryNodeOf); walk it so a nested try/catch is found
                    work.push(new LineCursor(stmt.childs, false));
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        work.push(new LineCursor(stmt.childs, false));
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
        Deque<LineCursor> work = new ArrayDeque<>();
        work.push(new LineCursor(lines, false));
        while (!work.isEmpty()) {
            LineCursor cursor = work.peek();
            if (cursor.index >= cursor.lines.size()) {
                work.pop();
                if (cursor.popsTryBlock) {
                    enclosingTryBlocks.pop();
                }
                continue;
            }
            Token stmt = cursor.lines.get(cursor.index++).childs.get(0);
            Token tryNode = tryNodeOf(stmt);
            if (tryNode != null) {
                work.push(new LineCursor(tryNode.childs, false));
                continue;
            }
            if (stmt.type != TokenType.KEYWORD) {
                continue;
            }
            switch (stmt.text) {
                case "if":
                case "match":
                    pushBranchBodies(work, stmt);
                    break;
                case "loop":
                case "for":
                case "unsafe":
                case "safe":
                case "lock":
                    work.push(new LineCursor(stmt.childs, false));
                    break;
                case "try":
                    // plain "try { }" only; label assigned before walking the body so a nested catch's 'continue' can resolve it
                    hub.core.tryBlockLabels.put(stmt, newLabel("after_try_block"));
                    enclosingTryBlocks.push(stmt);
                    work.push(new LineCursor(stmt.childs, true));
                    break;
                case "assume":
                    if (stmt.hasBlock) {
                        work.push(new LineCursor(stmt.childs, false));
                    }
                    break;
                case "continue":
                    if (!enclosingTryBlocks.isEmpty()) {
                        hub.core.continueTargetLabels.put(stmt, hub.core.tryBlockLabels.get(enclosingTryBlocks.peek()));
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

    /** The emission of one statement: lines that need nothing nested are written now and null is returned, otherwise the frame that does the rest. */
    public EmitFrame statementFrame(Token stmt) {
        if (stmt.type == TokenType.KEYWORD) {
            switch (stmt.text) {
                case "if":
                    return new EmitFrame(EmitFrame.IF_CHAIN, stmt);
                case "loop":
                    return new EmitFrame(EmitFrame.LOOP, stmt);
                case "for":
                    return new EmitFrame(EmitFrame.FOR_LOOP, stmt);
                case "match":
                    return new EmitFrame(EmitFrame.MATCH_CHAIN, stmt);
                case "unsafe":
                case "safe":
                    // compile-time safety markers only; emit the statements, no wrapper
                    return EmitFrame.script(stmt).block(stmt.childs, false).destruct(stmt.chk.destructOnExit, stmt);
                case "assume":
                    // no runtime presence: the block form emits its statements; the no-block form has no children (later siblings belong to the enclosing block)
                    if (stmt.hasBlock) {
                        return EmitFrame.script(stmt).block(stmt.childs, false).destruct(stmt.chk.destructOnExit, stmt);
                    } else if (stmt.assumeFieldVariants == null && stmt.sub != null && !stmt.sub.isEmpty()) {
                        return assumeResolvesFrame(stmt.operand());   // a nullable ref proven by `assume match Some(..)`: resolve it once
                    }
                    return null;
                case "return":
                    return returnFrame(stmt);
                case "throw":
                    return throwFrame(stmt);
                case "try":
                    // Two constructs share this KEYWORD (Token.isTryBlock). A plain "try { }" block emits its statements plus a label
                    // precomputed by collectTryBlockLabels (a hoisted catch's 'continue' jumps to it). Otherwise it is the bare
                    // "try call() catch(e){..}" statement; the call's result, if any, stays on the stack.
                    if (stmt.isTryBlock) {
                        return EmitFrame.script(stmt).block(stmt.childs, false).destruct(stmt.chk.destructOnExit, stmt)
                                .line(hub.core.tryBlockLabels.get(stmt) + ":");
                    }
                    return hub.core.tryGuardedCallFrame(stmt);
                case "break":
                    return breakFrame(stmt);
                case "continue":
                    return continueFrame(stmt);
                case "yield":
                    // bare mnemonic, no operands
                    hub.core.line("YIELD");
                    return null;
                case "ASM":
                    // named: emitted at each invocation site (see the "ASM" case in emit()); unnamed: only here
                    if (stmt.sub.isEmpty()) {
                        hub.core.emitAsm(stmt.chk.resolvedAsmContent);
                    }
                    return null;
                default:
                    throw new IllegalStateException("unexpected statement keyword: " + stmt.text);
            }
        }
        if (stmt.type == TokenType.VARREF && stmt.chk.resolvedAsmContent != null) {
            // "asm_name;" invocation (resolvedAsmContent is set only by TypeChecker, only for genuine ones)
            hub.core.emitAsm(stmt.chk.resolvedAsmContent);
            return null;
        }
        // plain expression statement: the result, if any, is left on the stack (no POP)
        return new EmitFrame(EmitFrame.EXPR, stmt);
    }

    /** if / elseif / else chain. Frame: s1 = end label, s2 = the current branch's next label, toks = the branches, i = current branch. */
    public int stepIfChain(EmitFrame f) {
        while (true) {
            switch (f.phase) {
                case 0:
                    f.s1 = newLabel("end_of_if");
                    f.toks = new ArrayList<>();
                    for (Token b = f.node; b != null; b = b.next) {
                        f.toks.add(b);
                    }
                    f.i = 0;
                    f.phase = 1;
                    continue;
                case 1: {
                    if (f.i >= f.toks.size()) {
                        hub.core.line(f.s1 + ":");
                        return EmitFrame.DONE;
                    }
                    Token branch = f.toks.get(f.i);
                    boolean isLast = (f.i == f.toks.size() - 1);
                    f.s2 = isLast ? f.s1 : newLabel("if_branch");
                    f.phase = 3;
                    if (!branch.sub.isEmpty()) {
                        Token cond = branch.condition();
                        if (hub.operations.isThenVariant(cond)) {
                            // "&&then"/"||then" root: cascading jumps (see stepShortCircuit)
                            return f.call(hub.operations.shortCircuitFrame(cond, f.s2, null));
                        }
                        f.phase = 2;
                        return f.callExpr(cond);
                    }
                    continue;
                }
                case 2:
                    hub.core.line("CMP");
                    hub.core.line("JMP " + f.s2);
                    f.phase = 3;
                    continue;
                case 3:
                    f.phase = 4;
                    return f.call(hub.core.blockFrame(f.toks.get(f.i).childs, false));
                case 4: {
                    Token branch = f.toks.get(f.i);
                    f.phase = 5;
                    EmitFrame destructs = hub.core.destructListFrame(branch.chk.destructOnExit, branch);
                    if (destructs != null) {
                        return f.call(destructs);
                    }
                    continue;
                }
                default: {
                    boolean isLast = (f.i == f.toks.size() - 1);
                    hub.core.line("JMP " + f.s1);
                    if (!isLast) {
                        hub.core.line(f.s2 + ":");
                    }
                    f.i++;
                    f.phase = 1;
                    continue;
                }
            }
        }
    }

    /** A match is an if-chain: one CMP/JMP per branch (conditions via the MATCH_COND frame), shared end label; a bare 'else' branch falls into its block. Frame slots as stepIfChain. */
    public int stepMatchChain(EmitFrame f) {
        while (true) {
            switch (f.phase) {
                case 0:
                    f.s1 = newLabel("end_of_match");
                    f.toks = new ArrayList<>();
                    for (Token b = f.node; b != null; b = b.next) {
                        f.toks.add(b);
                    }
                    f.i = 0;
                    f.phase = 1;
                    continue;
                case 1: {
                    if (f.i >= f.toks.size()) {
                        hub.core.line(f.s1 + ":");
                        return EmitFrame.DONE;
                    }
                    Token branch = f.toks.get(f.i);
                    boolean isLast = (f.i == f.toks.size() - 1);
                    f.s2 = isLast ? f.s1 : newLabel("match_branch");
                    f.phase = 3;
                    if (!branch.sub.isEmpty()) {
                        f.phase = 2;
                        return f.call(new EmitFrame(EmitFrame.MATCH_COND, branch.condition()));
                    }
                    continue;
                }
                case 2:
                    hub.core.line("CMP");
                    hub.core.line("JMP " + f.s2);
                    f.phase = 3;
                    continue;
                case 3:
                    f.phase = 4;
                    return f.call(hub.core.blockFrame(f.toks.get(f.i).childs, false));
                case 4: {
                    Token branch = f.toks.get(f.i);
                    f.phase = 5;
                    EmitFrame destructs = hub.core.destructListFrame(branch.chk.destructOnExit, branch);
                    if (destructs != null) {
                        return f.call(destructs);
                    }
                    continue;
                }
                default: {
                    boolean isLast = (f.i == f.toks.size() - 1);
                    hub.core.line("JMP " + f.s1);
                    if (!isLast) {
                        hub.core.line(f.s2 + ":");
                    }
                    f.i++;
                    f.phase = 1;
                    continue;
                }
            }
        }
    }

    /** Emits a match condition's bool result onto the stack -- ordinary operator emission for instanceof/implements/in, plus GT_ALIVE_CHECK for the bare form, going through "and" so every leaf (whichever kind) gets the right treatment before being combined. Frame: t1 = the index expression of a `Some(i) in x`. */
    public int stepMatchCondition(EmitFrame f) {
        Token node = f.node;
        while (true) {
            switch (f.phase) {
                case 0:
                    if (node.type == TokenType.OPERATOR && node.text.equals("and")) {
                        f.phase = 1;
                        return f.call(new EmitFrame(EmitFrame.MATCH_COND, node.left));
                    }
                    if (node.chk.someIndexElementExpr != null) {
                        // "match Some(i) in/into x": one combined bool (bounds IN, then element alive) gating the single block.
                        // node.left is the Some(..) call; only its index argument is pushed for the IN.
                        f.t1 = hub.operations.singleBuiltinArg(node.left);
                        f.phase = 3;
                        return f.callExpr(f.t1);
                    }
                    if (node.type == TokenType.OPERATOR
                            && (node.text.equals("instanceof") || node.text.equals("implements") || node.text.equals("in")
                                    || node.text.equals("within") || node.text.equals("into"))) {
                        // "within" as a match condition emits exactly like "within"
                        // within/into emit exactly like in as expressions; the proof is recorded separately by the type checker
                        return f.tail(new EmitFrame(EmitFrame.EXPR, node));
                    }
                    if (node.type == TokenType.OPERATOR && node.text.equals("CALL") && node.left.type == TokenType.VARREF
                            && node.left.text.equals("Some")) {
                        // the expression emitter already emits GT_ALIVE_CHECK for Some(..); the generic fallback below would duplicate it
                        return f.tail(new EmitFrame(EmitFrame.EXPR, node));
                    }
                    if (node.type == TokenType.OPERATOR && node.text.equals("fits")) {
                        return f.tail(hub.fitsFrame(node));
                    }
                    if (node.type == TokenType.OPERATOR && node.text.equals("is")) {
                        // "PUSH x / IS_BASE <leftType>": only the left side is pushed. The type is written out because for a composite
                        // left operand (e.g. a range literal = two scalar pushes) the preceding line does not carry it.
                        // See docs/BytecodeEmitter.md, "Operator emission".
                        return f.tail(EmitFrame.script(node).expr(node.left).line("IS_BASE " + node.left.chk.resolvedType));
                    }
                    if (node.type == TokenType.OPERATOR && (node.text.equals("!=") || node.text.equals(">"))
                            && node.left.type == TokenType.VARREF && node.right.type == TokenType.INTEGER
                            && node.right.text.equals("0")) {
                        // division-safety proof shape: an ordinary comparison, never a GT_ALIVE_CHECK
                        return f.tail(new EmitFrame(EmitFrame.EXPR, node));
                    }
                    // unreachable: checkMatchCondition rejects every other shape
                    throw new IllegalStateException(
                            "internal error: unrecognized match condition shape reached bytecode emission: " + node.text);
                case 1:
                    f.phase = 2;
                    return f.call(new EmitFrame(EmitFrame.MATCH_COND, node.right));
                case 2:
                    hub.core.line("AND " + node.left.chk.resolvedType + " " + node.right.chk.resolvedType + " " + node.chk.resolvedType);
                    return EmitFrame.DONE;
                case 3:
                    f.phase = 4;
                    return f.callExpr(node.right);
                case 4: {
                    hub.core.line("IN " + f.t1.chk.resolvedType + " " + node.right.chk.resolvedType + " " + node.chk.resolvedType);
                    if (hub.core.inGtSuppressedContext) {
                        throw new CompilerException("type", node.file, node.line,
                                "'Some(...)' (which needs '@gt_alive_check') can't be used here -- this "
                                        + "function is a ghost-table-decorated function, or is reachable "
                                        + "from one, and ghost-table instructions can never be generated "
                                        + "in that whole reachable set");
                    }
                    Token element = node.chk.someIndexElementExpr;
                    f.phase = 6;
                    if (element.chk.someOnRef) {
                        return f.call(someOnRefFrame(element, element, element.chk.someProofKey, true));
                    }
                    hub.core.requireGhostTableFunctionPresent("gt_alive_check", node);
                    f.phase = 5;
                    return f.callExpr(element);
                }
                case 5:
                    hub.core.line("GT_ALIVE_CHECK");
                    f.phase = 6;
                    continue;
                default:
                    hub.core.line("AND " + node.chk.resolvedType + " indeterminate_bool " + node.chk.resolvedType);
                    return EmitFrame.DONE;
            }
        }
    }

    public void noteLoopLabel(Token loopTok, String kind, String startLabel) {
        if (hub.core.curHobFn != null && loopTok.file != null) {
            hub.core.sourcePos.put(hub.core.curHobFn + "|" + startLabel, BytecodeEmitterCore.shownPos(loopTok));
            hub.core.termSites.add(new String[]{hub.core.curHobFn, startLabel, loopTok.file, String.valueOf(loopTok.line), kind});
        }
        DocComment td = DocComments.find(loopTok.file, loopTok.line, "termination", kind);
        if (td != null && hub.core.curHobFn != null) {
            hub.core.loopTermLabels.put(hub.core.curHobFn + "|" + startLabel, td);
        }
    }

    /** `loop`. Frame: s1 = start label, s2 = end label, toks = pre-loop assigns, i = next pre-loop assign. */
    public int stepLoop(EmitFrame f) {
        Token loopTok = f.node;
        while (true) {
            switch (f.phase) {
                case 0:
                    f.s1 = newLabel("loop");
                    noteLoopLabel(loopTok, "loop", f.s1);
                    if (loopTok.chk.lockSpinBound != 0 && hub.core.curHobFn != null) {
                        hub.core.spinBounds.put(hub.core.curHobFn + "|" + f.s1, loopTok.chk.lockSpinBound);
                    }
                    f.s2 = newLabel("loop_end");
                    hub.core.loopEndLabels.add(f.s2);
                    hub.core.loopContinueTargets.add(new String[] { f.s1 });
                    hub.core.emitDecorators("LOOP_DECORATE", loopTok.decorators);
                    // Pre-loop assigns of a lowered "match @lock" loop that uses a "CLOSED:default" policy ($lm_tries = 0 and the policy's
                    // parameters), run once before the start label and not through a block. Their ALLOCs are hoisted by collectHoistedAllocs.
                    f.toks = loopTok.chk.preLoopInit;
                    f.i = 0;
                    f.phase = 1;
                    continue;
                case 1:
                    if (f.toks != null && f.i < f.toks.size()) {
                        return f.call(new EmitFrame(EmitFrame.ASSIGN, f.toks.get(f.i++)));
                    }
                    hub.core.line(f.s1 + ":");
                    if (loopTok.chk.isLockSpinLoop) {
                        hub.core.lockRetryLabels.add(f.s1);
                    }
                    f.phase = 2;
                    return f.call(hub.core.blockFrame(loopTok.childs, false));
                case 2: {
                    if (loopTok.chk.isLockSpinLoop) {
                        hub.core.lockRetryLabels.remove(hub.core.lockRetryLabels.size() - 1);
                    }
                    f.phase = 3;
                    EmitFrame destructs = hub.core.destructListFrame(loopTok.chk.destructOnExit, loopTok);
                    if (destructs != null) {
                        return f.call(destructs);
                    }
                    continue;
                }
                default:
                    hub.core.line("JMP " + f.s1);
                    hub.core.line(f.s2 + ":");
                    hub.core.loopEndLabels.remove(hub.core.loopEndLabels.size() - 1);
                    hub.core.loopContinueTargets.remove(hub.core.loopContinueTargets.size() - 1);
                    return EmitFrame.DONE;
            }
        }
    }

    /**
     * Emits a 'for' that TypeChecker.checkForLoop desugared into four nodes (range let, loop-variable let, 'in' condition, '++')
     * through the ordinary emission paths, plus the loop control flow (check at top, continue label before the step, jump back).
     * The hidden variables' ALLOCs are hoisted by collectHoistedAllocs. Frame: s1 = start label, s2 = end label.
     */
    public int stepForLoop(EmitFrame f) {
        Token forTok = f.node;
        Token rangeAssign = forTok.sub.get(0);
        Token loopVarAssign = forTok.sub.get(1);
        Token conditionIn = forTok.sub.get(2);
        Token increment = forTok.sub.get(3);
        while (true) {
            switch (f.phase) {
                case 0:
                    f.phase = 1;
                    return f.call(new EmitFrame(EmitFrame.ASSIGN, rangeAssign));
                case 1:
                    f.phase = 2;
                    return f.call(new EmitFrame(EmitFrame.ASSIGN, loopVarAssign));
                case 2:
                    f.s1 = newLabel("for");
                    noteLoopLabel(forTok, "for", f.s1);
                    f.s2 = newLabel("for_end");
                    hub.core.loopEndLabels.add(f.s2);
                    hub.core.loopContinueTargets.add(new String[] { null });
                    hub.core.emitDecorators("FOR_DECORATE", forTok.decorators, true);
                    hub.core.line(f.s1 + ":");
                    f.phase = 3;
                    return f.callExpr(conditionIn);
                case 3:
                    hub.core.line("CMP");
                    hub.core.line("JMP " + f.s2);
                    f.phase = 4;
                    return f.call(hub.core.blockFrame(forTok.childs, false));
                case 4: {
                    f.phase = 5;
                    EmitFrame destructs = hub.core.destructListFrame(forTok.chk.destructOnExit, forTok);
                    if (destructs != null) {
                        return f.call(destructs);
                    }
                    continue;
                }
                case 5: {
                    String[] continueTarget = hub.core.loopContinueTargets.get(hub.core.loopContinueTargets.size() - 1);
                    if (continueTarget[0] != null) {
                        hub.core.line(continueTarget[0] + ":");
                    }
                    f.phase = 6;
                    return f.callExpr(increment);
                }
                default:
                    hub.core.line("JMP " + f.s1);
                    hub.core.line(f.s2 + ":");
                    hub.core.loopEndLabels.remove(hub.core.loopEndLabels.size() - 1);
                    hub.core.loopContinueTargets.remove(hub.core.loopContinueTargets.size() - 1);
                    return EmitFrame.DONE;
            }
        }
    }

    public String rangeVarNameOf(Token rangeAssign) {
        return rangeAssign.left.childs.get(0).text;
    }

    /** 'break': destruct/unlock lists, then a jump to the enclosing loop's end label. An empty stack means TypeChecker failed. */
    public EmitFrame breakFrame(Token breakTok) {
        if (hub.core.loopEndLabels.isEmpty()) {
            throw new IllegalStateException(
                    "internal error: 'break' reached bytecode gen outside any loop "
                            + "(should have been caught by TypeChecker)");
        }
        return EmitFrame.script(breakTok).destruct(breakTok.chk.destructOnExit, breakTok).unlock(breakTok.chk.unlockOnExit, breakTok)
                .line("JMP " + hub.core.loopEndLabels.get(hub.core.loopEndLabels.size() - 1));
    }

    /**
     * 'continue' in three forms: a user for/loop (jump to the step/start), the CLOSED case of a 'match @lock' (retry), or a
     * catch-body continue (jump to the label from continueTargetLabels, precomputed because the catch body is hoisted).
     * See docs/BytecodeEmitter.md, "Hoisted catch blocks".
     */
    public EmitFrame continueFrame(Token continueTok) {
        EmitFrame f = EmitFrame.script(continueTok);
        if (continueTok.chk.isLoopContinue) {
            // 'continue' in a user 'for'/'loop': unwind like 'break', then jump to the loop's step / start.
            if (hub.core.loopContinueTargets.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: loop 'continue' reached bytecode gen outside any loop");
            }
            String[] target = hub.core.loopContinueTargets.get(hub.core.loopContinueTargets.size() - 1);
            if (target[0] == null) {
                target[0] = newLabel("for_cont");
            }
            return f.destruct(continueTok.chk.destructOnExit, continueTok).unlock(continueTok.chk.unlockOnExit, continueTok)
                    .line("JMP " + target[0]);
        }
        if (continueTok.chk.isLockRetryContinue) {
            // CLOSED case of a 'match @lock': retry at the spin loop's start (after the pre-loop init, so counters are kept); no lock is held
            if (hub.core.lockRetryLabels.isEmpty()) {
                throw new IllegalStateException(
                        "internal error: lock-retry 'continue' reached bytecode gen outside a 'match @lock' loop");
            }
            return f.destruct(continueTok.chk.destructOnExit, continueTok).unlock(continueTok.chk.unlockOnExit, continueTok)
                    .line("JMP " + hub.core.lockRetryLabels.get(hub.core.lockRetryLabels.size() - 1));
        }
        String targetLabel = hub.core.continueTargetLabels.get(continueTok);
        if (targetLabel == null) {
            throw new IllegalStateException(
                    "internal error: 'continue' reached bytecode gen with no enclosing 'try { ... }' "
                            + "block (should have been caught by TypeChecker)");
        }
        return f.destruct(continueTok.chk.destructOnExit, continueTok).unlock(continueTok.chk.unlockOnExit, continueTok)
                .line("JMP " + targetLabel);
    }

    public EmitFrame returnFrame(Token returnTok) {
        EmitFrame f = EmitFrame.script(returnTok);
        if (returnTok.sub.isEmpty()) {
            return f.destruct(returnTok.chk.destructOnExit, returnTok).unlock(returnTok.chk.unlockOnExit, returnTok)
                    .line("RET " + returnTok.chk.resolvedType);
        }
        Token expr = returnTok.operand();
        if (hub.core.currentFuncInfo != null && hub.core.isStructByValueReturn(hub.core.currentFuncInfo.returnType)) {
            // Callee half of struct RVO: writes the returned struct through "$ret_dest" and returns that pointer. See docs/BytecodeEmitter.md, "Return and struct return (RVO)".
            String structName = hub.core.currentFuncInfo.returnType.baseType;
            String ptrType = "raw_mut_" + structName;
            String structType = "mut_" + structName;
            Token unwrapped = hub.operations.unwrapMutWrappers(expr);
            if (unwrapped.chk.isStructRvoCall) {
                // "return foo()": forward $ret_dest
                f.rvoForward(unwrapped, ptrType);
            } else {
                f.line("PUSH $ret_dest " + ptrType);
                f.expr(expr);
                f.line("ASSIGN " + structType + " " + expr.chk.resolvedType + " " + structType);
            }
            return f.destruct(returnTok.chk.destructOnExit, returnTok).unlock(returnTok.chk.unlockOnExit, returnTok)
                    .line("PUSH $ret_dest " + ptrType).line("RET " + ptrType);
        }
        f.expr(expr);
        // the value is evaluated before destructing; the returned value is never in the destruct list
        return f.destruct(returnTok.chk.destructOnExit, returnTok).unlock(returnTok.chk.unlockOnExit, returnTok)
                .line("RET " + returnTok.chk.resolvedType);
    }

    /**
     * THROW marker line, then the write of the operand into this frame's gt_error_message slot, the per-throw-site destruct list
     * and the unwind/exit. See docs/BytecodeEmitter.md, "Throw". The lines before the unlock calls are written now; null when nothing follows them.
     */
    public EmitFrame throwFrame(Token throwTok) {
        Token expr = throwTok.operand();
        String operand = expr.type == TokenType.VARREF ? expr.text : hub.literalValueOf(expr);
        hub.core.line("THROW " + operand);
        if (hub.core.inGtSuppressedContext) {
            // gt-suppressed function: no ghost-table instructions and no gt_routine slot exist here (checkThrow bans throw in the ghost-table functions)
            return null;
        }
        // operand is a string literal id or a variable name (a catch parameter is re-propagated unchanged)
        if (!(expr.type == TokenType.VARREF && hub.core.functionCatchParams.contains(expr.text))) {
            hub.core.emitTraceCapture();   // a fresh throw starts a new trace; `throw e` of a catch parameter keeps the one already captured
        }
        hub.core.line("ADDR gt_error_message static_imut_string");
        hub.core.line("PUSH " + operand + " " + expr.chk.resolvedType);
        hub.core.line("ASSIGN static_imut_string " + expr.chk.resolvedType + " static_imut_string");
        // per-throw-site destruct list; currentFuncInfo/currentFuncMangledName are the emitted (post-dundering) identity
        List<String> destructNames = throwTok.chk.destructOnExit != null ? throwTok.chk.destructOnExit
                : Collections.emptyList();
        if (!destructNames.isEmpty()) {
            hub.core.requireGhostTableFunctionPresent("gt_destruct", throwTok);
        }
        for (String name : destructNames) {
            hub.core.line("GT_DESTRUCT " + name);
        }
        String finalLine;
        if (hub.core.currentFuncMangledName.equals("main")) {
            finalLine = "EXIT";
        } else if (hub.core.currentFuncInfo != null && hub.core.currentFuncInfo.isAsync) {
            finalLine = "EXIT_THREAD";
        } else {
            finalLine = hub.core.gtUnwindLine();
        }
        return EmitFrame.script(throwTok).unlock(throwTok.chk.unlockOnExit, throwTok).line(finalLine);
    }

    public String newLabel(String prefix) {
        hub.core.labelCounter++;
        return "@" + prefix + "_" + hub.core.labelCounter;
    }

    /**
     * Nullable `ref` values are 64-bit ids, `ref some` values and proven bindings are addresses (see Token.refToId). This wrapper
     * applies the three marks the TypeChecker left on an expression: a nullable `ref` that is the subject of an alive proof is read from
     * the proof's hidden address slot; an address stored where a nullable `ref` is expected becomes an id (GT_REF_ID); a nullable `ref`
     * handed to a `raw` slot is resolved to its current address (GT_REF_RESOLVE, null when dead).
     */
    public int stepExpr(EmitFrame f) {
        Token node = f.node;
        if (f.phase == 0) {
            f.b1 = false;
            if (node.chk.refProofIsId && node.chk.refProofKey != null) {
                String slot = hub.core.refAddrSlots.get(node.chk.refProofKey);
                if (slot != null) {
                    hub.core.line("PUSH " + slot + " " + node.chk.resolvedType);
                    return EmitFrame.DONE;
                }
                f.b1 = true;   // proven by `assume match` (nothing ran, no address slot): resolve the id here
            }
            f.phase = 1;
            EmitFrame core = coreFrame(node);
            if (core != null) {
                return f.call(core);
            }
        }
        if (f.b1) {
            requireRefHook("gt_ref_resolve", node);
            hub.core.line("GT_REF_RESOLVE " + node.chk.resolvedType);   // like every instruction, it names the type it leaves (a following PUSH_FIELDNAME reads the struct from it)
            return EmitFrame.DONE;
        }
        if (node.chk.refToId) {
            requireRefHook("gt_ref_id", node);
            hub.core.line("GT_REF_ID");
        } else if (node.chk.refToRaw) {
            requireRefHook("gt_ref_resolve", node);
            hub.core.line("GT_REF_RESOLVE " + node.chk.resolvedType);
        }
        return EmitFrame.DONE;
    }

    public void requireRefHook(String decorator, Token at) {
        if (hub.core.inGtSuppressedContext) {
            throw new CompilerException("type", at.file, at.line,
                    "a nullable 'ref' (which needs '@" + decorator + "') can't be used here -- this function is a ghost-table-decorated "
                            + "function, or is reachable from one, and ghost-table instructions can never be generated in that whole reachable set");
        }
        hub.core.requireGhostTableFunctionPresent(decorator, at);
    }

    /**
     * `Some(x)` on a nullable `ref` (an id): resolve it into the proof's hidden address slot (one slot per proof key and function), then
     * test that address against null. The match body reads the slot instead of the id (stepExpr, Token.refProofKey).
     * `assume match Some(r)` (test false) does the same one-time resolve but no null test and no branch (the programmer vouches).
     */
    public EmitFrame someOnRefFrame(Token someCall, Token subject, String proofKey, boolean test) {
        requireRefHook("gt_ref_resolve", someCall);
        String type = subject.chk.resolvedType;
        String slot = proofKey == null ? null : hub.core.refAddrSlots.get(proofKey);
        if (slot == null) {
            slot = hub.declareHiddenLocal(type);
            if (proofKey != null) {
                hub.core.refAddrSlots.put(proofKey, slot);
            }
        }
        EmitFrame f = EmitFrame.script(someCall);
        f.line("ADDR " + slot + " " + type);
        f.expr(subject);
        f.line("GT_REF_RESOLVE " + type);
        f.line("ASSIGN " + type + " " + type + " " + type);
        if (test) {
            f.line("PUSH " + slot + " " + type);
            f.line("PUSH null null");
            f.line("NEQ " + type + " null indeterminate_bool");
        }
        return f;
    }

    /**
     * `assume match Some(r)` / `assume match <index> in v and Some(v[i].r)` on a nullable `ref`: the same one-time resolve into the proof's
     * address slot as a real `match Some`, but no null test and no branch, so the uses after it read the slot instead of resolving the id again at every use.
     * Walks the condition tree in pre-order. Frame: work = the nodes still to visit.
     */
    public EmitFrame assumeResolvesFrame(Token condition) {
        return new EmitFrame(EmitFrame.ASSUME_RESOLVES, condition);
    }

    public int stepAssumeResolves(EmitFrame f) {
        if (f.phase == 0) {
            f.work = new ArrayDeque<>();
            if (f.node != null) {
                f.work.push(f.node);
            }
            f.phase = 1;
        }
        while (!f.work.isEmpty()) {
            Token c = f.work.pop();
            if (c.sub != null) {
                for (int k = c.sub.size() - 1; k >= 0; k--) {
                    if (c.sub.get(k) != null) {
                        f.work.push(c.sub.get(k));
                    }
                }
            }
            if (c.right != null) {
                f.work.push(c.right);
            }
            if (c.left != null) {
                f.work.push(c.left);
            }
            EmitFrame resolve = null;
            if (c.chk.someOnRef && c.chk.someProofKey != null) {
                resolve = someOnRefFrame(c, hub.operations.singleBuiltinArg(c), c.chk.someProofKey, false);
            } else if (c.chk.someIndexElementExpr != null && c.chk.someIndexElementExpr.chk.someOnRef) {
                resolve = someOnRefFrame(c.chk.someIndexElementExpr, c.chk.someIndexElementExpr, c.chk.someIndexElementExpr.chk.someProofKey, false);
            }
            if (resolve != null) {
                return f.call(resolve);
            }
        }
        return EmitFrame.DONE;
    }

    /** The emission of an expression node, or null when its lines were all written here: literals, the operator dispatch and the transparent wrappers. */
    public EmitFrame coreFrame(Token node) {
        switch (node.type) {
            case STRING:
            case CHAR:
            case INTEGER:
            case FLOAT:
            case BOOL:
            case NULL:
                hub.core.line("PUSH " + hub.literalValueOf(node) + " " + node.chk.resolvedType);
                return null;
            case VARREF:
                hub.core.line((BytecodeEmitterCore.isAtomicCanonical(node.chk.resolvedType) ? "ATOMIC_PUSH " : "PUSH ") + node.text + " "
                        + node.chk.resolvedType);
                return null;
            case KEYWORD:
                if (node.text.equals("let")) {
                    Token nameTok = node.childs.get(0);
                    hub.core.line("PUSH " + nameTok.text + " " + node.chk.resolvedType);
                    return null;
                }
                if (node.text.equals("try")) {
                    // expression-position "let x = try .. catch .."; the assignment already emitted ADDR x
                    return hub.core.tryGuardedCallFrame(node);
                }
                break;
            case DELINEATOR:
                if (node.text.equals("(")) {
                    // transparent grouping parens
                    if (!node.childs.isEmpty()) {
                        return new EmitFrame(EmitFrame.EXPR, node.childs.get(0));
                    }
                    return null;
                }
                if (node.text.equals("[")) {
                    // array literal: each element pushed in written order
                    return new EmitFrame(EmitFrame.ARRAY_LITERAL, node);
                }
                break;
            case OPERATOR:
                return operatorFrame(node);
            default:
                break;
        }
        throw new IllegalStateException("don't know how to emit " + node.type + "(" + node.text + ")");
    }

    /** Array literal. Frame: toks = the elements, i = next element. */
    public int stepArrayLiteral(EmitFrame f) {
        if (f.phase == 0) {
            f.toks = new ArrayList<>();
            hub.operations.collectCommaArgs(f.node.childs.get(0), f.toks);
            f.i = 0;
            f.phase = 1;
        }
        if (f.i < f.toks.size()) {
            Token element = f.toks.get(f.i++);
            if (hub.core.dynLiteralTempNames != null && hub.isOwnsTemporary(element)) {
                return f.call(hub.ownsTemporaryFrame(element, hub.core.dynLiteralTempNames));
            }
            return f.callExpr(element);
        }
        return EmitFrame.DONE;
    }

    public EmitFrame operatorFrame(Token op) {
        switch (op.text) {
            case "=":
                return new EmitFrame(EmitFrame.ASSIGN, op);
            case "+=": case "-=": case "*=": case "/=": case "%=":
                return hub.operations.compoundAssignFrame(op);
            case "+": case "-": case "*": case "/": case "%":
                if (op.unary) {
                    return hub.operations.unaryMinusFrame(op);
                }
                return hub.operations.binaryArithFrame(op);
            case "!":
                return hub.operations.logicalNotFrame(op);
            case "++":
                return hub.operations.incDecFrame(op, true);
            case "--":
                return hub.operations.incDecFrame(op, false);
            case "&&":
                return hub.operations.logicalFrame(op, "AND");
            case "||":
                return hub.operations.logicalFrame(op, "OR");
            case "<": case ">": case "<=": case ">=": case "==": case "!=":
                return hub.operations.comparisonFrame(op);
            case "in": case "into": {
                // dedicated IN opcode; "into" emits identically (the difference is only a type-checker proof kind)
                EmitFrame f = EmitFrame.script(op);
                if (op.chk.isGuaranteedEnumMembershipCheck) {
                    // right side is a bare enum type name, pushed by name; the backend derives the check from the ENUM line
                    return f.expr(op.left).line("PUSH " + op.right.chk.resolvedType)
                            .line("IN " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
                }
                if (op.chk.isStringMembershipCheck) {
                    // a string has no stored length: runtime byte scan under its own mnemonic IN_SCAN
                    return f.expr(op.left).expr(op.right)
                            .line("IN_SCAN " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
                }
                return f.expr(op.left).expr(op.right)
                        .line("IN " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
            }
            case "within":
                // same shape as "in"; always WITHIN (pointerness is handled by MembershipLoweringPass from the operand types)
                return EmitFrame.script(op).expr(op.left).expr(op.right)
                        .line("WITHIN " + op.left.chk.resolvedType + " " + op.right.chk.resolvedType + " " + op.chk.resolvedType);
            case "CALL":
                return hub.operations.callOrBuiltinFrame(op);
            case "LOOKUP":
                return hub.lookupFrame(op);
            case "as":
                return hub.asFrame(op);
            case ".":
                return hub.dotFrame(op);
            case "INSTANTIATE":
                return new EmitFrame(EmitFrame.INSTANTIATE, op);
            case "owns": case "ref": case "auto": case "raw": case "static":
                return hub.addressOfFrame(op);
            case "mut": case "imut":
                // compile-time mutability retag: nothing to emit
                return new EmitFrame(EmitFrame.EXPR, op.left);
            case "new":
                return new EmitFrame(EmitFrame.NEW, op);
            case "await":
                // no bytecode of its own; the wrapped CALL emits AWAIT_CALL
                return new EmitFrame(EmitFrame.EXPR, op.left);
            case "par":
                // no bytecode of its own; the wrapped CALL emits PAR_CALL (its resolvedType was overwritten by checkPar)
                return new EmitFrame(EmitFrame.EXPR, op.left);
            case "unsafe":
                // no bytecode of its own ("unsafe dyn(..)"); the wrapped call carries the corrected resolvedType
                return new EmitFrame(EmitFrame.EXPR, op.left);
            case "atomic":
                // no bytecode of its own; the wrapped expression's resolvedType carries "atomic"
                return new EmitFrame(EmitFrame.EXPR, op.left);
            case "swap":
                return hub.operations.swapFrame(op);
            case "..":
                // a range is its two u64 bounds pushed back to back
                return EmitFrame.script(op).expr(op.left).expr(op.right);
            case ",":
                // no COMMA instruction, per spec -- just compile both sides
                return EmitFrame.script(op).expr(op.left).expr(op.right);
            case "instanceof": case "implements":
                // right operand is a bare type/interface name pushed by name. The left operand's type is a trailing operand
                // because a bare struct literal on the left leaves only its last member's push before this line.
                return EmitFrame.script(op).expr(op.left).line("PUSH " + op.right.chk.resolvedType)
                        .line(op.text.toUpperCase() + " " + op.left.chk.resolvedType);
            case "and":
                // same bytecode as "&&"; the extra meaning is type-checker only
                return hub.operations.logicalFrame(op, "AND");
            case "FLOAT_CHECK":
                // compiler-internal operator (TypeChecker.lowerFloatMatchToIfChain); op.right.text holds the "finite|nan" chain string
                // an f64 operand carries its width (the f32 form is unchanged: "FLOAT_CHECK cats")
                return EmitFrame.script(op).expr(op.left).line("FLOAT_CHECK " + op.right.text
                        + (op.left.chk.resolvedType != null && (op.left.chk.resolvedType.equals("f64") || op.left.chk.resolvedType.endsWith("_f64")) ? " 8" : ""));
            default:
                throw new IllegalStateException("unhandled operator in bytecode gen: '" + op.text + "'");
        }
    }

    /**
     * Pushes the base of an assignment target's '.'/LOOKUP chain: a no-storage bare variable gets ADDR instead of PUSH, a
     * no-storage '.'/LOOKUP root is itself an assignment target (so "grid[1][1] = x" computes nested addresses), and a
     * pointer-typed root or anything else is an ordinary expression. See docs/BytecodeEmitter.md, "Assignment targets". Null when the ADDR line was written here.
     */
    public EmitFrame dotLhsRootFrame(Token root) {
        if (root.type == TokenType.VARREF && !BytecodeEmitterCore.hasStorage(root.chk.resolvedType)) {
            hub.core.line("ADDR " + root.text + " " + root.chk.resolvedType);
            return null;
        }
        if (root.type == TokenType.OPERATOR && (root.text.equals(".") || root.text.equals("LOOKUP"))
                && !BytecodeEmitterCore.hasStorage(root.chk.resolvedType)) {
            return new EmitFrame(EmitFrame.ASSIGN_TARGET, root);
        }
        return new EmitFrame(EmitFrame.EXPR, root);
    }

    public int stepAssignTarget(EmitFrame f) {
        Token target = f.node;
        while (true) {
            switch (f.phase) {
                case 0: {
                    if (target.type == TokenType.OPERATOR && (target.text.equals(".") || target.text.equals("LOOKUP"))) {
                        f.phase = target.text.equals(".") ? 1 : 2;
                        EmitFrame root = dotLhsRootFrame(target.left);
                        if (root != null) {
                            return f.call(root);
                        }
                        continue;
                    }
                    if (target.type == TokenType.KEYWORD && target.text.equals("let")) {
                        // fresh 'let': the name is the wrapper's first child
                        Token nameTok = target.childs.get(0);
                        hub.core.line("ADDR " + nameTok.text + " " + target.chk.resolvedType);
                        return EmitFrame.DONE;
                    }
                    hub.core.line("ADDR " + target.text + " " + target.chk.resolvedType);
                    return EmitFrame.DONE;
                }
                case 1:
                    hub.core.line("PUSH_FIELDNAME " + target.right.text + " " + target.chk.resolvedType);
                    hub.core.line("DOT_LHS " + target.left.chk.resolvedType + " " + target.chk.resolvedType + " " + target.chk.resolvedType);
                    return EmitFrame.DONE;
                case 2:
                    f.phase = 3;
                    return f.callExpr(target.right.childs.get(0));
                default: {
                    Token indexExpr = target.right.childs.get(0);
                    hub.core.line("LOOKUP_LHS " + target.left.chk.resolvedType + " " + indexExpr.chk.resolvedType + " "
                            + target.chk.resolvedType);
                    return EmitFrame.DONE;
                }
            }
        }
    }

    /** A synthesized `base.member` access, enough for `emitAssignTarget`/`DOT_LHS`. */
    public Token syntheticMember(Token base, String member, TypeInfo memberType) {
        Token dot = new Token(TokenType.OPERATOR, ".", base.line, base.file);
        Token name = new Token(TokenType.VARREF, member, base.line, base.file);
        name.chk.resolvedType = memberType.canonical();
        dot.left = base;
        dot.right = name;
        dot.chk.resolvedType = memberType.canonical();
        return dot;
    }

    /** A synthesized `base[i]` with a literal index, enough for `emitAssignTarget`/`LOOKUP_LHS`. */
    public Token syntheticElement(Token base, int index, String elemCanonical) {
        Token look = new Token(TokenType.OPERATOR, "LOOKUP", base.line, base.file);
        Token idx = new Token(TokenType.INTEGER, Integer.toString(index), base.line, base.file);
        idx.chk.resolvedType = "imut_u64";
        Token holder = new Token(TokenType.DELINEATOR, "[", base.line, base.file);
        holder.childs.add(idx);
        look.left = base;
        look.right = holder;
        look.chk.resolvedType = elemCanonical;
        return look;
    }

    /** Nulls every owned member of the inline struct at `base` (recursing into inline owning members): what a move out of it, or its own drop, leaves behind. */
    public EmitFrame inlineNullOutFrame(Token base, String structName) {
        EmitFrame f = new EmitFrame(EmitFrame.INLINE_NULLOUT, base);
        f.s1 = structName;
        return f;
    }

    /** Frame: s1 = the struct (or fixed array) type name, b1 = fixed array, s2 = element name, s3 = element canonical type, n = element count, members/i = the walk, t2 = the member being nulled. */
    public int stepInlineNullOut(EmitFrame f) {
        Token base = f.node;
        while (true) {
            switch (f.phase) {
                case 0:
                    if (hub.core.checker.declarations.isFixedArrayTypeText(f.s1)) {
                        // A fixed array of inline owning structs: null every element's owned leaves (unrolled, the length is fixed).
                        f.b1 = true;
                        f.s2 = f.s1.substring(0, f.s1.lastIndexOf('['));
                        f.n = Integer.parseInt(f.s1.substring(f.s1.lastIndexOf('[') + 1, f.s1.length() - 1));
                        f.s3 = base.chk.resolvedType.substring(0, base.chk.resolvedType.lastIndexOf(f.s1)) + f.s2;
                    } else {
                        StructInfo si = hub.core.checker.declarations.getStructs().get(f.s1);
                        f.members = new ArrayList<>(si.members.entrySet());
                    }
                    f.i = 0;
                    f.phase = 1;
                    continue;
                case 1:
                    if (f.b1) {
                        if (f.i < f.n) {
                            return f.call(inlineNullOutFrame(syntheticElement(base, f.i++, f.s3), f.s2));
                        }
                        return EmitFrame.DONE;
                    }
                    while (f.i < f.members.size()) {
                        Map.Entry<String, TypeInfo> m = f.members.get(f.i++);
                        if (m.getKey().equals("___type")) {
                            continue;
                        }
                        TypeInfo mt = m.getValue();
                        if ("owns".equals(mt.storage)) {
                            f.t2 = syntheticMember(base, m.getKey(), mt);
                            f.s3 = mt.canonical();
                            f.phase = 2;
                            return f.callTarget(f.t2);
                        } else if (hub.core.checker.declarations.isInlineOwningStruct(mt)) {
                            return f.call(inlineNullOutFrame(syntheticMember(base, m.getKey(), mt), mt.baseType));
                        }
                    }
                    return EmitFrame.DONE;
                default:
                    hub.core.line("PUSH null " + f.s3);
                    hub.core.line("ASSIGN " + f.s3 + " " + f.s3 + " " + f.s3);
                    f.phase = 1;
                    continue;
            }
        }
    }

    /**
     * Nulls a moved-from owner right after the move (synthesized "x = null", only where isOwnershipMoveSource is set). For a call
     * argument it sits between "POP ARGn" and the CALL, so a backend must keep its scratch registers off pending ARG registers.
     * See docs/BytecodeEmitter.md, "Moves and owning assignment".
     */
    public EmitFrame nullOutFrame(Token valueExpr) {
        if (valueExpr.chk.inlineOwnsStruct != null) {
            return inlineNullOutFrame(hub.operations.unwrapMutWrappers(valueExpr), valueExpr.chk.inlineOwnsStruct);
        }
        Token source = hub.operations.unwrapMutWrappers(valueExpr); // `a = mut b`: the variable underneath the wrapper is what gets nulled
        return EmitFrame.script(valueExpr).target(source).line("PUSH null " + source.chk.resolvedType)
                .line("ASSIGN " + source.chk.resolvedType + " " + source.chk.resolvedType + " " + source.chk.resolvedType);
    }

    public boolean isFlatOwnsDestructTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.chk.resolvedType == null
                || !(target.chk.resolvedType.startsWith("owns") || target.chk.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.VARREF
                || (target.type == TokenType.OPERATOR && target.text.equals(".") && hub.isQualifiedNameableDot(target));
    }

    /** An `owns` assignment target with no flat name: a field reached through a pointer, or an indexed element. */
    public boolean isPointerCrossingOwnsTarget(Token target) {
        if (target.type == TokenType.KEYWORD || target.chk.resolvedType == null
                || !(target.chk.resolvedType.startsWith("owns") || target.chk.inlineOwnsStruct != null)) {
            return false;
        }
        return target.type == TokenType.OPERATOR && (target.text.equals("LOOKUP")
                || (target.text.equals(".") && !hub.isQualifiedNameableDot(target)));
    }
}
