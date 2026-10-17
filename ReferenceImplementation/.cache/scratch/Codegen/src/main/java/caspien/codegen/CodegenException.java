package caspien.codegen;

public class CodegenException extends RuntimeException {
    public CodegenException(String kind, String file, int line, String message) {
        super("[" + kind + " error] " + file + ":" + line + " - " + message);
    }
}
