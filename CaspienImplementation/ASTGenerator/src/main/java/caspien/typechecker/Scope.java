package caspien.typechecker;

import caspien.config.CompilerException;
import caspien.token.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.token.MatchPattern;

public class Scope {
    public final Scope parent;
    public final Map<String, TypeInfo> vars = new HashMap<>();
    /** Names declared via 'let static' in this exact scope -- checkAddressOf's 'static' case needs to know this to accept "raw someStaticVar"-style pointers. */
    public final Set<String> staticNames = new HashSet<>();
    /** Names declared by 'let const' in this scope (local counterpart of TypeInfo.isConst); see isConstName. */
    public final Set<String> constNames = new HashSet<>();
    /** Slot keys made unassignable by a for/for-match loop for its variable and source expression, for the loop body. Unlike constNames, checked along the whole scope chain. */
    public Set<String> ownLoopImmutableSlots;
    /** Walks all parent scopes: a loop's immutability grant covers nested blocks. */
    public boolean isLoopImmutable(String slotKey) { return false; }
    /** ASM names declared inside a function body; lexical, visible only after the declaration is checked. */
    public final Map<String, AsmInfo> localAsmNames = new HashMap<>();
    /** Slots (variable name or "a.b" path) currently holding a moved-away owns value. Shared across all scopes of one function, since moves are not lexically scoped. */
    public final Set<String> movedSlots;
    /** Current safety context ("unsafe"/"safe"/"indeterminate"), inherited and overridable by a nested block. */
    public final String currentSafety;
    /** The tagged 'unsafe' block this scope sits in (set on the scope that block opens, found via parents); null outside any statement-level unsafe block. */
    public UnsafeBlockRecord unsafeBlock;
    /** Proven MatchPatterns, inherited and extended by entering a match block; never mutated. */
    public final List<MatchPattern> activeMatchPatterns;
    /** True inside the base-case match of a @recursive function; checkCall/checkMethodCall reject self-calls there. */
    public final boolean suspendsSelfRecursion;
    /** Owns slots declared directly in this scope, in order (per block, not inherited); dropped at scope end. Filled by checkAssign (lets) and checkFunctionBody (params, in the root scope). */
    public final List<String> ownsDeclaredHere = new ArrayList<>();
    /** The function's outermost scope (itself at the root); a 'return' destructs owns locals up to and including it. */
    public final Scope functionRootScope;
    /** Innermost loop body scope (null outside loops); a 'break' destructs up to and including it. Reset by each nested loop. */
    public final Scope loopBoundaryScope;
    /** Innermost "try { }" body scope (null outside); a 'continue' in a catch body destructs/unlocks up to it and jumps past the try block. Reset by each nested try. */
    public final Scope tryBlockBoundaryScope;
    /** True inside a "for i in dynarrayExpr" body: the dynarray may not be resized, even through another function. Inherited; can only be turned on. */
    public final boolean insideDynArrayForLoopBody;
    /** Owed lock releases (synthesized unlock calls) in acquisition order, inherited and extended by lock/match @lock OPEN bodies; return reads all, break the suffix past the loop boundary. */
    public final List<Token> activeLockReleases;
    public Scope(Scope parent) { }
    /** Used only where movement needs to be tracked in an *isolated* copy (branch-checking) rather than the real, shared set. */
    public Scope(Scope parent, Set<String> movedSlotsOverride) { }
    /** Used only when entering a nested "unsafe"/"safe" block within a function body -- overrides currentSafety for this scope and its descendants. */
    public Scope(Scope parent, String currentSafetyOverride) { }
    /** Entering a match block: extends the proven patterns; suspends self-recursion for the required "RANGE_PARAM is base" match. */
    public Scope(Scope parent, List<MatchPattern> extendedMatchPatterns, boolean suspendsSelfRecursionOverride) { }
    /** One branch of a match/elsematch/else chain: isolated movedSlots copy plus this branch's patterns (a plain else adds none and never suspends recursion). */
    public Scope(Scope parent, Set<String> movedSlotsOverride, List<MatchPattern> extendedMatchPatterns,
            boolean suspendsSelfRecursionOverride) { }
    /** Entering a loop body: this scope becomes the innermost loop boundary. */
    public Scope(Scope parent, boolean isNewLoopBoundary) { }
    /** Entering a "for i in dynarrayExpr" body: loop boundary plus insideDynArrayForLoopBody. */
    public Scope(Scope parent, boolean isNewLoopBoundary, boolean isDynArrayForLoopBody) { }
    /** Entering a lock body or match @lock OPEN body: appends the synthesized release call. */
    public Scope(Scope parent, Token extraLockRelease) { }
    /** match @lock: isolated movedSlots copy plus the OPEN case's release call. */
    public Scope(Scope parent, Set<String> movedSlotsOverride, Token extraLockRelease) { }
    /** Entering a "try { }" body: this scope becomes the innermost try boundary (a marker type avoids an erasure clash with Scope(Scope, boolean, boolean)). */
    public Scope(Scope parent, TryBlockBoundaryMarker ignoredMarker) { }

    public TypeInfo lookup(String name) { return null; }

    public boolean isStatic(String name) { return false; }

    public boolean isConstName(String name) { return false; }

    /** An `unsafe`/`safe` block is a permission marker, not a lexical scope: what it declares belongs to the nearest enclosing real scope. */
    public boolean transparent;

    public Scope declScope() { return null; }

    public void declare(String name, TypeInfo type, Token at) { }
}
