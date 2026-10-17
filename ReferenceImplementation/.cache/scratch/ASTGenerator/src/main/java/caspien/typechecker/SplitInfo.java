package caspien.typechecker;

import java.util.List;
import java.util.Set;

/** What `--fix unsafe-split` needs about one written statement-level `unsafe` block. */
public final class SplitInfo {
    public final String file;
    public final int line;
    public final Set<String> declared;
    public final List<Integer> childLine;
    public final List<Boolean> childAssume;
    public final List<Set<String>> childTags;
    public final Set<String> unattributed;
    public SplitInfo(String file, int line, Set<String> declared, List<Integer> childLine, List<Boolean> childAssume, List<Set<String>> childTags, Set<String> unattributed) {
        this.file = file; this.line = line; this.declared = declared; this.childLine = childLine; this.childAssume = childAssume;
        this.childTags = childTags; this.unattributed = unattributed;
    }
}
