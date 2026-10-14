package caspien.rpnconverter;

import caspien.token.Token;

class OpEntry {
    final Token token;
    final boolean unary;
    OpEntry(Token token, boolean unary) {
        this.token = token;
        this.unary = unary;
    }
}
