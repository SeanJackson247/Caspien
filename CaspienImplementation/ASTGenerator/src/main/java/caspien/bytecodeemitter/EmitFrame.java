package caspien.bytecodeemitter;

import caspien.token.Token;
import caspien.typechecker.StructInfo;
import caspien.typechecker.TypeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One suspended emission on the explicit stack of EmitDriver (the emitter has no recursion): a handler is a `step...` method that runs from
 * `phase` and either finishes (DONE), asks for a nested emission with `call` (resumed afterwards, the finished child in `gotFrame`) or hands over to
 * a successor with `tail`. Pure "lines and nested emissions in a fixed order" handlers are SCRIPT frames (a list of EmitOps). The generic slots
 * (t1.., s1.., b1.., i, n, lists) hold a handler's locals between steps; each handler says what it keeps where.
 */
final class EmitFrame {
    static final int DONE = 0;
    static final int CALL = 1;
    static final int TAIL = 2;

    static final int SCRIPT = 0;
    static final int EXPR = 1;
    static final int ARRAY_LITERAL = 2;
    static final int TRY_GUARDED = 3;
    static final int ASSIGN = 4;
    static final int DESTRUCT_OLD = 5;
    static final int ASSIGN_TARGET = 6;
    static final int RVO_ASSIGN = 7;
    static final int NEW_FROM_RVO = 8;
    static final int NEW = 9;
    static final int INSTANTIATE = 10;
    static final int OWNS_TEMP = 11;
    static final int CLONE = 12;
    static final int DYN = 13;
    static final int RESIZE = 14;
    static final int ASYNC = 15;
    static final int ASSUME_RESOLVES = 16;
    static final int INLINE_NULLOUT = 17;
    static final int SHORT_CIRCUIT = 18;
    static final int CALL_SEQUENCE = 19;
    static final int ARG_TRANSFER = 20;
    static final int ARG_WORD = 21;
    static final int BLOCK = 22;
    static final int IF_CHAIN = 23;
    static final int MATCH_CHAIN = 24;
    static final int MATCH_COND = 25;
    static final int LOOP = 26;
    static final int FOR_LOOP = 27;
    static final int DESTRUCT_LIST = 28;
    static final int UNLOCK_LIST = 29;

    final int kind;
    int phase;
    Token node;
    EmitFrame pending;     // nested emission to run next (CALL) or successor (TAIL)
    EmitFrame gotFrame;    // the nested emission that just finished
    boolean result;        // DESTRUCT_OLD: the target's address was left on the stack

    Token t1;
    Token t2;
    String s1;
    String s2;
    String s3;
    String s4;
    boolean b1;
    boolean b2;
    int i;
    int n;
    List<Token> toks;
    List<Token> toks2;
    List<Token> toks3;
    List<String> strs;
    List<String> strs2;
    List<EmitOp> ops;                          // SCRIPT
    ArgCounters counters;                      // CALL_SEQUENCE, ARG_TRANSFER, ARG_WORD
    StructInfo structInfo;                     // INSTANTIATE, ASYNC
    List<StructLayoutEntry> entries;           // INSTANTIATE, ASYNC
    Map<String, Token> valueByMember;          // INSTANTIATE
    List<Map.Entry<String, TypeInfo>> members; // INLINE_NULLOUT
    ArrayDeque<Token> work;                    // ASSUME_RESOLVES

    EmitFrame(int kind, Token node) { }

    int call(EmitFrame child) { return 0; }

    int tail(EmitFrame child) { return 0; }

    int callExpr(Token expr) { return 0; }

    int callTarget(Token target) { return 0; }

    // ---- SCRIPT building -------------------------------------------------

    static EmitFrame script(Token node) { return null; }

    EmitFrame line(String text) { return null; }

    EmitFrame expr(Token t) { return null; }

    EmitFrame target(Token t) { return null; }

    EmitFrame nullOut(Token t) { return null; }

    EmitFrame require(String decorator, Token at) { return null; }

    EmitFrame destruct(List<String> names, Token at) { return null; }

    EmitFrame unlock(List<Token> unlockCalls, Token at) { return null; }

    EmitFrame block(List<Token> lines, boolean entryPointBody) { return null; }

    EmitFrame rvoForward(Token callOp, String ptrType) { return null; }
}
