package caspien.typechecker;

import caspien.token.Token;
import java.util.List;

/** Locals of one CLOSED:default substitution, carried between phases. */
final class SubstState {
    StructInfo structInfo;
    Token defaultRef;
    List<Token> builtinArgs;
    List<Token> callArgNodes;
    List<NameType> policyParams;
    Scope preLoopScope;
    List<Token> preLoopInit;
}
