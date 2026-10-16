package caspien.parser;

import caspien.token.Token;

import java.util.List;

/** One activation of a ParserBlocks gatherer on the explicit stack of ParserBlocks.runGather; `stage` says where to resume after a child frame returns. */
final class GatherFrame {
    static final int KEYWORD_BLOCKS = 0;
    static final int FUNC = 1;
    static final int IF_CHAIN = 2;
    static final int MATCH_CHAIN = 3;
    static final int MATCH_CASES = 4;
    static final int LOCK = 5;
    static final int TRY_SPAN = 6;
    static final int CATCH = 7;
    static final int BLOCK_STMT = 8;
    static final int INTERFACE = 9;
    static final int IMPL = 10;
    static final int LIBRARY = 11;
    static final int CONSTRUCTOR = 12;

    final int kind;
    int stage;
    /** The finished frame's answer. */
    Object ret;
    /** The answer of the child frame this one was waiting for, and how many lines that child consumed. */
    Object got;
    int gotConsumed;
    /** Lines consumed by this frame's statement (chains consume several). */
    int consumed = 1;

    // statement list (KEYWORD_BLOCKS, chains, cases)
    List<Token> lines;
    boolean isRoot;
    List<Token> result;
    int i;
    int j;
    Token line;
    List<Token> tokens;
    Token first;
    boolean expanded;
    Token tryGathered;
    Token tok;

    // construct being built
    Token blockTok;
    Token head;
    Token chainTail;
    Token nameTok;
    Token paramsTok;
    Token gathered;
    Token branchKw;
    Token branchBlockTok;
    List<Token> condition;
    List<Token> returnTypeTokens;
    TypeParamParseResult typeParams;
    TypeParamParseResult implTypeParams;
    String text;
    int index;
    boolean flag;

    // loops over member lines
    List<Token> members;
    List<String> names;
    List<Token> flat;
    List<Token> cases;
    List<Token> labels;
    Token caseTok;
    boolean allowDefault;
    Token interfaceNameTok;
    Token concreteNameTok;
    List<List<Token>> interfaceArgs;
    List<List<Token>> concreteArgs;
    boolean lineIsStatic;

    GatherFrame(int kind) { }

    /** Ends this frame with an answer; the driver hands it to the caller. */
    GatherFrame finish(Object answer) { return null; }
}
