package caspien.config;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DocComment comments: `/*! key: text *&#47;` and `//! key: text`. Commentary like any other comment (the compiler never treats the text as code), but
 * elevated: the `!` right after the opening marks it, the key says what it is, and where it may stand is checked.
 * <ul>
 * <li>`justify: ANY TEXT` goes directly before an `unsafe` block; free text, not checked, saying why the programmer had to use unsafe. The
 *     text `TODO` (what `--fix` writes) counts as "not justified yet".</li>
 * <li>`termination: bound|finite|unbound|none|conditional|indirect` goes directly before a `loop`, a `for`, a `func` or a `match @lock` (decorators may stand
 *     in between) and is checked against the termination class the audit computes: a wrong label is a compile error.</li>
 * </ul>
 * A doc comment anywhere else, with an unknown key, or with a bad value is a compile error; an absent one is fine. The Lexer validates and
 * registers each comment under the file and line of the keyword it labels, so later stages look it up there (no token plumbing to lose it).
 */
public final class DocComments {


    public static final List<String> CLASSES = java.util.Arrays.asList("bound", "finite", "unbound", "none", "conditional", "indirect");
    public static final Map<String, DocComment> BY_TARGET = new HashMap<>();

    public DocComments() { }

    public static String norm(String file) { return null; }

    public static String key(String file, int line, String docKey) { return null; }

    /** Registers a validated doc comment; false when the target already has one with this key. */
    public static boolean register(DocComment d) { return false; }

    /** The doc comment with this key on the keyword `kind` at file:line, or null. */
    public static DocComment find(String file, int line, String docKey, String kind) { return null; }

    public static boolean isTodo(String text) { return false; }
}
