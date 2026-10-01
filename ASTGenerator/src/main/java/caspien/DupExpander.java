package caspien;

import java.util.ArrayList;
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
     * Generic recursive walk over every Token field that can hold a nested
     * subtree (left/right/childs/sub), regardless of what kind of node it
     * is -- struct/func/interface/impl declarations, statements, and
     * expressions all get visited uniformly. Whenever a '[' delineator
     * (array literal) is found, its single resolved comma-chain child is
     * expanded in place before recursing further (so a dup'd element that
     * is itself an array literal gets its own nested dups expanded too, via
     * the ordinary recursion below).
     */
    private void walk(Token node) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.DELINEATOR && "[".equals(node.text) && node.childs != null
                && !node.childs.isEmpty()) {
            expandArrayLiteral(node);
        }
        walk(node.left);
        walk(node.right);
        if (node.childs != null) {
            for (Token c : node.childs) {
                walk(c);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                walk(s);
            }
        }
    }

    private void expandArrayLiteral(Token arrayNode) {
        List<Token> rawElements = new ArrayList<>();
        collectCommaArgs(arrayNode.childs.get(0), rawElements);

        List<Token> elements = new ArrayList<>();
        boolean sawDup = false;
        for (Token rawElement : rawElements) {
            if (rawElement.type == TokenType.OPERATOR && rawElement.text.equals("dup") && !rawElement.unary) {
                sawDup = true;
                Token lhs = rawElement.left;
                // the lhs itself may contain nested array literals / dups;
                // expand those first so 'lhs' is fully clean before we copy
                // it N times.
                walk(lhs);
                Token countExpr = rawElement.right;
                long count = evalConstantDupCount(countExpr);
                if (count < 0) {
                    throw new CompilerException("type", rawElement.file, rawElement.line,
                            "'dup' count must be non-negative, got " + count);
                }
                for (long i = 0; i < count; i++) {
                    elements.add(lhs);
                }
            } else {
                elements.add(rawElement);
            }
        }
        if (!sawDup) {
            return; // nothing to do; leave the chain exactly as-is
        }
        if (elements.isEmpty()) {
            throw new CompilerException("type", arrayNode.file, arrayNode.line,
                    "array literal must have at least one element");
        }
        arrayNode.childs = new ArrayList<>();
        arrayNode.childs.add(rebuildCommaChain(elements));
    }

    private void collectCommaArgs(Token node, List<Token> out) {
        if (node.type == TokenType.OPERATOR && node.text.equals(",")) {
            collectCommaArgs(node.left, out);
            collectCommaArgs(node.right, out);
        } else {
            out.add(node);
        }
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

    /** Unchanged from the old TypeChecker.evalConstantDupCount. */
    private long evalConstantDupCount(Token node) {
        if (node.type == TokenType.INTEGER) {
            return Long.parseLong(node.text);
        }
        if (node.type == TokenType.DELINEATOR && node.text.equals("(") && !node.childs.isEmpty()) {
            return evalConstantDupCount(node.childs.get(0));
        }
        if (node.type == TokenType.OPERATOR) {
            if (node.unary && node.text.equals("-")) {
                return -evalConstantDupCount(node.left);
            }
            if (!node.unary) {
                switch (node.text) {
                    case "+": return evalConstantDupCount(node.left) + evalConstantDupCount(node.right);
                    case "-": return evalConstantDupCount(node.left) - evalConstantDupCount(node.right);
                    case "*": return evalConstantDupCount(node.left) * evalConstantDupCount(node.right);
                    case "/": {
                        long divisor = evalConstantDupCount(node.right);
                        if (divisor == 0) {
                            throw new CompilerException("type", node.file, node.line,
                                    "division by zero in 'dup' count");
                        }
                        return evalConstantDupCount(node.left) / divisor;
                    }
                    case "%": {
                        long divisor = evalConstantDupCount(node.right);
                        if (divisor == 0) {
                            throw new CompilerException("type", node.file, node.line,
                                    "division by zero in 'dup' count");
                        }
                        return evalConstantDupCount(node.left) % divisor;
                    }
                    default:
                        break;
                }
            }
        }
        throw new CompilerException("type", node.file, node.line,
                "'dup' count must be a compile-time constant expression (integer literals and "
                        + "+,-,*,/,% only) -- functions, variables, and anything else are not allowed");
    }
}
