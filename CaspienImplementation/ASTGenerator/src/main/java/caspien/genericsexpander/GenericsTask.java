package caspien.genericsexpander;

import caspien.token.Token;

import java.util.List;

/** One unit of work of the GenericsExpander task stack (replaces its recursion; see GenericsExpander.run). */
final class GenericsTask {
    // walking the tree
    static final int WALK = 0;
    static final int WALK_NO_TOP = 1;
    // resolving one generic token
    static final int PROCESS = 2;
    static final int PROC_DYN = 3;
    static final int PROC_ARG = 4;
    static final int SET_MANGLED = 5;
    // instantiation follow-ups
    static final int INST_IMPLS = 6;
    static final int IMPL_INST = 7;
    static final int IMPL_ADD = 8;
    // annotation type-span scanning
    static final int SPAN = 9;
    static final int SPAN_ANGLE_POST = 10;
    static final int SPAN_PAREN_POST = 11;

    final int kind;
    final Token tok;
    List<Token> span;
    List<List<Token>> groups;
    int index;
    int closeIdx;
    int phase;
    List<String> argNames;
    List<Token> argTokens;
    String name;
    String mangled;

    GenericsTask(int kind, Token tok) { }
}
