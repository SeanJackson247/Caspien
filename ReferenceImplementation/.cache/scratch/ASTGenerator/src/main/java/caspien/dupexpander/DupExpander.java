package caspien.dupexpander;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * New pipeline stage, runs on the fully import-resolved root list, before
 * GenericsExpander and before TypeChecker.
 *
 * Pulls the 'dup' array-literal expansion logic out of
 * TypeChecker.checkArrayLiteral (where it used to live, run per-function-body
 * during pass 2) so it can run earlier, structurally, over every reachable
 * expression tree in the whole program -- including inside not-yet-expanded
 * generic template bodies, so a generic struct/func's dup usage is expanded
 * exactly once here rather than once per later monomorphized copy.
 *
 * Purely syntactic: 'dup' counts are restricted (as before, unchanged) to
 * compile-time-constant integer arithmetic with no variables or calls, so no
 * scope/type information is needed to expand them -- this is a clean
 * extraction, not a redesign of what 'dup' means.
 *
 * After this stage, no 'dup' operator node exists anywhere in the tree, and
 * TypeChecker.checkArrayLiteral's own dup-handling branch (and the
 * evalConstantDupCount/rebuildCommaChain helpers it used) is dead code and
 * has been removed from TypeChecker -- see the TypeChecker patch notes.
 */
public class DupExpander {

    /** Entry point: mutates the tree in place, walking every root entry. */
    public void expand(List<Token> rootEntries) {
        for (Token entry : rootEntries) {
            walk(entry);
        }
    }

    /**
     * Walk over every Token field that can hold a nested subtree (left/right/childs/sub), regardless of what kind of
     * node it is -- struct/func/interface/impl declarations, statements, and expressions all get visited uniformly.
     * Whenever a '[' delineator (array literal) is found, its single resolved comma-chain child is expanded in place
     * before the walk goes on (so a dup'd element that is itself an array literal gets its own nested dups expanded
     * too). An explicit task stack, in the order the old recursive walk visited things.
     */
    private void walk(Token start) {
        Deque<DupTask> stack = new ArrayDeque<>();
        stack.push(new DupTask(DupTask.WALK, start));
        List<DupTask> out = new ArrayList<>();
        while (!stack.isEmpty()) {
            DupTask t = stack.pop();
            out.clear();
            step(t, out);
            for (int i = out.size() - 1; i >= 0; i--) {
                stack.push(out.get(i));
            }
        }
    }

    private void step(DupTask t, List<DupTask> out) {
        switch (t.kind) {
            case DupTask.WALK: {
                Token node = t.node;
                if (node == null) {
                    return;
                }
                if (node.type == TokenType.DELINEATOR && "[".equals(node.text) && node.childs != null
                        && !node.childs.isEmpty()) {
                    beginArrayLiteral(node, out);
                }
                out.add(new DupTask(DupTask.CHILDREN, node));
                return;
            }
            case DupTask.CHILDREN: {
                // read after the array literal (if any) has been rewritten
                Token node = t.node;
                out.add(new DupTask(DupTask.WALK, node.left));
                out.add(new DupTask(DupTask.WALK, node.right));
                out.add(new DupTask(DupTask.WALK, node.next));
                if (node.childs != null) {
                    for (Token c : node.childs) {
                        out.add(new DupTask(DupTask.WALK, c));
                    }
                }
                if (node.sub != null) {
                    for (Token s : node.sub) {
                        out.add(new DupTask(DupTask.WALK, s));
                    }
                }
                return;
            }
            case DupTask.ELEMENT: {
                // the walk of a dup element's lhs is done: now count it and add the copies
                Token rawElement = t.node;
                Token lhs = rawElement.left;
                long count = evalConstantDupCount(rawElement.right);
                if (count < 0) {
                    throw new CompilerException("type", rawElement.file, rawElement.line,
                            "'dup' count must be non-negative, got " + count);
                }
                for (long i = 0; i < count; i++) {
                    t.elements.add(lhs);
                }
                return;
            }
            case DupTask.PLAIN_ELEMENT:
                t.elements.add(t.node);
                return;
            case DupTask.FINISH: {
                Token arrayNode = t.node;
                if (!t.sawDup) {
                    return; // nothing to do; leave the chain exactly as-is
                }
                if (t.elements.isEmpty()) {
                    throw new CompilerException("type", arrayNode.file, arrayNode.line,
                            "array literal must have at least one element");
                }
                arrayNode.childs = new ArrayList<>();
                arrayNode.childs.add(rebuildCommaChain(t.elements));
                return;
            }
            default:
                throw new IllegalStateException("unknown dup task " + t.kind);
        }
    }

    /** Queues the elements of an array literal in order; each dup element's lhs is walked before its count is read. */
    private void beginArrayLiteral(Token arrayNode, List<DupTask> out) {
        List<Token> rawElements = collectCommaArgs(arrayNode.childs.get(0));
        List<Token> elements = new ArrayList<>();
        boolean sawDup = false;
        for (Token rawElement : rawElements) {
            if (rawElement.type == TokenType.OPERATOR && rawElement.text.equals("dup") && !rawElement.unary) {
                sawDup = true;
                // the lhs itself may contain nested array literals / dups; expand those first so 'lhs' is fully clean before we copy it N times.
                out.add(new DupTask(DupTask.WALK, rawElement.left));
                DupTask el = new DupTask(DupTask.ELEMENT, rawElement);
                el.elements = elements;
                out.add(el);
            } else {
                DupTask el = new DupTask(DupTask.PLAIN_ELEMENT, rawElement);
                el.elements = elements;
                out.add(el);
            }
        }
        DupTask fin = new DupTask(DupTask.FINISH, arrayNode);
        fin.elements = elements;
        fin.sawDup = sawDup;
        out.add(fin);
    }

    /** The operands of a ',' chain, left to right. */
    private List<Token> collectCommaArgs(Token root) {
        List<Token> out = new ArrayList<>();
        Deque<Token> work = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            Token node = work.pop();
            if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
                work.push(node.right);
                work.push(node.left);
            } else {
                out.add(node);
            }
        }
        return out;
    }

    private Token rebuildCommaChain(List<Token> elements) {
        Token acc = elements.get(0);
        for (int i = 1; i < elements.size(); i++) {
            Token comma = new Token(TokenType.OPERATOR, ",", elements.get(i).line, elements.get(i).file);
            comma.left = acc;
            comma.right = elements.get(i);
            acc = comma;
        }
        return acc;
    }

    /**
     * Unchanged meaning from the old TypeChecker.evalConstantDupCount, with an explicit stack: operands are evaluated
     * in the same order as before (a '/' or '%' reads its right operand and checks for zero before its left).
     */
    private long evalConstantDupCount(Token root) {
        Deque<Object[]> tasks = new ArrayDeque<>();   // {Boolean evalNotCombine, Token node, String op}
        Deque<Long> values = new ArrayDeque<>();
        tasks.push(new Object[]{Boolean.TRUE, root, null});
        while (!tasks.isEmpty()) {
            Object[] task = tasks.pop();
            Token node = (Token) task[1];
            if (!(Boolean) task[0]) {
                String op = (String) task[2];
                switch (op) {
                    case "neg": values.push(-values.pop()); break;
                    case "+": { long r = values.pop(); long l = values.pop(); values.push(l + r); break; }
                    case "-": { long r = values.pop(); long l = values.pop(); values.push(l - r); break; }
                    case "*": { long r = values.pop(); long l = values.pop(); values.push(l * r); break; }
                    case "/": { long l = values.pop(); long r = values.pop(); values.push(l / r); break; }
                    case "%": { long l = values.pop(); long r = values.pop(); values.push(l % r); break; }
                    case "zero?":
                        if (values.peek() == 0) {
                            throw new CompilerException("type", node.file, node.line, "division by zero in 'dup' count");
                        }
                        break;
                    default: throw new IllegalStateException(op);
                }
                continue;
            }
            if (node.type == TokenType.INTEGER) {
                values.push(Long.parseLong(node.text));
                continue;
            }
            if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
                tasks.push(new Object[]{Boolean.TRUE, node.childs.get(0), null});
                continue;
            }
            if (node.type == TokenType.OPERATOR) {
                if (node.unary && node.text.equals("-")) {
                    tasks.push(new Object[]{Boolean.FALSE, node, "neg"});
                    tasks.push(new Object[]{Boolean.TRUE, node.left, null});
                    continue;
                }
                if (!node.unary) {
                    switch (node.text) {
                        case "+": case "-": case "*":
                            tasks.push(new Object[]{Boolean.FALSE, node, node.text});
                            tasks.push(new Object[]{Boolean.TRUE, node.right, null});
                            tasks.push(new Object[]{Boolean.TRUE, node.left, null});
                            continue;
                        case "/": case "%":
                            // right first (and its zero check), then the left
                            tasks.push(new Object[]{Boolean.FALSE, node, node.text});
                            tasks.push(new Object[]{Boolean.TRUE, node.left, null});
                            tasks.push(new Object[]{Boolean.FALSE, node, "zero?"});
                            tasks.push(new Object[]{Boolean.TRUE, node.right, null});
                            continue;
                        default:
                            break;
                    }
                }
            }
            throw new CompilerException("type", node.file, node.line,
                    "'dup' count must be a compile-time constant expression (integer literals and "
                            + "+,-,*,/,% only) -- functions, variables, and anything else are not allowed");
        }
        return values.pop();
    }
}
