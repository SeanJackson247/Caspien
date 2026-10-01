package caspien.codegen;

/** The one exception type used for every fatal codegen error -- mirrors caspien-compiler's own CompilerException. */
public class CodegenException extends RuntimeException {
    public CodegenException(String kind, String file, int line, String message) {
        super("[" + kind + " error] " + file + ":" + line + " - " + message);
    }
}
