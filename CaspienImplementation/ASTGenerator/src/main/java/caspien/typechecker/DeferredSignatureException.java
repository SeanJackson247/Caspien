package caspien.typechecker;

import caspien.config.CompilerException;

/** Thrown by buildFuncInfo for a generic-instance method whose signature is illegal only because of the chosen type argument (by-value struct parameter). */
public final class DeferredSignatureException extends CompilerException {
    public final String raw;
    public DeferredSignatureException(String file, int line, String message) { }
}
