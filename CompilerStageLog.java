import java.util.ArrayList;
import java.util.List;

/** What a stage printed that a cache hit has to reproduce. */
final class CompilerStageLog {
    final List<String> warnings = new ArrayList<>();
    /** "[note]" lines (what the optimizer did because the source asked for it); replayed on a hit like warnings. */
    final List<String> notes = new ArrayList<>();
}
