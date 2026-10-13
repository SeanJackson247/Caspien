package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bookkeeping for one generated pointer routine. Every `owns` value the routine clones is stored in a hidden local (a
 * "leaf temp", zero at entry) before anything is assembled. If any later allocation fails, `failLabel` destructs all of
 * those locals (the drop pass expands each `GT_DESTRUCT` recursively and null-safely) and returns null, so a failed
 * clone leaves nothing allocated. `failLabel` null means a by-value routine, which cannot return null: a failed member
 * simply stays null.
 */
final class CloneGenerationCloneCtx {
    final String failLabel;
    final List<String> tempNames = new ArrayList<>();
    final List<String> tempTypes = new ArrayList<>();
    final Map<String, String> tempOfPath = new HashMap<>();

    CloneGenerationCloneCtx(String failLabel) {
        this.failLabel = failLabel;
    }
}
