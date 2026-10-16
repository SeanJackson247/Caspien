package caspien.rpnconverter;

import caspien.token.Token;
import java.util.List;

/** One work item of RpnConverter's explicit task stack; `kind` is one of the constants below. */
final class RpnTask {
    static final int LINES = 1;          // list: statements to convert, in order
    static final int STMT = 2;           // tok: one statement line
    static final int FINISH_STMT = 3;    // tok = line, list = its flat expression, list2 = leading return/throw tokens
    static final int GATHERED = 4;       // tok: a gathered keyword block
    static final int NEXT = 5;           // tok: continue with tok.next (elseif/else/elsematch chain)
    static final int NESTED = 6;         // list: flat tokens whose nested groups are resolved first
    static final int RPN_CHILDS = 7;     // tok: its .childs become RPN
    static final int RPN_SUB = 8;        // tok: its .sub becomes RPN
    static final int STRUCT_BEGIN = 9;   // tok: a struct-literal '{'
    static final int STRUCT_MEMBER = 10; // tok = the '{', list = member value, list2 = name and separator (or empty), lines = output lines
    static final int STRUCT_END = 11;    // tok = the '{', list = its new lines

    final int kind;
    final Token tok;
    final List<Token> list;
    List<Token> list2;
    List<Token> lines;

    RpnTask(int kind, Token tok, List<Token> list) { }
}
