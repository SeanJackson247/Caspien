package caspien.typechecker;

import caspien.token.Token;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Working state of one type annotation being resolved (see TypeCheckerSignatures.resolveTypeAnnotationRaw); nested annotations get their own frame. */
public final class AnnotationFrame {
    public static final int WAIT_NONE = 0;
    public static final int WAIT_FUNC_PARAM = 1;
    public static final int WAIT_FUNC_RETURN = 2;
    public static final int WAIT_RAW = 3;
    public static final int WAIT_UNSAFE_DYN = 4;
    public static final int WAIT_DYN = 5;

    public List<Token> typeTokens;
    public boolean allowIndeterminateMutability;
    public int i;
    public boolean isConst;
    public String mutability;
    public String storage;
    public boolean isSome;
    public int modifierRank;                       // storage 1, some 2, mutability 3
    public TreeSet<String> floatStates;
    public String baseType;
    public Token baseTypeTok;                      // the visibility check anchors to its file, not `at`
    public final StringBuilder arraySuffix = new StringBuilder();
    public boolean baseIsIncompleteRange;
    public TypeInfo dynArrayElementType;
    public TypeInfo unsafeDynArrayElementType;
    public TypeInfo rawPointeeType;
    public List<TypeInfo> funcPtrParams;
    public TypeInfo funcPtrReturn;
    public Set<String> funcPtrDecorators;
    public String funcPtrCallConventionName;

    // what the frame waits for while a nested annotation is resolved
    public int wait = WAIT_NONE;
    public Token waitTok;                          // paren token of a dynarray / the `func` keyword
    public List<List<Token>> paramSpans;
    public int paramNext;
    public List<Token> returnSpan;

    public AnnotationFrame(List<Token> typeTokens, boolean allowIndeterminateMutability) {
        this.typeTokens = typeTokens;
        this.allowIndeterminateMutability = allowIndeterminateMutability;
    }
}
