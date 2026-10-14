package caspien.parser;

import caspien.token.Decorator;
import caspien.token.Token;

import java.util.List;

/** One unit of work of ParserLines' tree walkers (replaces their recursion; see ParserLines.runTasks). */
final class ParserTask {
    static final int RW_LINES = 0;
    static final int RW_FLAT = 1;
    static final int RW_PAREN = 2;
    static final int MT_LINES = 3;
    static final int MT_FLAT = 4;
    static final int CG_LINES = 5;
    static final int CG_FLAT = 6;
    static final int CG_FINISH = 7;
    static final int IC_LINES = 8;
    static final int IC_FLAT = 9;
    static final int TPL = 10;
    static final int SPLIT_FLAT = 11;
    static final int VISIT_LINES = 12;
    static final int VISIT_ONE = 13;
    static final int SCAN_BLOCKS = 14;
    static final int DEC = 15;
    static final int DEC_SCAN = 16;

    final int kind;
    List<Token> list;
    Token tok;
    int i;
    int j;
    int closeIdx;
    int phase;
    List<Token> result;
    List<Token> segments;
    List<Token> inner;
    Token brace;
    List<Decorator> pending;
    List<List<Token>> groups;
    LineVisitor visitor;

    ParserTask(int kind, List<Token> list) {
        this.kind = kind;
        this.list = list;
    }
}
