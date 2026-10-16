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
    public void expand(List<Token> rootEntries) { }

    /**
     * Walk over every Token field that can hold a nested subtree (left/right/childs/sub), regardless of what kind of
     * node it is -- struct/func/interface/impl declarations, statements, and expressions all get visited uniformly.
     * Whenever a '[' delineator (array literal) is found, its single resolved comma-chain child is expanded in place
     * before the walk goes on (so a dup'd element that is itself an array literal gets its own nested dups expanded
     * too). An explicit task stack, in the order the old recursive walk visited things.
     */
    private void walk(Token start) { }

    private void step(DupTask t, List<DupTask> out) { }

    /** Queues the elements of an array literal in order; each dup element's lhs is walked before its count is read. */
    private void beginArrayLiteral(Token arrayNode, List<DupTask> out) { }

    /** The operands of a ',' chain, left to right. */
    private List<Token> collectCommaArgs(Token root) { return null; }

    private Token rebuildCommaChain(List<Token> elements) { return null; }

    /**
     * Unchanged meaning from the old TypeChecker.evalConstantDupCount, with an explicit stack: operands are evaluated
     * in the same order as before (a '/' or '%' reads its right operand and checks for zero before its left).
     */
    private long evalConstantDupCount(Token root) { return 0L; }
}
