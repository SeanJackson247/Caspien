import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What a library stage returned, read from its Result object: the output text (and program), the stderr-style messages, whether it failed, and (front end) extras. */
final class CompilerOut {
    String text;
    /** The stage's in-memory result program (a caspien.bytecode.BytecodeProgram of the shared loader), when it returns one; else null. */
    Object program;
    List<String> messages = new ArrayList<>();
    boolean failed;
    List<String> deps;
    Map<String, String> reports = new java.util.HashMap<>();
}
