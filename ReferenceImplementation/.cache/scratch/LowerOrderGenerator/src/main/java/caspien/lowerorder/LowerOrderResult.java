package caspien.lowerorder;

import java.util.List;

/** `lob` = the low-order program text; `messages` = "[info]"/"[note]"/"[warning]" lines, in order (none today). */
public final class LowerOrderResult {
    public final String lob;
    public final List<String> messages;
    /** A stage that cannot finish throws; the field exists so every stage's Result can be read the same way. */
    public final boolean failed = false;

    LowerOrderResult(String lob, List<String> messages) {
        this.lob = lob;
        this.messages = messages;
    }
}
