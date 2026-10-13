package caspien.token;

import java.util.ArrayList;
import java.util.List;

/**
 * What the TypeChecker (and a few emitter helpers) record on a token for the BytecodeEmitter: resolved types and call targets,
 * lowering flags, ownership/proof marks. Reached as `token.chk`; the parser and the earlier stages never touch it.
 */
public final class TokenChecked {

    /**
     * Set by TypeChecker on a "CALL" operator node once overload/method
     * resolution picks a specific concrete function -- the mangled name
     * BytecodeEmitter should actually target. Needed because a method
     * call's CALL node has a '.' node as its left (receiver.methodName),
     * not a plain function-name VARREF the way an ordinary call does,
     * so there's no single token text BytecodeEmitter could read the
     * target off of uniformly without this. Set for *every* CALL node,
     * including ordinary ones (whose mangled name is just their plain
     * name), so BytecodeEmitter has one single, uniform place to read
     * the call target from either way.
     */
    public String resolvedCallTarget;

    /**
     * Set on every CALL-shaped node (ordinary/recursive call, extern
     * call, and the "call()" builtin/INVOKE alike) to the calling
     * convention that call site actually resolved to -- "@call_convention"
     * on the target func/extern's own declaration, or, for an INVOKE
     * (the callee isn't statically known at all -- only its
     * function-pointer *type* is), whatever that type's own
     * "@call_convention" resolved to; compiler.config's own declared
     * default when neither is present. Read directly by
     * BytecodeEmitter.emitCallSequence -- never re-derived or re-looked-
     * up there, same "narrow, purpose-built field set once during
     * checking" discipline resolvedCallTarget/resolvedType/etc. already
     * follow.
     */
    public String resolvedCallConvention;

    /** Populated during type checking. */
    public String resolvedType;

    /** True on an 'unsafe' block the compiler wrote itself (e.g. an async trampoline): exempt from the tag rules. */
    public boolean synthesizedUnsafe;

    /**
     * Set only on a "CALL" OPERATOR token (never any other node shape),
     * by TypeChecker.checkTry, immediately before it recursively
     * type-checks that exact call node -- true only for the one call
     * directly wrapped by a "try ... catch { ... }" ("try foo() catch
     * { ... }": this flag is set on the CALL node for "foo()", not on
     * the "try" token itself, which has its own `sub`/`childs`
     * instead). Read back by every one of TypeChecker's call-
     * resolution sites (checkCall, checkLibraryCall, checkStaticMethodCall,
     * checkImplMethodCall, and the interface-dispatch call site) once
     * the callee is known, to enforce the "@throws" contract both
     * directions: a call to an "@throws" function with this flag unset
     * is a hard error (missing "try"/"catch"), and a call with this
     * flag set whose callee is *not* "@throws" is equally an error
     * (pointless "try" around something that can never throw). See
     * TypeChecker.requireThrowsWrapping.
     */
    public boolean insideTry;

    /** Set by TypeChecker.checkNew on the constructor CALL node directly under a `try`-covered `new X(...)`: that one `try` covers the constructor's own throw as well as the allocation (there is no syntax to wrap the inner call separately), so a `@throws` constructor needs no `try` of its own there. See TypeChecker.requireThrowsWrapping. */
    public boolean coveredByThrowingNew;

    /** Set by TypeChecker on a try token whose catch body mentions `<e>.stack_trace`: the emitter then keeps a handler-local copy of the trace. */
    public boolean catchUsesStackTrace;

    /** Set by TypeChecker on a `.` token that is `<catch parameter>.msg` or `.stack_trace`: the member name; `catchTry` is the owning try token. */
    public String catchMember;

    public Token catchTry;

    /**
     * "compiler generated... ASSIGN a null... immediately after that
     * move," confirmed directly -- set by TypeChecker.markMovedIfOwned
     * directly on the moved-*from* expression node itself (the same
     * node `slotKeyOf` already resolves a real, nameable slot from --
     * this stays null for a move whose own source has no such slot at
     * all, e.g. a fresh `new`/call-result consumed directly, since
     * there's nothing to null out there in the first place).
     * BytecodeEmitter reads this at each of the handful of places a
     * move can happen (assignment, a call argument, a struct-literal
     * field) to emit the null-out at the correct point -- *before* the
     * value is actually handed off for a call argument specifically
     * (right after the PUSH/POP that captures it, before the CALL that
     * could throw), never after; see CLAUDE.md's own note on this for
     * why "after" is unsound. Every consumer of this flag needs a
     * genuinely *nameable* source, not just a `TypeInfo` -- exactly
     * what `slotKeyOf` already exists to answer.
     */
    public boolean isOwnershipMoveSource;

    /** Set (to the struct's name) with `isOwnershipMoveSource`, or on an assignment target, when the value is an inline struct that owns memory: moving it nulls every owned member, assigning over it drops the old members first. */
    public String inlineOwnsStruct;

    /**
     * Set on an "INSTANTIATE" node by `TypeChecker.checkInstantiate`
     * when this struct literal is the new, deliberate OTC ("One True
     * Construction") violation this project's locked-struct feature
     * allows: the struct is `@lock(match self.x : X)`-tagged, and the
     * literal provides *only* the lock/discriminant field's own value
     * -- a direct, literal `EnumName.Variant` reference already proven
     * (at type-check time) to NOT satisfy the lock -- with every other
     * member deliberately omitted, since the struct's own existing
     * lock-proof mechanism already makes them permanently unreadable.
     * Read by `BytecodeEmitter.emitInstantiate`, which for this one
     * case pushes only the lock field's own value (never iterating the
     * struct's full declared member list the ordinary, ordinary-OTC
     * path does) and emits a trailing "STACK_LOCK &lt;structName&gt;"
     * line instead of the ordinary per-member push sequence.
     */
    public boolean isLockViolationConstruct;

    /**
     * Set on a "CALL" node by `TypeChecker.checkCall` when this call
     * targets a function whose return type is a plain struct value (no
     * storage modifier) -- Return Value Optimization: "returning structs
     * on the stack by manipulating a hidden struct on the stack in the
     * parent frame," confirmed directly. Only ever set once `checkCall`
     * has also confirmed this call sits in one of the two positions
     * that's legal for it (a 'let' binding's own right-hand side, or a
     * plain assignment's right-hand side with a bare-variable target --
     * see `TypeChecker.insideStructRvoDestinationPosition`'s own doc
     * comment for exactly why those two and nothing else, for now).
     * Read by `BytecodeEmitter.emitAssign`, which for this one case
     * emits a completely different sequence than an ordinary call
     * result: no `PUSH_RET`/`ASSIGN` copy at all -- instead, the callee
     * is handed the destination's own address directly, as an implicit
     * leading argument, and writes the struct's fields into it directly
     * (see `BytecodeEmitter.emitStructRvoAssign`).
     */
    public boolean isStructRvoCall;

    /**
     * Set on a "CALL" node (the "dyn" builtin's own CALL) by
     * `TypeChecker.checkDynBuiltinCore` when this call's single argument
     * resolved to a string, not an array literal -- "Have dyn also take
     * a static imut string, returning a dyn of chars," confirmed
     * directly. Read by `BytecodeEmitter`'s own "dyn" case to choose the
     * "NEW_FROM_STRING" emission shape (push the string value, then one
     * runtime-character-copying instruction) instead of the ordinary
     * per-array-element push sequence "dyn([...])" always used before
     * this.
     */
    public boolean dynFromStringSource;

    /**
     * Set on a "CALL" node (the exact same node `resolvedCallTarget`
     * lives on) by `requireRecursiveCallReturnSiteOnly` once it's
     * confirmed this self-call is both the sole expression of its own
     * enclosing 'return' statement and properly 'within'-wrapped --
     * i.e. this is a legal '@recursive' tail-call site. Read back later
     * by `lowerRecursiveFunctionToLoop`'s own tree walk
     * (`replaceRecursiveReturnsInLines`) to find exactly which 'return'
     * statements need rewriting into the iterative loop form, without
     * re-deriving any of the restrictions that already proved this call
     * site legal in the first place.
     */
    public boolean isRecursiveTailCallSite;

    /**
     * Set on an "in" operator Token itself by `TypeChecker.checkIn`
     * (never on `left`/`right`) when this specific "in" is the new
     * "plain u64 in GuaranteedEnumName" shape -- "guaranteed" meaning
     * an enum whose every variant carries an explicit discriminant
     * value (`EnumInfo.explicitValues != null`). Distinguishes this
     * shape from the ordinary range/dynarray "in" at every later
     * stage that needs to treat it differently, most importantly
     * `BytecodeEmitter`'s own "in" case: the right side here is a bare
     * enum *type* name, never a value expression, so it must be
     * emitted the same "push the name itself, don't evaluate it as an
     * expression" way `checkInstanceof`'s own right operand already
     * is, not walked through the ordinary `emitExpr` an rvalue "in"'s
     * right side gets.
     */
    public boolean isGuaranteedEnumMembershipCheck;

    /**
     * "u64 in string" shape -- a plain u64 byte/char-code membership
     * check against a string's own bytes. Distinguishes this shape from
     * the ordinary range/dynarray "in" at every later stage that
     * needs to treat it differently, most importantly `BytecodeEmitter`'s
     * own "in" case: unlike the ordinary shape (a numeric bounds/length
     * comparison against values already resolvable from the operands
     * alone), this one needs a real runtime byte-by-byte scan (the
     * string has no stored length -- see `strlen`/the `LEN_SCAN`
     * precedent for the identical "no stored length, must scan" shape
     * an unsafe dynarray's own `len()` already has), so it's emitted
     * under its own dedicated mnemonic ("IN_SCAN"), never plain "IN".
     */
    public boolean isStringMembershipCheck;

    /** Set by checkAs (`x as f64`) and checkConvertBuiltin (`wrap:<f32>(x)`, `wrap:<f64>(x)`) for an f32<->f64 conversion: emits FCONV srcType dstType. */
    public boolean isFloatConvert = false;

    /** Set by checkAs when a narrowing cast was authorized by an active "x fits T" proof: emits TRUNC. */
    public boolean isTruncCast = false;

    /** For a "fits" match condition: the checked value's integer base type, and the target integer base type. */
    public String fitsSourceBase;

    public String fitsTargetBase;

    /**
     * "as" shape only -- true for the zero-cost alias-relabel case
     * (`value as SomeAlias`, legal only when the two sides' fully
     * resolved storage+baseType are already structurally identical --
     * see `checkAs`'s alias branch). Set by `checkAs`, read by
     * `BytecodeEmitter`'s own `emitAs`: this case emits no instruction
     * at all, the same "zero bytecode presence" treatment the `mut`/
     * `imut` MODIFIER case an earlier revision of `emitAs` also had
     * (since removed as dead code -- `mut`/`imut` no longer parses as the
     * right-hand side of `as` at all; see `TypeChecker.checkAs`'s own doc
     * comment), since a pure compile-time rename between two
     * identically-represented types has nothing for a runtime instruction
     * to do. False for the other "as"
     * shape (same-family integer widening), where `isSignedWideningCast`
     * below is the one that matters instead.
     */
    public boolean isAliasCast;

    /**
     * "as" shape only, meaningless when `isAliasCast` is true -- true
     * when the same-family integer-widening cast's family is signed
     * (`leftType.baseType.charAt(0) == 's'`), false when unsigned. Set
     * by `checkAs`, read by `BytecodeEmitter`'s own `emitAs` to choose
     * "SEXT" (sign-extend) or "ZEXT" (zero-extend) in place of the old,
     * single "CAST" mnemonic -- "cast just being instances where the
     * compiler can unambiguously do it," confirmed directly, so both new
     * mnemonics stay scoped to exactly what `checkAs` already supports
     * today (no pointer/float casts -- those are planned as stdlib
     * functions instead, out of scope here).
     */
    public boolean isSignedWideningCast;

    /**
     * "par or await would be mandatory," confirmed directly -- set on a
     * "CALL" Token itself (never on the "await"/"par" wrapper) by
     * checkAwait/checkPar, immediately before delegating to the
     * ordinary checkCall dispatch, so checkCall can enforce both
     * directions of the "@async" requirement itself: calling an
     * "@async" function without this set is rejected, and setting this
     * on a call to a function that *isn't* "@async" is rejected too.
     * Also read directly by BytecodeEmitter's own emitCall, to decide
     * between plain "CALL" and "AWAIT_CALL"/"PAR_CALL" -- "AWAIT" or
     * "PAR", or null for an ordinary call.
     */
    public String asyncCallKind;

    /**
     * Set on a "CALL" Token by TypeChecker.checkSleepCall, the moment a
     * "sleep(...)" keyword call site is resolved -- read by
     * BytecodeEmitter.emitCallOrBuiltin to route this call through
     * emitSleepCall (a dedicated "SLEEP <realFuncName> <durationType>"
     * bytecode line, not an ordinary "CALL") instead of the generic
     * "CALL"/"EXTERN_CALL" path every other call site (including
     * "@par_call"/"@await_call"'s own real function calls) still uses.
     * "<realFuncName>" is `resolvedCallTarget`'s own value (the real
     * "@sleep"-decorated function's mangled name) carried directly on
     * the "SLEEP" line itself as a plain operand -- never a hardcoded
     * literal the codegen stage has to already know, the same
     * "resolved dynamically, never guessed" property "@par_call"/
     * "@await_call" already have, just made visible in the bytecode
     * text itself here rather than only in `resolvedCallTarget`.
     */
    public boolean isSleepCall;

    /**
     * Set on a "CALL" Token by the compiler-synthesized async trampoline
     * body (see TypeChecker's trampoline synthesis for "par"/"await")
     * for its own single, direct call into the real "@async"-decorated
     * function it wraps -- this is the one legitimate place a plain,
     * un-"par"/"await"-wrapped call to an "@async" function is allowed:
     * checkCall's ordinary "'@async' can only be called through par or
     * await" rejection is bypassed exactly here, and only here, since
     * this call site genuinely *is* the thread body actually running
     * that function, not a second, illegitimate direct invocation of
     * it. Never set by anything else, never read anywhere but that one
     * check.
     */
    public boolean isTrampolineOwnCall;

    /**
     * Set on the same "CALL" Token as asyncCallKind, by checkPar/
     * checkAwait, once the async handle type this specific call site
     * needs (see TypeChecker.getOrCreateAsyncHandleType) is known --
     * the struct's own bare name (asyncHandleStructName) and its full
     * canonical type text (asyncHandleCanonicalType, "owns_some_..."),
     * both read directly by BytecodeEmitter's own emitAsyncCall to
     * construct/consume the handle at the call site, entirely at the
     * bytecode-emission level (no further Token-tree synthesis needed
     * for this part).
     */
    public String asyncHandleStructName;

    public String asyncHandleCanonicalType;

    /**
     * Set alongside asyncHandleStructName/asyncHandleCanonicalType, by
     * TypeChecker.prepareAsyncCallSite: the real, already-synthesized
     * trampoline function's own name for *this* call site (see
     * TypeChecker.synthesizeAsyncTrampoline's own "selfDestructHidden"
     * parameter) -- ordinarily just "__trampoline_<realFuncName>", the
     * same one every "par"/"await" call site against a given "@async"
     * function shares, EXCEPT for a void "@async" function's own "par"
     * call sites, which get their own distinct, self-destructing
     * trampoline instead (see BytecodeEmitter.emitAsyncCall's own doc
     * comment on the void-"par" handle-cleanup problem this solves).
     * BytecodeEmitter reads this directly rather than re-deriving a
     * trampoline name from op.resolvedCallTarget, since the two can now
     * genuinely differ for the same underlying "@async" function.
     */
    public String asyncTrampolineName;

    /**
     * Set by TypeChecker.checkLookup on a "LOOKUP" node -- true when this
     * exact access relied on a live "in"/"into" bounds proof rather than
     * being trivially safe from compile-time-known facts alone (a
     * literal index into a target whose length is baked into its own
     * type). Read by requireLookupWriteAuthorized (checkAssign/
     * checkCompoundAssign) to decide whether writing through this "[]"
     * additionally needs a write-specific "into" proof -- a literal,
     * provably-safe index never does, matching how it never needed an
     * "in" proof to be read either.
     */
    public boolean isLookupProofReliant;

    /**
     * Set only on an "in"/"into" OPERATOR node whose own `.left` is a
     * "Some(...)" CALL -- the "match Some(i) in/into x{...}" sugar
     * (TypeChecker.checkSomeIndexBoundsCondition). Holds the already
     * type-checked, throwaway "origin[i]" LOOKUP node this shape's own
     * inner element-alive proof was built and validated against, so
     * BytecodeEmitter can emit its own real "GT_ALIVE_CHECK" for it
     * directly (`emitExpr(someIndexElementExpr); line("GT_ALIVE_CHECK");`),
     * without needing to re-derive/re-unwrap "range(origin)" targets a
     * second time on the emitter side. Null for every other node.
     */
    public Token someIndexElementExpr;

    /**
     * Nullable `ref` values are 64-bit ids (ghost table `gt_ref_id`), `ref some` values and every proven binding are addresses.
     * Set by TypeChecker, acted on by BytecodeEmitter.emitExpr:
     * `refToId`: the expression yields an address (`ref some`, `owns`, or `ref x` of an owns value) that is stored where a nullable `ref` is expected: append GT_REF_ID.
     * `refToRaw`: the expression is a nullable `ref` (an id) used where a `raw` pointer is expected: append GT_REF_RESOLVE.
     * `refProofKey`: the expression is the subject of an alive proof (key of the covering `match Some`); a nullable `ref` is then read from the proof's hidden address slot instead of being an id.
     * `someOnRef`: a `Some(x)` call (or the index-element lookup of a `Some(i) in` match) whose subject is a nullable `ref`: resolve the id instead of probing an address.
     */
    public boolean refToId;

    public boolean refToRaw;

    public String refProofKey;

    /** With `refProofKey`: the proven subject is a nullable `ref` (an id), so the emitter reads the proof's address (or resolves the id inline when the proof was only assumed). */
    public boolean refProofIsId;

    public boolean someOnRef;

    /** On a `Some(x)` call (or the index-element lookup of `Some(i) in`) that is a match condition: the proof key under which the resolved address is kept. */
    public String someProofKey;

    /** Null unless this is a "match" KEYWORD token that's already been type-checked. */
    public List<Token.MatchPattern> matchPatterns;

    /**
     * Set by TypeChecker on a "CALL" node for the "sizeof"/"len"
     * builtins -- the compile-time-computed constant value, structurally
     * separate from resolvedType's text (never parsed back out of it).
     * BytecodeEmitter reads this directly to emit "PUSH value u64"
     * instead of an ordinary CALL -- "gets me the integer value, direct
     * replacement in bytecode - no instruction," confirmed directly.
     */
    public Long builtinConstantValue;

    /**
     * Set by TypeChecker on a "sizeof" CALL node whose argument is a struct (or an array of structs): the
     * struct's name. A struct's real size includes the hidden ___type field and alignment padding, and the
     * struct member reordering pass may still change the layout, so the front end does NOT fold it:
     * BytecodeEmitter emits the symbolic `SIZEOF <name> <type>` and the Optimizer's SizeofResolutionPass
     * replaces it with `PUSH <laid-out size> <type>` once the layout is final. builtinConstantValue keeps
     * only a rough estimate (used for decisions such as "is the pointee wider than one byte").
     */
    public String sizeofStruct;

    /** Like sizeofStruct, for the scale of C-style pointer arithmetic on a raw pointer to a struct (see pointerScale). */
    public String pointerScaleStruct;

    /**
     * C-style pointer arithmetic on a 'raw' pointer: set by TypeChecker on a
     * "++"/"--"/"+="/"-="/binary "+"/"-" node whose pointer operand's pointee is
     * wider than one byte -- the pointee size in bytes, by which
     * BytecodeEmitter scales the integer step/operand (0 = no scaling, the
     * node is ordinary integer arithmetic or a byte-sized pointee).
     */
    public long pointerScale;

    /** With pointerScale/binary "+"/"-": 1 = pointer +/- integer, 2 = integer + pointer, 3 = pointer - pointer (element count). 0 = not pointer arithmetic. */
    public int pointerArithKind;

    /** Set by TypeChecker on a "CALL" node for the "call" builtin -- the number of arguments passed (not counting the function-pointer argument itself), read by BytecodeEmitter to emit "INVOKE n". */
    public Integer invokeArgCount;

    /**
     * "When an owns value leaves scope, we need it to be deleted... GT_
     * DESTRUCT my_owns," confirmed directly. Set by TypeChecker at every
     * point control can leave a scope holding live 'owns' slots -- a
     * block's own natural closing point (on the block/loop/function's
     * own token), and separately on any 'return'/'break' statement that
     * jumps out early -- in the exact order BytecodeEmitter should emit
     * GT_DESTRUCT for each (reverse declaration order, already excluding
     * anything moved or, for 'return' specifically, the exact slot being
     * returned). Null/absent wherever nothing needs destructing.
     */
    public List<String> destructOnExit;

    /**
     * "guarantees the unlock() call happens on every exit path from the
     * block -- natural end, return, break," confirmed directly for
     * "lock EXPR{...}" and, separately, for a "match @lock EXPR{...}"
     * spin-lock's own OPEN case body -- the identical "things owed on
     * scope exit" unwind-list treatment destructOnExit already gets for
     * 'owns' locals, adapted here to call unlock() (or, for a spin-lock,
     * swap back to the CLOSED variant) instead of GT_DESTRUCT. Each
     * entry is an already fully type-checked, compiler-synthesized CALL
     * (or ATOMIC_SWAP-producing) expression node -- built once, then
     * reused by reference at every exit point that owes it, the same
     * "reuse the original, already-resolved token directly" precedent
     * an enum-match's own if-chain lowering already established for its
     * own condition clones. Null/absent wherever nothing is owed.
     */
    public List<Token> unlockOnExit;

    /**
     * Set only on the two ".field" DOT nodes `checkLockMatchStatement`
     * itself builds (the acquire attempt's own left operand, and the
     * release assignment's own left operand) -- the one, narrow
     * exemption from the "a 'swap'-tagged struct field can only ever be
     * touched through its own 'match @lock', or from 'unsafe' code"
     * restriction (checkDot's own requireSwapFieldAccessAuthorized): a
     * "match @lock" statement's own compiler-generated ATOMIC_SWAP/
     * release access is exactly the mediated access this restriction
     * exists to require, so it must never be held to the restriction
     * that exists specifically to steer *user* code away from touching
     * the field directly, unmediated.
     */
    public boolean isCompilerSynthesizedSwapAccess;

    /**
     * Set only on a "loop" KEYWORD token `checkLockMatchStatement`
     * itself lowers a "match @lock" statement into, and only when its
     * own CLOSED case used the "CLOSED:default"/"CLOSED:default(args)"
     * shorthand -- one already-type-checked, compiler-synthesized
     * "let" assign per entry (the shared "$lm_tries" per-iteration
     * counter, initialized once to 0, followed by one per real
     * configuration parameter the invoked "impl default match @lock"
     * policy declares), each to be emitted exactly once, immediately
     * before the loop's own start label -- never once per iteration,
     * unlike every other statement inside the loop's own body.
     * BytecodeEmitter.emitLoop reads this directly; null/absent for
     * every ordinary loop (including an ordinary "match @lock" whose
     * CLOSED case is a literal "{...}" body, not this shorthand).
     */
    public List<Token> preLoopInit;

    /** Set only on the "loop" KEYWORD token `checkLockMatchStatement` lowers a "match @lock" statement into: this loop is a swap-lock spin loop, so a `continue` marked `isLockRetryContinue` inside its CLOSED case retries it (jumps to the loop's start label, after any `preLoopInit`, so retry counters are not reset). */
    public boolean isLockSpinLoop;

    /** On a lowered `match @lock` whose CLOSED case is `default(..)`: the most retries the compiler allows (> 0 a literal limit, -1 a run-time value, 0 not known). The audit uses it as the loop's bound. */
    public long lockSpinBound;

    /** Set only on a "continue" KEYWORD token written directly in the CLOSED case of a "match @lock": it means "retry the acquire", not the try-block `continue` of a catch body. */
    public boolean isLockRetryContinue;

    /** Set only on a "continue" KEYWORD token whose innermost enclosing construct is a user-written `for`/`loop`: it means "next iteration" (jump to the loop's step / start), not the try-block or lock-retry `continue`. Re-set on every check. */
    public boolean isLoopContinue;

    /**
     * Set by TypeChecker on an "asm_name;" invocation statement's own
     * VARREF token (the bare identifier that *is* the whole statement)
     * once it's been validated as a legal reference to a known ASM
     * declaration -- the fully resolved raw content to splice in,
     * copied from whichever declaration it resolved to (root-level or
     * an enclosing function-local one), so BytecodeEmitter never needs
     * to re-look-up anything by name. Null on every other VARREF
     * (including one that merely shares text with some ASM name but
     * was rejected as an illegal non-statement use -- that case never
     * reaches bytecode emission at all, since TypeChecker throws on it
     * first).
     */
    public String resolvedAsmContent;
}
