package caspien.optimizer;

import java.util.ArrayList;
import java.util.List;

/** Where passes report "[note]"/"[warning]"/debug lines. Collected, never printed by a pass; the caller decides. */
public final class Messages {
    private final List<String> lines = new ArrayList<>();

    public void add(String line) {
        lines.add(line);
    }

    public List<String> lines() {
        return lines;
    }
}
