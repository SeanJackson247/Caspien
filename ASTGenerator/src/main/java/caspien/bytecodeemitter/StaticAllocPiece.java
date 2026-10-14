package caspien.bytecodeemitter;

import caspien.token.Token;

/** One declaration to lay out (`line` null) or one finished line; the struct/array expansion pushes its pieces in reverse so they pop in order. */
final class StaticAllocPiece {
    final String line;
    final String name;
    final String type;
    final Token rhs;

    StaticAllocPiece(String line, String name, String type, Token rhs) {
        this.line = line;
        this.name = name;
        this.type = type;
        this.rhs = rhs;
    }
}
