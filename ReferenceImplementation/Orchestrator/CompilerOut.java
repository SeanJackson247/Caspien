import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What a library stage returned, read from its Result object: the output text, the stderr-style messages, whether it failed, and (front end) extras. */
final class CompilerOut {
    String text;
    List<String> messages = new ArrayList<>();
    boolean failed;
    List<String> deps;
    Map<String, String> reports = new java.util.HashMap<>();
}
