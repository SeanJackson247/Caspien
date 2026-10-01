package caspien;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4: converts every RPN-ordered flat expression token list
 * produced by RpnConverter into an actual binary expression tree, using
 * each operator token's own .left/.right fields. Unary operators
 * (prefix '-'/'!', postfix '++') use only .left for their single
 * operand; binary operators (including CALL, LOOKUP, and ',') use both
 * .left and .right. Which is which for a given '-' token is read off
 * Token.unary, set earlier by RpnConverter.
 *
 * Wherever a flat, already-RPN'd token list is fully resolved into one
 * tree, the field that held that flat list is replaced with a single-
 * element list containing just the tree root -- a uniform convention
 * used everywhere a "one expression" slot exists: a plain/let/return
 * LINE's .childs, an if/elseif node's .sub, and a nested '('/'['
 * delineator's .childs (call args, subscripts, grouping parens).
 */
public class TreeBuilder {

    public void buildLines(List<Token> lines) {
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
                    return; // nothing to build
                case "return":
                    return; // bare 'return' (void), no expression to build
                case "break":
                case "continue":
                    return; // no expression, nothing to build
                case "yield":
                    return; // never any arguments, nothing to build
                default:
                    break; // fall through to expression handling below
            }
        }

        int start = 0;
        Token returnTok = null;
        if (first.type == TokenType.KEYWORD && (first.text.equals("return") || first.text.equals("throw"))) {
            returnTok = first;
            start = 1;
        }

        List<Token> exprTokens = new ArrayList<>(tokens.subList(start, tokens.size()));
        if (exprTokens.isEmpty()) {
            return;
        }
        resolveNestedGroups(exprTokens);
        Token root = buildTree(exprTokens, lineTok.file, lineTok.line);

        if (returnTok != null) {
            returnTok.sub = singleton(root);
            lineTok.childs = singleton(returnTok);
        } else {
            lineTok.childs = singleton(root);
        }
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
                resolveNestedGroups(t.sub);
                if (!t.sub.isEmpty()) {
                    t.sub = singleton(buildTree(t.sub, t.file, t.line));
                }
                if (t.isCaseMatch) {
                    // Same reasoning as RpnConverter's own identical fix
                    // -- t.childs here holds one Token per case, not
                    // ordinary "line" tokens; each case's own real body
                    // is one level deeper, in that case's own .childs.
                    for (Token caseTok : t.childs) {
                        buildLines(caseTok.childs);
                    }
                } else {
                    buildLines(t.childs);
                }
                break;
            case "else":
                buildLines(t.childs);
                break;
            case "assume":
                // Mirrors RpnConverter's own identical "assume" case --
                // t.sub always holds the condition (built into a real
                // tree the exact same way an "if"'s own is, just above);
                // t.childs only exists (and only needs walking) for the
                // block form.
                resolveNestedGroups(t.sub);
                if (!t.sub.isEmpty()) {
                    t.sub = singleton(buildTree(t.sub, t.file, t.line));
                }
                if (t.hasBlock) {
                    buildLines(t.childs);
                }
                break;
            case "loop":
            case "func":
            case "unsafe":
            case "safe":
                buildLines(t.childs);
                break;
            case "try":
                // Mirrors RpnConverter's own identical "try" case: t.sub
                // holds the wrapped call's already-RPN'd tokens, reduced
                // here to a single tree root (the established "one
                // expression" convention, same as "if"/"assume" just
                // above) -- TypeChecker.checkTry is what actually
                // requires that root be a real call. t.childs (the
                // catch block, always present -- 'catch { ... }' is
                // mandatory) gets the ordinary recursive buildLines walk.
                resolveNestedGroups(t.sub);
                if (!t.sub.isEmpty()) {
                    t.sub = singleton(buildTree(t.sub, t.file, t.line));
                }
                buildLines(t.childs);
                break;
            case "cast":
                buildLines(t.childs); // t.sub is just two raw name tokens, nothing to build
                break;
            case "struct":
            case "abstract":
            case "enum":
            case "type":
            case "extern":
            case "export":
            case "ASM":
            case "impl_default_lock_match":
                // Mirrors RpnConverter's own identical case -- a pure
                // template declaration, never itself built here.
                return;
            case "const":
                // Mirrors 'if''s own condition build -- resolve, build a
                // real tree, then replace the flat list with a single-
                // element one holding just the root (the established
                // convention wherever a "one expression" slot exists).
                resolveNestedGroups(t.childs);
                t.childs = singleton(buildTree(t.childs, t.file, t.line));
                return;
            case "interface":
                return; // pure declarations, nothing to build
            case "impl":
                for (Token method : t.childs) {
                    processGathered(method);
                }
                return;
            case "library":
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

    /** Bottom-up: resolves every nested '('/'[' delineator's flat RPN childs into a single tree root first. */
    /**
     * Also handles a struct-literal '{' whose childs (from RpnConverter's
     * resolveStructLiteralMembers) is a List of LINE tokens, one per
     * member assignment, each still holding a flat "name value... ="
     * RPN sequence. Running that straight through buildLines works with
     * zero new logic: a bare "name = value" flat sequence is exactly
     * the shape the ordinary plain-expression-statement path already
     * builds correctly (VARREF as the left operand, the value as the
     * right operand of the '=' node) -- a member assignment doesn't
     * need to be told apart from an ordinary reassignment statement at
     * this stage.
     */
    private void resolveNestedGroups(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.type == TokenType.DELINEATOR && (t.text.equals("(") || t.text.equals("["))) {
                resolveNestedGroups(t.childs);
                if (!t.childs.isEmpty()) {
                    t.childs = singleton(buildTree(t.childs, t.file, t.line));
                }
            } else if (t.type == TokenType.DELINEATOR && t.text.equals("{")) {
                buildLines(t.childs);
            } else if (t.type == TokenType.TEMPLATE_STRING) {
                // Same reasoning as RpnConverter's own identical case --
                // each "${...}" segment is a synthesized '(' group one
                // level deeper than this function's own top-level
                // `tokens` list.
                for (Token seg : t.childs) {
                    if (seg.type == TokenType.DELINEATOR && seg.text.equals("(")) {
                        resolveNestedGroups(seg.childs);
                        if (!seg.childs.isEmpty()) {
                            seg.childs = singleton(buildTree(seg.childs, seg.file, seg.line));
                        }
                    }
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try")) {
                // Same reasoning as RpnConverter's own identical case --
                // a 'try' embedded inside a larger expression (e.g.
                // "let x = try foo() catch{...}") still needs its own
                // `.sub`/`.childs` built, the same way the bare-
                // statement form's own "try" case in processGathered
                // just above already does.
                resolveNestedGroups(t.sub);
                if (!t.sub.isEmpty()) {
                    t.sub = singleton(buildTree(t.sub, t.file, t.line));
                }
                buildLines(t.childs);
            }
        }
    }

    private List<Token> singleton(Token t) {
        List<Token> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    /** Standard RPN-to-tree: an operand stack, popping 1 or 2 operands per operator depending on its arity. */
    private Token buildTree(List<Token> rpn, String file, int line) {
        List<Token> stack = new ArrayList<>();
        for (Token t : rpn) {
            int arity = arityOf(t);
            if (arity == 0) {
                stack.add(t);
            } else if (arity == 1) {
                if (stack.isEmpty()) {
                    throw new CompilerException("parse", t.file, t.line,
                            "'" + t.text + "' has no operand to apply to");
                }
                Token operand = stack.remove(stack.size() - 1);
                t.left = operand;
                stack.add(t);
            } else {
                if (stack.size() < 2) {
                    throw new CompilerException("parse", t.file, t.line,
                            "'" + t.text + "' is missing an operand");
                }
                Token right = stack.remove(stack.size() - 1);
                Token left = stack.remove(stack.size() - 1);
                t.left = left;
                t.right = right;
                stack.add(t);
            }
        }
        if (stack.size() != 1) {
            throw new CompilerException("parse", file, line,
                    "expression did not reduce to a single value (internal error in RPN conversion)");
        }
        return stack.get(0);
    }

    private int arityOf(Token t) {
        if (t.type != TokenType.OPERATOR) {
            return 0;
        }
        if (t.text.equals("++") || t.text.equals("--")) {
            return 1;
        }
        if (t.unary) {
            return 1;
        }
        return 2;
    }
}
