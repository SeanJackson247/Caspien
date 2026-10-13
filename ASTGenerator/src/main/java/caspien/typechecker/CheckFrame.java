package caspien.typechecker;

import caspien.token.Token;

/**
 * One suspended check on the explicit stack of CheckDriver (the type checker has no recursion): a handler is a `step` method that runs from
 * `phase`, and either finishes (`done`) or asks for a nested check (`call...`) and is resumed with that check's result in `got`.
 * The generic slots a..d, x, y, p, q, str, data hold the handler's locals between steps; each handler says what it keeps where.
 */
public final class CheckFrame {
    public static final int DONE = 0;
    public static final int CALL = 1;

    public static final int EXPR = 1;
    public static final int COMMA = 2;
    public static final int ARITH = 3;
    public static final int UNARY_MINUS = 4;
    public static final int LOGICAL_NOT = 5;
    public static final int COMPARISON = 6;
    public static final int LOGICAL = 7;
    public static final int MUTABILITY_OF = 8;
    public static final int RANGE = 9;
    public static final int INCREMENT = 10;
    public static final int REQUIRE_MUTABLE = 11;
    public static final int EXPR_LIST = 12;
    public static final int STATIC_METHOD_CALL = 13;
    public static final int INSTANCEOF = 14;
    public static final int IMPLEMENTS = 15;
    public static final int AS = 16;
    public static final int ARRAY_LITERAL = 17;
    public static final int LOOKUP = 18;
    public static final int ASSIGN = 19;
    public static final int COMPOUND_ASSIGN = 20;
    public static final int LET_ASSIGN = 21;
    public static final int LOCK_MATCH = 22;
    public static final int SUBST_POLICY = 23;
    public static final int DOT = 24;
    public static final int ATOMIC_WRAPPER = 25;
    public static final int SWAP = 26;
    public static final int INSTANTIATE = 27;
    public static final int NEW = 28;
    public static final int AWAIT = 29;
    public static final int PAR = 30;
    public static final int IN = 31;
    public static final int GUARANTEED_ENUM = 32;
    public static final int WITHIN = 33;
    public static final int ADDRESS_OF = 34;
    public static final int SOME_BUILTIN = 35;
    public static final int SIZEOF_BUILTIN = 36;
    public static final int RANGE_BUILTIN = 37;
    public static final int DEREF_BUILTIN = 38;
    public static final int CLONE_BUILTIN = 39;
    public static final int CONVERT_BUILTIN = 40;
    public static final int LEN_BUILTIN = 41;
    public static final int CALL_BUILTIN = 42;
    public static final int BITS_BUILTIN = 43;
    public static final int MEMCOPY_BUILTIN = 44;
    public static final int DYN_BUILTIN = 45;
    public static final int RESIZE_BUILTIN = 46;
    public static final int CALL_EXPR = 47;
    public static final int EXTERN_CALL = 48;
    public static final int SLEEP_CALL = 49;
    public static final int BUILTIN_CALL = 50;
    public static final int METHOD_CALL = 51;
    public static final int GENERIC_METHOD_CALL = 52;
    public static final int INTERFACE_DISPATCH = 53;
    public static final int UNSAFE_DYN_WRAPPER = 54;
    public static final int LIBRARY_CALL = 55;
    public static final int FUNCTION_BODY = 56;
    public static final int LINES = 57;
    public static final int LOOP_BODY = 58;
    public static final int LINES_IN_SCOPE = 59;
    public static final int STATEMENT = 60;
    public static final int TRY_BLOCK = 61;
    public static final int MATCH_CHAIN = 62;
    public static final int ASSUME_COND = 63;
    public static final int ASSUME_FIELD = 64;
    public static final int RECURSIVE_LOWER = 65;
    public static final int IF_CHAIN = 66;
    public static final int CASE_MATCH = 67;
    public static final int ENUM_FOR_MATCH = 68;
    public static final int ENUM_MATCH = 69;
    public static final int FLOAT_MATCH = 70;
    public static final int FOR_LOOP = 71;
    public static final int MATCH_CONDITION = 72;
    public static final int SOME_INDEX_BOUNDS = 73;
    public static final int RETURN = 74;
    public static final int THROW = 75;
    public static final int TRY = 76;
    public static final int LOWER_ENUM_FOR = 77;
    public static final int LOWER_ENUM_MATCH = 78;
    public static final int ASYNC_CALL_SITE = 79;
    public static final int ASYNC_TRAMPOLINE = 80;
    public static final int ASYNC_DROP_HOOK = 81;
    public static final int INSTANTIATE_METHOD = 82;

    public final int kind;
    public int phase;
    public Token node;
    public Scope scope;
    public FuncInfo func;

    public TypeInfo got;      // result of the nested check this frame asked for
    public TypeInfo result;   // this frame's own result, once done
    public CheckFrame pending;                 // nested check to run next
    public CheckFrame gotFrame;                // the finished nested check itself (for results beyond one TypeInfo)

    public Scope sc1;
    public Scope sc2;
    public TypeInfo a;
    public TypeInfo b;
    public Token x;
    public Token y;
    public boolean p;
    public boolean q;
    public String s1;
    public String s2;
    public int i;
    public java.util.List<Token> toks;
    public java.util.List<TypeInfo> list;   // EXPR_LIST result
    public Object data;
    public Object out;                          // result of a frame whose value is not a TypeInfo

    public CheckFrame(int kind, Token node, Scope scope, FuncInfo func) {
        this.kind = kind;
        this.node = node;
        this.scope = scope;
        this.func = func;
    }

    /** resolveExprType(expr) on this frame's scope and function. */
    public int call(Token expr) {
        pending = new CheckFrame(EXPR, expr, scope, func);
        return CALL;
    }

    /** resolveExprType of each expression in order; the types are `list` of the finished frame (see gotFrame). */
    public int callAll(java.util.List<Token> exprs) {
        CheckFrame c = new CheckFrame(EXPR_LIST, node, scope, func);
        c.toks = exprs;
        pending = c;
        return CALL;
    }

    public int call(CheckFrame child) {
        pending = child;
        return CALL;
    }

    public int done(TypeInfo r) {
        result = r;
        return DONE;
    }
}
