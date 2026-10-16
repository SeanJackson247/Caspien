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

    public void buildLines(List<Token> lines) { }

    private void run(TreeTask root) { }

    /** Runs one task; the tasks it needs next are appended to `out` in the order they must run. */
    private void step(TreeTask t, List<TreeTask> out) { }

    private void processStatement(Token lineTok, List<TreeTask> out) { }

    /** Handles an already-gathered if/elseif/else/loop/func/struct/enum/for token. */
    private void processGathered(Token t, List<TreeTask> out) { }

    /**
     * Bottom-up: every nested '('/'[' delineator's flat RPN childs are resolved into a single tree root first.
     * Also handles a struct-literal '{' whose childs (from RpnConverter's resolveStructLiteralMembers) are LINE tokens,
     * one per member assignment: running them through buildLines works unchanged, since a bare "name = value" flat
     * sequence is exactly the shape the ordinary expression-statement path already builds.
     */
    private void resolveNestedGroups(List<Token> tokens, List<TreeTask> out) { }

    private List<Token> singleton(Token t) { return null; }

    /** Standard RPN-to-tree: an operand stack, popping 1 or 2 operands per operator depending on its arity. */
    private Token buildTree(List<Token> rpn, String file, int line) { return null; }

    private int arityOf(Token t) { return 0; }
}
