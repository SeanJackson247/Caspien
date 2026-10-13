package caspien.typechecker;

import caspien.token.Token;
import caspien.token.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** One written `unsafe ...{` block: what it declares, and (unioned over every time it is checked, e.g. per generic instantiation) what it really used. */
public class UnsafeBlockRecord {
    public final Token tok;
    public final Set<String> declared = new java.util.TreeSet<>();
    public final Set<String> used = new java.util.TreeSet<>();
    /** For `--fix unsafe-split`: per direct child statement its source line, whether it is a bare `assume match`, and the tags it needed. */
    public final List<Integer> childLine = new ArrayList<>();
    public final List<Boolean> childAssume = new ArrayList<>();
    public final List<Set<String>> childTags = new ArrayList<>();
    /** Tags used while no direct child was being checked (the split cannot place them). */
    public final Set<String> unattributed = new java.util.TreeSet<>();
    public int cur = -1;
    public UnsafeBlockRecord(Token tok) { this.tok = tok; }
    public void noteChild(int idx, Token stmt) {
        while (childLine.size() <= idx) {
            childLine.add(0);
            childAssume.add(false);
            childTags.add(new java.util.TreeSet<>());
        }
        childLine.set(idx, stmt.line);
        childAssume.set(idx, stmt.type == TokenType.KEYWORD && stmt.text.equals("assume") && !stmt.hasBlock);
        cur = idx;
    }
    public void use(String tag) {
        used.add(tag);
        if (cur >= 0) {
            childTags.get(cur).add(tag);
        } else {
            unattributed.add(tag);
        }
    }
}
