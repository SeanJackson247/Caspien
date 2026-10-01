package caspien;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 3: converts every remaining infix expression token list in the
 * tree produced by Parser into RPN (shunting-yard, C-style precedence).
 * Tree building (turning an RPN sequence into an actual left/right
 * expression tree) is a separate, later stage -- this one only
 * reorders tokens.
 *
 * Where expressions live, and how each is found:
 *   - a plain expression-statement LINE: the whole line is the expression
 *   - a 'let' line: "let"+name (already merged into one token by the
 *     parser) acts as a single atomic operand, so the *entire* line
 *     (e.g. [let(x), =, a, +, b]) is RPN'd as one unit -- the merged
 *     let-token needs no special casing, it just behaves like a varref
 *   - a 'return' line: the leading "return" keyword is stripped first
 *     (it is a statement prefix, not part of the expression), the
 *     remainder is RPN'd, then "return" is placed back at the front of
 *     the result
 *   - an 'if'/'elseif' node's .sub (the condition)
 *   - the .childs of any nested '(' or '[' delineator found inside an
 *     expression (call argument lists, subscripts, and plain grouping
 *     parens) -- resolved bottom-up, *before* the list containing that
 *     delineator is itself scanned, so by the time the outer scan
 *     reaches it, it is treated as a single already-resolved operand.
 *     This is exactly the "a delineator being an operand is handled by
 *     compiling its nested children" rule from the spec.
 *   - recursion continues into every nested '{' block (as a lines list)
 *     and into loop/func bodies, and if/elseif/else chains via .right
 *
 * struct and enum bodies are declarations, not expressions, and are
 * left untouched.
 */
public class RpnConverter {

    /** Operators that can appear in a binary position (includes the synthetic CALL/LOOKUP/INSTANTIATE). */
    private static final Set<String> BINARY_TEXTS = new HashSet<>(Arrays.asList(
            "&&", "||", "&&then", "||then", "+=", "-=", "/=", "%=", "*=", "<=", ">=", "==", "!=",
            "<", ">", "+", "-", "/", "%", "*", "=", ":", ",", "CALL", "LOOKUP", "as", ".", "INSTANTIATE", "..", "in",
            "within", "into", "dup", "instanceof", "implements", "and", "is", "fits", "swap"
    ));

    /** Operators that can appear in a (prefix) unary position. */
    private static final Set<String> UNARY_CAPABLE_TEXTS = new HashSet<>(Arrays.asList("-", "!"));

    /** Storage-modifier keywords, usable as a prefix "address-of" operator (e.g. "auto x"). Excludes mut/imut deliberately -- see MUTABILITY_MODIFIER_TEXTS below, its own separate prefix-operator mechanism. */
    private static final Set<String> STORAGE_MODIFIER_TEXTS = new HashSet<>(Arrays.asList(
            "owns", "ref", "auto", "raw", "static"
    ));

    /**
     * "let i = mut 0" instead of "let i = 0 as mut," confirmed directly
     * -- 'mut'/'imut' as a prefix operator, structurally the exact same
     * shape as the storage-modifier prefix operators just above (same
     * promotion mechanism, same precedence), but kept as its own,
     * separate set: these two modifiers apply to *any* expression's
     * mutability, not just address-of a struct/enum value the way
     * owns/ref/auto/raw/static do, and 'as' no longer accepts a
     * mutability target at all (confirmed directly: "the as operator
     * should only effect types, but not mutability").
     */
    private static final Set<String> MUTABILITY_MODIFIER_TEXTS = new HashSet<>(Arrays.asList(
            "mut", "imut"
    ));

    private static final Set<String> RIGHT_ASSOCIATIVE = new HashSet<>(Arrays.asList(
            "=", "+=", "-=", "/=", "%=", "*=", ":"
    ));

    private static class OpEntry {
        final Token token;
        final boolean unary;
        OpEntry(Token token, boolean unary) {
            this.token = token;
            this.unary = unary;
        }
    }

    private static int precedence(String op, boolean unary) {
        if (op.equals("CALL") || op.equals("LOOKUP") || op.equals(".") || op.equals("INSTANTIATE")) {
            // '.' binds as tightly as postfix CALL/LOOKUP -- EnumName.Variant
            // is a single atomic reference, same tightness convention as
            // member access in C-family languages. INSTANTIATE (struct
            // literal) is the same tier for the same reason -- it's a
            // single atomic value construction, not something that should
            // interact with surrounding arithmetic precedence at all.
            return 100;
        }
        if (op.equals("as")) {
            // Infix cast, never unary. Binds tighter than everything except
            // CALL/LOOKUP -- matching the usual convention in C-family
            // languages that a cast binds tighter than arithmetic.
            return 95;
        }
        if (unary) {
            return 90;
        }
        switch (op) {
            case "*": case "/": case "%": return 80;
            case "+": case "-": return 70;
            case "<": case ">": case "<=": case ">=": return 60;
            case "==": case "!=": return 50;
            case "instanceof": case "implements": case "is": case "fits": return 50;
            case "&&": return 40;
            // "&&then"/"||then" -- the lazy "then" pseudo-operator (see
            // Parser's mergeThenOperators and BytecodeEmitter's
            // emitShortCircuit): same precedence tier as their eager
            // "&&"/"||" siblings exactly, left-associative the same way
            // (not in RIGHT_ASSOCIATIVE below), so an unbroken chain like
            // "a && then b || then c && d" parses with the identical
            // shape ordinary "a && b || c && d" would -- only *which*
            // AST operator text ends up at each node differs, never the
            // tree shape itself.
            case "&&then": return 40;
            case "and": return 40;
            case "||": return 30;
            case "||then": return 30;
            case "..": return 25;
            case "in": return 22;
            case "within": return 22;
            case "into": return 22;
            case "swap": return 22;
            case "=": case "+=": case "-=": case "*=": case "/=": case "%=": case ":": return 20;
            case "dup": return 15;
            case ",": return 10;
            default: return -1;
        }
    }

    private static boolean isRightAssociative(String op, boolean unary) {
        return unary || RIGHT_ASSOCIATIVE.contains(op);
    }

    /** Entry point: walks a list of LINE tokens (a whole file, or any block's body) and RPN-converts every expression found within it, recursively. */
    public void convertLines(List<Token> lines) {
        for (Token lineTok : lines) {
            processStatement(lineTok);
        }
    }

    private void processStatement(Token lineTok) {
        List<Token> tokens = lineTok.childs;
        if (tokens.isEmpty()) {
            return;
        }
        Token first = tokens.get(0);

        if (tokens.size() == 1 && first.type == TokenType.KEYWORD) {
            switch (first.text) {
                case "if":
                case "loop":
                case "func":
                case "struct":
                case "abstract":
                case "enum":
                case "interface":
                case "impl":
                case "impl_default_lock_match":
                case "library":
                case "for":
                case "cast":
                case "type":
                case "match":
                case "unsafe":
                case "safe":
                case "extern":
                case "export":
                case "ASM":
                case "const":
                case "assume":
                case "lock":
                case "try":
                    processGathered(first);
                    return;
                case "import":
                    return; // already fully resolved, nothing to convert
                case "break":
                case "continue":
                    return; // no expression, nothing to RPN-convert
                case "yield":
                    return; // bare form -- "never any arguments," confirmed directly; nothing to RPN-convert
                default:
                    break; // fall through to expression handling below
            }
        }
        if (tokens.size() == 2 && first.type == TokenType.KEYWORD
                && first.text.equals("yield")
                && tokens.get(1).type == TokenType.DELINEATOR && tokens.get(1).text.equals("(")
                && tokens.get(1).childs.isEmpty()) {
            // "yield or yield(), user preference," confirmed directly --
            // unlike a VARREF, a KEYWORD directly followed by '(' never
            // gets Parser's own "CALL" marker inserted at all
            // (insertCallLookupInFlat's own check is VARREF-only),
            // confirmed directly rather than assumed, so the call-syntax
            // form arrives here as two tokens, not three. Normalized down
            // to the bare, single-keyword shape (discarding the now-
            // redundant '(' entirely) rather than kept as a real call
            // expression -- "never any arguments," confirmed directly, so
            // there is nothing a genuine call shape would ever add; every
            // later stage (TypeChecker, BytecodeEmitter) only ever needs
            // to handle the one, bare form.
            lineTok.childs = new ArrayList<>();
            lineTok.childs.add(first);
            return;
        }

        int start = 0;
        List<Token> prefix = new ArrayList<>();
        if (first.type == TokenType.KEYWORD && (first.text.equals("return") || first.text.equals("throw"))) {
            prefix.add(first);
            start = 1;
        }

        List<Token> exprTokens = new ArrayList<>(tokens.subList(start, tokens.size()));
        if (exprTokens.isEmpty()) {
            return; // bare 'return' with no expression (void return)
        }
        resolveNestedGroups(exprTokens);
        List<Token> rpn = toRpn(exprTokens, lineTok.file, lineTok.line);

        List<Token> finalTokens = new ArrayList<>(prefix);
        finalTokens.addAll(rpn);
        lineTok.childs = finalTokens;
    }

    /** Handles an already-gathered if/elseif/else/loop/func/struct/enum/for token. */
    private void processGathered(Token t) {
        switch (t.text) {
            case "if":
            case "elseif":
            case "for":
            case "match":
            case "elsematch":
            case "lock":
                // "lock" (both the block form, "lock EXPR{...}", and the
                // case-match spin-lock form, "match @lock EXPR{...}") is
                // structurally identical here to "for"/"match" -- a
                // condition/target expression in .sub, a trailing block
                // in .childs, with .isCaseMatch distinguishing which of
                // the two shapes it actually is (set by the parser the
                // same way "match"'s own case-match form already is).
                resolveNestedGroups(t.sub);
                t.sub = toRpn(t.sub, t.file, t.line);
                if (t.isCaseMatch) {
                    // "match b{ TRUE:{...} FALSE:{...} }" -- t.childs
                    // here holds one Token per *case*, not ordinary
                    // statement "line" tokens the way every other match
                    // form's own t.childs does -- convertLines itself
                    // expects the latter shape, so calling it directly
                    // on t.childs would silently walk right past every
                    // case's own real body (each case's actual
                    // statements live one level deeper, in *that*
                    // case's own .childs) without ever RPN-converting a
                    // single expression inside them -- confirmed
                    // directly this is exactly what was happening
                    // before this fix ("return mut 1" inside a case
                    // failing as "empty 'return'", the value silently
                    // never reaching the RPN pass that turns raw tokens
                    // into a real expression tree at all).
                    for (Token caseTok : t.childs) {
                        convertLines(caseTok.childs);
                    }
                } else {
                    convertLines(t.childs);
                }
                break;
            case "else":
                convertLines(t.childs);
                break;
            case "assume":
                // "assume match expression;" or "assume match
                // expression{ ... }" -- t.sub always holds the
                // condition (RPN-converted exactly like an "if"'s own,
                // reusing the identical machinery); t.childs only
                // exists (and only needs walking) for the block form --
                // the no-block form's own t.childs is deliberately left
                // empty by the parser (see Token.hasBlock's own doc).
                resolveNestedGroups(t.sub);
                t.sub = toRpn(t.sub, t.file, t.line);
                if (t.hasBlock) {
                    convertLines(t.childs);
                }
                break;
            case "loop":
            case "func":
            case "unsafe":
            case "safe":
                convertLines(t.childs);
                break;
            case "try":
                // Two, unrelated shapes share this one KEYWORD text
                // (Token.isTryBlock tells them apart -- see its own doc
                // comment): a plain "try { ... }" lexical-scope block
                // (t.sub is empty, nothing to RPN-convert there, only
                // t.childs to walk -- structurally identical to
                // "unsafe"/"safe" just above), and the throwing "try
                // foo() catch(e){ ... }" construct, where t.sub holds
                // the wrapped call's own raw tokens (RPN-converted
                // exactly like an "if"/"assume" condition already is;
                // TypeChecker.checkTry is what actually requires the
                // resulting root be a call, not this stage), t.childs
                // the catch block's own gathered statement lines (unlike
                // "assume", always present -- 'catch { ... }' is
                // mandatory, Parser.gatherTryCatchSpan never leaves
                // this empty).
                if (!t.isTryBlock) {
                    resolveNestedGroups(t.sub);
                    t.sub = toRpn(t.sub, t.file, t.line);
                }
                convertLines(t.childs);
                break;
            case "cast":
                // t.sub = [varNameTok, targetTypeNameTok] -- two raw name
                // tokens, not an expression (no 'as' token even kept here,
                // see Parser.gatherCast), so nothing to RPN-convert there;
                // only the block body needs walking.
                convertLines(t.childs);
                break;
            case "struct":
            case "abstract":
            case "enum":
            case "type":
            case "extern":
            case "export":
            case "ASM":
            case "impl_default_lock_match":
                // "impl default match @lock TypeName{...}" -- a pure
                // template declaration (see TypeChecker.
                // DefaultLockMatchPolicy), never itself walked here:
                // its own raw param list/body are only ever gathered/
                // RPN-converted/tree-built as a fresh, deep-cloned copy,
                // once per "CLOSED:default"/"CLOSED:default(args)" use
                // site, by TypeChecker.checkLockMatchStatement directly
                // -- the identical "declaration only, nothing to
                // convert here" shape struct/enum/extern already have.
                return;
            case "const":
                // "const NAME EXPR" -- t.sub holds only the (non-
                // expression) name; the expression itself is parked in
                // t.childs (since t.sub is taken), converted the exact
                // same way an 'if''s own condition already is.
                resolveNestedGroups(t.childs);
                t.childs = toRpn(t.childs, t.file, t.line);
                return;
            case "interface":
                return; // pure declarations (method signatures, no bodies) -- nothing to convert
            case "impl":
                // t.childs is a flat list of gathered 'func' tokens (see
                // Parser.gatherImpl) -- each needs the exact same
                // processing a top-level func gets.
                for (Token method : t.childs) {
                    processGathered(method);
                }
                return;
            case "library":
                // t.childs is a flat list of gathered 'func' tokens (see
                // Parser.gatherLibrary) -- the exact same shape 'impl'
                // just above already has ('extends' lines were already
                // pulled out into t.extendsNames by the parser, so
                // t.childs holds nothing but real function declarations).
                for (Token func : t.childs) {
                    processGathered(func);
                }
                return;
            default:
                throw new CompilerException("parse", t.file, t.line,
                        "internal error: unexpected gathered keyword '" + t.text + "'");
        }
        if (t.right != null) {
            processGathered(t.right);
        }
    }

    /**
     * Recursively resolves every nested '(' or '[' delineator found in a
     * flat expression token list: RPN-converts its own .childs first
     * (bottom-up), so by the time the caller's shunting-yard scan reaches
     * that delineator token, it can be treated as a single atomic
     * operand standing in for the whole resolved group/args/subscript.
     */
    /**
     * Bottom-up: resolves every nested '(' or '[' delineator's flat RPN
     * childs into a single tree root first, and detects/resolves any
     * struct-literal instantiation ("StructName{ member= value, ... }")
     * found in this token list.
     *
     * Struct-literal detection ('{' immediately following a VARREF) is
     * deliberately done HERE -- inside RpnConverter, which only ever
     * walks token lists *after* Parser.gatherKeywordBlocks has already
     * consumed every struct/enum/func header -- rather than alongside
     * CALL/LOOKUP insertion in Parser.java (which runs *before*
     * gatherKeywordBlocks). Doing it earlier would collide with real
     * struct/enum headers ("struct Point{" -- Point is a VARREF
     * immediately followed by '{') and with a func's return-type token
     * ("func foo() u64 {" -- u64 is a VARREF immediately followed by
     * '{'). By the time RpnConverter runs, those headers no longer
     * exist as raw VARREF+'{' token sequences in any list this method
     * walks -- they've already been fully absorbed into .sub/.childs by
     * the parser -- so the same "VARREF then '{'" pattern found here can
     * only mean a struct literal, with zero special-case guarding
     * needed (unlike insertCallLookupInFlat's "not preceded by func"
     * guard, which exists specifically because that insertion runs too
     * early to avoid this same collision).
     */
    private void resolveNestedGroups(List<Token> tokens) {
        desugarSelfBolting(tokens);
        insertStructInstantiate(tokens);
        for (Token t : tokens) {
            if (t.type == TokenType.DELINEATOR && (t.text.equals("(") || t.text.equals("["))) {
                resolveNestedGroups(t.childs);
                t.childs = toRpn(t.childs, t.file, t.line);
            } else if (t.type == TokenType.DELINEATOR && t.text.equals("{")) {
                resolveStructLiteralMembers(t);
            } else if (t.type == TokenType.TEMPLATE_STRING) {
                // "let my_error_string = `...${CONFIG_FILE}...`,"
                // confirmed directly -- each "${...}" segment is a
                // synthesized '(' group (see Lexer's own comment on why
                // '(' rather than '{'), sitting one level deeper than
                // this function's own top-level `tokens` list, which is
                // why plain '(' recursion just above never reaches it
                // on its own. TEMPLATE_STRING_PART (literal-text)
                // segments are left completely untouched -- there's
                // nothing to RPN-convert in a bare piece of text.
                for (Token seg : t.childs) {
                    if (seg.type == TokenType.DELINEATOR && seg.text.equals("(")) {
                        resolveNestedGroups(seg.childs);
                        seg.childs = toRpn(seg.childs, seg.file, seg.line);
                    }
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try")) {
                // "let x = try foo() catch{ ... }" -- a 'try' found
                // embedded inside a larger expression's own flat token
                // list (rather than as a whole line's sole token, the
                // bare-statement form `processGathered`'s own "try"
                // case handles) still needs its `.sub`/`.childs`
                // resolved the identical way -- this method is the one
                // place both shapes funnel through, since a bare "try
                // ... catch{}" statement's line also reduces to exactly
                // one token, which never reaches resolveNestedGroups at
                // all (processStatement's own single-keyword switch
                // dispatches it straight to processGathered instead).
                // Left as a plain KEYWORD token (never promoted to
                // OPERATOR the way "new"/"await"/"unsafe" are) -- once
                // resolved, it's a fully self-contained leaf value, not
                // an operator combining with anything else here; toRpn's
                // own generic fallback already treats an unrecognized
                // KEYWORD as an ordinary atomic operand, so nothing
                // further is needed to make it participate correctly in
                // this expression's own shunting-yard.
                resolveNestedGroups(t.sub);
                t.sub = toRpn(t.sub, t.file, t.line);
                convertLines(t.childs);
            }
        }
    }

    /**
     * "self bolting... like Lua, we want to support the : token being
     * used for first argument bolting," confirmed directly. Runs here,
     * at the very top of resolveNestedGroups, before that function's
     * own recursion into any nested '('/'[' group and before either it
     * or toRpn have touched `tokens` at all -- called at *every* level
     * of nesting (resolveNestedGroups already recurses into every
     * nested group before converting it), so a bolt buried inside a
     * call argument, a subscript, or any other nested position is
     * found and desugared too, not just one written at a statement's
     * own top level.
     *
     * "This use will never be legal in a type bound or generic,"
     * confirmed directly, and confirmed this is genuinely narrower than
     * it first sounds: `:` also appears, unrelated to bolting entirely,
     * in every parameter/struct-member type annotation ("f: mut f32")
     * and every enum-/float-match case label ("finite:{...}") -- neither
     * wrapped in `<>` at all. Both are safe from this pass regardless,
     * confirmed directly by tracing the actual call graph rather than
     * assumed from the `<>` framing alone: a struct/enum/interface body
     * and a match's own case-label list are never passed through
     * resolveNestedGroups (or toRpn) to begin with -- `processGathered`
     * returns immediately for struct/enum/interface bodies without
     * converting anything, and a case-match's own labels are extracted
     * by the parser into each case Token's own `.sub` before this stage
     * ever runs, never left as raw tokens in a `.childs` list this pass
     * would scan. So this pass only ever needs to worry about `':<'`
     * (`let:<T>`/`TypeName:<T>.method(...)`), handled by simply refusing
     * to treat a `:` immediately followed by `<` as a bolt at all.
     *
     * "replace me with a . operator and take my left hand side, which
     * must be a varref, (foo():bar() is a compiler error), and find the
     * next function invocation on the right and place the left as the
     * first token there, followed by a comma, moving the existing
     * entries up," confirmed directly, later refined: the left (and,
     * symmetrically, the right, up to the call itself) may be a `.`
     * member-access chain, not only a single bare varref -- "member
     * access thru . is ok and should be supported" -- but never
     * contains a call anywhere in it either side, confirmed directly
     * ("exclude the results of function calls... it is static sugar, if
     * it is copied and pasted that becomes two function calls where the
     * user only intended one"): the left chain is textually duplicated
     * into the call's own argument list, so a call anywhere in it would
     * silently run twice.
     *
     * Rewriting itself needs no token movement for the left chain at
     * all -- `LHS ':' RHS '(' args ')'` already *is* `LHS '.' RHS '('
     * args ')'` the moment `:` becomes `.`, with nothing to relocate;
     * only a *clone* of the left chain (never the original tokens
     * themselves, which stay exactly where they already are) needs
     * inserting at the front of the call's own `.childs`.
     */
    private void desugarSelfBolting(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type != TokenType.OPERATOR || !t.text.equals(":")) {
                continue;
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).type == TokenType.OPERATOR
                    && tokens.get(i + 1).text.equals("<")) {
                continue; // "let:<T>"/"TypeName:<T>.method(...)" -- not a bolt at all
            }
            int lhsStart = findBoltChainStart(tokens, i);
            int rhsCallIndex = findBoltCallIndex(tokens, i + 1);
            List<Token> lhsChain = new ArrayList<>(tokens.subList(lhsStart, i));
            Token callParen = tokens.get(rhsCallIndex);
            t.text = ".";
            List<Token> injected = new ArrayList<>();
            for (Token lhsTok : lhsChain) {
                injected.add(new Token(lhsTok.type, lhsTok.text, lhsTok.line, lhsTok.file));
            }
            if (!callParen.childs.isEmpty()) {
                injected.add(new Token(TokenType.OPERATOR, ",", t.line, t.file));
            }
            callParen.childs.addAll(0, injected);
        }
    }

    /**
     * Walks backward from `colonIndex` over a "VARREF ('.' VARREF)*"
     * chain, returning the index the chain actually starts at. A hard
     * compile error, naming the specific violation, if the token
     * immediately before `colonIndex` isn't a VARREF at all -- most
     * notably a '(' (the left is the result of a call, "foo():bar() is
     * a compiler error," confirmed directly) or a ')'/']' (same
     * reasoning, a grouped/subscripted expression is still a computed
     * result, not a plain variable or member path).
     */
    private int findBoltChainStart(List<Token> tokens, int colonIndex) {
        Token colonTok = tokens.get(colonIndex);
        if (colonIndex == 0 || tokens.get(colonIndex - 1).type != TokenType.VARREF) {
            Token culprit = colonIndex == 0 ? colonTok : tokens.get(colonIndex - 1);
            throw new CompilerException("parse", colonTok.file, colonTok.line,
                    "the left of a bolt (':') must be a plain variable or a '.'-member-access chain, "
                            + "never the result of a function call or any other computed expression -- got '"
                            + culprit.text + "'");
        }
        int i = colonIndex - 1; // index of the chain's own last VARREF
        while (i - 1 >= 0 && tokens.get(i - 1).type == TokenType.OPERATOR && tokens.get(i - 1).text.equals(".")) {
            if (i - 2 < 0 || tokens.get(i - 2).type != TokenType.VARREF) {
                Token culprit = i - 2 < 0 ? tokens.get(i - 1) : tokens.get(i - 2);
                throw new CompilerException("parse", colonTok.file, colonTok.line,
                        "the left of a bolt (':') must be a plain variable or a '.'-member-access chain, "
                                + "never the result of a function call or any other computed expression -- "
                                + "got '" + culprit.text + "'");
            }
            i -= 2;
        }
        return i;
    }

    /**
     * Walks forward from `start` (the token right after ':') over a
     * "VARREF ('.' VARREF)*" chain, returning the index of the '('
     * delineator found at the end of it -- "find the next function
     * invocation on the right," confirmed directly: the first, nearest
     * call reached, not a later one past some intervening call. A hard
     * compile error if the chain doesn't lead straight to one (the
     * first token isn't a VARREF at all, a '.' isn't followed by
     * another VARREF, or the chain runs off the end of `tokens` without
     * ever reaching a '(').
     */
    private int findBoltCallIndex(List<Token> tokens, int start) {
        int i = start;
        if (i >= tokens.size() || tokens.get(i).type != TokenType.VARREF) {
            Token at = i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
            throw new CompilerException("parse", at.file, at.line,
                    "the right of a bolt (':') must name a function to call, optionally through a "
                            + "'.'-member-access chain first, e.g. 'x:method(...)' or 'x:a.b.method(...)'");
        }
        i++;
        while (true) {
            // "charAt(" arrives here as "VARREF CALL '('" -- Parser's
            // own earlier insertCallLookupInFlat pass already inserted
            // the "CALL" marker between them, unconditionally, for
            // *every* "VARREF directly followed by '('" anywhere in the
            // program, well before this stage ever runs -- confirmed
            // directly by tracing the actual pipeline order (Parser
            // runs before RpnConverter) rather than assumed from the
            // raw "VARREF '('" shape a source-level reading suggests.
            if (i + 1 < tokens.size() && tokens.get(i).type == TokenType.OPERATOR
                    && tokens.get(i).text.equals("CALL") && tokens.get(i + 1).type == TokenType.DELINEATOR
                    && tokens.get(i + 1).text.equals("(")) {
                return i + 1;
            }
            if (i + 1 < tokens.size() && tokens.get(i).type == TokenType.OPERATOR && tokens.get(i).text.equals(".")
                    && tokens.get(i + 1).type == TokenType.VARREF) {
                i += 2;
                continue;
            }
            Token at = i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
            throw new CompilerException("parse", at.file, at.line,
                    "a bolt (':') must resolve to a direct function call -- expected '(' (optionally after "
                            + "more '.'-member-access) here, got '" + at.text + "'");
        }
    }

    private void insertStructInstantiate(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TokenType.VARREF && i + 1 < tokens.size()) {
                Token next = tokens.get(i + 1);
                if (next.type == TokenType.DELINEATOR && next.text.equals("{")) {
                    tokens.add(i + 1, new Token(TokenType.OPERATOR, "INSTANTIATE", t.line, t.file));
                    i++;
                }
            }
        }
    }

    /**
     * A struct literal's '{' already arrives here with its childs as a
     * List of LINE tokens (from Parser's earlier, unconditional
     * splitIntoLines pass over every '{' found anywhere) -- one LINE
     * per ';'-separated member assignment, or a single LINE holding
     * several ','-separated ones packed together, matching the spec's
     * "comma and/or line terminator separated" convention used
     * elsewhere (func params, struct member declarations). This
     * re-groups by comma first (a no-op for the ';' form, where each
     * LINE is already exactly one group), then RPN-converts each
     * "name = value" group as an ordinary flat expression -- no
     * synthetic token construction needed, since the source tokens
     * already spell out a valid assignment shape that toRpn already
     * knows how to handle. The result replaces .childs with a new list
     * of LINE tokens, each now holding one member's RPN'd (not yet
     * tree-built -- that's TreeBuilder's job) "name value... =" sequence.
     */
    /**
     * "name: value" / "name= value" -- a struct-literal field's own
     * name-and-separator prefix is deliberately split off and excluded
     * from resolveNestedGroups/toRpn entirely here, never passed
     * through as part of the group being resolved -- found and fixed
     * directly while building self-bolting: "name:" is textually
     * indistinguishable, at the very start of a token span, from
     * "varref:funcCall(...)" (a bolt), so leaving it in would have made
     * desugarSelfBolting misfire on every ':'-style struct-literal
     * field, confirmed directly (caught via a live debug print showing
     * exactly this: a struct-literal field's own name/separator/value
     * being scanned as if it were an ordinary expression). Only the
     * value itself needs (or is even meaningful to) resolve/RPN at all
     * -- the name is a bare marker, structurally never an operand.
     */
    private void resolveStructLiteralMembers(Token braceTok) {
        List<List<Token>> groups = new ArrayList<>();
        for (Token lineTok : braceTok.childs) {
            splitByComma(lineTok.childs, groups);
        }
        List<Token> newLines = new ArrayList<>();
        for (List<Token> group : groups) {
            List<Token> nameAndSep = new ArrayList<>();
            List<Token> valueTokens = group;
            if (group.size() >= 2 && group.get(0).type == TokenType.VARREF
                    && group.get(1).type == TokenType.OPERATOR
                    && (group.get(1).text.equals(":") || group.get(1).text.equals("="))) {
                nameAndSep.add(group.get(0));
                nameAndSep.add(group.get(1));
                valueTokens = new ArrayList<>(group.subList(2, group.size()));
            }
            resolveNestedGroups(valueTokens);
            // True RPN order for a binary operator: left operand, then
            // right operand (or, if the right is itself a sub-
            // expression, that sub-expression's own already-reduced
            // RPN), then the operator last -- "name" is ':'/'='s own
            // left operand, so it comes *first* here, with the value's
            // own RPN in the middle and the separator itself last.
            // Confirmed directly this is what the original, unsplit
            // "toRpn(group, ...)" call already produced for this exact
            // shape (its own shunting-yard treats ':'/'=' as an
            // ordinary left-associative binary operator). Two earlier,
            // wrong orderings were tried and rejected in turn while
            // fixing this, not assumed correct on the first attempt:
            // "[name, sep] + rpn(value)" (operator immediately after a
            // single operand, with nothing yet on the stack to be its
            // second one -- TreeBuilder itself failed, "':' is missing
            // an operand") and "rpn(value) + [name, sep]" (valid RPN
            // shape-wise, but silently swaps which operand ends up on
            // which side of ':', since in RPN whichever operand is
            // pushed *second* becomes the right one once the operator
            // is reached). Caught by reasoning through the shunting-
            // yard's own stack semantics directly rather than trusting
            // either attempt because it merely stopped erroring.
            List<Token> rpn = new ArrayList<>();
            if (!nameAndSep.isEmpty()) {
                rpn.add(nameAndSep.get(0));
            }
            rpn.addAll(toRpn(valueTokens, braceTok.file, braceTok.line));
            if (!nameAndSep.isEmpty()) {
                rpn.add(nameAndSep.get(1));
            }
            Token newLine = new Token(TokenType.LINE, "line", braceTok.line, braceTok.file);
            newLine.childs = rpn;
            newLines.add(newLine);
        }
        braceTok.childs = newLines;
    }

    private void splitByComma(List<Token> flat, List<List<Token>> out) {
        List<Token> current = new ArrayList<>();
        for (Token t : flat) {
            if (t.type == TokenType.OPERATOR && t.text.equals(",")) {
                if (!current.isEmpty()) {
                    out.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
    }

    /** Core shunting-yard: converts one flat infix token list to RPN order. */
    private List<Token> toRpn(List<Token> tokens, String file, int line) {
        List<Token> output = new ArrayList<>();
        List<OpEntry> stack = new ArrayList<>();
        boolean expectOperand = true;
        boolean sawAnyToken = false;

        for (Token t : tokens) {
            sawAnyToken = true;

            if (t.type == TokenType.OPERATOR && (t.text.equals("++") || t.text.equals("--"))) {
                if (expectOperand) {
                    throw new CompilerException("parse", t.file, t.line,
                            "'" + t.text + "' with no preceding operand");
                }
                // A postfix '++'/'--' applies to the whole call result in
                // 'f(x)++': the pending CALL must be emitted first, or the
                // '++' lands on the argument group instead.
                while (!stack.isEmpty()
                        && stack.get(stack.size() - 1).token.text.equals("CALL")) {
                    OpEntry top = stack.remove(stack.size() - 1);
                    top.token.unary = top.unary;
                    output.add(top.token);
                }
                output.add(t);
                expectOperand = false;
                continue;
            }

            if (t.type == TokenType.OPERATOR) {
                boolean canBeUnary = expectOperand && UNARY_CAPABLE_TEXTS.contains(t.text);
                boolean canBeBinary = !expectOperand && BINARY_TEXTS.contains(t.text);

                if (!canBeUnary && !canBeBinary) {
                    if (expectOperand) {
                        throw new CompilerException("parse", t.file, t.line,
                                "unexpected operator '" + t.text + "' (expected an operand or a unary '-'/'!')");
                    }
                    throw new CompilerException("parse", t.file, t.line,
                            "unexpected operator '" + t.text + "' (expected a binary operator)");
                }

                boolean unary = canBeUnary;
                int prec = precedence(t.text, unary);
                boolean rightAssoc = isRightAssociative(t.text, unary);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec || (topPrec == prec && !rightAssoc)) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                // Multiple assignment operators (=, +=, -=, etc) chained
                // at the same expression level ("a = b = c") are
                // disallowed, confirmed directly. Anything genuinely
                // nested one level deeper -- inside parens, a call's
                // arguments, a struct literal's member value, and so on
                // -- gets its own independent shunting-yard scan
                // entirely (a fresh toRpn call), so it's naturally
                // unaffected by this check; only operators still sitting
                // on *this* scan's own stack count as "the same level".
                // Checking the whole stack, not just its top, matters:
                // an earlier assignment can still be sitting further
                // down un-popped (e.g. "a = b < c = d" pops '<' for the
                // second '=' but never pops the first '=', since equal
                // precedence doesn't yield to another right-associative
                // operator).
                if (RIGHT_ASSOCIATIVE.contains(t.text)) {
                    for (OpEntry entry : stack) {
                        if (RIGHT_ASSOCIATIVE.contains(entry.token.text)) {
                            throw new CompilerException("parse", t.file, t.line,
                                    "multiple assignment operators are not allowed in the same expression");
                        }
                    }
                }
                stack.add(new OpEntry(t, unary));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.MODIFIER && expectOperand && STORAGE_MODIFIER_TEXTS.contains(t.text)) {
                // "auto x" / "raw x" / "static x" (also "auto(x)" etc, which
                // resolveNestedGroups already reduces to the same shape) --
                // a storage-modifier keyword used here as a prefix address-of
                // operator, not as a type-annotation modifier. Mutate its
                // type to OPERATOR so every later stage (TreeBuilder,
                // TypeChecker, BytecodeEmitter) sees an ordinary unary
                // operator node, same mechanism as unary '-'/'!'.
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.MODIFIER && expectOperand && MUTABILITY_MODIFIER_TEXTS.contains(t.text)) {
                // "let i = mut 0" -- same promotion mechanism as the
                // storage-modifier block just above (see
                // MUTABILITY_MODIFIER_TEXTS's own comment for why these
                // are kept as a separate set rather than merged into it).
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.KEYWORD && expectOperand && t.text.equals("new")) {
                // "new Point{...}" -- a prefix operator, same shunting-yard
                // shape as the storage-modifier prefix operators just above,
                // but deliberately *not* merged into that same mechanism:
                // "new" isn't a storage modifier at all (there's no bare
                // "new x" applying storage to an existing lvalue the way
                // "raw x" does) -- it constructs a fresh value and
                // immediately homes it on the heap, a different operation
                // entirely that just happens to share the same prefix
                // grammatical shape. Kept as its own KEYWORD, promoted to
                // OPERATOR here the same way, rather than folded into
                // STORAGE_MODIFIER_TEXTS just because the parsing mechanics
                // are reusable.
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.KEYWORD && expectOperand && (t.text.equals("await") || t.text.equals("par"))) {
                // "par or await would be mandatory," confirmed directly
                // -- both are prefix operators wrapping a call
                // expression, the exact same shunting-yard shape "new"
                // just above already has (a keyword promoted to
                // OPERATOR here, unary, precedence 90 -- tighter than
                // ordinary arithmetic, looser than CALL/LOOKUP/'.', so
                // "await foo().bar" parses as "await (foo().bar)", the
                // whole chain, not just "foo()"). Kept as their own
                // KEYWORDs for the identical reason "new" is -- neither
                // is genuinely a storage/mutability modifier, they just
                // share this one grammatical shape.
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.KEYWORD && expectOperand && t.text.equals("unsafe")) {
                // "let my_unsafe_dynarray = unsafe dyn([0,1,2,3])" --
                // 'unsafe' promoted to a prefix operator here, the exact
                // same shunting-yard shape "new"/"await"/"par" above
                // already have. This is mid-expression only (gated on
                // expectOperand, so only reachable when an operand is
                // expected -- after '=', '(', ',', etc.) and never
                // collides with the pre-existing, statement-level
                // "unsafe{...}" block form: that form is recognized by
                // Parser.gatherKeywordBlocks, keyed on 'unsafe' being the
                // very *first* token of a whole statement line, which
                // this mid-expression form never is (its own statement
                // always starts with 'let' or similar).
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            if (t.type == TokenType.KEYWORD && expectOperand && t.text.equals("atomic")) {
                // "let myAtomicChar = atomic '!'" -- 'atomic' promoted to
                // a prefix operator here, the exact same shunting-yard
                // shape "new"/"await"/"par"/"unsafe" above already have:
                // a keyword mutated to OPERATOR, unary, precedence 90.
                // Wraps an ordinary value expression, tagging its type
                // as atomic (TypeChecker.checkAtomicWrapper) -- the
                // operand's own resolved mutability is preserved
                // unchanged (the same "new" precedent), so an explicit
                // outer mut/imut wrap is still needed, exactly as it
                // already is for "new"/"raw"/etc.
                t.type = TokenType.OPERATOR;
                int prec = precedence(t.text, true);
                while (!stack.isEmpty()) {
                    OpEntry top = stack.get(stack.size() - 1);
                    int topPrec = precedence(top.token.text, top.unary);
                    if (topPrec > prec) {
                        stack.remove(stack.size() - 1);
                        top.token.unary = top.unary;
                        output.add(top.token);
                    } else {
                        break;
                    }
                }
                stack.add(new OpEntry(t, true));
                expectOperand = true;
                continue;
            }

            // atomic operand: VARREF, INTEGER, FLOAT, STRING, CHAR, BOOL,
            // NULL, a merged 'let' token, or an already-resolved delineator.
            if (!expectOperand) {
                throw new CompilerException("parse", t.file, t.line,
                        "unexpected token '" + t.text + "', expected an operator");
            }
            output.add(t);
            expectOperand = false;
        }

        if (sawAnyToken && expectOperand) {
            throw new CompilerException("parse", file, line, "expression ends unexpectedly");
        }
        while (!stack.isEmpty()) {
            OpEntry entry = stack.remove(stack.size() - 1);
            entry.token.unary = entry.unary;
            output.add(entry.token);
        }
        return output;
    }
}
