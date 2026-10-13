package caspien.treebuilder;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    // Work items of the explicit task stack (no recursion): the order they run in is the order the old recursive walk visited things.
    private static final int LINES = 1;          // list: statements to build, in order
    private static final int STMT = 2;           // tok: one statement line
    private static final int FINISH_STMT = 3;    // tok = line, tok2 = leading return/throw or null, list = its flat expression
    private static final int GATHERED = 4;       // tok: a gathered keyword block
    private static final int NEXT = 5;           // tok: continue with tok.next (elseif/else/elsematch chain)
    private static final int NESTED = 6;         // list: flat tokens whose nested groups are resolved first
    private static final int BUILD_GROUP = 7;    // tok: a '(' '[' group, its childs become one tree
    private static final int BUILD_SUB = 8;      // tok: its .sub becomes one tree
    private static final int BUILD_CHILDS = 9;   // tok: its .childs become one tree (const)

    private static final class Task {
        final int kind;
        final Token tok;
        final Token tok2;
        final List<Token> list;
        Task(int kind, Token tok, Token tok2, List<Token> list) {
            this.kind = kind;
            this.tok = tok;
            this.tok2 = tok2;
            this.list = list;
        }
    }

    public void buildLines(List<Token> lines) {
        run(new Task(LINES, null, null, lines));
    }

    private void run(Task root) {
        Deque<Task> stack = new ArrayDeque<>();
        stack.push(root);
        List<Task> out = new ArrayList<>();
        while (!stack.isEmpty()) {
            Task t = stack.pop();
            out.clear();
            step(t, out);
            for (int i = out.size() - 1; i >= 0; i--) {
                stack.push(out.get(i));
            }
        }
    }

    /** Runs one task; the tasks it needs next are appended to `out` in the order they must run. */
    private void step(Task t, List<Task> out) {
        switch (t.kind) {
            case LINES:
                for (Token lineTok : t.list) {
                    out.add(new Task(STMT, lineTok, null, null));
                }
                return;
            case STMT:
                processStatement(t.tok, out);
                return;
            case FINISH_STMT: {
                Token lineTok = t.tok;
                Token root = buildTree(t.list, lineTok.file, lineTok.line);
                if (t.tok2 != null) {
                    t.tok2.sub = singleton(root);
                    lineTok.childs = singleton(t.tok2);
                } else {
                    lineTok.childs = singleton(root);
                }
                return;
            }
            case GATHERED:
                processGathered(t.tok, out);
                return;
            case NEXT:
                if (t.tok.next != null) {
                    out.add(new Task(GATHERED, t.tok.next, null, null));
                }
                return;
            case NESTED:
                resolveNestedGroups(t.list, out);
                return;
            case BUILD_GROUP:
                if (!t.tok.childs.isEmpty()) {
                    t.tok.childs = singleton(buildTree(t.tok.childs, t.tok.file, t.tok.line));
                }
                return;
            case BUILD_SUB:
                if (!t.tok.sub.isEmpty()) {
                    t.tok.sub = singleton(buildTree(t.tok.sub, t.tok.file, t.tok.line));
                }
                return;
            case BUILD_CHILDS:
                t.tok.childs = singleton(buildTree(t.tok.childs, t.tok.file, t.tok.line));
                return;
            default:
                throw new IllegalStateException("unknown tree task " + t.kind);
        }
    }

    private void processStatement(Token lineTok, List<Task> out) {
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
                case "enum":
                case "interface":
                case "impl":
                case "impl_default_lock_match":
                case "library":
                case "for":
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
                    out.add(new Task(GATHERED, first, null, null));
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
        out.add(new Task(NESTED, null, null, exprTokens));
        out.add(new Task(FINISH_STMT, lineTok, returnTok, exprTokens));
    }

    /** Handles an already-gathered if/elseif/else/loop/func/struct/enum/for token. */
    private void processGathered(Token t, List<Task> out) {
        switch (t.text) {
            case "if":
            case "elseif":
            case "for":
            case "match":
            case "elsematch":
            case "lock":
                out.add(new Task(NESTED, null, null, t.sub));
                out.add(new Task(BUILD_SUB, t, null, null));
                if (t.isCaseMatch) {
                    // Same reasoning as RpnConverter's own identical fix
                    // -- t.childs here holds one Token per case, not
                    // ordinary "line" tokens; each case's own real body
                    // is one level deeper, in that case's own .childs.
                    for (Token caseTok : t.childs) {
                        out.add(new Task(LINES, null, null, caseTok.childs));
                    }
                } else {
                    out.add(new Task(LINES, null, null, t.childs));
                }
                break;
            case "else":
                out.add(new Task(LINES, null, null, t.childs));
                break;
            case "assume":
                // t.sub always holds the condition (built into a real
                // tree the same way an "if"'s own is); t.childs only
                // exists (and only needs walking) for the block form.
                out.add(new Task(NESTED, null, null, t.sub));
                out.add(new Task(BUILD_SUB, t, null, null));
                if (t.hasBlock) {
                    out.add(new Task(LINES, null, null, t.childs));
                }
                break;
            case "loop":
            case "func":
            case "unsafe":
            case "safe":
                out.add(new Task(LINES, null, null, t.childs));
                break;
            case "try":
                // t.sub holds the wrapped call's already-RPN'd tokens,
                // reduced to a single tree root -- TypeChecker.checkTry
                // requires that root be a real call. t.childs (the catch
                // block, always present) gets the ordinary walk.
                out.add(new Task(NESTED, null, null, t.sub));
                out.add(new Task(BUILD_SUB, t, null, null));
                out.add(new Task(LINES, null, null, t.childs));
                break;
            case "struct":
            case "enum":
            case "type":
            case "extern":
            case "export":
            case "ASM":
            case "impl_default_lock_match":
                // a pure template declaration, never itself built here.
                return;
            case "const":
                // resolve, build a real tree, then replace the flat list with a
                // single-element one holding just the root.
                out.add(new Task(NESTED, null, null, t.childs));
                out.add(new Task(BUILD_CHILDS, t, null, null));
                return;
            case "interface":
                return; // pure declarations, nothing to build
            case "impl":
                for (Token method : t.childs) {
                    out.add(new Task(GATHERED, method, null, null));
                }
                return;
            case "library":
                for (Token func : t.childs) {
                    out.add(new Task(GATHERED, func, null, null));
                }
                return;
            default:
                throw new CompilerException("parse", t.file, t.line,
                        "internal error: unexpected gathered keyword '" + t.text + "'");
        }
        out.add(new Task(NEXT, t, null, null));
    }

    /**
     * Bottom-up: every nested '('/'[' delineator's flat RPN childs are resolved into a single tree root first.
     * Also handles a struct-literal '{' whose childs (from RpnConverter's resolveStructLiteralMembers) are LINE tokens,
     * one per member assignment: running them through buildLines works unchanged, since a bare "name = value" flat
     * sequence is exactly the shape the ordinary expression-statement path already builds.
     */
    private void resolveNestedGroups(List<Token> tokens, List<Task> out) {
        for (Token t : tokens) {
            if (t.type == TokenType.DELINEATOR && (t.text.equals("(") || t.text.equals("["))) {
                out.add(new Task(NESTED, null, null, t.childs));
                out.add(new Task(BUILD_GROUP, t, null, null));
            } else if (t.type == TokenType.DELINEATOR && t.text.equals("{")) {
                out.add(new Task(LINES, null, null, t.childs));
            } else if (t.type == TokenType.TEMPLATE_STRING) {
                // each "${...}" segment is a synthesized '(' group one level deeper
                for (Token seg : t.childs) {
                    if (seg.type == TokenType.DELINEATOR && seg.text.equals("(")) {
                        out.add(new Task(NESTED, null, null, seg.childs));
                        out.add(new Task(BUILD_GROUP, seg, null, null));
                    }
                }
            } else if (t.type == TokenType.KEYWORD && t.text.equals("try")) {
                // a 'try' embedded in a larger expression still needs its own .sub/.childs built
                out.add(new Task(NESTED, null, null, t.sub));
                out.add(new Task(BUILD_SUB, t, null, null));
                out.add(new Task(LINES, null, null, t.childs));
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
