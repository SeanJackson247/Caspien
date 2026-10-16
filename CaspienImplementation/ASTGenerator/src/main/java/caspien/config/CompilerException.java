package caspien.config;

/**
 * Fatal compiler error. The compiler fails hard on the first error it
 * encounters, at any stage, and reports as much context as it can:
 * file, line, and a human-readable message.
 */
public class CompilerException extends RuntimeException {

    public CompilerException(String stage, String file, int line, String message) { }

    private static String format(String stage, String file, int line, String message) { return null; }
}
