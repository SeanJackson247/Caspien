package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared analysis for VariableElisionPass and VariableShiftingPass: for one function, finds the local scalar variables whose every
 * mention is one of exactly three shapes -- their own "ALLOC name T", a read "PUSH name T" (not immediately followed by ADDR_OF, which
 * would take the variable's address), or a constant assignment "ADDR name T / PUSH literal T2 / ASSIGN ..." -- and records where each
 * read and assignment sits. Any other mention (a compound assignment, ++, an address taken, a swap, a struct field, ...) makes the
 * variable "bad": neither pass touches it.
 *
 * Also provides the two control-flow questions both passes ask, answered from the jumps and labels of the function text:
 *   dominated(a, m): can a read at line m be reached without passing line a? (a forward jump sitting before a that lands between a and m)
 *   inLoop(a):       does a backward jump after line a land before it (a is inside a loop)?
 */
final class VarAnalysis {

    private VarAnalysis() { }

    /** [start, end] line indices (FUNC_START .. FUNC_END inclusive) of every function. */
    static List<int[]> functions(BytecodeProgram L) { return null; }

    static String mnemonic(BytecodeStatement line) { return null; }

    static boolean hasMnemonic(BytecodeProgram L, int s, int e, String m) { return false; }

    /** A label definition line is one token "@name:". */
    static Map<String, Integer> labels(BytecodeProgram L, int s, int e) { return null; }

    /** Every "JMP @label" as {lineIndex, targetLabelLineIndex}; a target outside the function is ignored. */
    static List<int[]> jumps(BytecodeProgram L, int s, int e, Map<String, Integer> labels) { return null; }

    static boolean dominated(int a, int m, List<int[]> jumps) { return false; }

    static boolean inLoop(int a, List<int[]> jumps) { return false; }

    /** The scan described in the class comment, for the function spanning lines s..e. */
    static Map<String, VarAnalysisVar> scan(BytecodeProgram L, int s, int e) { return null; }

    /** "ADDR x T" at i, "PUSH literal T2" at i+1 (same base type as x), "ASSIGN ..." at i+2. */
    private static boolean isConstAssign(BytecodeProgram L, int i, VarAnalysisVar v) { return false; }

    static BytecodeStatement withToken(BytecodeStatement line, int idx, String text) { return null; }
}
