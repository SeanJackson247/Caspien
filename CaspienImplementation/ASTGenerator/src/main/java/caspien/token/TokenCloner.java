package caspien.token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The one deep copier for token trees (iterative, no recursion). Two flavours:
 * {@link #syntax} copies a gathered, tree-built, not yet type-checked tree (generic expansion, lazily materialised generic methods):
 * the flags and lists the Parser set travel along, `decorators`/`unsafeTags`/`pinnedComments` are shared, the checker's marks
 * (`chk`, `staticApproved`) and the generic-instance bookkeeping (`fromGenericInstance`, `pendingGenericBoundChecks`) start fresh.
 * {@link #checked} copies everything, including `chk`, for an already checked statement (the recursive-function lowering).
 * A shared sub-tree stays shared inside the copy. `tests/token_clone_check.sh` fails when a field of Token or TokenChecked is not handled here.
 */
public final class TokenCloner {

    private final IdentityHashMap<Token, Token> made = new IdentityHashMap<>();
    private final ArrayDeque<Token> pending = new ArrayDeque<>();
    private final boolean checked;

    private TokenCloner(boolean checked) { }

    public static Token syntax(Token root) { return null; }

    public static Token checked(Token root) { return null; }

    private Token run(Token root) { return null; }

    /** The copy of `src`, created (and queued to be filled) on first sight. */
    private Token shell(Token src) { return null; }

    private Token t(Token x) { return null; }

    private List<Token> tl(List<Token> l) { return null; }

    private static <T> List<T> copy(List<T> l) { return null; }

    private void fill(Token s, Token d) { }

    private void fillChecked(TokenChecked s, TokenChecked d) { }
}
