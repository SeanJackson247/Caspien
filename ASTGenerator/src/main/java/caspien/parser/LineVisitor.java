package caspien.parser;

import caspien.token.Token;

public interface LineVisitor {
    void visit(Token lineToken);
}
