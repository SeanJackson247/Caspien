package caspien;

/**
 * Fatal compiler error. The compiler fails hard on the first error it
 * encounters, at any stage, and reports as much context as it can:
 * file, line, and a human-readable message.
 */
public class CompilerException extends RuntimeException {

    public CompilerException(String stage, String file, int line, String message) {
        super(format(stage, file, line, message));
    }

    private static String format(String stage, String file, int line, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(stage).append(" error] ");
        if (file != null) {
            sb.append(file);
            if (line > 0) {
                sb.append(":").append(line);
            }
            sb.append(" - ");
        }
        sb.append(message);
        return sb.toString();
    }
}
