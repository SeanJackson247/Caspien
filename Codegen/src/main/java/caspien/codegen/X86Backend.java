package caspien.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one and only backend so far: x86-64, either target. This is a
 * *substantial* second pass, not a finished code generator -- "begin
 * building the fourth program... this will generate assembly
 * instructions from the lower order bytecode," confirmed directly, plus
 * a later, explicit "fix that [PUSH] bug and then work thru the
 * remaining 70+ [mnemonics]," confirmed directly. It now correctly
 * translates the great majority of the real low-order instruction set
 * (see the mnemonic list in `emitLine`'s own switch); anything still
 * genuinely unresolved is emitted as a visible `; TODO(codegen): <line>
 * not yet implemented` comment rather than silently dropped or guessed
 * at -- see this project's own CLAUDE.md, "Known gaps," for the
 * current, honest list of what's still missing and *why* (a few of
 * these turned out to need either a real runtime-protocol decision that
 * doesn't exist anywhere yet, such as GT_UNWIND/async, or a fix one
 * layer up in caspien-lowerordergenerator itself, such as WITHIN's
 * incomplete-range case and INSTANCEOF's class tag -- both still carry
 * unresolved symbolic operands even at "low order," not something
 * codegen alone can safely translate).
 *
 * Translation strategy: this bytecode is a stack machine (PUSH/POP/
 * ADDR all push or consume one 8-byte cell), and the host machine has
 * a real stack of its own -- so, for this first version, every
 * bytecode-level push/pop is translated 1:1 to a real `push`/`pop` of
 * the target's own hardware stack. This is deliberately not an
 * optimizing translation (a real backend would keep short-lived values
 * in registers instead) -- it is a straightforward, easy-to-verify
 * mapping, chosen so this first version can be checked for
 * correctness by actually assembling and running its output, not just
 * read for plausibility.
 *
 * Memory layout decisions this pass had to make on its own (nothing
 * else in the pipeline reads a dynarray's or a ghost-registered heap
 * pointer's actual bytes except the mnemonics this backend itself
 * implements, confirmed by grepping every sibling project's own source
 * for any DOT/PUSH_FIELDNAME-style structural access to one -- there is
 * none, so this backend is free to choose its own internal
 * representation as long as it is self-consistent):
 * - A dynarray value is a pointer to a malloc'd block laid out as
 *   [8-byte len][8-byte capacity][elements...]. `len`/`capacity` are
 *   always kept equal (no growth slack) for this first version's own
 *   simplicity -- see LEN/RESIZE/URESIZE/LOOKUP_DYN(_LHS).
 * - Every other heap value (NEW/CLONE) is a bare malloc'd block of
 *   exactly the given size, no header at all.
 *
 * Two concrete targets share this one class, distinguished by
 * `CodegenConfig.Target` (set from `codegen.config`); both emit GNU
 * assembler (GAS) AT&T syntax:
 * - LINUX_X64: SysV calling convention (rdi/rsi/rdx/rcx/r8/r9).
 * - WINDOWS_GNU_X64: win64 calling convention (rcx/rdx/r8/r9 + 32-byte
 *   shadow space), assembled and linked with mingw-w64 gcc.
 * Both are run in this project's test sandbox (Windows under Wine). There
 * is no MASM/Intel-syntax target: it was removed on purpose.
 */
public class X86Backend {

    private final CodegenConfig.Target target;
    private final StringBuilder out = new StringBuilder();

    // ---- callee-saved register preservation (per function) ----
    // Any generated function that touches a callee-saved register of the target ABI (SysV: rbx, r12-r15; win64 also
    // rdi, rsi, xmm6-15) saves it in its frame and restores it on every way out (RET, the FUNC_END fall-through and
    // GT_UNWIND). The uses are only known once the whole body is emitted, so every exit emits CSR_MARK and FUNC_END
    // (finishCalleeSaved) replaces each mark with the restores, or with nothing, and grows the single ALLOC.
    private static final String CSR_MARK = "@@CSR@@";
    private int csrFuncStart = -1;
    private int csrAllocPos = -1;
    private long csrAllocBytes = 0;
    private String csrAllocLine = null;
    /** Sum of every ALLOC (16-aligned) of the current function: the frame size below rbp, once the callee-saved area is added (finishCalleeSaved). */
    private long csrAllocSum = 0;
    /** A catch landing is reached by an unwind that skipped the call's own rsp restore (and any operand words pushed): the mark becomes `rsp = rbp - frame` once the frame size is final. */
    private static final String RSP_MARK = "@@RSP@@";

    // Declarations collected on a first pass so they can be emitted
    // into proper .data/.rodata/.bss sections ahead of .text, instead
    // of wherever they happen to sit in the linear bytecode stream
    // (GLOBAL/STRING/ALLOC_STATIC/EXTERN lines are legal anywhere --
    // interleaved with function bodies -- but real assembly sections
    // can't be interleaved the same way).
    private final Map<String, long[]> globalsSizeInit = new LinkedHashMap<>(); // name -> [size, init] (init may be absent -> bss)
    private final Map<String, Boolean> globalsHasInit = new LinkedHashMap<>();
    private final Map<String, String> stringLiterals = new LinkedHashMap<>(); // id -> text
    // Float-constant pool (AT&T targets only): every float immediate a register-form float op needs is stored once, in a
    // read-only section, and used as a memory operand (`mulsd .LFC3(%rip), %xmm0`) or loaded with one `movsd`, instead of
    // `movabs $bits, %r15 ; movq %r15, %xmm` on every use. Key "n:bits" -> label; n = 4 or 8.
    private final Map<String, String> floatPool = new LinkedHashMap<>();
    // Every real function this compilation unit defines ("FUNC_START
    // name" lines, collected on the same declarations pass as globals/
    // strings below), so a bare "PUSH size funcName" -- a real function's
    // own *address* being pushed as a value, needed for "par"/"await"'s
    // own compiler-synthesized trampoline function pointer (see
    // caspien-compiler's own CLAUDE.md, "par"/"await" real-thread
    // desugaring) -- can be told apart from an unresolved name this pass
    // genuinely doesn't understand, rather than falling through to the
    // generic "not a declared global" TODO placeholder. A function's own
    // "value" is its address (there is no separate "stored value" to
    // load the way an ordinary global has), so this pushes the label's
    // address directly, the same `leaGlobalToReg` a string literal's own
    // PUSH already uses for the identical "decays to its own address"
    // reason -- never followed by a `loadSizedFromAddr` the way an
    // ordinary global PUSH is.
    private final java.util.Set<String> declaredFunctionNames = new java.util.HashSet<>();
    // "parent.field"-shaped GLOBAL declarations where "parent" is *also*
    // separately declared as its own whole-block global (e.g. "GLOBAL
    // ghost_table 40" alongside "GLOBAL ghost_table.lockState 8 0") are
    // not independent storage -- confirmed directly by a real, very
    // confusing bug this pass found: gt_init/gt_destruct's lock
    // acquire/release read and write "ghost_table.lockState" as a bare
    // global (its own separate .data slot) in some places but compute
    // the *same* field's address via "ADDR 40 ghost_table" +
    // PUSH_FIELDNAME/DOT_LHS (an offset into the "ghost_table" block)
    // in others -- two different addresses for what the bytecode
    // clearly intends as the same storage, so the unlock write and the
    // lock-check read landed in different places and the lock never
    // appeared to clear. The fix: a dotted GLOBAL whose prefix (up to
    // the last '.') names another declared whole-block global is
    // treated as a pure alias -- no storage of its own -- resolving to
    // that parent's address plus the cumulative size of any
    // same-parent dotted fields declared before it (matching the
    // PUSH_FIELDNAME offsets used elsewhere for the same struct).
    private final Map<String, Long> globalAliasOffset = new LinkedHashMap<>(); // dotted name -> byte offset into its parent
    private final Map<String, List<long[]>> aliasInits = new LinkedHashMap<>(); // parent -> [offset, size, bits] of each initialised element
    private final Map<String, String> globalAliasParent = new LinkedHashMap<>(); // dotted name -> parent global name
    // Set only while generate() is walking the line list, so a bare
    // global-name PUSH can peek a couple of lines ahead to tell whether
    // it's feeding an ATOMIC_SWAP (needs the global's address) or
    // anything else (needs its value) -- see that PUSH case's own doc
    // comment for why both are genuinely needed here.
    private List<List<BytecodeToken>> allLines;
    private int currentLineIndex;

    /** Set by the "ASM_START" case to the index of the "ASM_END" line that
     * closes the block it just copied verbatim, so every raw line in
     * between (already emitted as part of that one case) is skipped by
     * the main per-line dispatch instead of falling through to the
     * generic TODO comment a second time. -1 when no block is open. */
    private int asmSkipUntilIndex = -1;

    /** True when the bytecode line `offset` lines after the current one is a bare "ATOMIC_SWAP" -- used to spot the "PUSH addressOperand / PUSH newValue / ATOMIC_SWAP" shape genuinely seen in this pipeline's own ghost-table lock (see PUSH's global-name fallback). */
    private boolean lineAtOffsetIsAtomicSwap(int offset) {
        int idx = currentLineIndex + offset;
        if (allLines == null || idx < 0 || idx >= allLines.size()) {
            return false;
        }
        List<BytecodeToken> l = allLines.get(idx);
        return !l.isEmpty() && l.get(0).text.equals("ATOMIC_SWAP");
    }

    // ---- Construction-scoped push detection --------------------------------
    //
    // A struct/array/primitive construction ("X{...}"/"new X{...}") emits
    // a run of "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" lines immediately
    // followed by a "NEW <size>" line whose declared size equals the sum
    // of that run's own sizes/gaps. Within such a run, each push can
    // safely write fewer than a full 8-byte hardware word (its own real,
    // declared width) instead of the usual, always-8-byte `pushq`,
    // because "NEW" (see its own case, below) does one straight block
    // copy starting at `%rsp`, which is only byte-exact -- matching
    // `computeStructLayout`'s own tightly-packed layout -- when every
    // push and STACK_LOCK gap in that run reserved exactly its own
    // declared width. An ordinary push anywhere else in the program (a
    // comparison operand, a call argument, an "if" condition, ...) is
    // never part of such a run, so it keeps doing a full, ordinary
    // `pushq`, completely unaffected by any of this.
    //
    // A subtlety found and fixed the hard way, by actually running a
    // nested-construction repro (a struct with an "owns" member itself
    // constructed via a nested "new NestedStruct{...}"): a naive,
    // purely-linear backward/forward scan over raw "PUSH" text cannot
    // tell an outer run's own field pushes apart from an inner,
    // completed construction's field pushes sitting textually in the
    // middle of it -- both are just "PUSH size operand" lines, with
    // nothing to mark where one run ends and another begins except the
    // "NEW" lines themselves. From the *outer* run's own perspective, a
    // nested "new NestedStruct{...}" field contributes exactly one
    // 8-byte pointer word (its own "NEW"'s own final, ordinary,
    // always-8-byte `pushReg` call) -- never the nested construction's
    // own raw internal field bytes, which are already fully consumed by
    // the time that inner "NEW" returns. So this is computed as a real,
    // two-level (recursively, any-level) parse, not a flat scan: a
    // "NEW" line encountered while walking a run backward is treated as
    // one complete, opaque, already-resolved 8-byte unit (after
    // recursively confirming *its own* run resolves to *its own*
    // declared size), never as more raw pushes belonging to the run
    // being walked.
    //
    // A second subtlety: two independent, back-to-back constructions can
    // butt up against each other with zero other instructions between
    // them (e.g. a field whose value is itself "new Nested{...}", the
    // very last field of the outer literal -- the inner "NEW" and the
    // outer "NEW" end up on two consecutive lines, with no ASSIGN or
    // other instruction separating them). Processing *every* "NEW" line
    // in the whole function independently (each one validating and
    // resolving only its own direct, non-nested members) handles this
    // correctly without either run's own bookkeeping leaking into the
    // other's.
    //
    // Precomputed once per `generate()` call (`allLines` doesn't change
    // during it) into `pushStoreOffsetByLine`, keyed by line index --
    // `case "PUSH"` below does a plain map lookup rather than re-scanning
    // per instruction.
    private final Map<Integer, Long> pushStoreOffsetByLine = new java.util.HashMap<>();

    /**
     * The real fix for a real segfault
     * (`register_overflow_args_even_cg_test.caspien`/`_odd_cg_test`, both
     * a plain 8/9-`u64`-parameter function called with more parameters
     * than sysv_x64's own 6 integer argument registers): a stack-passed
     * argument word is already correctly emitted as a bare, un-popped
     * "PUSH size operand" (see `BytecodeEmitter.emitArgTransferTail`'s own
     * doc comment on the compiler side: register-passed gets a "POP
     * ARGn"/"POP FARGn" right after its own value is fully computed,
     * stack-passed gets nothing) -- but this backend's own "PUSH"/"POP"
     * bytecode mnemonics are real, one-to-one `pushq`/`popq` on the
     * *actual* x86 stack, so every such deliberately-un-popped word stays
     * sitting there, real memory, through to the `call` -- and a second,
     * independent bug (found only once the first was already fixed and
     * the program printed a wrong value instead of segfaulting) meant
     * `emitAlignedCall`'s own 16-byte alignment reservation used to land
     * *between* those pushed words and the return address, so the callee's
     * own (correct) `%rbp+16+n*8` read formula read the alignment
     * machinery's own saved-rsp slot instead of the real argument.
     *
     * Both bugs are caller-side stack-accounting/-ordering bugs, neither
     * specific to integers or to one calling convention -- they fire
     * identically for a stack-passed float, a narrow type, or a call
     * under win64, since `emitAlignedCall`/"CALL"/"INVOKE" are the one
     * shared, convention-agnostic choke point every call goes through.
     *
     * **The fix**: the 16-byte-alignment reservation now happens at
     * "CC_START" -- before any argument, stack-passed or not, is pushed
     * -- sized to already account for the real number of bytes the
     * stack-passed arguments will add before the call. That number can't
     * be known by inspecting the bytecode text alone: an individual
     * argument's own "PUSH ... ; POP ARGn"-or-not shape can have any
     * number of ordinary intermediate lines in between the two (found via
     * a real, confirmed regression: `raw arr` compiles to "PUSH 24 $-24 /
     * ADDR_OF RAW $-24 / POP ARG0 8" -- a whole 24-byte array block is
     * pushed, then immediately collapsed back down to a single 8-byte
     * pointer by `ADDR_OF`'s own real codegen, *then* popped into a
     * register; a naive "is the very next line a POP ARGn" check
     * misclassifies this as 24 stack-passed bytes and corrupts an
     * otherwise-correct, all-register call). Trying to special-case every
     * such shape (`ADDR_OF`, `DOT`, `LOOKUP_ARRAY`, `CLONE`, ... -- any
     * mnemonic whose own codegen can shrink or discard an already-pushed
     * value) from bytecode text alone is exactly the kind of whack-a-mole
     * this class of bug already warns against.
     *
     * So this doesn't try to read intent from the bytecode text at all --
     * it measures the real thing directly, by tracking every *actual*
     * `pushq`/`popq`/`add rsp,N`/`sub rsp,N` this backend emits between a
     * call's own "CC_START" and its "CALL"/"INVOKE" (`argTrackDelta`,
     * updated from `raw()` itself -- see that method's own doc comment).
     * Whatever real bytes are still unaccounted for at the "CALL" line
     * are, by construction, exactly the stack-passed argument bytes: every
     * *register*-passed argument's own production sequence, however many
     * lines long, always nets to zero the moment its "POP ARGn"/"POP
     * FARGn" runs (push some bytes computing it, pop exactly that many
     * back out into the register) -- so only genuinely-unconsumed,
     * stack-passed bytes are ever left over to measure.
     *
     * Emission itself has to be deferred to make this work: the alignment
     * reservation has to be the *first* thing emitted for the call (see
     * above, this has to happen before the stack-passed words are
     * pushed), but the byte count it needs is only known once every
     * argument has already been produced, at the "CALL" line. `case
     * "CC_START"` therefore doesn't emit anything immediately -- it opens
     * a fresh buffer (`callBufferStack`) that every `raw()` call until the
     * matching "CC_END" writes into instead of `out` directly; `case
     * "CALL"`/`"INVOKE"` -- now knowing the real byte count -- prepend the
     * correctly-sized reservation to the front of that same buffer, then
     * append the bare call and its own restore; `case "CC_END"` closes the
     * buffer out, flushing its now-complete contents into whatever buffer
     * (or `out`) was active one level up, so a call nested inside another
     * call's own argument expression (`foo(bar(), x)`) buffers and
     * resolves independently, correctly nested. See `raw()`, `case
     * "CC_START"`, `case "CALL"`/`"INVOKE"`, and `case "CC_END"` for where
     * each piece of this is actually implemented.
     *
     * The ordinary, overwhelmingly common all-register-arguments case
     * (byte count resolves to 0 at "CALL") is completely unaffected in
     * its generated assembly -- it still goes through the original,
     * already-verified `emitAlignedCall` wrapping just the `call` itself,
     * with buffering/flushing around it a no-op change in output (the
     * buffered text is identical to what `out` would have received
     * directly, just flushed one step later).
     */
    private final java.util.Deque<Long> argTrackDeltaStack = new java.util.ArrayDeque<>();

    /**
     * Suppresses `argTrackDeltaStack` tracking for the duration of a call
     * wrapped by `emitAlignedCall` (a counter, not a boolean, since one
     * such call's own argument can itself trigger another) -- every
     * instruction `emitAlignedCall` itself emits (the alignment
     * reservation, the win64 shadow-space adjustment, the final restore)
     * nets to exactly zero real stack effect by the time it returns, by
     * construction (that's its entire contract), so none of it should
     * ever be attributed to an *enclosing* "CC_START"/"CALL" bracket's own
     * running total -- tracking it would double-count real bytes that
     * were only ever transient scratch for this nested call's own
     * mechanics, not a surviving stack-passed argument of the outer call.
     */
    private int argTrackSuspendDepth = 0;

    /**
     * One level of buffered, not-yet-flushed assembly text per currently-
     * open "CC_START"/"CC_END" bracket, paired one-to-one with
     * `argTrackDeltaStack` (both pushed at "CC_START", both popped at
     * "CC_END") -- see `argTrackDeltaStack`'s own doc comment (just above)
     * for why buffering, rather than emitting directly into `out`, is
     * necessary at all. `raw()` always writes into `callBufferStack.peek()`
     * when this is non-empty, `out` otherwise. A call nested inside
     * another call's own argument expression (`foo(bar(), x)`) gets its
     * own, separate level here -- `bar()`'s own buffer/tally resolve and
     * flush (at its own "CC_END") well before `foo`'s own "CALL" ever
     * looks at *its* tally, so the two never interfere.
     */
    private final java.util.Deque<StringBuilder> callBufferStack = new java.util.ArrayDeque<>();

    /**
     * Per open "CC_START"/"CC_END" bracket (paired one-to-one with
     * `callBufferStack`): the argument registers this call's own "POP
     * ARGn"/"POP FARGn" lines have already loaded, as "i<n>" (integer
     * bank) or "f<n>" (float bank), in load order. A call nested inside
     * this call's remaining argument expressions (`foo(a, bar(b), c)`)
     * sets up *its own* arguments in the very same physical registers, so
     * without protection it silently overwrites what `foo` has already
     * loaded (and `foo`'s later arguments too, when `bar`'s result is
     * itself popped into a register that `bar`'s own argument setup
     * clobbered). `case "CC_START"` therefore pushes every register in the
     * enclosing call's list before opening the nested call's own bracket,
     * and `case "CC_END"` pops them back afterwards -- see those cases.
     */
    private final java.util.Deque<java.util.List<String>> loadedArgRegsStack = new java.util.ArrayDeque<>();

    /**
     * The registers each currently-open nested call saved at its own "CC_START"
     * (the enclosing call's already-loaded argument registers, in push order),
     * one list per open bracket, popped at the matching "CC_END".
     */
    private final java.util.Deque<java.util.List<String>> savedOuterArgRegsStack = new java.util.ArrayDeque<>();

    /**
     * True when the last "CC_END" had to restore xmm0 (the float-bank
     * argument 0 register) that a just-finished nested call may also have
     * used to *return* a float: the possible float result was stashed in
     * r10 first, and the very next `PUSH_RET_FLOAT` reads it from there
     * instead of xmm0.
     */
    private boolean floatResultInR10 = false;

    private void noteArgRegLoaded(String tag) {
        if (!loadedArgRegsStack.isEmpty() && !loadedArgRegsStack.peek().contains(tag)) {
            loadedArgRegsStack.peek().add(tag);
        }
    }

    /** Current sink for `raw()` -- the innermost open call's own buffer, or `out` directly when no "CC_START"/"CC_END" bracket is currently open. */
    private StringBuilder currentSink() {
        return callBufferStack.isEmpty() ? out : callBufferStack.peek();
    }

    /**
     * The real number of xmm (float-bank) argument registers this
     * upcoming call's own arguments used, for the one, single SysV
     * varargs call this value ever needs to reach: the "%al must hold
     * the number of vector registers used" rule (`emitAlignedCall`'s
     * non-win64 branch, `resolveStackArgBytesAndCall`'s own overflow
     * branch). -1 means "no pending value" -- either the upcoming call
     * isn't variadic at all (the front end only ever emits
     * "VARARGS_XMM_COUNT" immediately before a real variadic call's own
     * "CALL"/"INVOKE" line -- see `BytecodeEmitter.emitCallSequence`'s
     * own `varargStartIndex` doc comment), or it's win64 (no such
     * convention exists there -- consumed and ignored either way, since
     * only the two non-win64 `%al`-zeroing sites ever read this field).
     * Set by `case "VARARGS_XMM_COUNT"`, always consumed (read, then
     * reset to -1) by whichever of those two sites' preamble runs next
     * -- exactly one of them, for exactly the one call this value was
     * computed for; never left stale across calls, since every real
     * variadic call site emits a fresh "VARARGS_XMM_COUNT" of its own,
     * and a non-variadic call in between would otherwise see a stale
     * leftover count belonging to an unrelated, earlier call.
     */
    private int pendingVarargsXmmCount = -1;

    /** Consumes (reads and clears) `pendingVarargsXmmCount`, defaulting to 0 (correct for a wholly non-variadic call, or an already-conservative "no floats" guess if this is somehow reached for a variadic call whose count was never set -- should not happen, but 0 is what this backend always assumed before this field existed at all). */
    private int consumeVarargsXmmCount() {
        int count = pendingVarargsXmmCount < 0 ? 0 : pendingVarargsXmmCount;
        pendingVarargsXmmCount = -1;
        return count;
    }

    /**
     * Line indices of an "ASSIGN size size size"/"ATOMIC_ASSIGN size
     * size size" whose immediately preceding run of pushes was
     * confirmed (via the exact same `findRunStart`/`populateDirectMembers`
     * this whole section already uses for "NEW") to be a real, freshly-
     * built construction -- a plain, non-heap struct/array/primitive
     * literal ("X{...}", never "new X{...}") left stack-resident rather
     * than copied into a fresh heap buffer. Populated by
     * `precomputeConstructionOffsets` alongside `pushStoreOffsetByLine`
     * itself, since both come from literally the same scan.
     *
     * This is the fix for a real, previously-undiscovered bug, found
     * the same day this mechanism's own doc comments were re-read
     * carefully for the first time in a while: `precomputeConstructionOffsets`
     * only ever looked for a run *ending in a "NEW <size>" line* --
     * meaning the "each push in a construction run reserves only its
     * own declared width, not a blind 8-byte word" treatment those
     * comments describe as construction-agnostic ("NEW's whole job is
     * now genuinely trivial and completely struct-agnostic") was, in
     * practice, silently coupled to heap allocation specifically. A
     * plain stack-resident literal with any field narrower than 8 bytes
     * (`let p = mut Mixed{flag=true, a='X', b='Y', id=123456789}`,
     * with no "new") never went through this scan at all, so its
     * narrow fields still fell back to the old, blind, always-8-byte
     * `pushReg`/`assignBlock` word-popping path below -- correct only
     * by accident, whenever every field already happened to be exactly
     * 8 bytes wide. Confirmed directly as a real, live bug via two
     * fixtures already in the corpus (`struct_mixed_width_cg_test`,
     * a genuine segfault from writing to the wrong stack slot;
     * `struct_array_member_narrow_elem_cg_test`, an outright invalid
     * `movzqq` instruction that fails to assemble) -- both had been
     * sitting there the whole time.
     *
     * The real fix is exactly what the class-level "Construction-scoped
     * push detection" note already promised and never actually
     * delivered: the *same* run-detection this file already has is now
     * also triggered by an "ASSIGN"/"ATOMIC_ASSIGN size size size" line
     * (three equal sizes, >8 -- the shape a struct/array/primitive
     * literal's own top-level store always has), not only "NEW". No
     * new detection logic was written -- `findRunStart`/
     * `populateDirectMembers` are reused completely unchanged; this set
     * just remembers which ASSIGN lines qualified, so `case "ASSIGN"`
     * below can pick the matching "trivial, byte-exact copy" treatment
     * `case "NEW"` already uses, instead of the old per-word
     * `assignBlock`, exactly when it's actually warranted -- see
     * `constructionScopedAssign`'s own use, below, for why an ordinary
     * whole-value block copy (`let b = a`, an existing struct/array
     * variable's own value re-pushed via a single "PUSH size $offset")
     * still needs the *old* path instead.
     */
    private final Set<Integer> constructionScopedAssign = new java.util.HashSet<>();

    /**
     * "NEW" lines whose run of field pushes `precomputeConstructionOffsets`
     * resolved into one clean, packed image (every direct field a plain
     * PUSH/STACK_LOCK, sizes summing exactly to the declared size). Any
     * OTHER "NEW" -- a field value that is an expression, a call, a moved
     * `owns` variable, a nested `new`/`dyn(...)` (with its out-of-memory
     * check code) -- is NOT packed: every field was pushed as a blind
     * 8-byte word (a wider field as a word-reversed block), so the stack
     * holds those words in REVERSE field order and "NEW"'s straight block
     * copy would put the fields backwards (the type id where the first
     * field belongs, and so on). Those go through `emitNewRepack` instead,
     * using the struct layout descriptor LowerOrderGenerator appends to
     * the "NEW" line.
     */
    private final Set<Integer> packedNewLines = new java.util.HashSet<>();

    /**
     * Returns the start index of the run of field productions ending
     * exactly at `endExclusive` (i.e. lines endExclusive-1,
     * endExclusive-2, ... going backward) whose sizes sum to EXACTLY
     * `targetSize` -- or -1 if no such run exists. A "production" is an
     * ordinary "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" line (contributing its
     * own declared size/gap) or a complete nested "NEW <size>"
     * (contributing exactly 8 bytes -- the pointer it leaves behind --
     * once its *own* run is confirmed, recursively, to sum to that same
     * declared size).
     *
     * `targetSize` is load-bearing, not a mere sanity check: a nested
     * construction's own trailing field pushes are textually
     * indistinguishable from the outer run's own earlier field pushes
     * (both are just "PUSH size operand" lines, with no marker of its
     * own separating one run from the next) -- the *only* thing that
     * tells this scan where the nested run actually started is that its
     * own declared size (the nested "NEW"'s own operand) is reached
     * exactly. A scan with no target, greedily consuming every
     * syntactically-valid production it finds, cannot stop there: it
     * would keep walking straight past the nested run's own true start
     * and into the outer run's remaining fields, silently miscounting
     * both. So this stops the instant the running total reaches
     * `targetSize` (success) or would exceed it (failure) -- never
     * "as much as looks valid."
     */
    private int findRunStart(int endExclusive, long targetSize) {
        long total = 0;
        int idx = endExclusive;
        while (total < targetSize && idx > 0) {
            List<BytecodeToken> prev = allLines.get(idx - 1);
            if (prev.isEmpty()) {
                return -1;
            }
            String m = prev.get(0).text;
            if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH")) && prev.size() >= 2) {
                total += Long.parseLong(prev.get(1).text);
                idx--;
            } else if (m.equals("STACK_LOCK") && prev.size() >= 2) {
                total += Long.parseLong(prev.get(1).text);
                idx--;
            } else if (m.equals("NEW") && prev.size() >= 2) {
                long declaredNestedSize = Long.parseLong(prev.get(1).text);
                int nestedStart = findRunStart(idx - 1, declaredNestedSize);
                if (nestedStart < 0) {
                    return -1;
                }
                idx = nestedStart;
                total += 8; // the nested NEW's own resulting pointer -- one ordinary 8-byte word to this (outer) run
            } else {
                return -1;
            }
        }
        return total == targetSize ? idx : -1;
    }

    /**
     * Populates `pushStoreOffsetByLine` for every *direct* (non-nested)
     * "PUSH"/"ATOMIC_PUSH" member of the run [start, endExclusive) --
     * each one's own value is `trueOffset - remainingBytes`, the signed
     * byte offset from `%rsp` *after that specific push's own `subq`*
     * to store its value at (see the class-level note above `case
     * "PUSH"`'s own doc comment for why plain "(%rsp)" -- offset 0 --
     * is wrong the moment a construction has more than one field: each
     * field's own `subq` grows the stack *downward*, so by itself that
     * lays fields out in the *reverse* of their real struct order;
     * `trueOffset - remainingBytes` corrects for exactly that). A
     * nested "NEW" found along the way is skipped as a single opaque
     * unit (it never itself needs a store-offset entry -- its own
     * `pushReg` inside `case "NEW"` is a plain, ordinary, always-8-byte
     * push, ineligible for this treatment entirely) -- its own direct
     * members are populated separately, when *that* "NEW" is processed
     * on its own by `precomputeConstructionOffsets`.
     */
    private void populateDirectMembers(int start, int endExclusive, long runTotal) {
        RunEntries entries = collectRunEntries(start, endExclusive);
        long before = 0;
        long after = runTotal;
        for (int i = 0; i < entries.sizes.size(); i++) {
            long sz = entries.sizes.get(i);
            after -= sz;
            int lineIdx = entries.storeLines.get(i);
            if (lineIdx >= 0) {
                pushStoreOffsetByLine.put(lineIdx, before - after);
            }
            before += sz;
        }
    }

    /**
     * After a value's own bits are already sitting in `regName` (always
     * "rax" today, but kept general for clarity), either stores it at its
     * own precomputed, tightly-packed construction offset (see
     * `populateDirectMembers`'s own doc comment) when this exact PUSH line
     * is a direct member of a struct/array/range/primitive construction,
     * or, when it isn't, does a plain, ordinary full-word `pushq`
     * (`pushReg`), the identical fallback every PUSH already had before
     * construction-packing existed.
     *
     * Shared between every PUSH operand shape (`case "PUSH"`'s "$offset"
     * branch and its own generic literal/ARG/global fallback) so newly-
     * added operand shapes automatically participate in construction-
     * packing rather than needing this decision duplicated -- **the exact
     * fix this doc comment itself documents**: the "$offset" branch used
     * to call `pushReg` unconditionally, silently NEVER checking
     * `pushStoreOffsetByLine` at all, so any construction with a variable-
     * sourced field (e.g. "0..index", a range literal whose end is a
     * parameter's own value, not a literal) got its variable-sourced entry
     * pushed as an ordinary, unrelated stack push -- corrupting the whole
     * tightly-packed image the terminating ASSIGN/`assignConstructionBlock`/
     * `NEW` expects, in a way that only manifests once that entry is
     * actually consumed (silently, no crash at the construction site
     * itself). Found via a real segfault inside the ghost table's own
     * `gtSlotAt`/`gtWriteSlot` machinery (`stdlib/ghost_table.caspien`'s
     * own "for i in 0..index" -- a range literal with one immediate ("0")
     * and one frame-loaded ("index") entry).
     */
    private void storeConstructionOrPlainPush(String regName, int size) {
        Long constructionOffset = pushStoreOffsetByLine.get(currentLineIndex);
        if (constructionOffset == null) {
            pushReg(regName);
            return;
        }
        raw(("    subq $" + size + ", %rsp"));
        if (constructionOffset == 0) {
            storeSizedToAddr(regName, "rsp", size);
        } else if (isOddSize(size)) {
            storeOddSize(regName, "rsp", constructionOffset, size);
        } else {
            raw("    mov" + movSuffix(size) + " %" + sizedReg(regName, size) + ", " + constructionOffset
                    + "(%rsp)");
        }
    }

    /** Plain data holder for {@link #collectRunEntries}: parallel lists of each direct entry's own size and its store-instruction line index (-1 when it has none of its own -- a `STACK_LOCK` padding gap, or a nested `NEW`'s own opaque pointer). */
    private static final class RunEntries {
        final List<Long> sizes = new ArrayList<>();
        final List<Integer> storeLines = new ArrayList<>();
    }

    /**
     * The shared forward-scan used both to *count* a run's own direct
     * members (`precomputeConstructionOffsets`'s own way of telling a
     * genuine multi-field construction apart from an ordinary
     * single-value `ASSIGN` -- ordinary scalar assigns and whole-block
     * copies alike are always exactly *one* `PUSH`, whatever their
     * declared size, while any real struct/array literal is always
     * *more than one* -- so the entry *count*, not the byte size, is
     * what actually distinguishes them) and to lay out their real
     * store offsets (`populateDirectMembers`). Walking forward, a
     * nested construction's own field pushes are indistinguishable
     * from this (outer) run's own direct field pushes right up until
     * its terminating "NEW" line is actually reached -- so they get
     * recorded provisionally, exactly like any other direct member,
     * and are only recognized and collapsed retroactively, once that
     * "NEW" line is seen: pop entries off the back of what's been
     * recorded so far until their sizes sum to exactly this "NEW"'s
     * own declared size (an already-collapsed deeper-nested "NEW" pops
     * as a single 8-byte entry, so this handles arbitrary nesting
     * depth correctly), then replace them all with one opaque 8-byte
     * entry (the nested "NEW"'s own resulting pointer, pushed by its
     * own plain, ordinary, always-8-byte `pushReg` -- never eligible
     * for a store offset of its own).
     */
    private RunEntries collectRunEntries(int start, int endExclusive) {
        RunEntries entries = new RunEntries();
        int idx = start;
        while (idx < endExclusive) {
            List<BytecodeToken> l = allLines.get(idx);
            String m = l.get(0).text;
            if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH")) && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(idx);
                idx++;
            } else if (m.equals("STACK_LOCK") && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(-1);
                idx++;
            } else if (m.equals("NEW") && l.size() >= 2) {
                long declaredNestedSize = Long.parseLong(l.get(1).text);
                long sum = 0;
                int popCount = 0;
                for (int j = entries.sizes.size() - 1; j >= 0; j--) {
                    sum += entries.sizes.get(j);
                    popCount++;
                    if (sum >= declaredNestedSize) {
                        break;
                    }
                }
                if (sum == declaredNestedSize) {
                    for (int k = 0; k < popCount; k++) {
                        entries.sizes.remove(entries.sizes.size() - 1);
                        entries.storeLines.remove(entries.storeLines.size() - 1);
                    }
                }
                entries.sizes.add(8L);
                entries.storeLines.add(-1);
                idx++;
            } else {
                break; // shouldn't happen -- the caller already validated this exact range
            }
        }
        return entries;
    }

    /**
     * "NEW size m8 m8 p4 m1 ..." where the run of field pushes could NOT be
     * packed (see `packedNewLines`). Every field production was pushed the
     * ordinary way: a scalar as one full 8-byte word, a wider field as a
     * word-reversed block (`pushBlockFromFrame`'s convention, highest-offset
     * word nearest %rsp), and each padding gap as an exact `subq` -- so the
     * stack region, read from %rsp upward, is the word-for-word REVERSE of
     * the "padded image" (fields in declared order, each rounded up to whole
     * words, gaps exact). This rebuilds the real, tightly-packed struct in a
     * fresh heap buffer straight from that description: for each layout
     * entry, copy its bytes from where that chunk sits on the stack to the
     * entry's true offset in the buffer. Unlike the packed path this needs
     * to know the layout, which LowerOrderGenerator appends to the line.
     */
    private void emitNewRepack(long size, List<BytecodeToken> line) {
        // Chunk sizes on the stack, in push order (first field first).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();   // 0 for a padding gap
        List<Long> elemSize = new ArrayList<>();    // > 0 for "a<elemBytes>x<n>": a fixed array written element by element, n whole words
        List<Long> elemCount = new ArrayList<>();
        for (int i = 2; i < line.size(); i++) {
            String tok = line.get(i).text;
            if (tok.charAt(0) == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (tok.charAt(0) == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw("    movq %rsp, %r12"); // r12: the reversed field words on the stack
        emitMallocCall(size);
        raw("    movq %rax, %r14"); // r14: the fresh buffer
        // A failed malloc leaves r14 null: skip every copy into it; the null is pushed and the bytecode's own check throws.
        String newRepackDone = newInternalLabel("new_done");
        raw("    testq %r14, %r14");
        raw("    jz " + newRepackDone);
        long dst = 0;      // running true offset in the buffer
        long consumed = 0; // bytes of chunks pushed so far (from the first field)
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed; // this chunk's lowest address, relative to r12
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i); // padding gap: bytes in the buffer stay whatever malloc left
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    copyBytesR12ToR14(chunkBase + (cnt - 1 - k) * 8, dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
                copyBytesR12ToR14(srcOff, dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(newRepackDone + ":");
        raw(("    addq $" + total + ", %rsp"));
        pushReg("r14");
    }

    /**
     * "ASSIGN size size size m8 m4 p4 a4x3 ..." for a stack struct literal whose field pushes could not be packed (see
     * `emitNewRepack`, which does the same for a heap `new`): every scalar field was pushed as one whole 8-byte word, a wider
     * field as a word-reversed block, and each padding gap as an exact `subq`, so the stack region from %rsp upward is the
     * reverse of the padded image, with the destination address in the word just above it. Copies each field's bytes to its
     * true offset from that address, then drops the field words and the address. Uses only the scratch registers %r10 (source
     * base), %r11 (destination) and %rax.
     */
    private void emitAssignRepack(long size, List<BytecodeToken> line) {
        // One entry per layout token: chunk = bytes it occupies on the stack, fieldSize = real bytes (0 for a padding gap),
        // elemSize/count > 0 for "a<elemBytes>x<n>" (a fixed array written element by element: n whole words, one per element).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();
        List<Long> elemSize = new ArrayList<>();
        List<Long> elemCount = new ArrayList<>();
        for (int i = 4; i < line.size(); i++) {
            String tok = line.get(i).text;
            char kind = tok.charAt(0);
            if (kind == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (kind == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw("    movq %rsp, %r10");
        raw(("    movq " + total + "(%rsp), %r11"));
        long dst = 0;
        long consumed = 0;
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed;
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i);
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    // element k was pushed k-th, so the last element sits nearest %rsp
                    copyBytes("r10", chunkBase + (cnt - 1 - k) * 8, "r11", dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
                copyBytes("r10", srcOff, "r11", dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(("    addq $" + (total + 8) + ", %rsp"));
    }

    /** Copies `n` (1..8) bytes from srcOff(srcReg) to dstOff(dstReg) through %rax, in the fewest naturally-sized moves. */
    private void copyBytes(String srcReg, long srcOff, String dstReg, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%" + srcReg + "), %" + sizedReg("rax", w));
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%" + dstReg + ")");
            
            done += w;
        }
    }

    /** Copies `n` (1..8) bytes from srcOff(%r12) to dstOff(%r14) through %rax, in the fewest naturally-sized moves. */
    private void copyBytesR12ToR14(long srcOff, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%r12), %" + sizedReg("rax", w));
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%r14)");
            
            done += w;
        }
    }

    /**
     * Runs once per `generate()` call: finds every construction
     * *terminator* in the program -- a "NEW <size>" line (a heap
     * construction), or an "ASSIGN"/"ATOMIC_ASSIGN size size size" line
     * whose three size operands agree and exceed 8 (a plain,
     * stack-resident struct/array/primitive literal's own top-level
     * store -- never a scalar ASSIGN, which this bytecode's own
     * convention always carries as three *equal*, but not necessarily
     * `>8`, sizes too, so the `>8` check is what actually tells the two
     * apart) -- and, for each whose own immediately-preceding run of
     * pushes resolves to that exact declared size (`findRunStart`),
     * records every direct member's own store offset into
     * `pushStoreOffsetByLine`, exactly as it already did for "NEW"
     * alone.
     *
     * This single generalization is the whole fix for a real bug: the
     * "each push in a construction run reserves only its own declared
     * width" treatment only ever fired ahead of a "NEW" line before --
     * a plain, non-heap struct/array literal fell back to the old,
     * blind, always-8-byte-word path instead (see
     * `constructionScopedAssign`'s own doc comment for the real
     * fixtures this broke). Nothing about *how* a run is detected
     * changed at all -- `findRunStart`/`collectRunEntries` are the
     * identical, unmodified methods "NEW" alone used before; only
     * *which lines are allowed to trigger that same detection* grew a
     * second case.
     *
     * An "ASSIGN"/"ATOMIC_ASSIGN size size size" line's three equal
     * size operands alone do NOT tell a genuine construction (a
     * struct/array literal's own top-level store) apart from an
     * ordinary scalar assign or a whole-block copy (`let b = a`) --
     * this bytecode's own convention emits all three the exact same
     * way, and a copy's declared size can just as easily be large (a
     * big struct) as a literal's can be small (a two-element `char`
     * array, total 2 bytes). A size threshold was tried first and was
     * wrong on both ends: it misses small literals like a `char[4]`
     * (declared size 4, well under any plausible threshold) and it
     * would just as readily misfire on a large whole-struct copy given
     * a low enough one. What actually distinguishes them is the
     * *number of direct pushes* feeding the ASSIGN, not their combined
     * size: a scalar assign and a whole-block copy alike are always
     * fed by exactly *one* PUSH (a single value, or a single `$offset`
     * reference to the whole source block); a real literal construction
     * -- however small -- is always fed by *more than one*, one per
     * member. So the run is found the same way regardless (`findRunStart`
     * doesn't care what the caller intends to do with it), and only
     * *afterward*, once `collectRunEntries` has actually counted the
     * direct entries, is a single-entry run recognized as "not a
     * construction" and left on the old, already-correct blind path.
     */
    private void precomputeConstructionOffsets() {
        pushStoreOffsetByLine.clear();
        constructionScopedAssign.clear();
        packedNewLines.clear();
        for (int i = 0; i < allLines.size(); i++) {
            List<BytecodeToken> l = allLines.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            boolean isNew = m.equals("NEW") && l.size() >= 2;
            boolean isBlockAssign = (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() >= 4
                    && l.get(1).text.equals(l.get(2).text) && l.get(2).text.equals(l.get(3).text);
            if (!isNew && !isBlockAssign) {
                continue;
            }
            long declaredSize = Long.parseLong(l.get(1).text);
            int runStart = findRunStart(i, declaredSize);
            if (runStart < 0) {
                continue;
            }
            if (isBlockAssign) {
                RunEntries entries = collectRunEntries(runStart, i);
                if (entries.sizes.size() <= 1) {
                    continue; // a single PUSH feeding this ASSIGN: an ordinary scalar assign or a whole-block copy, not a construction
                }
            }
            populateDirectMembers(runStart, i, declaredSize);
            if (isBlockAssign) {
                constructionScopedAssign.add(i);
            } else {
                packedNewLines.add(i);
            }
        }
    }

    private final boolean bmi2;
    /** `avx on`: R_FBINX uses VEX 3-operand scalar forms */
    public boolean avx;

    public X86Backend(CodegenConfig.Target target) {
        this(target, false);
    }

    public X86Backend(CodegenConfig.Target target, boolean bmi2) {
        this.target = target;
        this.bmi2 = bmi2;
    }

    /** True for the Windows target (windows_gnu: GNU as + mingw-w64): gates win64 ABI choices (argument registers, 32-byte shadow space, no SysV varargs %al convention). The assembly syntax is AT&T on every target. */
    private boolean isWinAbi() {
        return target == CodegenConfig.Target.WINDOWS_GNU_X64;
    }

    public String generate(List<List<BytecodeToken>> lines) {
        out.setLength(0);
        globalsSizeInit.clear();
        globalsHasInit.clear();
        stringLiterals.clear();
        floatPool.clear();
        globalAliasOffset.clear();
        aliasInits.clear();
        globalAliasParent.clear();
        collectDeclarations(lines);
        emitHeader();
        emitDataSections();
        raw(".text");
        allLines = lines;
        precomputeConstructionOffsets();
        callBufferStack.clear();
        argTrackDeltaStack.clear();
        argTrackSuspendDepth = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            currentLineIndex = lineIndex;
            List<BytecodeToken> line = lines.get(lineIndex);
            emitLine(line);
        }
        emitFloatPool();
        emitFooter();
        return out.toString();
    }

    // ---- Pass 1: declarations -------------------------------------------------

    private void collectDeclarations(List<List<BytecodeToken>> lines) {
        // Pass 1: every plain (non-dotted) GLOBAL/ALLOC_STATIC name, so
        // pass 2 below can tell a dotted "parent.field" declaration
        // apart from one whose "parent" isn't actually a separately
        // declared whole-block global (in which case it keeps its own
        // independent storage, the pre-existing behavior).
        java.util.Set<String> plainGlobalNames = new java.util.HashSet<>();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if ((mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) && line.size() >= 3) {
                String name = line.get(1).text;
                if (!name.contains(".")) {
                    plainGlobalNames.add(name);
                }
            }
            if (mnemonic.equals("FUNC_START") && line.size() >= 2) {
                declaredFunctionNames.add(line.get(1).text);
            }
        }
        // Pass 2: real declarations, aliasing dotted fields of a known
        // parent block instead of giving them their own storage.
        Map<String, Long> parentRunningOffset = new LinkedHashMap<>();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if (mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) {
                // "GLOBAL name size" (bss, zero-init) or
                // "GLOBAL name size initVal" (data, non-zero init) --
                // "ALLOC_STATIC name size initVal" is the identical
                // shape for a function-local 'static', just declared at
                // its first-use point inside a function body instead of
                // at top level.
                String name = line.get(1).text;
                long size = Long.parseLong(line.get(2).text);
                int dot = name.lastIndexOf('.');
                String direct = dot >= 0 ? name.substring(0, dot) : null;
                // A nested member ("b.0.x" of a static array of structs) has an already-aliased direct parent ("b.0"): it lives in the ROOT
                // global at (parent's offset + running offset inside the parent). Before, only a plain parent was aliased, so the members of
                // every struct element kept their own stray storage and the array itself was emitted zeroed.
                if (direct != null && (plainGlobalNames.contains(direct) || globalAliasOffset.containsKey(direct)) && !globalAliasOffset.containsKey(name)) {
                    boolean nested = globalAliasOffset.containsKey(direct);
                    String parent = nested ? globalAliasParent.get(direct) : direct;
                    long offset = (nested ? globalAliasOffset.get(direct) : 0L) + parentRunningOffset.getOrDefault(direct, 0L);
                    globalAliasOffset.put(name, offset);
                    globalAliasParent.put(name, parent);
                    parentRunningOffset.put(direct, parentRunningOffset.getOrDefault(direct, 0L) + size);
                    if (line.size() >= 4) {
                        // The element's own initial value (a static array
                        // literal's elements arrive as "GLOBAL a.0 4 1.5" ...)
                        // -- kept so the parent's storage can be emitted with
                        // them; before, they were dropped and every static
                        // array started zeroed.
                        aliasInits.computeIfAbsent(parent, k -> new ArrayList<>())
                                .add(new long[]{offset, size, initBits(line.get(3).text, size)});
                    }
                    continue;
                }
                if (line.size() >= 4) {
                    long init = initBits(line.get(3).text, size);
                    globalsSizeInit.put(name, new long[]{size, init});
                    globalsHasInit.put(name, true);
                } else if (!globalsSizeInit.containsKey(name)) {
                    globalsSizeInit.put(name, new long[]{size, 0});
                    globalsHasInit.put(name, false);
                }
            } else if (mnemonic.equals("STRING") && line.size() >= 3) {
                String id = line.get(1).text;
                String text = line.get(2).text;
                // Strip the surrounding quotes the parser keeps on a
                // STRING literal token -- real .asciz wants the raw text.
                if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                }
                stringLiterals.put(id, text);
            }
        }
    }

    /** A GLOBAL initialiser's bit pattern for a slot of `size` bytes: null/integer as before, a float literal as its IEEE-754 bits (4 bytes: single, otherwise double) -- previously any float initialiser was silently zero. */
    private long initBits(String text, long size) {
        if (!text.equals("null") && !isInteger(text) && isFloatLiteral(text)) {
            return size <= 4
                    ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(text)))
                    : Double.doubleToRawLongBits(Double.parseDouble(text));
        }
        return parseInitValue(text);
    }

    private long parseInitValue(String text) {
        if (text.equals("null")) {
            return 0;
        }
        if (isInteger(text)) {
            return Long.parseLong(text);
        }
        // A float initializer, or anything else this first version
        // doesn't resolve to a plain integer -- zero-initialize rather
        // than guess at a bit pattern.
        return 0;
    }

    private void emitDataSections() {
        if (!globalsSizeInit.isEmpty()) {
            raw(".data");
            for (Map.Entry<String, long[]> e : globalsSizeInit.entrySet()) {
                String label = mangleGlobalName(e.getKey());
                long size = e.getValue()[0];
                long init = e.getValue()[1];
                boolean hasInit = globalsHasInit.getOrDefault(e.getKey(), false);
                raw((".globl " + label));
                raw(label + ":");
                List<long[]> parts = aliasInits.get(e.getKey());
                if (parts != null) {
                    // A whole-block global whose elements carry initial
                    // values: lay the bytes out little-endian.
                    byte[] image = new byte[(int) size];
                    for (long[] part : parts) {
                        for (int b = 0; b < part[1] && part[0] + b < size; b++) {
                            image[(int) (part[0] + b)] = (byte) (part[2] >>> (8 * b));
                        }
                    }
                    StringBuilder bytes = new StringBuilder();
                    for (int b = 0; b < image.length; b++) {
                        bytes.append(b == 0 ? "" : ", ").append(image[b] & 0xFF);
                    }
                    raw(("    .byte ") + bytes);
                } else {
                    emitStorageDirective(size, hasInit ? init : 0);
                }
            }
        }
        if (!stringLiterals.isEmpty()) {
            raw(".section .rodata");
            for (Map.Entry<String, String> e : stringLiterals.entrySet()) {
                raw(e.getKey() + ":");
                raw(("    .asciz \"" + e.getValue() + "\""));
            }
        }
    }

    /** Emits `size` bytes of storage, in units of quad/long/word/byte, initialized to `init` (repeated/truncated as needed -- good enough for the scalar globals this pass actually sees; anything wider than 8 bytes is zero-filled regardless of `init`, since no observed GLOBAL line initializes a multi-word aggregate). */
    private void emitStorageDirective(long size, long init) {
        if (size == 8) {
            raw(("    .quad " + init));
        } else if (size == 4) {
            raw(("    .long " + init));
        } else if (size == 2) {
            raw(("    .word " + init));
        } else if (size == 1) {
            raw(("    .byte " + init));
        } else {
            raw(("    .zero " + size));
        }
    }

    /** "ghost_table.lockState" -> "ghost_table_lockState" -- '.' isn't valid in an assembly label; every GLOBAL declaration and every reference to it (PUSH/ADDR/ATOMIC_* of a bare name) goes through this same mangling, so the two always agree. */
    private String mangleGlobalName(String name) {
        return name.replace('.', '_');
    }

    private void emitHeader() {
        if (target == CodegenConfig.Target.WINDOWS_GNU_X64) {
            out.append("# Generated by caspien-codegen (target: windows_gnu x86-64, mingw-w64/GNU as)\n");
            out.append("# GAS AT&T syntax, win64 ABI -- assemble/link with x86_64-w64-mingw32-gcc.\n");
        } else {
            out.append("# Generated by caspien-codegen (target: linux x86-64)\n");
            out.append("# GAS AT&T syntax -- assemblable/runnable directly with as/ld/gcc.\n");
        }
    }

    private void emitFooter() {
    }

    private void comment(String text) {
        out.append("# ").append(text).append('\n');
    }

    /**
     * The single low-level text-emission sink every other emission helper
     * in this file ultimately funnels through -- which makes it the one
     * place that can both (a) redirect output into whichever call's own
     * buffer is currently open (`currentSink()`, `callBufferStack`) and
     * (b) track the real running stack-pointer delta a call's own
     * argument-marshalling sequence leaves behind (`argTrackDelta`) by
     * recognizing the handful of instruction shapes that actually move
     * `%rsp`/`rsp`, directly off the real text being emitted -- see
     * `argTrackDelta`'s own doc comment for why this has to measure the
     * real emitted instructions rather than infer intent from the
     * bytecode. Only ever silently *not* attributed when
     * `argTrackSuspendDepth > 0` (inside an `emitAlignedCall`-wrapped
     * nested call, whose own net effect is always zero by construction)
     * or when no "CC_START" bracket is currently open at all (ordinary
     * code outside any call's own argument marshalling never needs this).
     */
    private void raw(String text) {
        currentSink().append(text).append('\n');
        trackStackDelta(text);
    }

    /**
     * Recognizes the exact instruction shapes this backend ever emits
     * that move the real stack pointer by a literal, fixed amount --
     * `pushq %reg`/`push reg` (+8), `popq %reg`/`pop reg` (-8), `subq
     * $N, %rsp`/`sub rsp, N` (+N), `addq $N, %rsp`/`add rsp, N` (-N) --
     * and folds each into `argTrackDelta`. Deliberately does *not*
     * recognize an absolute assignment to rsp (`movq ..., %rsp`/`mov
     * rsp, ...`, the shape `emitAlignedCall`'s own restore and this same
     * fix's own "CC_START"/"CALL" reservation-and-restore both use) --
     * those aren't a *relative* move by a byte count this tracker could
     * even meaningfully add or subtract, and every real caller of one
     * already guarantees, by its own construction, that the net effect
     * across it is exactly zero.
     */
    private void trackStackDelta(String rawText) {
        if (argTrackSuspendDepth > 0 || argTrackDeltaStack.isEmpty()) {
            return;
        }
        String t = rawText.trim();
        long delta = 0;
        if (t.startsWith("pushq %") || (t.startsWith("push ") && !t.startsWith("pushq"))) {
            delta = 8;
        } else if (t.startsWith("popq %") || (t.startsWith("pop ") && !t.startsWith("popq"))) {
            delta = -8;
        } else if (t.startsWith("subq $") && t.endsWith(", %rsp")) {
            delta = parseRspDelta(t, "subq $", ", %rsp");
        } else if (t.startsWith("addq $") && t.endsWith(", %rsp")) {
            delta = -parseRspDelta(t, "addq $", ", %rsp");
        } else if (t.startsWith("sub rsp, ")) {
            delta = Long.parseLong(t.substring("sub rsp, ".length()).trim());
        } else if (t.startsWith("add rsp, ")) {
            delta = -Long.parseLong(t.substring("add rsp, ".length()).trim());
        }
        if (delta != 0) {
            argTrackDeltaStack.push(argTrackDeltaStack.pop() + delta);
        }
    }

    private long parseRspDelta(String t, String prefix, String suffix) {
        return Long.parseLong(t.substring(prefix.length(), t.length() - suffix.length()).trim());
    }

    /** push the 64-bit value in the given register onto the real machine stack. */
    private void pushReg(String reg) {
        raw(("    pushq %" + reg));
    }

    /** pop the top of the real machine stack into the given register. */
    private void popReg(String reg) {
        raw(("    popq %" + reg));
    }

    private void movMemToReg(String reg, long offset) {
        raw("    movq " + offset + "(%rbp), %" + reg);
        
    }

    private void movRegToMem(String reg, long offset) {
        raw("    movq %" + reg + ", " + offset + "(%rbp)");
        
    }

    private void leaMemToReg(String reg, long offset) {
        raw("    leaq " + offset + "(%rbp), %" + reg);
        
    }

    private void leaGlobalToReg(String reg, String globalName) {
        if (globalAliasOffset.containsKey(globalName)) {
            // An alias into a parent block's own storage (see
            // globalAliasOffset's doc comment) -- load the parent's
            // address and add the field's fixed offset, rather than
            // referencing a separate (nonexistent) label of its own.
            String parentLabel = mangleGlobalName(globalAliasParent.get(globalName));
            long offset = globalAliasOffset.get(globalName);
            raw("    leaq " + parentLabel + (offset != 0 ? "+" + offset : "") + "(%rip), %" + reg);
            
            return;
        }
        String label = mangleGlobalName(globalName);
        raw("    leaq " + label + "(%rip), %" + reg);
        
    }

    /** The RIP-relative memory operand of a global (an alias into a parent block adds its fixed offset): `label+off(%rip)`. */
    private String globalRipOperand(String globalName) {
        // "sym+N" (GlobalConstAddrPass: a global array element at a constant index) is the global plus N bytes
        long extra = 0;
        int plus = globalName.indexOf('+');
        if (plus > 0) {
            extra = Long.parseLong(globalName.substring(plus + 1));
            globalName = globalName.substring(0, plus);
        }
        if (globalAliasOffset.containsKey(globalName)) {
            String parentLabel = mangleGlobalName(globalAliasParent.get(globalName));
            long offset = globalAliasOffset.get(globalName) + extra;
            return parentLabel + (offset != 0 ? "+" + offset : "") + "(%rip)";
        }
        return mangleGlobalName(globalName) + (extra != 0 ? "+" + extra : "") + "(%rip)";
    }

    private void movImmToReg(String reg, long imm) {
        // 0 .. 0xFFFFFFFF: `movl $imm, %r32` (5-6 bytes, zero-extends, flags untouched) instead of a 10-byte movabs
        if (imm >= 0 && imm <= 0xFFFFFFFFL && sizedReg(reg, 4) != reg) {
            raw("    movl $" + imm + ", %" + sizedReg(reg, 4));
            return;
        }
        raw("    movq $" + imm + ", %" + reg);
    }

    // ---- Size-aware register/memory helpers ------------------------------

    /** al/ax/eax/rax-style sub-register name for a bare 64-bit register name and a byte width. */
    private String sizedReg(String reg64, int size) {
        switch (reg64) {
            case "rax": return size == 1 ? "al" : size == 2 ? "ax" : size == 4 ? "eax" : "rax";
            case "rbx": return size == 1 ? "bl" : size == 2 ? "bx" : size == 4 ? "ebx" : "rbx";
            case "rcx": return size == 1 ? "cl" : size == 2 ? "cx" : size == 4 ? "ecx" : "rcx";
            case "rdx": return size == 1 ? "dl" : size == 2 ? "dx" : size == 4 ? "edx" : "rdx";
            case "rsi": return size == 1 ? "sil" : size == 2 ? "si" : size == 4 ? "esi" : "rsi";
            case "rdi": return size == 1 ? "dil" : size == 2 ? "di" : size == 4 ? "edi" : "rdi";
            default:
                if (reg64.matches("r(8|9|1[0-5])") && size < 8) {
                    return reg64 + (size == 1 ? "b" : size == 2 ? "w" : "d");
                }
                return reg64;
        }
    }

    /** Sign-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8). */
    private void signExtendReg(String reg, int size) {
        if (size >= 8) {
            return;
        }
        if (size == 4) {
            raw("    movslq %" + sizedReg(reg, 4) + ", %" + reg);
        } else {
            raw("    movs" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    /**
     * The shared core of every variable-count shift (stack form SHL/SHR/SAR, register-form rfShift). On entry %rcx holds the shift
     * count already zero-extended from the operand width (the caller saved %rcx and loaded it); `v` is a 64-bit register holding
     * the value (stale bits above `size` bytes allowed). On exit `v` holds the result, zero-extended from `size` bytes, and %rcx
     * (and %r15 for SAR) are clobbered. The rule (see the SHL case): count >= 64 gives 0 (SHL/SHR) / sign fill (SAR); a count
     * in [size*8, 63] needs no special case, because SHL's extra bits fall off the truncation, SHR's zero-extended operand has
     * no bits left, and SAR's sign-extended operand already is all sign bits.
     */
    private void emitShiftCore(String op, int size, String v) {
        if (op.equals("SAR")) {
            signExtendReg(v, size);
            // count = min(count, 63)
            movImmToReg(RF_SCRATCH, 63);
            raw("    cmpq %" + RF_SCRATCH + ", %rcx");
            raw("    cmovaq %" + RF_SCRATCH + ", %rcx");
            raw("    sarq %cl, %" + v);
            
            zeroExtendReg(v, size);
            return;
        }
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        raw("    " + (left ? "shlq" : "shrq") + " %cl, %" + v);
        raw("    cmpq $64, %rcx");
        raw("    sbbq %rcx, %rcx");
        raw("    andq %rcx, %" + v);
        
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    /**
     * BMI2 form of a variable SHL/SHR (same meaning as emitShiftCore: a count >= 64 gives 0): `shlx/shrx` take the count from any register (no
     * %rcx save/restore), then the same compare-and-mask removes a count >= 64. `cnt` holds the zero-extended count and is overwritten (the mask).
     */
    private void emitShiftBmi2(String op, int size, String v, String cnt) {
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        raw("    " + (left ? "shlxq" : "shrxq") + " %" + cnt + ", %" + v + ", %" + v);
        raw("    cmpq $64, %" + cnt);
        raw("    sbbq %" + cnt + ", %" + cnt);
        raw("    andq %" + cnt + ", %" + v);
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    /** Zero-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8 or an odd size). */
    private void zeroExtendReg(String reg, int size) {
        if (size != 1 && size != 2 && size != 4) {
            return;
        }
        if (size == 4) {
            raw("    movl %" + sizedReg(reg, 4) + ", %" + sizedReg(reg, 4));
        } else {
            raw("    movz" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    private String movSuffix(int size) {
        return size == 1 ? "b" : size == 2 ? "w" : size == 4 ? "l" : "q";
    }

    /**
     * Rounds a byte count up to a real, loadable hardware width -- 1, 2,
     * 4, or 8 -- the only widths a single x86 load instruction actually
     * has (there is no such thing as a 3, 5, 6, or 7-byte load; "movzqq"
     * -- naively asking `movSuffix` for one -- isn't a real instruction
     * at all, and used to be emitted verbatim, a genuine, confirmed
     * assembler-rejected gap: a struct with a packed sub-word array
     * member, e.g. `letters: mut char[3]`, produces exactly this "PUSH 3
     * $offset" shape whenever that member is read back by value (a
     * `LOOKUP_ARRAY` on it). Over-reading a few extra bytes past the
     * real value is harmless here: `ALLOC` always rounds a frame's own
     * size up to 16 bytes, so there is always a little slack past any
     * field; and every real consumer of a sub-8-byte loaded value only
     * ever reads back its own declared low `size` bytes (an array
     * element extraction, a narrow comparison, ...), never the whole
     * 8-byte register as one opaque scalar, so garbage sitting in the
     * unused high bytes is never actually observed.
     */
    private static int roundUpToLoadableWidth(int size) {
        return size <= 2 ? size : size <= 4 ? 4 : 8;
    }

    /** zero-extending load of `size` bytes at (%rbp+offset) into the full 64-bit `reg64`. */
    private void loadSizedFromFrame(String reg64, long offset, int size) {
        int loadSize = roundUpToLoadableWidth(size);
        if (loadSize == 8) {
            raw("    movq " + offset + "(%rbp), %" + reg64);
        } else if (loadSize == 4) {
            // movl auto-zero-extends into the full 64-bit register.
            raw("    movl " + offset + "(%rbp), %" + sizedReg(reg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + offset + "(%rbp), %" + reg64);
        }
    }

    /** store the low `size` bytes of `reg64` to (%rbp+offset). */
    private void storeSizedToFrame(String reg64, long offset, int size) {
        if (isOddSize(size)) {
            storeOddSize(reg64, "rbp", offset, size);
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(reg64, size) + ", " + offset + "(%rbp)");
    }

    /** zero-extending load of `size` bytes at (%addrReg) into `destReg64`. Same over-read-past-a-non-power-of-2-size reasoning as `loadSizedFromFrame` -- see `roundUpToLoadableWidth`'s own doc comment. */
    private void loadSizedFromAddr(String destReg64, String addrReg64, int size) {
        int loadSize = roundUpToLoadableWidth(size);
        String mem = rfMemOperand(addrReg64);
        if (loadSize == 8) {
            raw("    movq " + mem + ", %" + destReg64);
        } else if (loadSize == 4) {
            raw("    movl " + mem + ", %" + sizedReg(destReg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + mem + ", %" + destReg64);
        }
    }

    /**
     * Cuts a field / element of `size` (> 8) bytes out of the pushed value block of `totalWords` words and leaves it as a block of its own on the
     * stack (rsp raised past the words that are no longer needed). On entry `rax` = address of the field's first byte inside the block and `r14` =
     * that byte's offset inside its word (0..7). Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word, so the
     * field's bytes are NOT contiguous in memory unless it starts on a word boundary: result word j is the 64 bits starting at byte `within` of source
     * word w+j, continued into word w+j+1 (8 bytes BELOW it), which is exactly `shrd` (count 0 for an aligned field leaves the word unchanged).
     * Result word j goes to the slot of source word j (rsp + (T-1-j)*8): once rsp is raised by (T - E)*8 that is where it must be (rsp' + (E-1-j)*8),
     * and writing in ascending j never overwrites a word still to be read. The word past the block end (read for the last, possibly partial, result
     * word) lies just below rsp: valid stack, and only bytes beyond the field come from it.
     * %rcx is the shift count register and may hold an already-loaded call argument: it is stashed in r13 (backend scratch).
     * (Used by LOOKUP_ARRAY for an element wider than a word, and by DOT for a field wider than a word: a field that is not a whole number of
     * words used to be cut out with a shrink by `total*8 - size` bytes, which is only right when the field's offset and size are whole words.)
     */
    private void extractWordsFromReversedBlock(long totalWords, int size) {
        long elemWords = (size + 7) / 8;
        raw("    movq %rax, %r11");
        raw("    subq %r14, %r11");
        raw("    shlq $3, %r14");
        raw("    movq %rcx, %r13");
        raw("    movq %r14, %rcx");
        
        for (long j = 0; j < elemWords; j++) {
            long lo = -(j * 8);
            long hi = -(j * 8 + 8);
            long dst = (totalWords - 1 - j) * 8;
            raw("    movq " + lo + "(%r11), %rax");
            raw("    movq " + hi + "(%r11), %r14");
            raw("    shrdq %cl, %r14, %rax");
            raw("    movq %rax, " + dst + "(%rsp)");
            
        }
        raw("    movq %r13, %rcx");
        if (totalWords > elemWords) {
            raw(("    addq $" + ((totalWords - elemWords) * 8) + ", %rsp"));
        }
    }


    /** 3, 5, 6 and 7 bytes: no single x86 store has these widths. */
    private static boolean isOddSize(int size) {
        return size == 3 || (size >= 5 && size <= 7);
    }

    /**
     * Stores exactly the low `size` bytes (3, 5, 6 or 7) of `srcReg64` at `off(base)`, as a 4/2/1-byte sequence. A single 8-byte store here (what
     * `movSuffix`/`sizedReg` used to fall back to) writes 1 to 5 bytes too many: past the end of a packed construction image that is the
     * pointer sitting just above it (a `u16[3][2]` built from two row variables lost the low 2 bytes of its destination address), in a frame
     * it is the neighbouring variable. The source register is rotated between the pieces and rotated back, so it is unchanged afterwards.
     */
    private void storeOddSize(String srcReg64, String baseReg64, long off, int size) {
        int[] widths = size == 3 ? new int[] {2, 1} : size == 5 ? new int[] {4, 1} : size == 6 ? new int[] {4, 2} : new int[] {4, 2, 1};
        long at = off;
        int rot = 0;
        for (int i = 0; i < widths.length; i++) {
            storePiece(srcReg64, baseReg64, at, widths[i]);
            at += widths[i];
            if (i + 1 < widths.length) {
                rotReg(srcReg64, true, widths[i] * 8);
                rot += widths[i] * 8;
            }
        }
        if (rot > 0) {
            rotReg(srcReg64, false, rot);
        }
    }

    private void storePiece(String srcReg64, String baseReg64, long off, int width) {
        raw("    mov" + movSuffix(width) + " %" + sizedReg(srcReg64, width) + ", " + (off == 0 ? "" : String.valueOf(off)) + "(%" + baseReg64 + ")");
        
    }

    private void rotReg(String reg64, boolean right, int bits) {
        String op = right ? "ror" : "rol";
        raw(("    " + op + "q $" + bits + ", %" + reg64));
    }

    /** store the low `size` bytes of `srcReg64` to (%addrReg64). */
    private void storeSizedToAddr(String srcReg64, String addrReg64, int size) {
        if (isOddSize(size)) {
            storeOddSize(srcReg64, addrReg64, 0, size);
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(srcReg64, size) + ", " + rfMemOperand(addrReg64));
    }

    /** pushes a >8-byte value stored at (%rbp+baseOffset), one whole word at a time, lowest source offset first -- the highest-offset word ends up on top of the real stack. Mirror of `assignBlock`/POP's own block-store below. */
    private void pushBlockFromFrame(long baseOffset, long size) {
        long words = (size + 7) / 8;
        for (long i = 0; i < words; i++) {
            loadSizedFromFrame("rax", baseOffset + i * 8, 8);
            pushReg("rax");
        }
    }

    /**
     * The construction-aware sibling of `pushBlockFromFrame`, used only
     * when this exact "PUSH size $offset" line is itself a direct member
     * of a construction run `precomputeConstructionOffsets` already
     * resolved (see that "PUSH" case's own call site for the real bug
     * this fixes). Unlike `pushBlockFromFrame` -- which reverses word
     * order, matching DEREF's own "highest-offset word on top" pushed-
     * value convention -- this reserves exactly this entry's own
     * declared width and copies its bytes straight across, front to
     * back, preserving the source's own real, natural, ascending-offset
     * order, landing them at the exact packed position
     * (`constructionOffset`, possibly negative -- see
     * `storeConstructionOrPlainPush`'s own doc comment for why that's
     * both expected and safe) the run's own layout already computed for
     * it. `%rcx` is saved/restored around the copy for the identical
     * reason `assignConstructionBlock` already does -- this can run as
     * one entry inside a still-in-progress call's own argument
     * marshalling, where `%rcx` may already be carrying a live,
     * not-yet-consumed incoming parameter.
     */
    private void pushBlockFromFrameConstructionAware(long baseOffset, long size) {
        Long constructionOffset = pushStoreOffsetByLine.get(currentLineIndex);
        if (constructionOffset == null) {
            pushBlockFromFrame(baseOffset, size);
            return;
        }
        // Copy through rax in 8/4/2/1-byte pieces. This used to be `rep movsb` with rdi/rsi/rcx and a `push rcx` around it: the push writes 8 bytes
        // just below rsp, which is where the fields of this construction stored EARLIER already sit (each field is stored at its final place,
        // below the current rsp, before rsp reaches it), so an earlier narrow field (`tag: u8` before an array member) was overwritten; and rdi/rsi
        // are argument registers that an enclosing call may already have loaded.
        raw("    subq $" + size + ", %rsp");
        
        long pos = 0;
        while (pos < size) {
            long left = size - pos;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            loadSizedFromFrame("rax", baseOffset + pos, w);
            long dst = constructionOffset + pos;
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + dst + "(%rsp)");
            
            pos += w;
        }
    }

    /** the block-store counterpart of `pushBlockFromFrame`/DEREF's own multi-word push: pops a >8-byte value (top of stack = highest-offset word) together with the single-word address pushed just before it, and stores each word to its real position. The address is read non-destructively first (it sits `size` bytes below the current top, past the whole value block) so it can still be located after the value words are popped off. */
    private void assignBlock(long size) {
        long words = (size + 7) / 8;
        raw("    movq " + (words * 8) + "(%rsp), %r15");
        
        for (long i = 0; i < words; i++) {
            popReg("rax");
            // Word k of the value sits at byte 8k. The top word popped first is the highest one, k = words-1. (This used `size - 8 - 8i`, which is
            // the same only when size is a whole number of words: a 12- or 20-byte array copy (`let a = mut s.v`) put its words 4 bytes too low.)
            long k = words - 1 - i;
            long destOff = k * 8;
            long n = Math.min(8, size - destOff); // the last word of a size that is not a multiple of 8 is partial: write only its real bytes
            if (n == 8) {
                raw("    movq %rax, " + (destOff == 0 ? "" : String.valueOf(destOff)) + "(%r15)");
                
            } else if (isOddSize((int) n)) {
                storeOddSize("rax", "r15", destOff, (int) n);
            } else {
                storePiece("rax", "r15", destOff, (int) n);
            }
        }
        // The value block is now fully popped; the address word pushed
        // ahead of it is still sitting on top -- drop it for real now
        // that it's been read.
        raw("    addq $8, %rsp");
    }

    /**
     * The construction-scoped counterpart of `assignBlock`, used only
     * when `constructionScopedAssign` confirms this exact "ASSIGN" line
     * is a plain, stack-resident struct/array/primitive literal's own
     * top-level store (see that field's own doc comment for the bug
     * this fixes). Stack, top to bottom: a real, byte-exact,
     * `size`-byte tightly-packed image of the value (every push in the
     * run reserved only its own declared width), then the single-word
     * destination address pushed ahead of it. Mirrors `case "NEW"`'s own
     * "genuinely trivial and completely struct-agnostic" copy exactly --
     * one straight `rep movsb` block copy, no field count, no offsets,
     * nothing type-specific -- except the destination is this already-
     * pushed address rather than a fresh `malloc` buffer, so there's no
     * allocation call here at all.
     */
    // 2026-09: fixed a real, silent register-clobber bug here -- see
    // this project's own CLAUDE.md, "assignConstructionBlock clobbered
    // %rcx" -- pushq/popq %rcx now bracket the rep movsb counter setup
    // so a still-unread incoming argument register (SysV ARG3/win64
    // ARG3, both %rcx) survives this method's own use of it.
    private void assignConstructionBlock(long size) {
        if (size > 0 && size % 8 == 0 && size <= 64) {
            // A small whole-word block (a range is 16 bytes, built on every `for` entry) is copied word by word: no rep prefix, no
            // rcx save/restore. Only r15 (destination) and rdi (data) are used; both were already clobbered by the general path below.
            raw("    movq " + size + "(%rsp), %r15");
            for (long o = 0; o < size; o += 8) {
                raw("    movq " + o + "(%rsp), %rdi");
                raw("    movq %rdi, " + o + "(%r15)");
            }
            raw("    addq $" + (size + 8) + ", %rsp");
            
            return;
        }
        raw("    movq " + size + "(%rsp), %r15"); // destination address, sitting just past the value block
        raw("    movq %r15, %rdi");
        raw("    movq %rsp, %rsi");
        raw("    pushq %rcx"); // rcx may still hold a live, not-yet-consumed incoming argument (SysV's ARG3) -- rep movsb needs it as a byte counter, so save/restore around that use rather than clobbering it
        raw("    movq $" + size + ", %rcx");
        raw("    rep movsb");
        raw("    popq %rcx");
        raw("    addq $" + size + ", %rsp");
        raw("    addq $8, %rsp"); // the address word itself, now that it's been read
        
    }

    /**
     * The byte count of the MEMCOPY on the current line when it is a literal of at most 64 bytes, else -1. The line shape is
     * "[dest expr] PUSH 8 <size> [src expr] MEMCOPY" where the source expression is one value ("PUSH 8 $x", optionally followed by
     * "ADDR_OF ..."): walk back over the lines after the size push and require that they leave exactly one value.
     */
    private long memcopyConstantSize() {
        int values = 0;
        for (int back = 1; back <= 4; back++) {
            int idx = currentLineIndex - back;
            List<BytecodeToken> l = allLines == null || idx < 0 ? null : allLines.get(idx);
            if (l == null || l.isEmpty()) {
                return -1;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR_OF")) {
                continue;
            }
            if (!m.equals("PUSH") || l.size() < 3) {
                return -1;
            }
            String operand = l.get(2).text;
            if (operand.matches("\\d+") && l.get(1).text.equals("8")) {
                if (values != 1) {
                    return -1;
                }
                long v = Long.parseLong(operand);
                return v >= 1 && v <= 64 ? v : -1;
            }
            values++;
            if (values > 1) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }

    /** "'a'", "'\n'", "'\0'", ... -- a char-literal PUSH operand, exactly as `BytecodeEmitter.escapeForBytecode` serializes one: an opening single quote, one real character's worth of content, a closing single quote. Deliberately checked structurally (quote ... quote) rather than by first ruling out every other operand shape, since a bare identifier can never itself start *and* end with `'`. */
    private static boolean isCharLiteral(String s) {
        return s.length() >= 3 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'';
    }

    /**
     * Decodes a char-literal PUSH operand back to its real byte value --
     * the exact inverse of `BytecodeEmitter.escapeForBytecode`, which
     * this must stay in lockstep with: every one of that method's own
     * escape outputs ("\\\\", "\\\"", "\\n", "\\t", "\\r", "\\0", "\\b",
     * "\\f", "\\a", "\\v") is decoded back here, plus the plain,
     * unescaped-single-byte case for every other character. A char is
     * always exactly one byte in this language (`PRIMITIVE_SIZE.put(
     * "char", 1L)` in the front end), so the result is always a single
     * 0-255 value, never a multi-byte sequence.
     */
    private static long decodeCharLiteral(BytecodeToken operandTok) {
        String operand = operandTok.text;
        String content = operand.substring(1, operand.length() - 1);
        char value;
        if (content.length() == 1) {
            value = content.charAt(0);
        } else if (content.length() == 2 && content.charAt(0) == '\\') {
            switch (content.charAt(1)) {
                case '\\': value = '\\'; break;
                case '"': value = '"'; break;
                case 'n': value = '\n'; break;
                case 't': value = '\t'; break;
                case 'r': value = '\r'; break;
                case '0': value = '\0'; break;
                case 'b': value = '\b'; break;
                case 'f': value = '\f'; break;
                case 'a': value = 7; break; // bell -- no Java char-literal shorthand
                case 'v': value = 11; break; // vertical tab -- no Java char-literal shorthand
                default:
                    throw new CodegenException("codegen", operandTok.file, operandTok.line,
                            "unrecognized char-literal escape '" + operand + "'");
            }
        } else {
            throw new CodegenException("codegen", operandTok.file, operandTok.line,
                    "malformed char-literal operand '" + operand + "'");
        }
        return value;
    }

    // ---- Float (SSE) helpers -------------------------------------------------
    //
    // Every value on this backend's own value stack -- float bits included
    // -- already travels as opaque bytes through the ordinary
    // push/pop-a-64-bit-GPR mechanism (see PUSH's own isFloatLiteral
    // branch above, and ASSIGN/frame-load, neither of which interprets a
    // float's bits any differently from an int's). What genuinely needs
    // the separate SSE register file is *arithmetic*: `movq` between a
    // GPR and an xmm register moves the raw 64 bits across with zero
    // reinterpretation (the exact same "opaque bytes" property the
    // whole-word stack model already relies on), so every float op below
    // pops its operand(s) through the ordinary GPR path first, shuttles
    // them into xmm0/xmm1 with this one instruction, does the real
    // floating-point op there, and shuttles the result back the same way
    // before pushing it -- no persistent xmm allocation, matching this
    // pass's own documented "simplest correct mapping" philosophy
    // (CLAUDE.md, "What's implemented").

    private void movRegToXmm(String gpr64, String xmmReg) {
        raw(("    movq %" + gpr64 + ", %" + xmmReg));
    }

    private void movXmmToReg(String xmmReg, String gpr64) {
        raw(("    movq %" + xmmReg + ", %" + gpr64));
    }

    /** "ss" (single-precision, 4-byte) vs "sd" (double-precision, 8-byte) SSE instruction suffix for a given byte size. `f32` is the only floating-point type this language has at all (checked directly, not assumed -- see FLOAT_CHECK's own doc comment below), so every real call site passes 4 here; the 8-byte ("sd") branch is kept only so this doesn't silently mishandle a size this pass has never actually seen, not because anything in the language can produce one today. */
    private String sseSuffix(int size) {
        return size <= 4 ? "ss" : "sd";
    }

    /** reads `size` bytes at (%addrReg64) and pushes them: a single sized load+push for size<=8, or a whole-word-at-a-time block push (lowest source offset first, highest last/top) for a larger aggregate -- the read-side counterpart of DOT/LOOKUP_ARRAY/LOOKUP_DYN's own "_LHS" (address-only) variants, generalized to structs/array elements bigger than one register. `addrReg64` is clobbered. */
    private void pushSizedOrBlockFromAddr(String addrReg64, int size) {
        if (size <= 8) {
            loadSizedFromAddr("rax", addrReg64, size);
            pushReg("rax");
            return;
        }
        long words = (size + 7) / 8;
        // The word is loaded into r11, never rax: callers such as
        // LOOKUP_DYN pass "rax" as the address register, and loading
        // the first word into it destroyed the address before the
        // second word was read (every dynarray element wider than one
        // word -- any struct -- read back garbage).
        for (long i = 0; i < words; i++) {
            loadSizedFromAddr("r11", addrReg64, 8);
            if (i < words - 1) {
                raw(("    addq $8, %" + addrReg64));
            }
            pushReg("r11");
        }
    }

    // ---- Calling convention ------------------------------------------------

    private static final String[] LINUX_ARG_REGS = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
    private static final String[] WIN_ARG_REGS = {"rcx", "rdx", "r8", "r9"};
    private static final String[] LINUX_ARG_REGS_FLOAT =
            {"xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7"};
    private static final String[] WIN_ARG_REGS_FLOAT = {"xmm0", "xmm1", "xmm2", "xmm3"};

    private String argReg(int index) {
        String[] table = isWinAbi() ? WIN_ARG_REGS : LINUX_ARG_REGS; // register choice is an ABI question, not a syntax one -- WINDOWS_GNU_X64 uses these same win64 registers, just named/referenced in AT&T syntax
        if (index < table.length) {
            return table[index];
        }
        // Beyond the register-passed args: this first version doesn't
        // marshal stack-passed arguments -- no fixture this pass was
        // verified against needs a 5th+ (Linux) / 5th+ (Windows) arg.
        return "rax";
    }

    /** The float-bank counterpart of `argReg` -- `ARGn`/`FARGn`'s own real per-bank index already accounts for the two conventions' different counting rules (see `AddressLoweringPass.resolveArgWordSlots`/`CompilerConfig.CallingConvention.sharedArgumentPosition` on the sibling lowering project), so this is a plain, dumb table lookup, exactly like `argReg` -- no convention-mode awareness needed here at all. */
    private String argFloatReg(int index) {
        String[] table = isWinAbi() ? WIN_ARG_REGS_FLOAT : LINUX_ARG_REGS_FLOAT;
        if (index < table.length) {
            return table[index];
        }
        return "xmm0"; // beyond the register-passed args -- same documented stack-argument gap argReg's own fallback has.
    }

    /**
     * Wraps a call sequence with 16-byte stack alignment, saving and
     * restoring the *exact* current rsp around it -- needed because
     * this backend's own stack-machine-to-real-stack translation
     * pushes/pops arbitrarily many words between calls, so rsp's
     * alignment at any given `call` site isn't otherwise known or
     * guaranteed, and an unaligned rsp across a real `call` is
     * undefined behavior per the x86-64 ABI (some libc routines really
     * do fault on it). Restoring the saved rsp afterward keeps this
     * backend's own "abstract stack depth" bookkeeping exactly correct
     * regardless of the alignment adjustment.
     *
     * The pre-alignment rsp used to be stashed in %r13 for the
     * duration of the call, on the theory that r13 is callee-saved and
     * so survives any well-behaved call -- true for a real, externally
     * compiled function (malloc/printf/...), which genuinely honors
     * the ABI's callee-saved registers, but **false** for a call into
     * a function this same backend generated: `FUNC_START`/`FUNC_END`
     * only ever save/restore %rbp, never r12-r15/rbx, so a
     * Caspien-compiled callee that itself makes further calls reuses
     * r13 for its own identical save/align/restore dance around each
     * of *its* calls, silently overwriting the value the outer call
     * was relying on. Root-caused directly against `gt_register`
     * (itself calling `gtSlotAt`/`gtReadSlot`): confirmed via gdb that
     * r13 held the correct pre-call rsp immediately before `call
     * gt_register`, but held something else entirely (off by over a
     * hundred bytes -- gt_register's own last internal save) the
     * instant it returned, which was then blindly copied into rsp,
     * corrupting the caller's whole abstract stack depth from that
     * point on.
     *
     * Fixed by not trusting *any* register to survive the call at
     * all -- the pre-alignment rsp is stashed in a dedicated stack
     * slot instead, reserved right at the call site. A stack slot
     * can't be silently reused by a nested call the way a shared
     * register can: a nested call just gets its own slot, further
     * down, out of the way. This relies on nothing but the one
     * invariant every function here already upholds regardless of
     * what it's compiled from -- a well-behaved callee (real or
     * Caspien-compiled) always returns with rsp exactly where it was
     * the moment the `call` instruction executed -- so the slot is
     * still exactly where it was left, at a fixed offset from the
     * (unchanged) rsp, the instant the call returns.
     */
    /**
     * Wraps a call sequence with 16-byte stack alignment, saving and
     * restoring the *exact* current rsp around it -- needed because
     * this backend's own stack-machine-to-real-stack translation
     * pushes/pops arbitrarily many words between calls, so rsp's
     * alignment at any given `call` site isn't otherwise known or
     * guaranteed, and an unaligned rsp across a real `call` is
     * undefined behavior per the x86-64 ABI (some libc routines really
     * do fault on it). Restoring the saved rsp afterward keeps this
     * backend's own "abstract stack depth" bookkeeping exactly correct
     * regardless of the alignment adjustment.
     *
     * The pre-alignment rsp used to be stashed in %r13 for the
     * duration of the call, on the theory that r13 is callee-saved and
     * so survives any well-behaved call -- true for a real, externally
     * compiled function (malloc/printf/...), which genuinely honors
     * the ABI's callee-saved registers, but **false** for a call into
     * a function this same backend generated: `FUNC_START`/`FUNC_END`
     * only ever save/restore %rbp, never r12-r15/rbx, so a
     * Caspien-compiled callee that itself makes further calls reuses
     * r13 for its own identical save/align/restore dance around each
     * of *its* calls, silently overwriting the value the outer call
     * was relying on. Root-caused directly against `gt_register`
     * (itself calling `gtSlotAt`/`gtReadSlot`): confirmed via gdb that
     * r13 held the correct pre-call rsp immediately before `call
     * gt_register`, but held something else entirely (off by over a
     * hundred bytes -- gt_register's own last internal save) the
     * instant it returned, which was then blindly copied into rsp,
     * corrupting the caller's whole abstract stack depth from that
     * point on.
     *
     * Fixed by not trusting *any* register to survive the call at
     * all -- the pre-alignment rsp is stashed in a dedicated stack
     * slot instead, reserved right at the call site. A stack slot
     * can't be silently reused by a nested call the way a shared
     * register can: a nested call just gets its own slot, further
     * down, out of the way. This relies on nothing but the one
     * invariant every function here already upholds regardless of
     * what it's compiled from -- a well-behaved callee (real or
     * Caspien-compiled) always returns with rsp exactly where it was
     * the moment the `call` instruction executed -- so the slot is
     * still exactly where it was left, at a fixed offset from the
     * (unchanged) rsp, the instant the call returns.
     */
    /**
     * How many bytes `case "CC_START"` reserves below its own aligned
     * rsp, *before* `stackArgBytes` worth of stack-passed argument words
     * get pushed on top of that reservation and *before* the `call`
     * itself pushes its own 8-byte return address -- chosen so that,
     * after both of those, rsp lands back on a real 16-byte boundary
     * (the x86-64 ABI's own call-site alignment requirement) regardless
     * of `stackArgBytes`'s own value.
     *
     * `stackArgBytes` is always a multiple of 8 (every real `pushq`/`popq`/
     * `add rsp,N`/`sub rsp,N` `trackStackDelta` ever recognizes moves rsp
     * by a whole 8-byte word or a whole multiple of one -- see
     * `argTrackDeltaStack`'s own doc comment). Reasoned from a 16-aligned
     * starting point (right after "and rsp, -16"): the x86-64 ABI's own
     * rule is that %rsp must read 0 (mod 16) at the exact point the
     * `call` instruction executes -- confirmed directly against the real
     * ABI text ("the value (%rsp + 8) is always a multiple of 16 when
     * control is transferred to the function entry point," i.e.
     * immediately *after* `call`'s own 8-byte return-address push, which
     * only holds if %rsp was 0 mod 16 immediately *before* it) and
     * against this exact file's own long-since-verified, hardcoded
     * zero-stack-arg case (`emitAlignedCall`'s fixed `subq $16, %rsp`
     * reservation, with nothing pushed between it and `call`, which only
     * ever worked because 16-aligned-minus-16 is still 0 mod 16). An
     * 8-byte-granular reservation followed by an 8-byte-granular
     * argument push always lands on an even multiple of 8, so the only
     * question is *which* multiple -- exactly 0 (mod 16) when
     * `stackArgBytes` is itself a multiple of 16 (needing a full 16-byte
     * reservation to stay at 0), exactly 8 (mod 16, needing only an
     * 8-byte reservation to land back on 0) otherwise.
     *
     * **This ternary was previously inverted -- a real, confirmed bug,
     * not a hypothetical one.** The two, pre-existing overflow fixtures
     * that first verified this whole reservation/restore mechanism
     * (`register_overflow_args_even_cg_test`/`_odd_cg_test`) both call a
     * plain, all-integer function -- one that never performs any
     * alignment-sensitive SSE stack access -- so both silently tolerated
     * a call site that was, in fact, misaligned by 8 bytes the entire
     * time. This surfaced for real only once a call combining a
     * stack-passed argument *with* a genuinely alignment-sensitive
     * callee was tested: `printf(...)` with one float argument pushed
     * onto the stack (a true C varargs call, whose own glibc
     * implementation spills its incoming xmm registers into its
     * register-save area with alignment-requiring `movaps`) segfaulted
     * deep inside `__printf` itself -- confirmed via gdb to be a stack-
     * alignment fault, not a value-correctness one (the pushed argument's
     * own value, and every register-passed one, were already confirmed
     * correct by that point). Fixed by swapping the ternary's two
     * branches to match the real requirement above.
     */
    private int alignPadFor(long stackArgBytes) {
        return (stackArgBytes % 16 == 0) ? 16 : 8;
    }

    /**
     * The `case "CALL"`/`case "INVOKE"` half of the stack-passed-argument
     * fix (see `argTrackDeltaStack`'s own doc comment): restores rsp
     * to its real pre-reservation value by reading the value
     * `resolveStackArgBytesAndCall`'s own reservation saved back out of
     * its own reserved slot. That slot's own address never moved, but rsp has, by exactly `stackArgBytes`
     * (the argument words pushed on top of it since) -- so it's read from
     * `[rsp+stackArgBytes]`, not `[rsp]`, at this point. Called only when
     * `stackArgBytes > 0`; the ordinary all-register-arguments case never
     * reaches this at all, still cleaning up through `emitAlignedCall`'s
     * own original, unchanged restore instead.
     */
    private void emitStackArgRestore(long stackArgBytes) {
        // A single memory-to-register mov straight into rsp -- no scratch
        // register at all, deliberately: %rax holds this call's own
        // just-returned value at exactly this point (for a non-void
        // callee), so staging through it here (an earlier version of this
        // fix did exactly that) would silently clobber the real return
        // value before "PUSH_RET_INT" ever gets to read it. Found via a
        // real, confirmed bug: with the alignment-timing fix alone in
        // place, `sum8(1..8)` correctly computed and returned 36 in %rax
        // (confirmed directly via gdb), only for the very next
        // instruction to immediately overwrite it with a stray stack
        // address. `emitAlignedCall`'s own original restore ("movq
        // 8(%rsp), %rsp") already avoided this trap the same way; this
        // mirrors it exactly, just at a variable offset.
        raw(("    movq " + stackArgBytes + "(%rsp), %rsp"));
    }

    /**
     * Builds the same four-instruction alignment reservation
     * `emitAlignedCall` uses, but as plain text to `insert` at the very
     * front of a call's own buffer (`callBufferStack`) rather than
     * `raw()`-emitting it immediately -- see `argTrackDeltaStack`'s own
     * doc comment for why this has to be inserted retroactively, once
     * `stackArgBytes` is finally known at the "CALL" line, rather than
     * emitted up front at "CC_START" the way the ordinary, zero-stack-arg
     * case's `emitAlignedCall` gets away with. Deliberately built as a
     * plain string (not via `raw()`) so it is never itself mistaken for a
     * real, trackable stack-pointer move by `trackStackDelta` -- its own
     * net effect, paired with `emitStackArgRestore`, is zero by
     * construction, exactly like `emitAlignedCall`'s own reservation.
     */
    private String buildStackArgReservation(long stackArgBytes) {
        int pad = alignPadFor(stackArgBytes);
        return "    movq %rsp, %rax\n"
                + "    andq $-16, %rsp\n"
                + "    subq $" + pad + ", %rsp\n"
                + "    movq %rax, (%rsp)\n";
    }

    /**
     * Puts the stack-passed argument words into the order the calling
     * conventions require: the FIRST stack-passed argument at the LOWEST
     * address (immediately above the return address), i.e. pushed
     * right-to-left. The front end evaluates and pushes arguments
     * left-to-right, so at the call the words sit in reverse: the first
     * stack-passed argument is deepest, the last is on top. A callee -- a
     * Caspien function reading `$16`, `$24`, ... or a C function such as
     * `printf` -- expects the opposite, so with two or more stack-passed
     * words the later ones were being read as the earlier ones (`eight(1,
     * ..., 8)` saw its seventh and eighth arguments swapped, and a
     * `printf` with eight integer varargs printed `7 8 6` for the last
     * three).
     *
     * Every stack-passed word is one whole 8-byte slot (`stackArgBytes` is
     * always a multiple of 8, see `alignPadFor`), and evaluation order
     * stays strictly left-to-right, so the fix is a plain in-place
     * reversal of those slots at the call, after every argument has been
     * produced. Uses rax and r11 as scratch (nothing live in either at
     * this point; r10 may hold an INVOKE target and is left alone).
     */
    private void reverseStackArgWords(long stackArgBytes) {
        long words = stackArgBytes / 8;
        for (long i = 0; i < words / 2; i++) {
            long lo = i * 8;
            long hi = (words - 1 - i) * 8;
            raw("    movq " + lo + "(%rsp), %rax");
            raw("    movq " + hi + "(%rsp), %r11");
            raw("    movq %r11, " + lo + "(%rsp)");
            raw("    movq %rax, " + hi + "(%rsp)");
            
        }
    }

    /**
     * The shared `case "CALL"`/`case "INVOKE"` dispatch for a bytecode-
     * level call site: reads this call's own real stack-passed-argument
     * byte count (`argTrackDeltaStack`'s own doc comment explains how it
     * got there) and either takes the ordinary, unmodified
     * `emitAlignedCall` path (zero stack-passed bytes -- the
     * overwhelmingly common case) or, when there is at least one real
     * stack-passed argument, retroactively prepends the correctly-sized
     * alignment reservation to the front of this call's own buffer
     * (`callBufferStack`, opened back at this call's own "CC_START"),
     * emits the bare call, and restores rsp afterward -- see
     * `argTrackDeltaStack`'s own doc comment for the full design this
     * implements.
     */
    private void resolveStackArgBytesAndCall(Runnable callBody) {
        if (argTrackDeltaStack.isEmpty() || callBufferStack.isEmpty()) {
            // No open "CC_START" bracket at all -- shouldn't happen for a
            // real bytecode-level "CALL"/"INVOKE" (every one is always
            // wrapped in its own "CC_START"/"CC_END"), but falls back to
            // the always-safe, zero-stack-arg path rather than guessing.
            emitAlignedCall(callBody);
            return;
        }
        long stackArgBytes = argTrackDeltaStack.peek();
        if (stackArgBytes > 0) {
            callBufferStack.peek().insert(0, buildStackArgReservation(stackArgBytes));
            // win64 always reserves its own fixed 32-byte "shadow space"
            // immediately below the return address, *in addition to* any
            // real stack-passed arguments (`AddressLoweringPass.
            // resolveArgWordSlots`'s own callee-side offset formula
            // already bakes this in as `convention.shadowStack`) --
            // `emitAlignedCall`'s own zero-stack-arg path already
            // reserves/undoes this around its own call; this path needs
            // the identical reservation, just placed *after* the stack-
            // passed arguments are pushed rather than immediately after
            // the alignment slot, so it ends up adjacent to the return
            // address exactly where the callee's own formula expects it.
            // 32 is already a multiple of 16, so it never changes the
            // alignment parity `alignPadFor` already solved for.
            // Suspended (not tracked) for the same reason `emitAlignedCall`
            // suspends its own identical reservation: it's fully undone
            // before this call's own bracket closes, so it has zero real
            // net effect worth attributing to anything.
            boolean winAbi = isWinAbi();
            argTrackSuspendDepth++;
            try {
                reverseStackArgWords(stackArgBytes);
                if (winAbi) {
                    raw("    subq $32, %rsp");
                } else {
                    // Identical SysV "%al = vector registers used" rule as
                    // `emitAlignedCall`'s own non-win64 branch -- see that
                    // site's own doc comment for the bug this fixes. This
                    // path (a call with at least one real stack-passed
                    // argument) previously set %al to nothing at all, always
                    // leaving whatever value happened to already be in %eax
                    // -- worth fixing alongside the zero-stack-arg site
                    // rather than leaving this adjacent, same-class gap
                    // standing (this path is reached only via the AT&T-
                    // syntax `linux` target -- the win64 path is
                    // unconditionally `winAbi`, so it can never reach this
                    // `else`).
                    raw("    movl $" + consumeVarargsXmmCount() + ", %eax");
                }
                callBody.run();
                if (winAbi) {
                    raw("    addq $32, %rsp");
                }
            } finally {
                argTrackSuspendDepth--;
            }
            emitStackArgRestore(stackArgBytes);
        } else {
            emitAlignedCall(callBody);
        }
    }

    private void emitAlignedCall(Runnable body) {
        // Suspended for this whole call (preamble, body, postamble alike)
        // -- see `argTrackSuspendDepth`'s own doc comment. Every
        // instruction below nets to exactly zero real stack effect by the
        // time this method returns, by construction; without this, the
        // preamble's own "sub rsp, 16"/"sub rsp, 32" would be tracked as
        // a real, surviving push (its own matching undo is an *absolute*
        // "mov rsp, ..." restore, a shape `trackStackDelta` deliberately
        // never recognizes as a relative move) and wrongly counted toward
        // an *enclosing* "CC_START"/"CALL" bracket's own stack-passed-
        // argument tally.
        argTrackSuspendDepth++;
        try {
            boolean winAbi = isWinAbi(); // shadow space vs SysV varargs %al -- an ABI question, independent of syntax
            raw("    movq %rsp, %rax");
            raw("    andq $-16, %rsp");
            raw("    subq $16, %rsp"); // reserved slot for the pre-align rsp -- keeps 16-alignment (subtracting a multiple of 16)
            raw("    movq %rax, 8(%rsp)");
            if (winAbi) {
                raw("    subq $32, %rsp"); // win64 shadow space (WINDOWS_GNU_X64, AT&T syntax)
            } else {
                // SysV varargs rule: %al must hold the number of vector
                // (xmm) registers used for a varargs call (printf, most
                // visibly). This used to be unconditionally zeroed here
                // on the claim "this backend never passes float args in
                // xmm registers at all yet" -- stale even at the time it
                // was written (register-passed float arguments already
                // went through "POP FARGn" into a real xmm register by
                // then) and the root cause of a real, confirmed bug:
                // printf("%f", floatValue) always printed 0.000000, even
                // for one single, non-overflowing float argument with
                // zero relation to varargs-argument-count overflow --
                // glibc's own printf, trusting this (wrong) claim of
                // "0 vector registers used," never even looks at %xmm0
                // to find the real value. Fixed by reading the real,
                // compile-time-known count the front end already
                // computed for this exact call (`consumeVarargsXmmCount`
                // -- see its own doc comment) instead of a hardcoded
                // constant. Win64 has no equivalent convention at all,
                // so this whole branch stays SysV-only.
                raw("    movl $" + consumeVarargsXmmCount() + ", %eax");
            }
            body.run();
            if (winAbi) {
                raw("    addq $32, %rsp"); // undo shadow space -- rsp is back at the reserved slot
            }
            raw("    movq 8(%rsp), %rsp"); // load the pre-align rsp straight into rsp -- also discards the reserved slot
            
        } finally {
            argTrackSuspendDepth--;
        }
    }

    private void emitCallByName(String name) {
        xvSpillAll();
        raw(("    call " + name));
        xvReloadAll();
    }

    private void emitCallIndirect(String reg64) {
        xvSpillAll();
        raw(("    call *%" + reg64));
        xvReloadAll();
    }

    // ---- Main per-line dispatch --------------------------------------------

    /**
     * Instructions whose code uses r12/r13/r14 as scratch (NEW*, CLONE*, RESIZE*, DOT, LOOKUP_ARRAY) are bracketed by two marks;
     * finishCalleeSaved turns them into saves/restores of the variable registers (%v0..%v2 = r13, r14, r12) of the functions that
     * actually keep a variable there, and deletes them from every other function (whose text is then exactly what it was without marks).
     */
    private static final String V1314_SAVE = "@@V1314S@@";
    private static final String V1314_REST = "@@V1314R@@";

    private void emitLine(List<BytecodeToken> line) {
        if (!line.isEmpty() && currentLineIndex > asmSkipUntilIndex && line.size() >= 1 && !(line.size() == 1 && line.get(0).text.endsWith(":"))
                && RF_R13_R14_USERS.contains(line.get(0).text) && !line.get(0).text.equals("ASM_START") && csrFuncStart >= 0) {
            raw(V1314_SAVE);
            emitLineInner(line);
            raw(V1314_REST);
            return;
        }
        emitLineInner(line);
    }

    private void emitLineInner(List<BytecodeToken> line) {
        if (line.isEmpty()) {
            return;
        }
        if (currentLineIndex <= asmSkipUntilIndex) {
            // Already copied verbatim by the "ASM_START" case below.
            return;
        }
        String first = line.get(0).text;

        // A bare label line, e.g. "@loop_1:" or "end_of_gt_routine__main:"
        // -- carried over as a real assembly label ("@" isn't valid in a
        // real label, so it's mangled to "L_"; a label with no "@" is
        // used unchanged, which is exactly what a JMP to the same text
        // must also do -- see mangleLabel).
        if (first.endsWith(":") && line.size() == 1) {
            raw(mangleLabel(first.substring(0, first.length() - 1)) + ":");
            if (first.contains("catch_") && csrFuncStart >= 0) {
                raw(RSP_MARK); // a catch entry: the unwind left rsp wherever the callee's frame ended (finishCalleeSaved fixes it)
            }
            return;
        }

        boolean wasCmp = lastWasCmp;
        lastWasCmp = false;

        // See "value-block" handling in PUSH/LOOKUP_ARRAY/DOT below:
        // captured and reset by default on every instruction, and only
        // restored where it needs to survive (an intervening small
        // PUSH, e.g. an index or a field offset).
        long pendingBlockSize = lastValueBlockSize;
        lastValueBlockSize = 0;
        // A pending value tag survives ONE intervening PUSH of a word (the index of `PUSH arr; PUSH idx; LOOKUP_ARRAY`), not two: in
        // `z + d[i].a` the small `PUSH 4 z` before `PUSH 8 d; PUSH 8 i` used to tag the pointer lookup as an in-register small array (a float
        // sum silently kept its old value with the register-form passes off). A word PUSH that passed a tag on is remembered; a second
        // PUSH right after it drops the tag.
        if (first.equals("PUSH") && line.size() > 1 && line.get(1).text.matches("\\d+")) {
            if (pendingBlockSize != 0 && pushedWordWithTag) {
                pendingBlockSize = 0;
            }
            pushedWordWithTag = pendingBlockSize != 0 && line.get(1).text.equals("8");
        } else {
            pushedWordWithTag = false;
        }

        String mnemonic = first;
        if (mnemonic.equals("ASM_START")) {
            rfClobberSeen = true; // inline assembly may use anything; the other scratch users are bracketed by V1314 marks
        }
        switch (mnemonic) {
            case "FUNC_START": {
                String name = line.get(1).text;
                currentFuncName = name;
                rfVarUsed = false;
                rfVarMask = 0;
                rfClobberSeen = false;
                xvHome.clear();
                xvWidth.clear();
                raw("");
                raw(".globl " + name);
                raw(name + ":");
                
                pushReg("rbp");
                movRegToRegRbpFromRsp();
                csrFuncStart = out.length();
                csrAllocSum = 0;
                csrAllocPos = -1;
                csrAllocLine = null;
                return;
            }
            case "FUNC_END": {
                // A void function with no explicit "return" statement
                // never emits a "RET" line at all -- control just falls
                // straight through into this copy of the epilogue (see
                // RET's own doc comment for the case where a real RET
                // line *does* run first). RET's own "size 0" branch
                // explicitly zeroes %rax/%eax before handing off to
                // emitFunctionEpilogue precisely so a void function's
                // exit code is predictable rather than whatever garbage
                // its last real instruction happened to leave there --
                // but that zeroing lives on the RET line itself, so a
                // function that falls off the end without ever running
                // one skipped it entirely. Confirmed as a real, live bug
                // this way: a bare "func main() void { printf(...) }"
                // (no explicit return) exited with whatever count printf
                // itself returned (6, for a 6-character format string)
                // instead of 0, since printf's own return value was
                // still sitting in %rax when this fell through to here.
                // A non-void function can never reach this point live --
                // the type-checker requires every path to return a real
                // value -- so zeroing %rax here unconditionally is safe:
                // it only ever actually executes for a void fall-through,
                // and is otherwise this same harmless "dead code after an
                // already-emitted RET" this method's own doc comment
                // already describes.
                movImmToReg("rax", 0);
                emitFunctionEpilogue();
                if (rfVarUsed && rfClobberSeen) {
                    throw new RuntimeException("codegen internal error: '" + currentFuncName
                            + "' keeps variables in r13/r14 but also has an instruction that uses them as scratch");
                }
                finishCalleeSaved();
                return;
            }
            case "ALLOC": {
                long size = Long.parseLong(line.get(1).text);
                long aligned = (size + 15) & ~15L;
                csrAllocSum += aligned;
                if (csrAllocPos < 0 && callBufferStack.isEmpty() && csrFuncStart >= 0) {
                    csrAllocPos = out.length();
                    csrAllocBytes = aligned;
                    csrAllocLine = ("    subq $" + aligned + ", %rsp\n");
                }
                raw("    subq $" + aligned + ", %rsp");
                
                return;
            }
            case "ADDR": {
                // "ADDR size $offset" -> push that address.
                // "ADDR size globalName" -> push that global's address.
                String operand = line.get(2).text;
                if (operand.startsWith("$")) {
                    leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                } else {
                    leaGlobalToReg("rax", operand);
                }
                pushReg("rax");
                return;
            }
            case "PUSH": {
                if (line.size() < 3) {
                    // The 2-token "PUSH <bareName>" form -- a class/type
                    // tag operand (INSTANCEOF's own right-hand side, e.g.
                    // "PUSH Circle") rather than an ordinary "PUSH size
                    // operand" value push. INSTANCEOF itself is one of
                    // this pass's honest, documented gaps (see class doc
                    // comment and CLAUDE.md) -- the lowering pipeline
                    // never resolves this name to a runtime class tag, so
                    // there is nothing safe to push here either; flagged,
                    // not guessed at (this used to crash outright --
                    // "fix that bug," confirmed directly -- now it doesn't).
                    comment("TODO(codegen): PUSH (2-token form) '" + String.join(" ", textsOf(line)) + "' not yet implemented");
                    return;
                }
                int size = (int) Long.parseLong(line.get(1).text);
                String operand = line.get(2).text;
                if (operand.startsWith("$") || operand.startsWith("%x")) {
                    boolean fromXvar = operand.startsWith("%x"); // a float variable promoted to an xmm register (RegVarPromotionPass)
                    long offset = fromXvar ? 0 : Long.parseLong(operand.substring(1));
                    if (size <= 8) {
                        if (fromXvar) {
                            rfXmmToGpr(xvReg(operand), "rax", size == 8 ? 8 : 4); // movd/movq: the variable's bits, zero-extended -- what the frame load gave
                        } else {
                            loadSizedFromFrame("rax", offset, size);
                        }
                        storeConstructionOrPlainPush("rax", size);
                        if (pendingBlockSize == 0 && size < 8) {
                            // Nothing was already pending, and this value
                            // is narrower than a full 8-byte pointer --
                            // it can only be a whole small fixed array
                            // (`letters: mut char[3]`, say) pushed by
                            // value as a single, ordinary, natural-byte-
                            // order word (a pointer is always exactly 8
                            // bytes, never narrower), about to feed a
                            // following LOOKUP_ARRAY. Encoded as a
                            // *negative* size specifically so LOOKUP_ARRAY
                            // can tell this genuinely different shape
                            // apart from the multi-word block case just
                            // below (whose own reversed-word layout
                            // convention would extract the *wrong* byte
                            // entirely if applied here -- see that case's
                            // own doc comment). An ordinary, unrelated
                            // narrow scalar read harmlessly carries this
                            // same tag forward for exactly one
                            // instruction too, but this bytecode's own
                            // emission convention never actually follows
                            // one with a LOOKUP_ARRAY/DOT except as part
                            // of a real access chain, so nothing ever
                            // misreads it.
                            //
                            // A real, previously-undiscovered bug found
                            // this way: this shape used to leave
                            // `lastValueBlockSize` at 0 like any other
                            // ordinary scalar read, so the following
                            // LOOKUP_ARRAY fell into its own "no block
                            // pending" fallback -- built for an unsafe
                            // dynarray's own bare *pointer* value -- and
                            // treated this packed array value as if it
                            // were itself a memory address, corrupting it
                            // into a bogus pointer and dereferencing it.
                            // Confirmed as a real, live segfault via
                            // `struct_array_member_narrow_elem_cg_test`'s
                            // own `letters: mut char[3]` member.
                            lastValueBlockSize = -size;
                        } else {
                            // A small push right after a pushed value-block
                            // is always the index/field-offset that a
                            // following LOOKUP_ARRAY/PUSH_FIELDNAME+DOT
                            // consumes *alongside* that block -- preserve
                            // the pending block size across it rather than
                            // treating it as unrelated.
                            lastValueBlockSize = pendingBlockSize;
                        }
                    } else {
                        // A multi-word (>8 byte) value pushed by plain
                        // value -- a small struct passed/copied whole
                        // (e.g. "PUSH 24 $-64", "PUSH 48 $-200"), or an
                        // array pushed by its own full declared size for
                        // a following LOOKUP_ARRAY read (confirmed
                        // directly against array_lookup_cg_test.caspien's
                        // own real low-order output: a read
                        // ("PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8")
                        // pushes the *whole* array by value, while a
                        // write ("ADDR 40 $-40 / PUSH idx /
                        // LOOKUP_ARRAY_LHS 8") pushes only its address --
                        // two genuinely different conventions for the
                        // same LOOKUP_ARRAY family, not a codegen choice).
                        // Pushes ceil(size/8) whole words, lowest source
                        // offset first, so the highest-offset word ends
                        // up on top -- the same convention DEREF's own
                        // multi-word case and the "POP $addr size"
                        // block-store already share -- and remembers the
                        // total size for LOOKUP_ARRAY/DOT to extract a
                        // sub-range from afterward.
                        //
                        // UNLESS this exact line is itself a direct
                        // member of a construction run `precomputeConstructionOffsets`
                        // already resolved (`pushStoreOffsetByLine`) --
                        // i.e. a whole, already-fully-built value (e.g. a
                        // hidden RVO temp local) being fed, as one single
                        // >8-byte entry, straight into a following "NEW"/
                        // construction-scoped "ASSIGN". That terminator's
                        // own straight `rep movsb` copy (see "NEW"'s own
                        // doc comment) assumes the bytes sitting at the
                        // top of the stack are already in the same,
                        // natural, ascending-offset order the source
                        // struct/array itself has -- true for an ordinary
                        // multi-field literal (each field individually
                        // reserves and writes only its own declared
                        // width, via `storeConstructionOrPlainPush`,
                        // directly at its own natural position), but
                        // false for the reversed, "highest-offset word on
                        // top" order this branch's own ordinary path
                        // produces. Using the ordinary reversed path here
                        // silently swapped a struct's own front and back
                        // words end for end once copied into the final
                        // buffer -- confirmed as a real, live bug via
                        // `new` constructing a value already built by a
                        // separate call (a struct's own constructor,
                        // called via its stack-RVO destination pointer,
                        // then handed to `new` as one already-complete
                        // value) rather than via an inline literal:
                        // `new MyClass(11,13)`'s own `y` field silently
                        // read back as `1` (the struct's own hidden
                        // classId, its front-most word) instead of `13`.
                        // `pushBlockFromFrameConstructionAware` below
                        // keeps this entry's own bytes in their real,
                        // natural order and lands them at the exact
                        // packed offset `populateDirectMembers` already
                        // computed for it (0, unconditionally, whenever
                        // this is the run's *only* entry -- a whole-value
                        // feed like this always is one, since
                        // `collectRunEntries` only ever splits a value
                        // into more than one entry at real, separate
                        // "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" lines, and a
                        // single already-built value is always exactly
                        // one line), so the terminator's own straight,
                        // order-preserving copy sees exactly the bytes it
                        // already assumes.
                        pushBlockFromFrameConstructionAware(offset, size);
                        lastValueBlockSize = size;
                    }
                    return;
                } else if (operand.equals("null")) {
                    movImmToReg("rax", 0);
                } else if (isInteger(operand)) {
                    movImmToReg("rax", operand.startsWith("-") ? Long.parseLong(operand) : Long.parseUnsignedLong(operand));
                } else if (isFloatLiteral(operand)) {
                    // A float literal's own IEEE-754 bit pattern is just
                    // as pushable as an int's bits through this exact
                    // same integer-immediate/pushq mechanism -- this
                    // instruction (and the "ASSIGN"/frame-load machinery
                    // downstream of it) already treats every value as
                    // opaque bytes of the declared `size`, never
                    // interpreting them, so there is nothing SSE/xmm-
                    // specific needed just to get a literal's own bits
                    // to sit correctly in memory. The real xmm-register
                    // work this doesn't touch -- float *arithmetic*
                    // (ADD_FLOAT/SUB_FLOAT/MUL_FLOAT/DIV_FLOAT), float
                    // *comparison* (EQ_FLOAT/NEQ_FLOAT/LT_FLOAT/...),
                    // returning a float (RET_FLOAT), and FLOAT_CHECK
                    // (the finite/infinite/nan classification match
                    // lowers to) -- is all implemented elsewhere in this
                    // same file; none of it falls through to the generic
                    // TODO any more (verified directly: a real program
                    // exercising all three FLOAT_CHECK categories
                    // together -- a finite value, 1.0/0.0, and 0.0/0.0
                    // -- compiles with zero TODOs in the generated
                    // assembly and correctly prints "finite"/"infinite"/
                    // "nan").
                    long bits = size <= 4
                            ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(operand)))
                            : Double.doubleToRawLongBits(Double.parseDouble(operand));
                    movImmToReg("rax", bits);
                } else if (operand.matches("\\d+\\.(start|end)")) {
                    // A synthesized pseudo-member push against an
                    // *incomplete* range value ("5.start"/"5.end") --
                    // part of the same WITHIN/incomplete-range gap this
                    // pass already documents elsewhere (the lowering
                    // pipeline leaves this operand as a symbolic,
                    // non-address form even at "low order"); flagged
                    // rather than mismangled into a bogus symbol.
                    comment("TODO(codegen): PUSH of incomplete-range pseudo-member '" + operand + "' not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                } else if (operand.startsWith("ARG")) {
                    // A calling-convention register-transfer slot read
                    // directly as a value -- the callee side of the same
                    // convention "POP ARGn" uses on the caller side.
                    // Confirmed directly: every real site is the very
                    // first instruction touching registers right after
                    // FUNC_START/ALLOC (storing an incoming parameter to
                    // its own local), so the argument register still
                    // holds its true incoming value here untouched.
                    int idx = Integer.parseInt(operand.substring(3));
                    raw(("    movq %" + argReg(idx) + ", %rax"));
                } else if (operand.startsWith("FARG")) {
                    // The float-bank counterpart of the "ARG" branch just
                    // above -- the incoming parameter's own real bits are
                    // already sitting in a real xmm register at function
                    // entry (the calling convention's own float-argument
                    // slot), never a GPR, so this moves the raw 64 bits
                    // out of it via the same bit-copying `movq` every
                    // other float op in this file already uses, rather
                    // than reading a GPR that was never written.
                    int idx = Integer.parseInt(operand.substring(4));
                    movXmmToReg(argFloatReg(idx), "rax");
                } else if (stringLiterals.containsKey(operand)) {
                    // A string-pool id -- always its address (a string
                    // literal decays to a pointer to its own .rodata
                    // bytes, exactly like a real array/struct global;
                    // there's no separate "value" to push instead,
                    // confirmed directly against every real string-
                    // literal PUSH site, e.g. printf's format-string
                    // argument and NEW_FROM_STRING's source operand).
                    leaGlobalToReg("rax", operand);
                } else if (globalsSizeInit.containsKey(operand) || globalAliasOffset.containsKey(operand)) {
                    // A bare (possibly dotted) declared GLOBAL/
                    // ALLOC_STATIC name -- ordinarily its *value* (the
                    // same "read what's stored there" semantics a local
                    // variable's own "PUSH size $offset" already has,
                    // and the same thing ATOMIC_PUSH does explicitly,
                    // atomically, for the identical bare-global-name
                    // case) -- confirmed directly, the hard way: this
                    // pass originally treated every bare global name as
                    // "push its address" across the board, which
                    // happened to work for the ghost-table lock (see
                    // below) but silently broke gt_destruct's own
                    // ghost-table *search* the first time a real test
                    // actually exercised it (ghost_table.len's real
                    // stored value is 0, but its *address* is some large
                    // nonzero number, so the address reading turned an
                    // always-skipped search loop into one that ran with
                    // garbage bounds and eventually dereferenced garbage
                    // -- a real, confusing segfault this pass had to
                    // bisect down to before finding the true convention
                    // split here). The one confirmed exception is a
                    // global immediately feeding an ATOMIC_SWAP two
                    // lines ahead (this pipeline's own lock-acquire
                    // shape, "PUSH lockGlobal / PUSH newValue /
                    // ATOMIC_SWAP" -- an in-place memory swap that
                    // genuinely needs the address, not the value, and is
                    // the one bare-global PUSH site this pass actually
                    // confirmed wants that) -- everywhere else (ASSIGN,
                    // arithmetic, comparisons, ARG passing) it's the
                    // value.
                    if (lineAtOffsetIsAtomicSwap(2)) {
                        leaGlobalToReg("rax", operand);
                    } else if (size > 8) {
                        // A multi-word global (a static array, most often,
                        // about to feed LOOKUP_ARRAY) is pushed as a whole
                        // value block -- the identical shape "PUSH size
                        // $offset" produces for a local of that size. Before,
                        // this fell to the single-word load below, so only
                        // the array's first 8 bytes were pushed and
                        // LOOKUP_ARRAY indexed into (and dereferenced) that
                        // word as if it were the array: reading any element
                        // of a static array segfaulted.
                        leaGlobalToReg("rax", operand);
                        pushSizedOrBlockFromAddr("rax", size);
                        lastValueBlockSize = size;
                        return;
                    } else {
                        leaGlobalToReg("rax", operand);
                        loadSizedFromAddr("rax", "rax", size);
                        if (pendingBlockSize == 0 && size < 8) {
                            // Same tag as the local "PUSH size $offset" case:
                            // a narrow value may be a whole small static array
                            // about to feed LOOKUP_ARRAY (see that case).
                            lastValueBlockSize = -size;
                            storeConstructionOrPlainPush("rax", size);
                            return;
                        }
                    }
                } else if (declaredFunctionNames.contains(operand)) {
                    // A real function's own address -- see
                    // "declaredFunctionNames"'s own doc comment. Never
                    // dereferenced afterward, unlike a global: the value
                    // *is* the address.
                    leaGlobalToReg("rax", operand);
                } else if (isCharLiteral(operand)) {
                    // A char literal -- "'a'", "'\n'", "'\0'", ... --
                    // BytecodeEmitter.escapeForBytecode's own serialized
                    // form: a single quote, one real character's worth of
                    // content (either one printable byte, or a two-
                    // character "\x"-style escape for anything this
                    // language's own lexer recognizes as an escape --
                    // "\\"/"\""/"\n"/"\t"/"\r"/"\0"/"\b"/"\f"/"\a"/"\v"),
                    // then a closing single quote. A real, previously-
                    // undiscovered gap: this operand shape had no branch
                    // here at all -- every char literal anywhere in the
                    // whole language silently fell to the generic
                    // "not a declared global" placeholder below, pushing
                    // 0 in place of the real character, every time,
                    // regardless of context (a bare `let c = 'a'`, an
                    // array-of-char literal, a match/comparison against a
                    // char, ...). Confirmed directly, not assumed: 22
                    // distinct example fixtures across the whole test
                    // corpus hit this, including three that had been
                    // separately tracked all along as unrelated,
                    // "pre-existing baseline failures"
                    // (array_literal_narrow_elem_cg_test,
                    // struct_array_member_narrow_elem_cg_test,
                    // struct_mixed_width_cg_test) -- all three were
                    // actually this one bug the whole time. Decoded the
                    // same way an integer/float literal already is just
                    // above: to its real numeric byte value, immediate-
                    // loaded into %rax, no runtime work needed (a char is
                    // always exactly one compile-time-known byte).
                    movImmToReg("rax", decodeCharLiteral(line.get(2)));
                } else {
                    comment("TODO(codegen): PUSH of '" + operand + "' (not a declared global) not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                }
                // Any of these forms (a literal index, "null", an ARG
                // read, a global address) can legitimately be the
                // index/offset half of a LOOKUP_ARRAY/DOT chain, so a
                // pending value-block from just before is preserved
                // through it, same as the small "$offset" branch above.
                lastValueBlockSize = pendingBlockSize;
                storeConstructionOrPlainPush("rax", size);
                return;
            }
            case "ASSIGN":
            case "ATOMIC_ASSIGN": {
                // Stack, top to bottom: value, address. size operands
                // (3 of them, always equal) tell us the real width to
                // store -- previously ignored (always did a full 8-byte
                // store, which is wrong for anything narrower, e.g. a
                // struct's own 'bool'/'u8'/'f32' field sitting between
                // other fields). A plain aligned store is already atomic
                // on x86-64, so ATOMIC_ASSIGN needs no different
                // instruction, just the same width-aware store.
                int size = (int) Long.parseLong(line.get(1).text);
                if (constructionScopedAssign.contains(currentLineIndex)) {
                    // A plain, stack-resident struct/array/primitive
                    // literal's own top-level store -- see
                    // `constructionScopedAssign`'s own doc comment. %rsp
                    // already holds a real, byte-exact, tightly-packed
                    // image of the value (every push in the run just
                    // reserved its own declared width, never a blind
                    // 8-byte word), exactly the same "genuinely trivial
                    // and completely struct-agnostic" copy `case "NEW"`
                    // already does for the identical shape -- just to an
                    // address already sitting on the stack (below the
                    // value block) rather than a freshly malloc'd one, so
                    // no allocation call is needed here at all. Checked
                    // *before* the `size <= 8` case below: a small (say,
                    // 4-byte, four-`char`) construction is exactly as
                    // real a construction as a large one -- its value
                    // block was reserved byte-exact too, not as one
                    // ordinary 8-byte word, so the plain scalar pop/pop
                    // path below would desynchronize with it (popping 8
                    // bytes of a 4-byte-reserved value block spills into
                    // the address word sitting right above it).
                    assignConstructionBlock(size);
                } else if (line.size() > 4) {
                    // A stack struct literal whose field values were not all plain pushes (an expression, a call, ...): every
                    // field was pushed as a whole 8-byte word, so the block-copy paths below would misread the stack. The layout
                    // descriptor LowerOrderGenerator appended tells us where each field really goes.
                    emitAssignRepack(size, line);
                } else if (size <= 8) {
                    popReg("rax"); // value
                    popReg("rbx"); // address
                    storeSizedToAddr("rax", "rbx", size);
                } else {
                    // Not a recognized construction -- an ordinary
                    // whole-value block copy (`let b = a`, passing a
                    // struct/array by value, ...), whose own pushed image
                    // came from `pushBlockFromFrame`'s uniform, always-
                    // whole-8-byte-word convention instead. Handled the
                    // old way, popping one word at a time.
                    assignBlock(size);
                }
                return;
            }
            case "R_MOV": {
                // Register-form (see the LowerOrderGenerator's RegisterFormPass): "R_MOV 8 dst src".
                rfMov(line.get(2).text, line.get(3).text);
                return;
            }
            case "R_BIN": {
                // "R_BIN OP size %tD a b" -- %tD = a OP b.
                rfBin(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_DIVC": {
                // "R_DIVC DIV|MOD 8 %tD a #k" -- unsigned division / remainder by a constant, by multiplying with the reciprocal.
                rfDivConst(line.get(1).text.equals("MOD"), (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, rfImm(line.get(5).text));
                return;
            }
            case "R_UN": {
                // "R_UN OP size %tD a" -- %tD = OP a.
                rfUn(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text);
                return;
            }
            case "R_BRF": {
                // "R_BRF %tN @label" -- jump if the temp is zero (replaces CMP + JMP).
                String reg = rfReg(line.get(1).text);
                raw(("    testq %" + reg + ", %" + reg));
                raw("    je " + mangleLabel(line.get(2).text));
                return;
            }
            case "R_BRC": {
                // "R_BRC OP 8 a b @label" -- compare a with b and jump to the label when NOT (a OP b) (the fused form of
                // R_BIN cmp + R_BRF, made by the LowerOrderGenerator's BranchFusionPass; the compare result never exists).
                if (line.get(2).text.startsWith("F")) { // "R_BRC OP F4|F8 a b @label": float compare (ucomiss/ucomisd) + jump
                    rfFloat(true, line.get(1).text, Integer.parseInt(line.get(2).text.substring(1)), null, line.get(3).text, line.get(4).text,
                            mangleLabel(line.get(5).text));
                    return;
                }
                int brcSize = (int) Long.parseLong(line.get(2).text);
                if (brcSize < 8) {
                    rfBrcNarrow(line.get(1).text, brcSize, line.get(3).text, line.get(4).text, mangleLabel(line.get(5).text));
                    return;
                }
                rfBrc(line.get(1).text, line.get(3).text, line.get(4).text, mangleLabel(line.get(5).text));
                return;
            }
            case "R_CMOV": {
                rfCmov(line);
                return;
            }
            case "R_BRCM": {
                // "R_BRCM OP 8 a %base @label" -- R_BRC whose second operand is the 8-byte word AT the address in %base (made by the
                // LowerOrderGenerator's LengthCompareFusionPass from `R_LD 8 %t %base ; R_BRC OP 8 a %t @label`).
                rfBrcMem(line.get(1).text, line.get(3).text, line.get(4).text, mangleLabel(line.get(5).text));
                return;
            }
            case "R_PUSH": {
                // "R_PUSH 8 src" -- materialise an operand (%tN, $off or #imm) on the real stack. Goes through
                // pushReg so the stack-delta tracker sees it; memory/immediate sources use the spare scratch.
                String src = line.get(2).text;
                if (rfIsTemp(src)) {
                    pushReg(rfReg(src));
                } else {
                    rfMov("%s", src);
                    pushReg(RF_SCRATCH);
                }
                return;
            }
            case "R_PUSHA": {
                // "R_PUSHA 8 $off" / "R_PUSHA 8 &sym" -- push the address of a frame slot / a global.
                rfAddrToReg(RF_SCRATCH, line.get(2).text);
                pushReg(RF_SCRATCH);
                return;
            }
            case "R_SETV": {
                // "R_SETV n %vK src" -- n bytes of src (#imm, %tN or %vN) into a promoted variable, zero-extended to 64 bits.
                int n = (int) Long.parseLong(line.get(1).text);
                String vr = rfReg(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsImm(src)) {
                    movImmToReg(vr, rfExtImm(rfImm(src), n, false));
                } else if (n == 4 || n == 2 || n == 1) {
                    // one instruction: movl / movzwl / movzbl from the source's sub-register straight into the 32-bit destination
                    // (writing a 32-bit register clears bits 32..63), instead of a 64-bit copy followed by an in-place zero-extension
                    String sr = rfReg(src);
                    if (n == 4) {
                        raw("    movl %" + sizedReg(sr, 4) + ", %" + sizedReg(vr, 4));
                    } else {
                        raw("    movz" + movSuffix(n) + "l %" + sizedReg(sr, n) + ", %" + sizedReg(vr, 4));
                    }
                } else {
                    rfRegToReg(vr, rfReg(src));
                }
                return;
            }
            case "R_LD": {
                // "R_LD n %tD addr" -- %tD = n bytes at addr (a frame slot, a global or the pointer in a temp), zero-extended.
                rfLoad((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return;
            }
            case "R_ST": {
                // "R_ST n addr src" -- n bytes at addr = src (#imm or %tN).
                rfStore((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return;
            }
            case "R_LDI": {
                // "R_LDI n %tD &sym %vK scale" -- %tD = n bytes at sym + vK*scale (an R_LEA folded into the load that used it).
                rfLoadIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return;
            }
            case "R_STI": {
                // "R_STI n &sym %vK scale src" -- n bytes at sym + vK*scale = src (#imm or %tN).
                rfStoreIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return;
            }
            case "R_LDXI": {
                // "R_LDXI n %xK &sym %vK scale" -- variable K = the float (n = 4 or 8 bytes) at sym + vK*scale (an R_LEA folded into R_LDX).
                rfLoadXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text),
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return;
            }
            case "R_STXI": {
                // "R_STXI n &sym %vK scale %xK" -- the float in variable K stored at sym + vK*scale (an R_LEA folded into R_STX).
                rfStoreXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text,
                        line.size() > 6 ? Long.parseLong(line.get(6).text) : 0L);
                return;
            }
            case "R_LDD": {
                // "R_LDD n %tD %base disp" -- %tD = n bytes at base + disp, zero-extended (an `R_LEA %t base #k 1` folded into the load: a field read).
                rfLoadDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text));
                return;
            }
            case "R_STD": {
                // "R_STD n %base disp src" -- n bytes at base + disp = src (#imm or %tN): a field write.
                rfStoreDisp((int) Long.parseLong(line.get(1).text), line.get(2).text, Long.parseLong(line.get(3).text), line.get(4).text);
                return;
            }
            case "R_LDXD": {
                // "R_LDXD n %xK %base disp" -- variable K = the float (n = 4 or 8 bytes) at base + disp.
                rfLoadXDisp(xvReg(line.get(2).text), line.get(3).text, Long.parseLong(line.get(4).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_STXD": {
                // "R_STXD n %base disp %xK" -- the float in variable K stored at base + disp.
                rfStoreXDisp(line.get(2).text, Long.parseLong(line.get(3).text), xvReg(line.get(4).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_LEA": {
                // "R_LEA %tD base idx scale [disp]" -- %tD = base + idx*scale + disp.
                rfLea(line.get(1).text, line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text),
                        line.size() > 5 ? Long.parseLong(line.get(5).text) : 0L);
                return;
            }
            case "R_FBIN":
            case "R_FCMP": {
                // "R_FBIN OP size %tD a b" / "R_FCMP OP size %tD a b" -- scalar float arithmetic / compare on raw bit patterns.
                rfFloat(mnemonic.equals("R_FCMP"), line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_XVAR": {
                // "R_XVAR %xK $off [n]" -- declaration only (no code): float variable K lives in an xmm register; $off is its
                // home frame slot, used to spill/reload around calls where the ABI does not preserve xmm registers; n is the
                // variable's width (4 = f32, the default; 8 = f64).
                int xk = Integer.parseInt(line.get(1).text.substring(2));
                xvHome.put(xk, rfSlot(line.get(2).text));
                xvWidth.put(xk, line.size() > 3 ? Integer.parseInt(line.get(3).text) : 4);
                return;
            }
            case "R_XMOV": {
                // "R_XMOV %xK|%yK %xJ|%yJ" -- register copy of a float value (FloatTempPass).
                rfMovaps(xvReg(line.get(1).text), xvReg(line.get(2).text));
                return;
            }
            case "R_XSPILL": {
                // write every float variable to its home slot: emitted before a direct jump into a catch label (no call spills them)
                xvSpillAll();
                return;
            }
            case "R_XRELOAD": {
                // reload every float variable from its home slot: emitted where control can arrive from an unwind (catch entry),
                // after which the register-held copies are gone on ABIs whose xmm registers do not survive a call.
                xvReloadAll();
                return;
            }
            case "R_XTOG": {
                // "R_XTOG n %tD %xK" -- %tD = the n*8 float bits of variable K, zero-extended.
                rfXmmToGpr(xvReg(line.get(3).text), rfReg(line.get(2).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_GTOX": {
                // "R_GTOX n %xK src" -- variable K = the float bits in src (%tN or #imm).
                int gn = Integer.parseInt(line.get(1).text);
                String src = line.get(3).text;
                if (rfIsImm(src)) {
                    rfLoadXmm(src, xvReg(line.get(2).text), gn);
                    
                } else {
                    rfGprToXmm(xvReg(line.get(2).text), rfReg(src), gn);
                }
                return;
            }
            case "R_LDX": {
                // "R_LDX n %xK addr" -- variable K = the float at addr (a frame slot, a global or the pointer in a temp).
                rfLoadX(xvReg(line.get(2).text), line.get(3).text, Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_STX": {
                // "R_STX n addr %xK" -- the float in variable K stored at addr.
                rfStoreX(line.get(2).text, xvReg(line.get(3).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_FBINX": {
                // "R_FBINX OP 4 %xK a b" -- variable K = a OP b, computed in place when it can be.
                rfFloatX(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_FSQRT": {
                // "R_FSQRT n DST SRC" -- DST = sqrt(SRC), both xmm-register operands (FloatIntrinsicPass).
                int fn = (int) Long.parseLong(line.get(1).text);
                String xd = xvReg(line.get(2).text);
                String xs = xvReg(line.get(3).text);
                // sqrtsd/sqrtss keep the upper bits of DST, so they depend on DST's old value; zeroing DST first (when it is not SRC) breaks that chain.
                if (!xd.equals(xs)) raw("    xorps %" + xd + ", %" + xd);
                raw("    sqrt" + (fn == 4 ? "ss" : "sd") + " %" + xs + ", %" + xd);
                return;
            }
            case "R_POPV": {
                // "R_POPV n %vK" -- integer variable K = the n-byte word on top of the real stack (what ASSIGN n n n stored through its address).
                popReg("rax");
                zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
                rfRegToReg(rfReg(line.get(2).text), "rax");
                return;
            }
            case "R_GETRET": {
                // "R_GETRET n %vK" -- integer variable K = the n-byte integer a just-finished call returned (what PUSH_RET_INT + ASSIGN did).
                zeroExtendReg("rax", Integer.parseInt(line.get(1).text));
                rfRegToReg(rfReg(line.get(2).text), "rax");
                return;
            }
            case "R_POPX": {
                // "R_POPX n %xK" -- variable K = the float word on top of the real stack (what ASSIGN n n n stored through its address).
                popReg("rax");
                rfGprToXmm(xvReg(line.get(2).text), "rax", Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_GETRETF": {
                // "R_GETRETF 4 %xK" -- variable K = the float a just-finished call returned (what PUSH_RET_FLOAT + ASSIGN did).
                String xk = xvReg(line.get(2).text);
                if (floatResultInR10) {
                    rfGprToXmm(xk, "r10", 8);
                    floatResultInR10 = false;
                } else {
                    raw(("    movaps %xmm0, %" + xk));
                }
                return;
            }
            case "R_RMW": {
                // "R_RMW OP 8 $x [b]" -- one memory-operand read-modify-write on an 8-byte frame slot.
                rfRmw(line.get(1).text, line.get(3).text, line.size() > 4 ? line.get(4).text : null);
                return;
            }
            case "R_ARG": {
                // "R_ARG n size src" -- integer argument register n = src (what "PUSH src / POP ARGn" did).
                String reg = rfArgReg(Integer.parseInt(line.get(1).text));
                int asize = (int) Long.parseLong(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsXvar(src)) {
                    rfXmmToGpr(xvReg(src), reg, asize == 8 ? 8 : 4);
                } else if (rfIsTemp(src)) {
                    rfRegToReg(reg, rfReg(src));
                } else if (rfIsImm(src)) {
                    movImmToReg(reg, rfImm(src));
                } else {
                    loadSizedFromFrame(reg, rfSlot(src), asize);
                }
                noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_ARGA": {
                // "R_ARGA n addr" -- integer argument register n = an address ($off or &sym).
                String reg = rfArgReg(Integer.parseInt(line.get(1).text));
                rfAddrToReg(reg, line.get(2).text);
                noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_FARG": {
                // "R_FARG n size src" -- float argument register n = the bit pattern src.
                int fidx = Integer.parseInt(line.get(1).text);
                String xr = argFloatReg(fidx);
                int fsize = (int) Long.parseLong(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsXvar(src)) {
                    raw(("    movaps %" + xvReg(src) + ", %" + xr));
                } else if (rfIsTemp(src)) {
                    movRegToXmm(rfReg(src), xr);
                } else {
                    if (rfIsImm(src)) {
                        movImmToReg(RF_SCRATCH, rfImm(src));
                    } else {
                        loadSizedFromFrame(RF_SCRATCH, rfSlot(src), fsize);
                    }
                    movRegToXmm(RF_SCRATCH, xr);
                }
                noteArgRegLoaded("f" + fidx);
                return;
            }
            case "R_RET":
            case "R_RETF": {
                // "R_RET src" / "R_RETF src" -- the return value into rax / xmm0, then the function epilogue.
                String src = line.get(1).text;
                if (mnemonic.equals("R_RET")) {
                    if (rfIsTemp(src)) {
                        rfRegToReg("rax", rfReg(src));
                    } else if (rfIsImm(src)) {
                        movImmToReg("rax", rfImm(src));
                    } else {
                        movMemToReg("rax", rfSlot(src));
                    }
                } else if (rfIsXvar(src)) {
                    raw(("    movaps %" + xvReg(src) + ", %xmm0"));
                } else if (rfIsTemp(src)) {
                    movRegToXmm(rfReg(src), "xmm0");
                } else {
                    if (rfIsImm(src)) {
                        movImmToReg(RF_SCRATCH, rfImm(src));
                    } else {
                        movMemToReg(RF_SCRATCH, rfSlot(src));
                    }
                    movRegToXmm(RF_SCRATCH, "xmm0");
                }
                emitFunctionEpilogue();
                return;
            }
            case "ADD_INT":
            case "SUB_INT":
            case "MUL_INT": {
                popReg("rbx");
                popReg("rax");
                String op = mnemonic.equals("ADD_INT") ? "add" : mnemonic.equals("SUB_INT") ? "sub" : "imul";
                raw("    " + op + "q %rbx, %rax");
                
                pushReg("rax");
                return;
            }
            case "SDIV_INT":
            case "SMOD_INT": {
                // Signed division/modulo (s8/s16/s32/s64 operands): sign-extend both operands from
                // their declared width, then cqto/idiv (remainder takes the dividend's sign, as in C).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx");
                popReg("rax");
                signExtendReg("rax", size);
                signExtendReg("rbx", size);
                // MIN / -1 overflows and traps (SIGFPE) in idiv: a divisor of -1 is handled
                // without idiv (x / -1 = -x wrapping, x % -1 = 0).
                boolean isDiv = mnemonic.equals("SDIV_INT");
                String notMinusOne = newInternalLabel("sdiv_normal");
                String doneLabel = newInternalLabel("sdiv_done");
                raw("    cmpq $-1, %rbx");
                raw("    jne " + notMinusOne);
                if (isDiv) {
                    raw("    negq %rax");
                } else {
                    raw("    xorq %rax, %rax");
                }
                raw("    jmp " + doneLabel);
                raw(notMinusOne + ":");
                raw("    cqto");
                raw("    idivq %rbx");
                if (!isDiv) {
                    raw("    movq %rdx, %rax");
                }
                raw(doneLabel + ":");
                
                pushReg("rax");
                return;
            }
            case "SLT_INT":
            case "SLT_EQ_INT":
            case "SGT_EQ_INT":
            case "SGT_INT": {
                // Signed comparisons: sign-extend both operands from their declared width first.
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx");
                popReg("rax");
                signExtendReg("rax", size);
                signExtendReg("rbx", size);
                String scc = mnemonic.equals("SLT_INT") ? "setl"
                        : mnemonic.equals("SLT_EQ_INT") ? "setle"
                        : mnemonic.equals("SGT_EQ_INT") ? "setge"
                        : "setg";
                raw("    cmpq %rbx, %rax");
                raw("    " + scc + " %al");
                raw("    movzbq %al, %rax");
                
                pushReg("rax");
                return;
            }
            case "DIV_INT":
            case "MOD_INT": {
                // Unsigned division -- this bytecode carries only a byte
                // size per operator, never a signedness flag, and this
                // language's own numeric literals default to unsigned
                // ("indeterminate_u64") throughout -- so, like LT_INT/
                // LT_EQ_INT/GT_EQ_INT below, this pass treats every
                // int comparison/division as unsigned. A real signed
                // s8/s16/s32/s64 division would need idiv/cqto instead;
                // documented as a known gap rather than silently wrong
                // for the (much more common, in this language) unsigned
                // case.
                int dsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                zeroExtendReg("rax", dsize);
                zeroExtendReg("rbx", dsize);
                if (dsize <= 4) {
                    // both operands were zero-extended from at most 32 bits: the 32-bit divide gives the same quotient and remainder,
                    // several times faster than divq on most x86 cores (a divl clears the upper halves of rax and rdx itself)
                    raw("    xorl %edx, %edx");
                    raw("    divl %ebx");
                } else {
                    raw("    xorq %rdx, %rdx");
                    raw("    divq %rbx");
                }
                
                pushReg(mnemonic.equals("DIV_INT") ? "rax" : "rdx");
                return;
            }
            case "SHL":
            case "SHR":
            case "SAR": {
                // Shifts have ONE well-defined meaning at every width, on both targets and in both forms (the register-form
                // twin is rfShift): the count is read as an UNSIGNED number of the operand's own width (a negative signed
                // count is therefore huge); a count >= the operand width gives 0 for SHL and SHR and the sign fill (0 or
                // -1) for SAR -- not x86's "count modulo 64". SAR = arithmetic shift right for signed operands (the value is
                // sign-extended from its width first); SHR zero-extends first. A narrow result is re-truncated, so the stack
                // word keeps holding the zero-extended value.
                int ssize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx"); // shift count
                popReg("rax"); // value
                if (bmi2 && !mnemonic.equals("SAR")) {
                    zeroExtendReg("rbx", ssize);
                    emitShiftBmi2(mnemonic, ssize, "rax", "rbx");
                    pushReg("rax");
                    return;
                }
                // %rcx is the shift-count register but also an argument register: save it around the shift (see LOOKUP_ARRAY's small-array path).
                pushReg("rcx");
                raw("    movq %rbx, %rcx");
                zeroExtendReg("rcx", ssize);
                emitShiftCore(mnemonic, ssize, "rax");
                popReg("rcx");
                pushReg("rax");
                return;
            }
            case "ROTL":
            case "ROTR": {
                // bits_rotl / bits_rotr: rotate the low `size` bytes, count modulo the width (what rol/ror do: the hardware masks the count to 5 or 6
                // bits, and a 1/2-byte rotate by (count mod 32) is the same as by (count mod width)). The value is zero-extended first so the
                // untouched upper bits of a 1/2-byte sub-register stay zero.
                int rsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx"); // count
                popReg("rax"); // value
                zeroExtendReg("rax", rsize);
                pushReg("rcx");
                raw("    movq %rbx, %rcx");
                raw("    " + (mnemonic.equals("ROTL") ? "rol" : "ror") + movSuffix(rsize) + " %cl, %" + sizedReg("rax", rsize));
                popReg("rcx");
                pushReg("rax");
                return;
            }
            case "BITS_OR":
            case "BITS_AND":
            case "BITS_XOR": {
                // "BITS_xxx size": pop two, and/or/xor, push. Size 8 is also what StrengthReductionPass makes of x % 2^n. A narrow
                // result is re-truncated, so the stack word keeps holding the zero-extended value (like the shifts).
                int bsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                String bop = mnemonic.equals("BITS_OR") ? "or" : mnemonic.equals("BITS_AND") ? "and" : "xor";
                raw(("    " + bop + "q %rbx, %rax"));
                zeroExtendReg("rax", bsize);
                pushReg("rax");
                return;
            }
            case "BITS_NOT": {
                // "BITS_NOT size": the bitwise complement, truncated to the operand width (u8: ~0 = 255).
                int nsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rax");
                raw("    notq %rax");
                zeroExtendReg("rax", nsize);
                pushReg("rax");
                return;
            }
            case "EQ_INT":
            case "NEQ_INT":
            case "LT_INT":
            case "LT_EQ_INT":
            case "GT_EQ_INT":
            case "GT_INT": {
                // "GT_INT" found genuinely missing here while building
                // float support -- the sibling `caspien-lowerordergenerator`
                // project's own AddressLoweringPass rewrites all six
                // comparison operators (GT/LT/GT_EQ/LT_EQ/EQ/NEQ) through
                // one identical rule (see its own doc comment on that
                // rewrite), so every one of them was always just as
                // reachable as GT_EQ_INT, right below it, which *was*
                // handled -- this one case was simply never added.
                // Unrelated to anything float-specific; fixed alongside it
                // rather than left now that it's been noticed, same
                // unsigned-only scope as its five siblings (see DIV_INT's
                // own note).
                int csize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                zeroExtendReg("rax", csize);
                zeroExtendReg("rbx", csize);
                String setcc = mnemonic.equals("EQ_INT") ? "sete"
                        : mnemonic.equals("NEQ_INT") ? "setne"
                        : mnemonic.equals("LT_INT") ? "setb"
                        : mnemonic.equals("LT_EQ_INT") ? "setbe"
                        : mnemonic.equals("GT_EQ_INT") ? "setae"
                        : "seta"; // GT_INT (unsigned; see DIV_INT's own note on signedness)
                raw("    cmpq %rbx, %rax");
                raw("    " + setcc + " %al");
                raw("    movzbq %al, %rax");
                
                pushReg("rax");
                return;
            }
            case "ADD_FLOAT":
            case "SUB_FLOAT":
            case "MUL_FLOAT":
            case "DIV_FLOAT": {
                int size = (int) Long.parseLong(line.get(1).text);
                String sfx = sseSuffix(size);
                popReg("rbx"); // right operand bits
                popReg("rax"); // left operand bits
                movRegToXmm("rax", rfXmmA());
                movRegToXmm("rbx", rfXmmB());
                String op = mnemonic.equals("ADD_FLOAT") ? "add"
                        : mnemonic.equals("SUB_FLOAT") ? "sub"
                        : mnemonic.equals("MUL_FLOAT") ? "mul"
                        : "div";
                // AT&T "op src, dest" computes dest = dest OP src; xmm0
                // holds the left operand (pushed first, popped last,
                // exactly the ADD_INT/SUB_INT/MUL_INT convention just
                // above), xmm1 the right, so "opss %xmm1, %xmm0" gives
                // left-OP-right, in the correct order for the
                // non-commutative SUB_FLOAT/DIV_FLOAT cases.
                raw(("    " + op + sfx + " %" + rfXmmB() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "EQ_FLOAT":
            case "NEQ_FLOAT":
            case "LT_FLOAT":
            case "LT_EQ_FLOAT":
            case "GT_EQ_FLOAT":
            case "GT_FLOAT": {
                // Same unordered-compare caveat DIV_INT/MOD_INT's own note
                // flags for unsigned-only integer division: `ucomiss` sets
                // CF=ZF=PF=1 whenever either operand is NaN ("unordered"),
                // so a NaN compared with anything reads as both "less than"
                // and "equal to" it here, rather than IEEE-754's own rule
                // that every ordered comparison against NaN is false. This
                // mirrors the file's existing precedent for a scoped,
                // documented gap rather than a silent one: correct for
                // every ordinary (non-NaN) float comparison, not yet
                // NaN-aware.
                int size = (int) Long.parseLong(line.get(1).text);
                String cmp = size <= 4 ? "ucomiss" : "ucomisd";
                popReg("rbx");
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                movRegToXmm("rbx", rfXmmB());
                raw(("    " + cmp + " %" + rfXmmB() + ", %" + rfXmmA()));
                String setcc = mnemonic.equals("EQ_FLOAT") ? "sete"
                        : mnemonic.equals("NEQ_FLOAT") ? "setne"
                        : mnemonic.equals("LT_FLOAT") ? "setb"
                        : mnemonic.equals("LT_EQ_FLOAT") ? "setbe"
                        : mnemonic.equals("GT_EQ_FLOAT") ? "setae"
                        : "seta"; // GT_FLOAT
                raw("    " + setcc + " %al");
                raw("    movzbq %al, %rax");
                
                pushReg("rax");
                return;
            }
            case "AND":
            case "OR": {
                popReg("rbx");
                popReg("rax");
                raw(("    " + (mnemonic.equals("AND") ? "and" : "or") + "b %bl, %al"));
                pushReg("rax");
                return;
            }
            case "NOT": {
                popReg("rax");
                raw("    xorb $1, %al");
                pushReg("rax");
                return;
            }
            case "NEG": {
                popReg("rax");
                raw("    negq %rax");
                pushReg("rax");
                return;
            }
            case "NEG_FLOAT": {
                // Float negation flips the IEEE-754 sign bit; an integer `neg`
                // would two's-complement the bit pattern (wrong value).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (size <= 4) {
                    raw("    xorl $0x80000000, %eax");
                } else {
                    movImmToReg("rbx", Long.MIN_VALUE);
                    raw("    xorq %rbx, %rax");
                }
                pushReg("rax");
                return;
            }
            case "INC_INT":
            case "DEC_INT": {
                popReg("rax");
                raw(("    " + (mnemonic.equals("INC_INT") ? "inc" : "dec") + "q %rax"));
                pushReg("rax");
                return;
            }
            case "INC_FLOAT":
            case "DEC_FLOAT": {
                // No SSE "increment by one" instruction exists (unlike
                // `inc`/`dec` on a GPR) -- 1.0's own IEEE-754 bit pattern
                // is loaded as a plain integer immediate (exactly how a
                // float literal already reaches a register in PUSH's own
                // isFloatLiteral branch above) and added/subtracted as a
                // real SSE operand instead.
                int size = (int) Long.parseLong(line.get(1).text);
                long oneBits = size <= 4
                        ? Integer.toUnsignedLong(Float.floatToRawIntBits(1.0f))
                        : Double.doubleToRawLongBits(1.0);
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                movImmToReg("rbx", oneBits);
                movRegToXmm("rbx", rfXmmB());
                String sfx = sseSuffix(size);
                String op = mnemonic.equals("INC_FLOAT") ? "add" : "sub";
                raw(("    " + op + sfx + " %" + rfXmmB() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "CMP": {
                // Pops the boolean condition and sets the real flags
                // from it -- "if true, fall through; if false, jump
                // away" (emitForLoop's own doc comment, confirmed
                // directly), and CMP is always immediately followed by
                // exactly one JMP (verified against every real low-order
                // output this pass was checked against) -- so the
                // *next* JMP becomes a conditional "jump if false"
                // rather than an unconditional jump; see the JMP case.
                popReg("rax");
                raw("    testq %rax, %rax");
                lastWasCmp = true;
                return;
            }
            case "JMP": {
                String label = mangleLabel(line.get(1).text);
                if (wasCmp) {
                    raw(("    je " + label));
                } else {
                    raw(("    jmp " + label));
                }
                return;
            }
            case "SEXT": {
                int srcSize = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (srcSize == 4) {
                    raw("    movslq %eax, %rax");
                } else {
                    raw("    movs" + movSuffix(srcSize) + "q %" + sizedReg("rax", srcSize) + ", %rax");
                }
                if (line.size() > 2) {
                    // Narrow results are kept zero-extended in the stack word: drop the sign bits above dstSize.
                    int sextDst = (int) Long.parseLong(line.get(2).text);
                    if (sextDst < 8) {
                        if (sextDst == 4) {
                            raw("    movl %eax, %eax");
                        } else {
                            raw("    movz" + movSuffix(sextDst) + "q %" + sizedReg("rax", sextDst) + ", %rax");
                        }
                    }
                }
                pushReg("rax");
                return;
            }
            case "TRUNC": {
                // "TRUNC srcSize destSize" -- integer narrowing (only ever emitted where a
                // `match x fits T` proof shows the value is representable in T). Keeps the low
                // destSize bytes and zero-fills the rest of the stack word.
                int dstSize = (int) Long.parseLong(line.get(2).text);
                popReg("rax");
                if (dstSize < 8) {
                    if (dstSize == 4) {
                        raw("    movl %eax, %eax");
                    } else {
                        raw("    movz" + movSuffix(dstSize) + "q %" + sizedReg("rax", dstSize) + ", %rax");
                    }
                }
                pushReg("rax");
                return;
            }
            case "ZEXT": {
                int srcSize = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (srcSize == 4) {
                    raw("    movl %eax, %eax"); // writing the 32-bit half auto-zeroes the upper 32 bits
                } else if (srcSize < 8) {
                    raw("    movz" + movSuffix(srcSize) + "q %" + sizedReg("rax", srcSize) + ", %rax");
                }
                pushReg("rax");
                return;
            }
            case "DUP_TOP": {
                // Duplicates the top 8-byte stack word in place (pop,
                // then push it back twice) -- used only by the front
                // end's own win64-varargs-float-duplication rule (see
                // `BytecodeEmitter.emitArgTransferTail`'s own doc
                // comment): "for variadic functions, floating-point
                // arguments must be passed in both the XMM register and
                // the corresponding general-purpose register" is a
                // win64-only ABI requirement with no SysV equivalent --
                // the one already-promoted 8-byte double bit pattern
                // needs to reach both a "POP FARGn" and a "POP ARGn"
                // line, and this is what makes a second, independent
                // copy of it available to consume. A generic "duplicate
                // the top of stack" primitive, not itself float-specific
                // -- nothing about this mnemonic cares what the 8 bytes
                // mean.
                popReg("rax");
                pushReg("rax");
                pushReg("rax");
                return;
            }
            case "FCONV": {
                // "FCONV srcSize dstSize" -- float conversion: 4 -> 8 widens (cvtss2sd, exact), 8 -> 4 narrows (cvtsd2ss, rounds to
                // nearest); equal sizes leave the value alone. The value travels as its bit pattern in one stack word.
                int fromSize = (int) Long.parseLong(line.get(1).text);
                int toSize = (int) Long.parseLong(line.get(2).text);
                if (fromSize == toSize) {
                    return;
                }
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                String cv = fromSize == 4 ? "cvtss2sd" : "cvtsd2ss";
                raw(("    " + cv + " %" + rfXmmA() + ", %" + rfXmmA()));
                if (toSize == 4) {
                    rfXmmToGpr(rfXmmA(), "rax", 4); // movd: zero-extends the 32 result bits
                } else {
                    movXmmToReg(rfXmmA(), "rax");
                }
                pushReg("rax");
                return;
            }
            case "PROMOTE_F32_TO_F64": {
                // C's own "default argument promotion" rule: a `float`
                // (this language's `f32`) argument reaching the `...`
                // portion of a variadic call (printf's own "%f" and
                // friends) is promoted to `double` *before* the call,
                // unconditionally -- never left as a raw 4-byte value,
                // regardless of what the callee's own varargs-reading
                // code assumes. This is a real, separate ABI step from
                // ordinary argument marshalling: the fixed, non-varargs
                // prefix of a variadic function's own declared
                // parameters (e.g. printf's own format-string argument)
                // is NOT promoted -- only the true varargs are -- so this
                // mnemonic is only ever emitted by the front end
                // (`emitArgWord`'s own promote flag) for an argument
                // index at or past the extern's own declared fixed
                // parameter count, on a genuinely `f32`-typed argument.
                // No `f64`/`double` type exists anywhere in this
                // language (confirmed directly, see `FLOAT_CHECK`'s own
                // doc comment above) -- this mnemonic's whole job is
                // purely a *value*-level ABI conversion, in place, on
                // the stack; it introduces no new type anywhere in the
                // rest of this bytecode format.
                //
                // Every value on this backend's own stack already
                // travels as an opaque 8-byte word (see the "Float
                // (SSE) helpers" doc comment above) -- a pushed `f32`
                // sits in the low 4 bytes of that word, upper 4 bytes
                // zero (confirmed directly against real generated
                // assembly: `movq %xmm0, %rax` zero-extends because the
                // source xmm register's own upper 32 bits are zero for
                // a real `f32` value). So: pop that word, reinterpret
                // its low 32 bits as a real `float` in a scratch xmm
                // register (`movq`, the same opaque-bit-move
                // `movRegToXmm` already uses elsewhere -- exact here
                // since the upper garbage bits are already known zero),
                // widen with a genuine `cvtss2sd` (the one real
                // floating-point conversion this mnemonic exists for),
                // move the resulting 8-byte `double` bit pattern back to
                // a GPR, and push it -- now a full, ABI-correct 8-byte
                // double occupying the same one stack word a raw `f32`
                // word already occupied, so nothing downstream
                // (register-vs-stack argument counting, `POP FARGn`, an
                // overflowing stack-passed word) needs to change at
                // all: this still consumes exactly one float-bank
                // argument slot, exactly as an unpromoted `f32` word
                // already did.
                //
                // **Deliberately uses `xmm8`, never `xmm0`, as scratch --
                // a real, confirmed register-clobber bug otherwise, the
                // identical class of hazard already documented for
                // `LOOKUP_ARRAY`'s own scratch-register choice** (this
                // project's own CLAUDE.md, "a second, real bug found
                // ... this time a register-clobber"): a multi-float
                // variadic call (e.g. `printf("%f %f", a, b)`) already
                // has `a`'s own promoted value sitting live in `xmm0`
                // (its real "POP FARG0" destination) by the time `b`'s
                // own "PUSH b / PROMOTE_F32_TO_F64 / POP FARG1" sequence
                // runs -- using `xmm0` as this mnemonic's own scratch
                // register for `b` would silently overwrite `a`'s
                // already-transferred value before the call ever runs
                // (confirmed directly: printed both floats as `b`'s own
                // value before this fix). `xmm8` is never a real SysV or
                // win64 argument register in either convention (both cap
                // out at `xmm7`/`xmm3` respectively), so it can never
                // collide with a live, not-yet-consumed `FARGn`
                // transfer, no matter how many float arguments a call
                // has or how many of them have already landed in their
                // own real argument register by the time this mnemonic
                // runs for a later one.
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                raw(("    cvtss2sd %" + rfXmmA() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "PUSH_FIELDNAME": {
                // Already address-lowered to "PUSH_FIELDNAME offset
                // size" -- pushes the (compile-time-constant) field
                // offset as an immediate, for the DOT/DOT_LHS that
                // always immediately follows to add to the base address
                // already on the stack.
                long offset = Long.parseLong(line.get(1).text);
                movImmToReg("rax", offset);
                pushReg("rax");
                // Preserve a pending value-block across this (see PUSH's
                // own note above) -- e.g. a DOT chained right after a
                // LOOKUP_ARRAY read needs to know the element value
                // LOOKUP_ARRAY just left on the stack is still there.
                lastValueBlockSize = pendingBlockSize;
                return;
            }
            case "DOT_LHS": {
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // field offset (from PUSH_FIELDNAME)
                popReg("rax"); // base address
                raw("    addq %rbx, %rax");
                pushReg("rax"); // the computed field address itself
                return;
            }
            case "DOT": {
                // Like LOOKUP_ARRAY vs LOOKUP_ARRAY_LHS above: DOT_LHS's
                // base is a plain address, but a *read* DOT's base is
                // the whole containing value pushed by value (confirmed
                // directly by the same evidence: a bare struct's own
                // "PUSH structSize $offset" before PUSH_FIELDNAME+DOT --
                // e.g. reading a field off an array-of-structs element
                // LOOKUP_ARRAY (non-LHS) just extracted, "PUSH 48 $-200 /
                // PUSH idx / LOOKUP_ARRAY 24 / PUSH_FIELDNAME off size /
                // DOT size"). `pendingBlockSize` is that containing
                // value's own total byte size.
                int size = (int) Long.parseLong(line.get(1).text);
                if (pendingBlockSize <= 0) {
                    // The containing value is a struct of at most 8 bytes held BY VALUE in one natural-order word (tagged negative by PUSH / DEREF,
                    // or untagged: an element read through a pointer base is just loaded): the field is a shift and a mask, no address. (This used to fall into the placeholder below, which
                    // silently read 0: `d[i].a0` on an unsafe dynarray of a 3-byte struct with the register-form passes off.)
                    boolean fieldIsSmallArrayV = line.size() > 2 && line.get(2).text.equals("ra");
                    popReg("rbx"); // field offset in bytes
                    popReg("rax"); // the whole value
                    raw("    pushq %rcx");   // %rcx is the shift count and may hold an already-popped call argument
                    raw("    movq %rbx, %rcx");
                    raw("    shlq $3, %rcx");
                    raw("    shrq %cl, %rax");
                    raw("    popq %rcx");
                    if (size < 8) {
                        if (isOddSize(size)) {
                            int sh = 64 - size * 8;
                            raw("    shlq $" + sh + ", %rax");
                            raw("    shrq $" + sh + ", %rax");
                        } else {
                            raw(size == 4 ? "    movl %eax, %eax" : size == 2 ? "    movzwl %ax, %eax" : "    movzbl %al, %eax");
                        }
                    }
                    pushReg("rax");
                    if (fieldIsSmallArrayV) {
                        lastValueBlockSize = -size;
                    }
                    return;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // field offset (in bytes, from the value's own front)
                if (size <= 8) {
                    // A field inside one word. The block's words are reversed (the front word is the deepest), so the byte at struct offset X lives at
                    // rsp + (totalWords-1-X/8)*8 + X%8. (The formula below is right only for a whole aligned word or a wider field: for a narrower field it
                    // pointed into the wrong half of a word, e.g. `arr[i].a` for a u32 at offset 8 read the byte at offset 12.)
                    raw("    movq %rbx, %rax");
                    raw("    andq $-8, %rax");
                    raw("    negq %rax");
                    raw("    addq $" + ((totalWords - 1) * 8) + ", %rax");
                    raw("    andq $7, %rbx");
                    raw("    addq %rbx, %rax");
                    raw("    addq %rsp, %rax");
                    
                } else {
                    raw("    movq %rbx, %r14");
                    raw("    andq $7, %r14");
                    raw("    shrq $3, %rbx");
                    raw("    imulq $8, %rbx, %rbx");
                    raw("    movq $" + (totalWords * 8) + ", %rax");
                    raw("    subq $8, %rax");
                    raw("    subq %rbx, %rax");
                    raw("    addq %r14, %rax");
                    raw("    addq %rsp, %rax");
                }
                boolean fieldIsSmallArray = line.size() > 2 && line.get(2).text.equals("ra");
                if (size <= 8 && fieldIsSmallArray) {
                    // A fixed array of at most 8 bytes: its bytes may straddle two of the block's (reversed) words, so they are not contiguous in
                    // memory. rax = address of the field's first byte, rbx = its offset within its word. Read the word it starts in and the next
                    // struct word (the next-lower address) -- only when the field really straddles, otherwise the same word again, so nothing below the pushed block is read -- and shift the pair together (shrd); bytes past the field are junk, as for any small array.
                    raw("    movq %rcx, %r14");
                    raw("    movq %rbx, %rcx");
                    raw("    shlq $3, %rcx");
                    raw("    subq %rbx, %rax");
                    raw("    leaq -8(%rax), %r11");
                    raw("    cmpq $" + (8 - size) + ", %rbx");
                    raw("    cmovbeq %rax, %r11");   // no straddle: read the same word again, never the word below the block
                    raw("    movq (%r11), %r11");
                    raw("    movq (%rax), %rax");
                    raw("    shrdq %cl, %r11, %rax");
                    raw("    movq %r14, %rcx");
                    raw("    addq $" + (totalWords * 8) + ", %rsp");
                    
                    pushReg("rax");
                    lastValueBlockSize = -size;
                } else if (size <= 8) {
                    loadSizedFromAddr("rax", "rax", size);
                    raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                } else {
                    // A multi-word nested field (a struct- or array-typed
                    // field read off a base that arrived as a whole pushed
                    // value, e.g. "arr[i].point" -- the array element
                    // itself came from LOOKUP_ARRAY as a still-pending
                    // multi-word block, and this DOT is now reading a
                    // >8-byte field out of it).
                    //
                    // `rax` (just computed above, shared with the size<=8
                    // branch) is *not* the field's front-most word the way
                    // LOOKUP_ARRAY's own analogous `elemSize > 8` fix's
                    // `rax` is (that formula subtracts a fixed one-word `8`;
                    // this one subtracts the field's own full `size`) --
                    // confirmed by working the address algebra through for
                    // a field at byte-offset `off` covering word indices
                    // `off/8 .. off/8+fieldWords-1` within the containing
                    // block: this formula's `rax` lands on word index
                    // `off/8 + fieldWords - 1`, i.e. the field's own
                    // *back*-most word (nearest rsp), the same structural
                    // role plain `%rsp` itself already plays for a whole
                    // pushed block (address_of_wordIndex(totalWords-1) ==
                    // rsp+0, exactly this formula's own shape with
                    // size==totalWords*8, off==0). Since `rax` is already
                    // a same-role "back-word anchor" for the field the way
                    // `rsp` is for the whole block, moving the field's own
                    // words into a fresh, `rsp`-anchored block needs no
                    // reversal at all -- a word at relative offset `k*8`
                    // from `rax` goes to relative offset `k*8` from the new
                    // (post-shrink) `rsp`, k ascending, preserving this
                    // backend's own "back-most word nearest rsp" convention
                    // automatically. (A first attempt at this copied
                    // LOOKUP_ARRAY's own reversed-copy shape verbatim,
                    // reasoning `rax` was a front-word anchor like that
                    // fix's own `rax` -- confirmed wrong via a real
                    // execution test, `arr[i].point` printing `x=8 y=0`
                    // instead of the real field values, before this
                    // same-relative-offset version was worked out and
                    // reverified.)
                    //
                    // rsp is shrunk to the field's own final size FIRST
                    // (mirroring the *reason*, not the shape, of
                    // LOOKUP_ARRAY's own fix: `rax`, captured into `r14`
                    // beforehand, is an absolute address unaffected by
                    // moving rsp afterward, so this ordering is always
                    // safe and guarantees the copy's own destination is
                    // the address range that will actually still be live).
                    extractWordsFromReversedBlock(totalWords, size);
                    lastValueBlockSize = size; // for a chained DOT right after this
                }
                return;
            }
            case "LOOKUP_ARRAY_LHS": {
                // The write side always gets its base via a plain
                // "ADDR" (a single address word), confirmed directly
                // against array_lookup_cg_test.caspien's own real
                // low-order output -- ordinary address arithmetic.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // base address
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                raw("    addq %rbx, %rax");
                
                pushReg("rax");
                return;
            }
            case "LOOKUP_ARRAY": {
                // The *read* side, by contrast, always gets its base via
                // a plain "PUSH <totalArraySize> $offset" -- the whole
                // array pushed by value (see PUSH's own note above) --
                // never an address, confirmed directly against the same
                // fixture: "PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8"
                // (a real bug this pass shipped with until caught by
                // actually running this exact test -- the old code
                // treated the popped array VALUE as if it were a
                // pointer, corrupting every later use of the real stack).
                // `pendingBlockSize` is the whole array's own total byte
                // count, tracked from that preceding PUSH; extracting
                // element `index` means locating word
                // index*elemSize/8 counting from the *front* of the
                // array (its lowest source offset, which -- per
                // pushBlockFromFrame's own convention -- ended up
                // *deepest* on the real stack, not on top).
                int elemSize = (int) Long.parseLong(line.get(1).text);
                // "ra": the element read is itself a fixed array narrower than 8 bytes, held by value -- tag it for the next LOOKUP_ARRAY (as PUSH does)
                boolean resultIsSmallArray = false;
                for (int q = 2; q < line.size(); q++) {
                    resultIsSmallArray |= line.get(q).text.equals("ra");
                }
                if (pendingBlockSize == 0 && line.size() > 2 && line.get(2).text.equals("t8")) {
                    // The lowering says the value is a whole 8-byte fixed array (not a pointer): its bytes are one natural-order word, exactly the shape
                    // of a smaller array pushed by value.
                    pendingBlockSize = -8;
                }
                if (pendingBlockSize < 0) {
                    // A small (<8-byte) fixed array pushed by value as a
                    // single, ordinary, natural-byte-order word -- see
                    // PUSH's own "size < 8" branch, which is the only
                    // place this negative encoding is ever produced. No
                    // memory access at all: the whole array's own bytes
                    // are already sitting directly in a register, so
                    // extracting element `index` is a plain shift-and-
                    // mask, never an address computation followed by a
                    // dereference (which is exactly what the fallback
                    // just below does, and exactly what corrupted this
                    // case before this branch existed -- a small array's
                    // own packed *value* is not a pointer).
                    popReg("rbx"); // index
                    popReg("rax"); // the whole small array's own packed value
                    // %rcx is both the shift-count register and an argument register (4th SysV, 1st win64): a finished earlier argument
                    // of the call being assembled may already sit in it (`printf(fmt, a, s[0], s[1], s[2])` popped s[0]/s[1] into their
                    // registers before this lookup runs), so save and restore it around the shift.
                    raw("    imulq $" + (elemSize * 8) + ", %rbx, %rbx"); // index*elemSize, in bits
                    raw("    pushq %rcx");
                    raw("    movq %rbx, %rcx");
                    raw("    shrq %cl, %rax");
                    raw("    popq %rcx");
                    
                    if (isOddSize(elemSize)) {
                        // 3, 5, 6, 7 bytes (a row of a `u8[3][2]`): keep exactly those bytes. (The 1-byte `movzbl` used to be applied here too,
                        // cutting a row down to its first element.)
                        int sh = 64 - elemSize * 8;
                        raw(("    shlq $" + sh + ", %rax"));
                        raw(("    shrq $" + sh + ", %rax"));
                    } else if (elemSize < 8) {
                        // `andq` takes only a sign-extended 32-bit immediate, so a 4-byte
                        // mask (0xFFFFFFFF) was rejected by the assembler; a zero-extending
                        // move does the same job for 1, 2 and 4 bytes.
                        raw(elemSize == 4 ? "    movl %eax, %eax" : elemSize == 2 ? "    movzwl %ax, %eax" : "    movzbl %al, %eax");
                        
                    }
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                    return;
                }
                if (pendingBlockSize == 0) {
                    // The same LOOKUP_ARRAY(_LHS) family is also used for
                    // an unsafe-dynarray's own direct (unchecked) index,
                    // whose "array" is really just a pointer *value* --
                    // an ordinary single 8-byte PUSH, not a multi-word
                    // block -- confirmed directly (an unsafe-dynarray
                    // fixture pushes "PUSH 8 $ptr / PUSH 8 idx /
                    // LOOKUP_ARRAY size" for both its read *and* write
                    // sides, unlike a true fixed array's own read/write
                    // split above). There, the popped value already *is*
                    // the base address to index from, exactly like the
                    // pre-existing single-word logic this pass shipped
                    // with originally -- so that's the fallback here,
                    // not a guess.
                    popReg("rbx"); // index
                    popReg("rax"); // base address (a plain pointer value)
                    raw("    imulq $" + elemSize + ", %rbx, %rbx");
                    raw("    addq %rbx, %rax");
                    
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    } else if (elemSize > 8) {
                        lastValueBlockSize = elemSize; // a struct element read through a pointer base: the DOT that follows needs the block size
                    }
                    return;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // index
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                
                // rbx now holds the target element's byte offset from the
                // array's own front (source-offset order). The pushed
                // block's own *words* are reversed (word i-from-front
                // sits at rsp + (totalWords-1-i)*8), but the *bytes
                // within* any one already-placed word are never
                // reordered by the push at all -- so a byte offset has to
                // be split into a word index (which gets the reversed
                // treatment) and a byte-within-word remainder (which
                // doesn't) before it can be turned into a real address:
                // rdx = rbx & 7 (byte-within-word), rbx = rbx >> 3
                // (word index, reusing rbx in place), then address =
                // rsp + (totalWords - 1 - wordIndex)*8 + byteWithinWord.
                //
                // 2026-09: two bugs fixed here together, both found via
                // real execution tests, neither previously caught because
                // every fixture ever checked against this branch before
                // now happened to have `elemSize` be an exact multiple of
                // 8 with `index*elemSize` therefore always landing on a
                // word boundary (byteWithinWord always 0) --
                //
                // 1. This used to subtract a bare `elemSize` here instead
                // of a fixed one-word `8` -- wrong for any element wider
                // than one word (a nested array, a struct: a 2D
                // `u64[3][3]`'s every row read back as row 0's own data,
                // regardless of the real index).
                //
                // 2. Even with that fixed, treating the *whole* byte
                // offset as if it were a whole-word offset is wrong the
                // moment more than one logical element shares a single
                // packed word -- this language's own tight sub-word
                // array/struct-literal construction packing means a
                // `char[10]` (say) really occupies just two real 8-byte
                // words, not ten (see `populateDirectMembers`'s own doc
                // comment) -- so a narrow (<8-byte) element's own real
                // address needs this word/byte split; without it, only
                // elements sharing word 0 (i.e. index 0 itself) ever
                // happened to read back correctly. See
                // caspien-codegen's own CLAUDE.md for the full writeup
                // of all three bugs this branch has now had fixed.
                //
                // The byte-within-word remainder needs a scratch
                // register -- `%rdx` was tried first and was wrong: this
                // whole LOOKUP_ARRAY sequence can sit in the middle of a
                // multi-argument call's own argument list (e.g. each of
                // `printf(fmt, arr[0], arr[1], arr[2])`'s three lookups
                // runs back to back, with each finished argument
                // immediately popped into its own real calling-convention
                // register -- `%rsi`, then `%rdx`, then `%rcx` for SysV --
                // before the *next* argument's own lookup even starts).
                // Using `%rdx` here clobbered exactly that: the second
                // argument's own already-computed, not-yet-called value
                // sitting in `%rdx` was silently overwritten by the
                // *third* argument's own lookup reusing `%rdx` as scratch,
                // before `printf` was ever called -- found via a real
                // execution test, a 2D array's middle column printing `0`
                // instead of its real value, and confirmed via gdb
                // (`%rdx` held the correct, second argument's value right
                // up until the third lookup's own `and $7, %rdx`
                // instruction). `%r14` is used instead -- already this
                // branch's own scratch register for the `elemSize > 8`
                // case just below, never a calling-convention argument
                // register in either ABI, and (unlike `%rdx`) never used
                // to carry a live, not-yet-consumed value across separate
                // LOOKUP_ARRAY invocations anywhere else in this file.
                raw("    movq %rbx, %r14");
                raw("    andq $7, %r14");
                raw("    shrq $3, %rbx");
                raw("    imulq $8, %rbx, %rbx");
                raw("    movq $" + (totalWords * 8) + ", %rax");
                raw("    subq $8, %rax");
                raw("    subq %rbx, %rax");
                raw("    addq %r14, %rax");
                raw("    addq %rsp, %rax");
                
                if (isOddSize(elemSize)) {
                    // A 3, 5, 6 or 7 byte element (a row of a `u16[3][2]`) can start in one source word and end in the next, and the words of
                    // the pushed block are in reverse order (word k+1 sits 8 bytes BELOW word k), so its bytes are not contiguous in memory and a
                    // plain load at `rax` reads the bytes above the block instead of the second word. Same shrd extraction as the multi-word case
                    // below, for one result word: `rax` = address of the element's first byte, `r14` = its offset inside the word (0..7).
                    raw("    movq %rax, %r11");
                    raw("    subq %r14, %r11");
                    raw("    shlq $3, %r14");
                    raw("    movq %rcx, %r13");
                    raw("    movq %r14, %rcx");
                    raw("    movq (%r11), %rax");
                    raw("    movq -8(%r11), %r14");
                    raw("    shrdq %cl, %r14, %rax");
                    raw("    movq %r13, %rcx");
                    raw("    addq $" + (totalWords * 8) + ", %rsp");
                    
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else if (elemSize <= 8) {
                    loadSizedFromAddr("rax", "rax", elemSize);
                    // discard the whole original block now that the one
                    // element we need is safely copied into rax.
                    raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else {
                    // A multi-word element (an array of structs, or a nested array, say). `rax` is the address of the element's first BYTE
                    // inside the pushed block, `r14` = that byte's offset inside its word (0..7).
                    // Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word. So element bytes are NOT
                    // contiguous in memory unless the element starts on a word boundary (a 12-byte `u32[3]` row of a `u32[3][2]` starts at byte
                    // 12): result word j is the 64 bits starting at byte `within` of source word w+j, continued into word w+j+1 (which sits
                    // 8 bytes BELOW it) -- exactly `shrd`. A count of 0 (aligned element) leaves the word unchanged.
                    // Result word j goes to source word j's slot (rsp + (T-1-j)*8): that is where it must end up once rsp is raised by
                    // (T - E)*8 (result word j sits at rsp' + (E-1-j)*8), and writing in ascending j never overwrites a word still to be read
                    // (step j reads words w+j and w+j+1, both >= j+... greater than every slot already written).
                    // The word past the block end (read for the last, possibly partial, result word) lies just below rsp: valid stack, and
                    // only bytes beyond the element come from it.
                    // %rcx is the shift count register and may hold an already-loaded call argument: stash it in r13 (backend scratch).
                    extractWordsFromReversedBlock(totalWords, elemSize);
                    lastValueBlockSize = elemSize; // for a chained DOT right after this
                }
                return;
            }
            case "LOOKUP_DYN":
            case "LOOKUP_DYN_LHS": {
                // This backend's own dynarray layout: [len:8][cap:8]
                // [elements...] -- see class doc comment. The element
                // offset is 16 + index*elemSize.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // dynarray pointer
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                raw("    addq %rbx, %rax");
                raw("    addq $16, %rax");
                
                if (mnemonic.equals("LOOKUP_DYN_LHS")) {
                    pushReg("rax");
                } else {
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    // An element wider than a word (a struct) is now a pushed value block: tell a DOT / LOOKUP that follows (`es[i].to`) how big it
                    // is, exactly as LOOKUP_ARRAY does for an array element. Without this the DOT found no pending block ("DOT with no preceding
                    // pushed value") and pushed 0, so any read of a field of a dynarray-of-struct element was wrong or crashed.
                    if (elemSize > 8) {
                        lastValueBlockSize = elemSize;
                    }
                }
                return;
            }
            case "LEN": {
                popReg("rax"); // dynarray pointer
                loadSizedFromAddr("rax", "rax", 8); // the header's own len field, offset 0
                pushReg("rax");
                return;
            }
            case "STACK_LOCK":
            case "STRUCT_PADDING": {
                // STACK_LOCK: reserve N raw, valueless bytes of stack
                // space mid-struct-construction so later-pushed members
                // land at their real, padded offsets (see
                // caspien-compiler's own emitInstantiate, "STACK_LOCK
                // reused for padding at struct construction"). Plain
                // 'sub rsp,N' does exactly this. STRUCT_PADDING, by
                // contrast, was never actually observed inside any real
                // function body across every fixture this pass was
                // checked against -- only as inert, stray top-level
                // metadata between two FUNC_END/FUNC_START lines (dead
                // leftovers that should have been fully stripped a stage
                // earlier) -- so it's treated as a pure no-op rather
                // than reserving stack space that would silently
                // misalign this backend's own push/pop accounting if
                // that assumption turns out wrong somewhere this pass
                // wasn't checked.
                if (mnemonic.equals("STACK_LOCK")) {
                    long n = Long.parseLong(line.get(1).text);
                    raw(("    subq $" + n + ", %rsp"));
                }
                return;
            }
            case "NEW": {
                // Every field push between the true start of this
                // construction and this exact line is now
                // construction-scoped (see `precomputeConstructionOffsets`/
                // `populateDirectMembers`'s own doc comments): each one reserved
                // and wrote only its own declared width, not a uniform
                // 8-byte word, and each "STACK_LOCK" padding gap did its
                // own exact `subq`. So `%rsp`, right here, already
                // points at the start of a real, byte-exact,
                // tightly-packed image of the value being constructed
                // -- `size` bytes, laid out exactly the way
                // `computeStructLayout` says, with zero further
                // reconciliation needed. NEW's whole job is now
                // genuinely trivial and completely struct-agnostic --
                // no field count, no offsets, nothing type-specific:
                // malloc(size), copy those `size` bytes straight off
                // the stack into the new buffer, deallocate the stack
                // region they came from now that they're safely copied,
                // and push the new pointer.
                //
                // This used to pop one 8-byte hardware word per
                // (size+7)/8, silently under-counting the real number
                // of pushed words the moment any field was narrower
                // than 8 bytes (a `bool` next to a `u64`, say) --
                // confirmed directly against real bytecode/assembly for
                // exactly that shape. Fixed at the true root instead
                // (the pushes themselves, above, weren't byte-exact),
                // so this case no longer needs to know or care how many
                // fields there were: one straight block copy, the exact
                // same `rep movsb` convention `case "CLONE"` already
                // uses for its own whole-buffer copy, just from the
                // stack instead of from another heap pointer.
                long size = Long.parseLong(line.get(1).text);
                if (!packedNewLines.contains(currentLineIndex) && line.size() > 2) {
                    emitNewRepack(size, line);
                    return;
                }
                raw("    movq %rsp, %r12"); // r12: source address -- the construction's own tightly-packed image already sitting on the stack, callee-saved, survives the malloc call below
                emitMallocCall(size);
                raw("    movq %rax, %r14"); // r14: the fresh buffer, also callee-saved
                // A failed malloc leaves r14 null: skip the copy (it would fault on address 0); the bytecode's own null check throws.
                String newDone = newInternalLabel("new_done");
                raw("    testq %r14, %r14");
                raw("    jz " + newDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq $" + size + ", %rcx");
                raw("    rep movsb");
                
                raw(newDone + ":");
                raw(("    addq $" + size + ", %rsp"));
                pushReg("r14");
                return;
            }
            case "CLONE_DYN": {
                // A safe dynarray pointee: the block is a 16-byte header (length, capacity) plus length*elemSize bytes of
                // elements, so the size is only known at run time. Same null handling as CLONE.
                long elemSize = Long.parseLong(line.get(1).text);
                popReg("r12"); // source block start
                raw("    movq (%r12), %r15");
                raw("    imulq $" + elemSize + ", %r15, %r15");
                raw("    addq $16, %r15");
                raw("    movq %r15, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("malloc"));
                raw("    movq %rax, %r14");
                String cloneDynDone = newInternalLabel("clone_dyn_done");
                raw("    testq %r14, %r14");
                raw("    jz " + cloneDynDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq %r15, %rcx");
                raw("    rep movsb");
                raw("    movq (%r14), %rax"); // the copy holds exactly length elements: capacity = length
                raw("    movq %rax, 8(%r14)");
                
                raw(cloneDynDone + ":");
                pushReg("r14");
                return;
            }
            case "CLONE": {
                long size = Long.parseLong(line.get(1).text);
                popReg("r12"); // source address (r12: callee-saved, survives the call below)
                emitMallocCall(size);
                raw("    movq %rax, %r14"); // r14: new pointer, also callee-saved
                // A failed malloc leaves r14 null: skip the copy (it would fault on address 0) and push the null,
                // which the bytecode's own null check right after CLONE turns into a throw.
                String cloneDone = newInternalLabel("clone_done");
                raw("    testq %r14, %r14");
                raw("    jz " + cloneDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq $" + size + ", %rcx");
                raw("    rep movsb");
                
                raw(cloneDone + ":");
                pushReg("r14");
                return;
            }
            case "MEMCOPY": {
                // Pops (top to bottom) src, size, dest -- a plain byte
                // copy, no result pushed (the two "PUSH $addr" lines
                // immediately around every real MEMCOPY site this pass
                // was checked against re-read their own variables
                // separately afterward, confirmed directly).
                long constSize = memcopyConstantSize();
                popReg("rsi"); // src
                popReg("rcx"); // size
                popReg("rdi"); // dest
                if (constSize >= 0) {
                    // A small compile-time size (a char, an integer, a key being hashed) is copied with plain moves: `rep movsb` costs a
                    // few dozen cycles of start-up however short the copy is. rax is free here (every sequence of this backend uses it as scratch).
                    long pos = 0;
                    while (pos < constSize) {
                        long left = constSize - pos;
                        int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
                        raw("    mov" + movSuffix(w) + " " + pos + "(%rsi), %" + sizedReg("rax", w));
                        raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + pos + "(%rdi)");
                        pos += w;
                    }
                    return;
                }
                raw("    rep movsb");
                return;
            }
            case "DEREF": {
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // pointer
                // The value this leaves is a whole aggregate by value when a LOOKUP_ARRAY/DOT follows (an array member read through an
                // owning pointer: "DOT_LHS / DEREF 24 / PUSH idx / LOOKUP_ARRAY 8"), so record its shape the way PUSH does for a value read
                // from the frame: > 8 bytes is a pushed word block, < 8 bytes a single natural-order word (negative tag). Without this
                // LOOKUP_ARRAY took its "bare pointer" fallback and dereferenced the array's own contents as an address.
                lastValueBlockSize = size > 8 ? size : (size < 8 ? -size : 0);
                if (size <= 8) {
                    loadSizedFromAddr("rax", "rbx", size);
                    pushReg("rax");
                } else {
                    // A multi-word (>8 byte) aggregate dereference --
                    // pushes ceil(size/8) whole words, lowest source
                    // address first, so the highest-address word ends up
                    // on top of the real stack; the matching "POP $addr
                    // size" block-store (see POP below) unwinds this in
                    // the mirror order. Only whole-8-byte chunks are
                    // handled -- a non-multiple-of-8 aggregate size was
                    // never observed and isn't specially handled.
                    long words = (size + 7) / 8;
                    for (long i = 0; i < words; i++) {
                        loadSizedFromAddr("rax", "rbx", 8);
                        if (i < words - 1) {
                            raw("    addq $8, %rbx");
                        }
                        pushReg("rax");
                    }
                }
                return;
            }
            case "ADDR_OF": {
                // The "ADDR_OF kind $offset" shape (a plain addressable
                // local) was, at first, only ever observed here with
                // kind=RAW -- but AddressLoweringPass's own rewrite
                // (this pass's own doc comment: "opText (AUTO/RAW --
                // REF and a storage-bearing RAW never reach this
                // [rewrite]") already produces the identical "$offset"
                // shape for kind=AUTO too, confirmed directly while
                // testing a real auto-storage pointer end to end (see
                // BytecodeEmitter's "A pointer-typed dot-chain access
                // gets a real runtime dereference" fix): AUTO/RAW are
                // both just "take the address of an already-addressable
                // stack slot," with no runtime difference between them
                // -- the storage keyword only ever changes what the
                // *type checker* allows through it, never what the CPU
                // has to do to compute the address itself, so both kinds
                // get the identical leaMemToReg treatment below. Other
                // operand shapes (a global, a dotted field root, ...)
                // are still flagged rather than guessed at.
                //
                // AddressLoweringPass's own doc comment for this rewrite
                // says the plain "PUSH name type" line immediately
                // preceding this one (emitAddressOf's own
                // `emitExpr(op.left)`, run before ADDR_OF itself) is
                // superseded once the real address is known -- but that
                // pass only rewrites *this* line, it doesn't reach back
                // and delete the one before it, so that now-dead pushed
                // value genuinely survives into real low-order output
                // (confirmed directly by actually assembling and running
                // a real "raw <local>" fixture -- without this discard,
                // the stray value silently shifts everything below it on
                // the real stack, corrupting the very next real ASSIGN).
                // Popping it here, once, before pushing the real address
                // is exactly what the doc comment's own "dropped" already
                // says should happen to it.
                //
                // The dead value being discarded isn't always a single
                // 8-byte word: "auto obj" on a struct-typed `obj` pushes
                // `obj` by its own full declared size first (the ordinary
                // "PUSH size $offset, size>8 -> pushBlockFromFrame"
                // multi-word case, same as any other by-value struct
                // push), and *that's* the value ADDR_OF's own preceding
                // line pushed here -- confirmed directly while testing a
                // real "let p = auto structObj" end to end (a hardcoded
                // single-word discard left one whole stray word behind on
                // the stack for every struct-sized `auto`, corrupting
                // everything below it, the exact same class of bug this
                // case's own doc comment already describes for the
                // no-discard-at-all version). `pendingBlockSize` (already
                // computed above from the immediately preceding PUSH's
                // own `lastValueBlockSize`, the same state DOT/LOOKUP_ARRAY
                // already key off of) is 0 for an ordinary single-word
                // push and the real total byte size for a multi-word one.
                String kind = line.get(1).text;
                String operand = line.get(2).text;
                long discardBytes = pendingBlockSize > 8 ? ((pendingBlockSize + 7) / 8) * 8 : 8;
                raw(("    addq $" + discardBytes + ", %rsp")); // discard the dead pushed value
                if ((kind.equals("RAW") || kind.equals("AUTO")) && operand.startsWith("$")) {
                    leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                    pushReg("rax");
                } else {
                    comment("TODO(codegen): '" + String.join(" ", textsOf(line)) + "' not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                    pushReg("rax");
                }
                return;
            }
            case "NEW_DYN": {
                // "NEW_DYN totalPushedBytes count" -- `count` initializer
                // values, each currently assumed to be a plain 8-byte
                // word (this backend's own push/pop model treats every
                // value uniformly as one stack word regardless of its
                // declared type -- a narrower- or wider-than-8-byte
                // dynarray element isn't specially handled, matching
                // this pass's pre-existing "8-byte word" limitation
                // elsewhere). Allocates [len][cap][elements...], with
                // len == cap == count, and pops the `count` initializer
                // values (top of stack = last-pushed = last element)
                // into their slots.
                long count = Long.parseLong(line.get(2).text);
                // An element wider than one word (an inline struct) is pushed as es/8 words, lowest offset first, so the
                // element's highest word is on top; it is stored back at its natural offsets with the stride es.
                long totalPushed = Long.parseLong(line.get(1).text);
                long elemBytes = count > 0 && totalPushed / count > 8 ? totalPushed / count : 8;
                if (elemBytes % 8 != 0) {
                    throw new IllegalStateException("NEW_DYN: element size " + elemBytes + " is not a whole number of words");
                }
                long elemWords = elemBytes / 8;
                long dataBytes = count * elemBytes;
                emitMallocCall(16 + dataBytes);
                raw("    movq %rax, %r12");
                // A failed malloc leaves r12 null: write nothing into it, drop the element words and push the null so the
                // bytecode's own null check can throw.
                String newDynNull = newInternalLabel("newdyn_null");
                String newDynDone = newInternalLabel("newdyn_done");
                raw("    testq %r12, %r12");
                raw("    jz " + newDynNull);
                storeSizedToAddr_withOffsetImm("r12", 0, count, 8);
                storeSizedToAddr_withOffsetImm("r12", 8, count, 8);
                for (long i = count - 1; i >= 0; i--) {
                    for (long w = elemWords - 1; w >= 0; w--) {
                        popReg("rax");
                        long off = 16 + i * elemBytes + w * 8;
                        raw("    movq %rax, " + off + "(%r12)");
                        
                    }
                }
                raw("    jmp " + newDynDone);
                raw(newDynNull + ":");
                if (count > 0) {
                    raw(("    addq $" + count * elemWords * 8 + ", %rsp"));
                }
                raw(newDynDone + ":");
                pushReg("r12");
                return;
            }
            case "NEW_UDYN": {
                // "NEW_UDYN totalPushedBytes" -- the unsafe sibling of NEW_DYN; the element count is derived as
                // totalBytes/8 under the same "8-byte elements" working assumption. An unsafe dynarray is HEADERLESS:
                // just the elements, and the pointer it returns (and the variable holds) is exactly what malloc
                // returned, so it is also the address registered with the ghost table and the one free() needs.
                // Direct indexing is plain "base + index*elemSize" (LOOKUP_ARRAY). It stores no length, so there is
                // no len(); never allocates 0 bytes (an empty one gets 1) so a live array is never a NULL/zero-size block.
                long totalBytes = Long.parseLong(line.get(1).text);
                long count = totalBytes / 8;
                emitMallocCall(Math.max(totalBytes, 1));
                raw("    movq %rax, %r12");
                // Failed malloc: r12 stays null (see NEW_DYN); nothing is written.
                String newUdynNull = newInternalLabel("newudyn_null");
                String newUdynDone = newInternalLabel("newudyn_done");
                raw("    testq %r12, %r12");
                raw("    jz " + newUdynNull);
                for (long i = count - 1; i >= 0; i--) {
                    popReg("rax");
                    long off = i * 8;
                    raw("    movq %rax, " + off + "(%r12)");
                    
                }
                emitGtCall("gt_register", "r12");
                raw("    jmp " + newUdynDone);
                raw(newUdynNull + ":");
                if (count > 0) {
                    raw(("    addq $" + count * 8 + ", %rsp"));
                }
                raw(newUdynDone + ":");
                pushReg("r12");
                return;
            }
            case "NEW_FROM_USTRING":
            case "NEW_FROM_STRING": {
                // Builds a dynarray(u8-ish) from a string-pool id already
                // on the stack (its address, per the bare-name-PUSH
                // convention above) -- copies strlen(s)+ bytes... this
                // backend doesn't have strlen's result at codegen time,
                // so it calls the real, already-declared extern strlen
                // to get it, then allocates and copies via the same
                // dynarray header layout as NEW_DYN/NEW_UDYN.
                // NEW_FROM_USTRING: the unsafe, headerless form -- malloc'd block = the bytes plus a NUL terminator (so
                // len(arr, '\0') works), pointer = block, registered with the ghost table.
                final boolean ustr = mnemonic.equals("NEW_FROM_USTRING");
                popReg("r12"); // address of the string literal
                // The argument register is an ABI question (win64: rcx,
                // SysV: rdi), not a syntax one -- argReg(0) resolves
                // that; hardcoding "rdi" here (as this used to) is only
                // right for `linux` and silently wrong for
                // `windows_gnu` (AT&T syntax, win64 ABI).
                raw("    movq %r12, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("strlen"));
                // rax now holds the string length.
                raw("    movq %rax, %r13"); // r13: length, callee-saved
                raw("    addq $" + (ustr ? 1 : 16) + ", %rax");
                
                raw("    movq %rax, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("malloc"));
                raw("    movq %rax, %r14"); // r14: new block
                if (ustr) {
                    String ustrDone = newInternalLabel("newustr_done");
                    raw("    testq %r14, %r14");
                    raw("    jz " + ustrDone);
                    raw("    movq %r14, %rdi");
                    raw("    movq %r12, %rsi");
                    raw("    leaq 1(%r13), %rcx");
                    raw("    rep movsb");
                    
                    emitGtCall("gt_register", "r14");
                    raw(ustrDone + ":");
                    pushReg("r14");
                    return;
                }
                raw("    movq %r13, (%r14)");
                raw("    movq %r13, 8(%r14)");
                raw("    leaq 16(%r14), %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq %r13, %rcx");
                raw("    rep movsb");
                
                pushReg("r14");
                return;
            }
            case "RESIZE":
            case "URESIZE": {
                // RESIZE (safe, 3 popped values: fill, newCount, ptr) /
                // URESIZE (unsafe, 2 popped values: newCount, ptr, no
                // fill) -- both confirmed directly against
                // resize_uresize_lowering_test.caspien's own real
                // low-order output and BytecodeEmitter's own doc comment
                // for it. `elemSize` is the mnemonic's own trailing
                // operand. Reallocates to exactly newCount elements (no
                // growth slack, matching NEW_DYN/NEW_UDYN above), and
                // for the safe form, fills any newly-added slots
                // (oldLen..newCount) with the fill value.
                //
                // The safe (RESIZE) pointer is the malloc'd block start with a 16-byte [len][cap] header before the elements
                // (matching NEW_DYN/LOOKUP_DYN). The unsafe (URESIZE) pointer is the malloc'd block itself and the block is
                // just the elements: no header, nothing to read or write, realloc(ptr, newCount*elemSize) rounded up to
                // at least 1 byte (realloc(p, 0) would free the block and return NULL).
                int elemSize = (int) Long.parseLong(line.get(1).text);
                boolean hasFill = mnemonic.equals("RESIZE");
                // A fill value wider than one word (a struct element) is a
                // whole pushed block, nw words deep, ahead of newCount and
                // the old pointer; it stays on the real stack (rsp is
                // restored by emitAlignedCall) until the fill loop has
                // copied it. A fill of <= 8 bytes is one word, popped
                // into r14 and stored at exactly elemSize bytes below.
                final int fillWords = (elemSize + 7) / 8;
                final boolean wideFill = hasFill && elemSize > 8;
                if (wideFill) {
                    raw("    movq " + (fillWords * 8) + "(%rsp), %r12");
                    raw("    movq " + (fillWords * 8 + 8) + "(%rsp), %rax");
                    
                } else {
                    if (hasFill) {
                        popReg("r14"); // fill value
                    }
                    popReg("r12"); // newCount
                    popReg("rax"); // old pointer (scratch; moved into the call's arg reg below)
                }
                // An unsafe dynarray is headerless: its pointer IS the malloc'd block (nothing to subtract, no length to read).
                // The old block start is kept on the stack across the realloc: if the block moves, the ghost table
                // entry for it is dropped (gt_moved: no free, realloc already released it) and the new address registered.
                pushReg("rax");
                resizeExtraStack = 8;
                // oldLen must survive the emitAlignedCall below, which
                // internally saves/restores %rsp through %r13 -- so it
                // cannot be kept in r13 (that was the actual bug behind a
                // very confusing ghost-table-lock hang: r13 silently
                // turned into the saved pre-call %rsp value instead of
                // staying oldLen, and the fill loop below then computed
                // addresses from that garbage). rbx is free once the old
                // pointer's been moved into the call's argument register,
                // and emitAlignedCall never touches it, so oldLen lives
                // there instead across the call.
                if (hasFill) {
                    loadSizedFromAddr("rbx", "rax", 8); // rbx = oldLen
                }
                // The two argument registers are an ABI question (win64:
                // rcx/rdx, SysV: rdi/rsi), independent of syntax --
                // argReg(0)/argReg(1) resolve that; this used to hardcode
                // rdi/rsi in the AT&T branch, which is only right for
                // `linux` and silently wrong for `windows_gnu` (AT&T
                // syntax, win64 ABI).
                // Safe form: the header's capacity word is honoured. A resize that fits the capacity just rewrites the length (no
                // realloc, the block does not move, so the ghost table is untouched); one that does not fit grows to
                // max(newCount, 2 * capacity), so a run of one-element growths costs O(1) amortised reallocs; one that leaves
                // fewer than a quarter of the capacity in use reallocs down to exactly newCount. r15 carries the new
                // capacity across the realloc call (callee-saved); the old block's header is never touched before the call.
                String resizeGrow = newInternalLabel("resize_grow");
                String resizeHdr = newInternalLabel("resize_hdr");
                String resizeShrink = newInternalLabel("resize_shrink");
                String resizeCall = newInternalLabel("resize_call");
                if (hasFill) {
                    raw("    movq 8(%rax), %rcx"); // rcx = old capacity
                    raw("    cmpq %rcx, %r12");
                    raw("    ja " + resizeGrow);
                    raw("    leaq 0(,%r12,4), %rdx");
                    raw("    cmpq %rcx, %rdx");
                    raw("    jb " + resizeShrink); // newCount < capacity / 4: give the memory back
                    raw("    movq %rax, %r15"); // fits: same block, capacity unchanged (rcx)
                    raw("    jmp " + resizeHdr);
                    raw(resizeShrink + ":");
                    raw("    movq %r12, %r15"); // shrink to exactly newCount
                    raw("    jmp " + resizeCall);
                    raw(resizeGrow + ":");
                    raw("    leaq (%rcx,%rcx), %rdx");
                    raw("    cmpq %rdx, %r12");
                    raw("    cmovaq %r12, %rdx"); // rdx = max(newCount, 2 * capacity)
                    raw("    movq %rdx, %r15"); // r15 = new capacity
                    raw(resizeCall + ":");
                }
                raw("    movq %rax, %" + argReg(0)); // arg1 = old (block-start) pointer
                raw("    movq %" + (hasFill ? "r15" : "r12") + ", %" + argReg(1));
                raw("    imulq $" + elemSize + ", %" + argReg(1) + ", %" + argReg(1));
                if (hasFill) {
                    raw("    addq $16, %" + argReg(1));
                } else {
                    String nz = newInternalLabel("uresize_nz");
                    raw("    testq %" + argReg(1) + ", %" + argReg(1));
                    raw("    jnz " + nz);
                    raw("    movq $1, %" + argReg(1));
                    raw(nz + ":");
                }
                
                // realloc(oldBlockPtr, newTotalBytes) -- args already
                // staged into the ABI's own first two argument registers
                // just above (rdi/rsi for SysV, rcx/rdx for win64).
                emitAlignedCall(() -> emitCallByName("realloc"));
                if (hasFill) {
                    raw("    movq %r15, %rcx"); // rcx = the new capacity (saved in r15 across the call)
                }
                raw("    movq %rax, %r15"); // r15: new block-start pointer
                // A failed realloc leaves r15 null (the old block stays valid): write nothing, skip the fill and the
                // data-start offset, and push the null so the bytecode's own check can throw.
                String resizeEnd = newInternalLabel("resize_end");
                raw("    testq %r15, %r15");
                raw("    jz " + resizeEnd);
                if (hasFill) {
                    raw(resizeHdr + ":");
                    storeSizedToAddr_reg("r15", 0, "r12", 8); // length = newCount
                    storeSizedToAddr_reg("r15", 8, "rcx", 8); // capacity
                }
                if (!hasFill) {
                    raw(resizeEnd + ":");
                    emitResizeGtUpdate();
                }
                if (hasFill) {
                    // for (i = oldLen; i < newCount; i++) data[i] = fill;
                    String loop = newInternalLabel("resize_fill");
                    String end = resizeEnd;
                    if (!wideFill && (elemSize == 1 || elemSize == 2 || elemSize == 4 || elemSize == 8)) {
                        // A big fill of zero bytes (any zero element, or any 1-byte element) is one `rep stosb`; a small one, or any other
                        // value, runs the pointer loop below (rdi/rcx/rdx are free here: the realloc call above already clobbered them).
                        String generic = newInternalLabel("resize_fill_loop");
                        raw("    cmpq %r12, %rbx");
                        raw("    jge " + end);
                        raw("    movq %r12, %rdx");
                        raw("    subq %rbx, %rdx");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rdx, %rdx");
                        }
                        boolean useRep = !isWinAbi(); // rdi is callee-saved on win64: only the pointer loop there
                        if (useRep) {
                        raw("    cmpq $64, %rdx");
                        raw("    jb " + generic);
                        if (elemSize > 1) {
                            raw("    test" + movSuffix(elemSize) + " %" + sizedReg("r14", elemSize) + ", %" + sizedReg("r14", elemSize));
                            raw("    jnz " + generic);
                            raw("    xorl %eax, %eax");
                        } else {
                            raw("    movq %r14, %rax");
                        }
                        raw("    movq %rbx, %rdi");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rdi, %rdi");
                        }
                        raw("    leaq 16(%r15,%rdi), %rdi");
                        raw("    movq %rdx, %rcx");
                        raw("    rep stosb");
                        raw("    movq %r12, %rbx");
                        raw("    jmp " + end);
                        }
                        raw(generic + ":");
                        raw("    movq %rbx, %rax");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rax, %rax");
                        }
                        raw("    leaq 16(%r15,%rax), %rax");
                        raw(loop + ":");
                        storeSizedToAddr("r14", "rax", elemSize);
                        raw("    addq $" + elemSize + ", %rax");
                        raw("    incq %rbx");
                        raw("    cmpq %r12, %rbx");
                        raw("    jl " + loop);
                        raw(end + ":");
                    } else {
                    raw(loop + ":");
                    raw("    cmpq %r12, %rbx");
                    raw("    jge " + end);
                    raw("    movq %rbx, %rax");
                    raw("    imulq $" + elemSize + ", %rax, %rax");
                    raw("    leaq 16(%r15,%rax), %rax");
                    if (wideFill) {
                        emitResizeWideFillCopy(elemSize, fillWords);
                    } else {
                        storeSizedToAddr("r14", "rax", elemSize);
                    }
                    raw("    incq %rbx");
                    
                    raw(("    jmp " + loop));
                    raw(end + ":");
                    }
                    emitResizeGtUpdate();
                    if (wideFill) {
                        long drop = fillWords * 8L + 16;
                        raw(("    addq $" + drop + ", %rsp"));
                    }
                }
                pushReg("r15");
                return;
            }
            case "ATOMIC_PUSH": {
                // "ATOMIC_PUSH size globalName" -- an aligned load off a
                // global is already atomic on x86-64; no lock prefix
                // needed for a plain read.
                int size = (int) Long.parseLong(line.get(1).text);
                String name = line.get(2).text;
                leaGlobalToReg("rbx", name);
                loadSizedFromAddr("rax", "rbx", size);
                pushReg("rax");
                return;
            }
            case "ATOMIC_SWAP": {
                // Pops (top to bottom) newValue, address -- xchg with a
                // memory operand is *always* atomic on x86, no lock
                // prefix required -- and leaves the previous value in
                // the same register, which every real call site this
                // pass was checked against immediately compares against
                // something (confirmed directly).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rax"); // new value
                popReg("rbx"); // address
                raw("    xchg" + movSuffix(size) + " %" + sizedReg("rax", size) + ", (%rbx)");
                
                pushReg("rax");
                return;
            }
            case "GT_INIT": {
                // The compiler's own ghost-table bootstrap opcode --
                // "gt_init" is itself a real, already-compiled function
                // sitting elsewhere in this exact same low-order file
                // (from the imported stdlib/gt_init.caspien), with no
                // ordinary CALL to it anywhere in the bytecode -- so
                // this opcode's job is to call it directly, confirmed
                // directly by the exact, unmistakable name match (and
                // the identical pattern for GT_ALIVE_CHECK/GT_DESTRUCT
                // below).
                emitAlignedCall(() -> emitCallByName("gt_init"));
                return;
            }
            case "GT_REGISTER": {
                // The fourth ghost-table opcode, added alongside this
                // case -- BytecodeEmitter's own "new" case now emits
                // this immediately after "NEW", confirmed directly:
                // "then I pass that malloced pointer thru the provided
                // gt_register function, before putting it on the
                // stack." Same shape as GT_ALIVE_CHECK just below (pop
                // the pointer into the first argument register, call
                // the real stdlib function by its own unmistakable
                // name), except gt_register's own return value is void
                // -- nothing useful ends up in rax after the call.
                //
                // The pointer surviving the call is kept on the real
                // stack, not in a register -- confirmed directly to be
                // necessary, not just cautious, by an actual segfault:
                // "callee-saved, survives the call" (the reasoning
                // "NEW"'s own case above relies on for r12/r14) only
                // holds when the callee is a real, externally-compiled C
                // function such as malloc, which genuinely honors the
                // calling convention's callee-saved registers. This
                // opcode's own callee, `gt_register`, is a Caspien
                // function compiled by this exact backend, and
                // `FUNC_START`/`FUNC_END` (above) never save or restore
                // r12-r15/rbx -- only rbp -- so nothing stops
                // `gt_register`'s own body (which itself calls
                // `gtSlotAt`/`gtReadSlot`/`gtWriteSlot`/`realloc`, each
                // freely reusing those same scratch registers) from
                // clobbering whatever this opcode left in one of them.
                // The real hardware stack has no such problem: a
                // properly-balanced callee (push/sub on the way in,
                // matching pop/add on the way out -- which is exactly
                // what every "FUNC_START"/"FUNC_END" pair here already
                // does) never disturbs anything sitting below its own
                // return address, so a second, throwaway copy of the
                // pointer pushed here for the call to consume leaves the
                // first copy sitting undisturbed underneath, already
                // exactly where the next real instruction expects "NEW"'s
                // own result to be.
                popReg("rax");
                pushReg("rax"); // the surviving copy -- left alone, underneath the call
                pushReg("rax"); // the throwaway copy -- this one becomes the argument
                popReg("rax");
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_register"));
                // gt_register returns false when the table could not grow (the pointer is then NOT registered): free the
                // block and leave null in its place, exactly what a failed allocation leaves, so the allocation site's own
                // null check takes the out-of-memory path. A null pointer registers as success (and stays null).
                String regOk = newInternalLabel("gtreg_ok");
                raw("    testb %al, %al");
                raw("    jnz " + regOk);
                popReg("rax"); // the surviving copy
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("free"));
                raw("    xorq %rax, %rax");
                pushReg("rax");
                raw(regOk + ":");
                return;
            }
            case "GT_ALIVE_CHECK": {
                popReg("rax"); // the pointer being checked
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_alive_check"));
                pushReg("rax"); // its bool result
                return;
            }
            case "GT_DESTRUCT": {
                // Operand is an immediate stack offset, not a popped
                // value -- "GT_DESTRUCT $-240" -- read the pointer
                // *value* stored there (gt_destruct's own real parameter
                // is the pointer by value, confirmed directly from its
                // own body: "PUSH ARG0 / ASSIGN" storing its incoming
                // parameter to a local, ordinary by-value handling).
                String operand = line.get(1).text;
                if (operand.startsWith("$")) {
                    loadSizedFromFrame("rax", Long.parseLong(operand.substring(1)), 8);
                    raw(("    movq %rax, %" + argReg(0)));
                    emitAlignedCall(() -> emitCallByName("gt_destruct"));
                } else {
                    comment("TODO(codegen): 'GT_DESTRUCT " + operand + "' (non-stack-offset operand) not yet implemented");
                }
                return;
            }
            case "GT_DESTRUCT_ADDR": {
                // The pointer-crossing sibling of the plain, flat-offset
                // "GT_DESTRUCT $offset" case just above -- see
                // caspien-compiler's own CLAUDE.md, "GT_DESTRUCT
                // dotted-operand gap," for the front-end half of this
                // fix. Unlike that case, this operand is a type string
                // only (kept for this bytecode format's own "every
                // instruction states the type it operates on"
                // convention; not used for dispatch here -- an owns
                // pointer is always exactly 8 bytes regardless of what
                // it points to). The real work already happened on the
                // stack: `BytecodeEmitter.emitDestructOldOwnedValue`
                // computed this target's own real, possibly-pointer-
                // crossing ADDRESS via the same DOT_LHS/LOOKUP_LHS chain
                // an ordinary assignment target already uses, then
                // duplicated it with `DUP_TOP` -- one copy for this line
                // to consume, one left for the store that follows. So
                // where the plain `GT_DESTRUCT` case reads the pointer
                // value directly off a known, fixed frame offset
                // (`loadSizedFromFrame`), this one starts one level
                // further out: pop the ADDRESS itself, then dereference
                // it (`loadSizedFromAddr`) to get the same pointer value
                // `gt_destruct` always expects by value (confirmed
                // directly from its own body: "PUSH ARG0 / ASSIGN",
                // ordinary by-value handling -- identical to the plain
                // case above).
                popReg("rax"); // the computed address itself
                loadSizedFromAddr("rax", "rax", 8); // dereference: the owns pointer's own value
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_destruct"));
                return;
            }
            case "YIELD": {
                // "yield is intended to say 'I dont need my execution
                // time, im low priority, prioritize someone else'"
                // (caspien-compiler's own CLAUDE.md) -- a plain,
                // argument-less scheduling *hint*, not a delay: unlike
                // "sleep(...)" (a real "SLEEP" instruction just below,
                // naming the real "@sleep"-decorated function to call
                // directly), "yield" carries no duration at all and
                // never blocks the calling thread for any guaranteed
                // length of time -- it just gives up the rest of its
                // current scheduling turn, immediately eligible to run
                // again the instant nothing else wants the CPU.
                //
                // The real primitive for this is POSIX's own
                // "sched_yield(void)" -- confirmed empirically to link
                // and run correctly, with zero target-branching needed
                // at all, on *both* this backend's real targets: glibc
                // (linux) and mingw-w64's own libpthread/sched.h
                // compatibility layer (windows_gnu, already linked in
                // via "-pthread"/"-static" for pthread_create/join/exit)
                // both export it under this exact same name -- the
                // identical "one literal C symbol, no per-target
                // branching" shape "exit"/"pthread_exit" already
                // established just below. ("pthread_yield" -- the more
                // obvious-looking name, given "pthread_create"/"_join"/
                // "_exit" already being called this way -- was tried
                // first and rejected: it's a non-standard GNU extension
                // glibc happens to also provide, but mingw-w64's own
                // winpthreads does not export it at all, confirmed by a
                // real "undefined reference to 'pthread_yield'" link
                // failure under the windows_gnu target.) No arguments,
                // no return value read by anything -- "sched_yield"
                // itself returns an int (0 on success) that nothing
                // downstream of "yield" ever consumes.
                emitAlignedCall(() -> emitCallByName("sched_yield"));
                return;
            }
            case "SLEEP": {
                // "SLEEP sleep_call duration" -- confirmed directly: a
                // dedicated instruction, not an ordinary CALL, carrying
                // the real "@sleep"-decorated function's own name
                // directly as this line's second operand (resolved once,
                // by TypeChecker.checkSleepCall -- never a hardcoded
                // literal this backend has to already know, the exact
                // "resolved dynamically" property that keeps this immune
                // to the naming fragility the four original ghost-table
                // opcodes -- "GT_INIT" etc. -- once had; see
                // caspien-compiler's own CLAUDE.md, "a non-literal name
                // broke the linker"). Shaped like "GT_ALIVE_CHECK" just
                // above (pop the one pushed argument, move it into the
                // first argument register, call, push the real result
                // back) rather than the full CC_START/CC_END-wrapped
                // "CALL" machinery, since "@sleep" is required
                // (TypeChecker.requireSleepSignature) to take exactly
                // one plain "u64" and return exactly one plain "u64" --
                // always exactly 8 bytes each way, never anything this
                // fixed shape needs to branch on. The trailing operand
                // (the pushed duration argument's own resolved type) is
                // never actually read here at all -- carried purely for
                // the same "every instruction states the type of what it
                // operates on" documentation convention NEG/DEREF/CLONE/
                // LEN_SCAN already follow, "sleep is used like
                // sleep(duration) where duration is the duration type C
                // expects," confirmed directly.
                String realFuncName = line.get(1).text;
                popReg("rax"); // the pushed duration argument
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName(realFuncName));
                pushReg("rax"); // the real C "sleep"'s own u64 result (remaining, unslept seconds)
                return;
            }
            case "EXIT": {
                // Reached only when a THROW somewhere in this compilation
                // unit unwinds all the way up through every intervening
                // frame to the true root (main, or @event_loop) itself --
                // never reached by an ordinary, no-exception completion
                // of main, which always jumps clean over this whole label
                // (the "JMP end_of_gt_routine__main" emitted right after
                // it -- see emitGtRoutineBody) and never runs its body at
                // all. This can NOT simply do nothing and fall through:
                // the label immediately following this one in the
                // bytecode ("end_of_gt_routine__main:") is main's own
                // *ordinary body*, not a second copy of its epilogue --
                // falling through into it, as this case used to
                // (incorrectly) assume, would silently restart and re-run
                // main's entire body a second time the moment a real
                // throw ever actually unwound this far, rather than
                // ending the program. Confirmed against this project's
                // own gt_unwind_throw_multi_frame_cg_test.caspien fixture
                // doc comment: unwinding to the true root has its own
                // distinct, documented status -- "this codegen stage's
                // own chosen, distinct 'unwound to the true root
                // boundary' status," exit code 1 -- separate from a
                // normal, exception-free "exit 0" completion of main.
                // Calling the C library's own `exit` (already linked in
                // for malloc/printf/etc -- no separate EXTERN declaration
                // needed for codegen to call it by name) is the
                // straightforward way to actually end the process here,
                // with that exact distinct status.
                raw(("    movq $1, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("exit"));
                return;
            }
            case "EXIT_THREAD": {
                // The real-OS-thread-backed "par"/"await" counterpart to
                // "EXIT" just above, emitted (unconditionally, see
                // caspien-compiler's own BytecodeEmitter.emitGtRoutineBody)
                // at the tail end of every "@async" function's own
                // gt_routine unwind label, in place of "EXIT" -- reached
                // the identical way "EXIT" is (an unwind that propagates
                // all the way up through every frame this thread's own
                // call stack has, to the very entry point pthread started
                // this thread at -- the compiler-synthesized
                // "__trampoline_<name>" function, never "main" itself,
                // since an "@async" function only ever runs on its own,
                // separate OS thread, started by "@par_call"/"@await_call"'s
                // own "pthread_create", never by the process's own
                // ordinary startup path). Ending the whole *process* here,
                // the way "EXIT" does via the C library's own "exit", would
                // be wrong: it would tear down every other thread
                // (including whichever one is "await"-ing or "par"-polling
                // this one) along with it, not just this one thread's own
                // execution. The real, ordinary way to end *one* thread
                // without ending the process is the C library's own
                // "pthread_exit" -- already linked in via "-pthread" (the
                // same "already linked in, no EXTERN declaration needed"
                // precedent "exit" itself already established just above;
                // "stdlib/libc.caspien"'s own comment on "pthread_exit"
                // confirms it's deliberately never declared there, called
                // directly by this exact case, by its own fixed, literal
                // C symbol name, for the identical reason). Passed a null
                // "void*" return value: nothing downstream (no real
                // "pthread_join" call site in "@await_call" ever reads
                // back a thread's own real exit-value pointer -- see
                // "stdlib/await_call.caspien"'s own "pthread_join(tid,
                // null)") ever consumes it.
                raw(("    movq $0, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("pthread_exit"));
                return;
            }
            case "GT_UNWIND": {
                // "Make the assembly to return from a function as you
                // normally would, but then locate the first variable in
                // that stack frame (should be relative to base pointer?)
                // and set the instruction pointer to that," confirmed
                // directly (the original design notes, recovered
                // from the compiler's emitGtRoutineBody
                // after this backend was found to have never implemented
                // it at all -- see this project's own CLAUDE.md).
                //
                // GT_UNWIND only ever appears inside this function's own
                // "gt_routine__<name>:" label, reached either by falling
                // through from a THROW originating somewhere in this same
                // function, or by a nested callee's own GT_UNWIND jumping
                // straight here (see below) -- in both cases every
                // GT_DESTRUCT this function's own gt_routine needed has
                // already run (emitGtRoutineBody emits them immediately
                // before this), so all that's left is to leave.
                //
                // "Return from a function as you normally would" is the
                // ordinary epilogue -- deallocate this frame (mov
                // rsp,rbp) and restore the *caller's* rbp (pop rbp) --
                // exactly what emitFunctionEpilogue does for a real RET.
                // The difference starts right after: a real RET would
                // now `ret` back into the caller's own body, to the
                // return address `call` pushed. That's precisely what
                // must NOT happen here -- this frame isn't returning
                // normally, it's unwinding, so the caller's own body must
                // never be resumed. The return address is discarded
                // unread (rsp bumped past it, exactly as if it had been
                // popped and thrown away) and control instead jumps
                // straight to the caller's own gt_routine label instead.
                //
                // "Locate the first variable in that stack frame" -- by
                // the time rbp has just been restored to the caller's
                // own value, "that stack frame" is the caller's, and its
                // first variable is always the caller's own
                // "gt_routine_address" slot (see emitGtRoutineAlloc's own
                // doc comment: reserved before any other local, in every
                // function that has one), which is confirmed, by
                // construction, to always sit at a fixed rbp-8 in every
                // such frame (verified directly against this project's
                // own real lowered bytecode: every function's first
                // "ALLOC ... code_addr" always lowers to exactly "$-8").
                // That slot already holds the address of the caller's own
                // gt_routine label -- written there by the caller's own
                // prologue (its own "ADDR gt_routine_address / PUSH_LABEL
                // .../ ASSIGN", emitted before this callee was ever
                // called) -- so reading it and jumping there is exactly
                // "set the instruction pointer to that."
                //
                // This chains correctly through an arbitrarily deep call
                // stack with no special-casing: the whole-program trigger
                // that gives a function its gt_routine machinery at all
                // (checker.usesOwnsRefDynNew() || checker.usesThrow(),
                // see emitGtRoutineAlloc) applies uniformly to *every*
                // function in a compilation unit that needs one, so the
                // caller reached here is always guaranteed to have its
                // own gt_routine_address slot at that same fixed offset
                // -- landing on the caller's own gt_routine label runs
                // *its* own GT_DESTRUCTs, then either unwinds one frame
                // further the same way, or (the true root -- main/
                // @event_loop/@async) terminates via EXIT/EXIT_THREAD
                // instead of a further GT_UNWIND.
                raw(CSR_MARK);
                // "GT_UNWIND MSG": the compiler reserved a second slot, gt_error_message, at rbp-16 in every frame (right behind
                // gt_routine_address; ArgToAllocLoweringPass keeps it ahead of the parameters). Carry this frame's message up to the
                // caller's slot, so a catch anywhere up the chain sees what was thrown. rax is free here (it is loaded with the jump
                // target below).
                boolean carryMessage = line.size() > 1 && "MSG".equals(line.get(1).text);
                if (carryMessage) {
                    movMemToReg("rax", -16);
                }
                raw("    movq %rbp, %rsp");
                
                popReg("rbp");
                raw("    addq $8, %rsp");
                
                if (carryMessage) {
                    movRegToMem("rax", -16); // the caller's gt_error_message slot
                }
                movMemToReg("rax", -8); // the caller's own gt_routine_address slot
                raw("    jmp *%rax");
                
                return;
            }
            case "THROW": {
                // UPDATE (per-throw-site/per-call-site unwinding): "THROW
                // string_id" used to carry a second operand (its own
                // enclosing function's whole-function "gt_routine" label)
                // and compiled to a plain unconditional "jmp" to it. That
                // label -- and the whole-function conservative destruct
                // list it guarded -- no longer exist at all (see the
                // sibling caspien-compiler project's own CLAUDE.md,
                // "Fixed: the 'GT_DESTRUCT dotted-operand gap'" era work
                // is unrelated; see instead "per-throw-site/per-call-site
                // unwinding" in that project's history for this specific
                // redesign). A throw's own destination is now resolved
                // entirely at compile time (always this exact lexical
                // point), so there is nothing left for THROW itself to
                // jump to -- the compiler now emits THROW's own precise
                // GT_DESTRUCT list, followed by GT_UNWIND/EXIT/EXIT_THREAD
                // as appropriate, as plain, ordinary lines immediately
                // following this one, using machinery this backend
                // already implements for every other destruct/unwind
                // site (return sites, call-site landing pads). THROW
                // itself is therefore a pure marker now -- a genuine
                // no-op here -- confirmed unchanged from before this
                // round: the thrown message was always inert at runtime
                // (never read, stored, or propagated -- there is still no
                // 'catch' construct anywhere in this language), so
                // dropping its own, now-obsolete control-transfer
                // responsibility loses nothing real.
                return;
            }
            case "RET": {
                // A void "RET 0" leaves %rax/%eax holding whatever the
                // last instruction happened to put there -- harmless
                // while every real test drove the compiled function
                // through a C harness that itself always returned 0,
                // but this program's own "main" is now linked (and its
                // real exit code inspected) directly, where the C
                // runtime reads %eax as the process exit code -- so a
                // void function now explicitly zeroes it, for a
                // predictable "exit 0" rather than whatever garbage a
                // caller wasn't meant to look at.
                long size = Long.parseLong(line.get(1).text);
                if (size > 0) {
                    popReg("rax");
                } else {
                    movImmToReg("rax", 0);
                }
                // RET can occur anywhere in a function's body -- nested
                // inside an if/match/loop, not just as the unconditional
                // final statement immediately before FUNC_END. It has to
                // actually leave the function *here*, at this exact
                // point, rather than falling through into whatever
                // merge-label/JMP scaffolding the surrounding control
                // flow emits next: falling through let later code
                // silently overwrite this return value (and, for a
                // conditional return, run the rest of the function body
                // it was meant to skip) before the real epilogue+ret
                // ever executed, a real bug found via a return nested
                // inside a chain of plain (no-else) ifs. The identical
                // epilogue FUNC_END emits at the function's true textual
                // end is emitted again here -- harmless dead code where
                // RET already was the last statement (FUNC_END's own
                // copy simply becomes unreachable), correct and required
                // everywhere else.
                emitFunctionEpilogue();
                return;
            }
            case "RET_FLOAT": {
                // The float return-value convention (`codegen.config`'s
                // own `return-register-float: XMM0`, shared by both win64
                // and SysV) puts the result in xmm0, not rax -- the one
                // real difference from plain RET. Never reached for a
                // void return (RET_FLOAT only exists for a real,
                // non-void, f32-returning function), so there's no
                // "leave it zeroed" case to mirror RET's own.
                popReg("rax");
                movRegToXmm("rax", "xmm0");
                // See RET's own identical comment just above -- the same
                // "must actually leave the function here" fix applies
                // equally to a float return.
                emitFunctionEpilogue();
                return;
            }
            case "VARARGS_XMM_COUNT": {
                // Pure compile-time metadata, emitted by the front end
                // immediately before a real variadic call's own
                // "CALL"/"INVOKE" line (never for an ordinary, wholly
                // fixed-arity call) -- see `pendingVarargsXmmCount`'s own
                // doc comment for why this needs to travel through the
                // bytecode at all (the SysV "%al = vector registers used"
                // varargs rule) rather than being computed here. Emits no
                // assembly of its own -- just records the value for
                // whichever call site's own preamble consumes it next.
                pendingVarargsXmmCount = Integer.parseInt(line.get(1).text);
                return;
            }
            case "CALL": {
                String name = line.get(1).text;
                if ((name.startsWith("__drop_") || name.startsWith("__clone_")) && callBufferStack.isEmpty()) {
                    emitDropGlueCall(name);
                    return;
                }
                resolveStackArgBytesAndCall(() -> emitCallByName(name));
                return;
            }
            case "INVOKE": {
                // The function pointer value was pushed *before* the
                // preceding "POP ARGn" sequence, so it's what's left on
                // top of the stack right now -- pop it into a scratch
                // register not used by any argument slot and call
                // through it. The numeric operand (arg count) is purely
                // informational here.
                popReg("r10");
                resolveStackArgBytesAndCall(() -> emitCallIndirect("r10"));
                return;
            }
            case "POP": {
                String dest = line.get(1).text;
                int size = (int) Long.parseLong(line.get(2).text);
                if (dest.startsWith("ARG")) {
                    int idx = Integer.parseInt(dest.substring(3));
                    popReg(argReg(idx));
                    noteArgRegLoaded("i" + idx);
                } else if (dest.startsWith("FARG")) {
                    // The float-bank counterpart of "ARG" just above --
                    // there's no real "pop directly into an xmm register"
                    // instruction, so the value comes off the ordinary
                    // GPR-based value stack into rax first, then across
                    // into the real argument xmm register via the same
                    // bit-copying `movq` every other float op here uses.
                    int idx = Integer.parseInt(dest.substring(4));
                    popReg("rax");
                    movRegToXmm("rax", argFloatReg(idx));
                    noteArgRegLoaded("f" + idx);
                } else if (dest.startsWith("$")) {
                    long addr = Long.parseLong(dest.substring(1));
                    if (size <= 8) {
                        popReg("rax");
                        storeSizedToFrame("rax", addr, size);
                    } else {
                        // Mirror of DEREF's own multi-word push, above:
                        // the *first* pop is the highest-address word
                        // (last pushed), stored at addr+size-8, working
                        // back down to addr+0 for the final pop.
                        long words = (size + 7) / 8;
                        for (long i = 0; i < words; i++) {
                            popReg("rax");
                            storeSizedToFrame("rax", addr + size - 8 - i * 8, 8);
                        }
                    }
                } else {
                    comment("TODO(codegen): 'POP " + dest + " " + size + "' not yet implemented");
                }
                return;
            }
            case "PUSH_LABEL": {
                String name = line.get(1).text;
                leaGlobalToReg("rax", mangleLabel(name));
                pushReg("rax");
                return;
            }
            case "PUSH_RET_INT": {
                pushReg("rax"); // the call result already sitting in rax
                return;
            }
            case "PUSH_RET_FLOAT": {
                // The caller-side mirror of RET_FLOAT: a just-called
                // function's float result is sitting in xmm0 (the same
                // shared win64/SysV convention), not rax -- move it over
                // and push it exactly like PUSH_RET_INT does for rax.
                if (floatResultInR10) {
                    // xmm0 was already restored for the enclosing call's
                    // argument 0 (see "CC_END"); the real result is in r10.
                    raw("    movq %r10, %rax");
                    floatResultInR10 = false;
                } else {
                    movXmmToReg("xmm0", "rax");
                }
                pushReg("rax");
                return;
            }
            case "ASM_START": {
                // Everything from here to the matching "ASM_END" is
                // opaque, verbatim assembly text (see AsmInfo/emitAsm in
                // the front end -- this compiler never inspects an ASM
                // block's content beyond that one reservation check, and
                // no lowering pass touches it either), so there is
                // nothing to resolve: copy each raw line through
                // unchanged, in order, and skip past them here so the
                // main dispatch above doesn't also see them as ordinary
                // bytecode lines.
                int end = currentLineIndex + 1;
                while (end < allLines.size()) {
                    List<BytecodeToken> candidate = allLines.get(end);
                    if (candidate.size() == 1 && candidate.get(0).text.equals("ASM_END")) {
                        break;
                    }
                    end++;
                }
                for (int i = currentLineIndex + 1; i < end; i++) {
                    raw(String.join(" ", textsOf(allLines.get(i))));
                }
                asmSkipUntilIndex = end; // skips the raw lines and ASM_END itself
                return;
            }
            case "ASM_END":
                // Reached only for a malformed/unpaired block (no matching
                // ASM_START skipped it first) -- nothing to emit either way.
                return;
            case "FLOAT_CHECK": {
                // "PUSH f / FLOAT_CHECK finite|nan|..." -- a float
                // classification test (never parsed from real source; see
                // BytecodeEmitter's own doc comment on this operator).
                // Only ever reached against a genuine 4-byte value:
                // "finite"/"infinite"/"nan" are only ever attached to a
                // bare 'f32' condition (TypeChecker's own
                // requireF32ForFloatState-style gate, checked directly at
                // FLOAT_STATE_NAMES/PRIMITIVE_SIZE), and 'f32' is the
                // *only* floating-point type this language has at all --
                // no 'f64' exists anywhere in caspien-compiler, confirmed
                // by grep -- so there is no second width this could ever
                // arrive as, unlike every genuinely dual-width _FLOAT op
                // above.
                //
                // IEEE-754 single precision: the top bit is sign (masked
                // off below), and a value is classified purely by
                // comparing what's left against the all-ones-exponent,
                // zero-mantissa bit pattern (0x7f800000, i.e.
                // 2139095040): strictly less is finite (every ordinary
                // value, subnormal, or zero), exactly equal is +-Infinity,
                // strictly greater is any NaN payload. Each requested
                // category (op.right.text is a "|"-joined, never-empty,
                // never-all-three subset -- see Token.MatchPattern's own
                // doc comment) contributes one setcc reading the *same*
                // comparison's flags -- untouched by an intervening setcc,
                // so one cmp legitimately serves all of them -- OR'd
                // together into a single 0/1 result for the CMP/JMP that
                // always follows a match branch condition.
                String[] categories = line.get(1).text.split("\\|");
                boolean f64Check = line.size() > 2 && line.get(2).text.equals("8");
                popReg("rax");
                if (f64Check) {
                    // double: strip the sign bit (bit 63) by shifting it out, compare against the +-Inf exponent pattern 0x7ff0000000000000
                    raw("    btrq $63, %rax");
                    movImmToReg("rdx", 0x7ff0000000000000L);
                    raw("    cmpq %rdx, %rax");
                } else {
                raw("    andl $2147483647, %eax"); // strip the sign bit -> |bits|
                raw("    cmpl $2139095040, %eax"); // vs. the shared +-Inf/NaN exponent pattern
                }
                boolean firstCategory = true;
                for (String category : categories) {
                    String setcc = category.equals("finite") ? "setb"
                            : category.equals("infinite") ? "sete"
                            : "seta"; // "nan"
                    String reg = firstCategory ? "rax" : "rdx";
                    raw("    " + setcc + " %" + sizedReg(reg, 1));
                    raw("    movzbq %" + sizedReg(reg, 1) + ", %" + reg);
                    
                    if (!firstCategory) {
                        raw("    orq %rdx, %rax");
                    }
                    firstCategory = false;
                }
                pushReg("rax");
                return;
            }
            case "CC_START": {
                // Opens a fresh buffer/tally level for this call's own
                // argument marshalling -- nothing is emitted immediately
                // (unlike the old, pure-no-op version of this case): the
                // alignment reservation this call may or may not need
                // can't be sized correctly until its own real stack-
                // passed-argument byte count is known, which only happens
                // once every argument has been produced, at this same
                // call's own "CALL"/"INVOKE" line
                // (`resolveStackArgBytesAndCall`). See
                // `argTrackDeltaStack`'s own doc comment for the full
                // design and why a naive, immediate reservation here (the
                // shape this case used to have) is exactly the bug this
                // closes.
                // If this call is nested inside another call's argument
                // list, save the registers that outer call has already
                // loaded: this call is about to set up its own arguments in
                // the same physical registers. (These pushes land in the
                // enclosing call's buffer and tally; the matching pops at
                // this call's "CC_END" cancel them exactly, so the outer
                // call's stack-passed-byte accounting is unchanged.)
                java.util.List<String> saved = new java.util.ArrayList<>();
                if (!loadedArgRegsStack.isEmpty()) {
                    saved.addAll(loadedArgRegsStack.peek());
                }
                for (String r : saved) {
                    if (r.charAt(0) == 'i') {
                        pushReg(argReg(Integer.parseInt(r.substring(1))));
                    } else {
                        movXmmToReg(argFloatReg(Integer.parseInt(r.substring(1))), "r11");
                        pushReg("r11");
                    }
                }
                savedOuterArgRegsStack.push(saved);
                loadedArgRegsStack.push(new java.util.ArrayList<>());
                callBufferStack.push(new StringBuilder());
                argTrackDeltaStack.push(0L);
                return;
            }
            case "CC_END": {
                // Closes out this call's own buffer/tally level (opened
                // at the matching "CC_START") and flushes its now-
                // complete text (reservation, if any, plus every
                // argument-transfer/call/restore instruction emitted
                // since) into whatever was the active sink one level up
                // -- the enclosing call's own still-open buffer, if this
                // call was itself nested inside another call's own
                // argument expression, or `out` directly otherwise.
                StringBuilder finished = callBufferStack.pop();
                argTrackDeltaStack.pop();
                loadedArgRegsStack.pop();
                currentSink().append(finished);
                // Restore the enclosing call's already-loaded argument
                // registers (saved at this call's "CC_START"), in reverse
                // order. rax (an integer result) is never touched; r11 is
                // the scratch for float-bank restores; a possible float
                // result in xmm0 is stashed in r10 first, only when xmm0
                // itself is about to be overwritten.
                java.util.List<String> saved = savedOuterArgRegsStack.pop();
                if (saved.contains("f0")) {
                    movXmmToReg("xmm0", "r10");
                    floatResultInR10 = true;
                }
                for (int k = saved.size() - 1; k >= 0; k--) {
                    String r = saved.get(k);
                    if (r.charAt(0) == 'i') {
                        popReg(argReg(Integer.parseInt(r.substring(1))));
                    } else {
                        popReg("r11");
                        movRegToXmm("r11", argFloatReg(Integer.parseInt(r.substring(1))));
                    }
                }
                return;
            }
            case "EXPORT":
                // EXPORT's own target function already gets a real
                // .globl/PUBLIC from its own FUNC_START.
                return;
            case "GLOBAL":
            case "ALLOC_STATIC":
            case "STRING":
            case "EXTERN":
                // Already handled in the data-section pre-pass -- ELF
                // resolves an EXTERN'd symbol via the linker automatically,
                // with no explicit declaration needed in GAS.
                return;
            default:
                comment("TODO(codegen): '" + String.join(" ", textsOf(line)) + "' not yet implemented");
                return;
        }
    }

    private void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size) {
        raw("    movq $" + value + ", " + offset + "(%" + baseReg64 + ")");
        
    }

    private void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size) {
        raw("    movq %" + srcReg64 + ", " + offset + "(%" + baseReg64 + ")");
        
    }

    /**
     * A drop-glue call (`__drop_<T>`, and likewise a clone-glue call `__clone_<T>`, whose pointer result comes back in rax) is a deliberately minimal, ad hoc
     * shape: the LowerOrderGenerator emits a bare `PUSH pointer` /
     * `CALL __drop_T` with no `CC_START`/`CC_END`, and the routine reads
     * its one parameter as stack word 0 (`PUSH $16`), i.e. the word
     * immediately above the return address. The generic aligned-call
     * wrapper puts its alignment reservation between the pushed word and
     * the return address, so the routine read an uninitialised slot
     * instead of its pointer (it only worked when stale stack contents
     * happened to match -- a nested drop such as String -> DynamicArray
     * dereferenced null). Here the argument is popped, rsp is aligned
     * with the original rsp saved in a slot below the argument, the
     * argument is pushed back so it sits directly above the return
     * address, and rsp lands 16-aligned at the `call`.
     */
    private void emitDropGlueCall(String name) {
        // `__clone_value_T(dst, src)` takes two words (dst pushed first, src on top): same dance with both words kept.
        boolean twoWords = name.startsWith("__clone_value_");
        popReg("rax");
        if (twoWords) {
            popReg("rcx");
        }
        raw("    movq %rsp, %rdx");
        raw("    andq $-16, %rsp");
        raw("    subq $8, %rsp");
        raw("    movq %rdx, (%rsp)");
        
        if (twoWords) {
            pushReg("rcx");
        }
        pushReg("rax");
        emitCallByName(name);
        int argBytes = twoWords ? 16 : 8;
        raw("    addq $" + argBytes + ", %rsp");
        raw("    movq (%rsp), %rsp");
        
    }

    /** Bytes pushed on top of RESIZE's wide fill block while its fill loop runs (the saved old block start). */
    private int resizeExtraStack = 0;

    /** Push `reg`, pass it as the first argument to the ghost table function `fn`, restore `reg`. */
    private void emitGtCall(String fn, String reg) {
        pushReg(reg);
        raw(("    movq %" + reg + ", %" + argReg(0)));
        emitAlignedCall(() -> emitCallByName(fn));
        popReg(reg);
    }

    /**
     * End of RESIZE/URESIZE: the old block start is on top of the stack, the realloc result (block start or null) in
     * r15. A moved block (new address, non-null) is unregistered at the old address WITHOUT freeing (gt_moved) and
     * registered at the new one; an unmoved block or a failed realloc (old block still valid and registered) changes
     * nothing. Clobbers rbx.
     */
    private void emitResizeGtUpdate() {
        resizeExtraStack = 0;
        popReg("rbx");
        String skip = newInternalLabel("resize_gt_skip");
        raw("    testq %r15, %r15");
        raw("    jz " + skip);
        raw("    cmpq %rbx, %r15");
        raw("    je " + skip);
        emitGtCall("gt_moved", "rbx");
        emitGtCall("gt_register", "r15");
        raw(skip + ":");
    }

    /**
     * RESIZE's fill loop for an element wider than one word: copies the
     * fill block still sitting on the stack (word k of the value is at
     * rsp + (words-1-k)*8, the same reversed-word convention
     * pushBlockFromFrame uses) into the element whose address is in rax,
     * in natural ascending byte order. Clobbers rdx.
     */
    private void emitResizeWideFillCopy(int elemSize, int words) {
        for (int k = 0; k < words; k++) {
            long srcOff = (long) (words - 1 - k) * 8 + resizeExtraStack;
            int n = Math.min(8, elemSize - 8 * k);
            raw("    movq " + srcOff + "(%rsp), %rdx");
            
            long dst = 8L * k;
            for (int b = 0; b < n;) {
                int chunk = n - b >= 8 ? 8 : n - b >= 4 ? 4 : n - b >= 2 ? 2 : 1;
                raw("    mov" + movSuffix(chunk) + " %" + sizedReg("rdx", chunk) + ", " + (dst + b) + "(%rax)");
                
                if (b + chunk < n) {
                    raw(("    shrq $" + (chunk * 8) + ", %rdx"));
                }
                b += chunk;
            }
        }
    }

    private void emitMallocCall(long size) {
        // The argument register is an ABI question (win64: rcx, SysV:
        // rdi), independent of syntax -- argReg(0) already resolves
        // that correctly (see its own doc comment); previously this
        // hardcoded "rdi" in the AT&T branch, which is only right for
        // `linux` and silently wrong for `windows_gnu` (AT&T syntax,
        // but win64 ABI) -- confirmed directly by a real crash: malloc
        // read its size out of whatever garbage was sitting in rcx
        // instead of the real size sitting in rdi, and the resulting
        // wild/failed allocation surfaced downstream as a null-pointer
        // or wild-pointer fault (`NEW`'s own "rep movsb", most
        // visibly).
        raw("    movq $" + size + ", %" + argReg(0));
        
        emitAlignedCall(() -> emitCallByName("malloc"));
    }

    private boolean lastWasCmp = false;
    /** See the "value-block" handling threaded through PUSH/LOOKUP_ARRAY/DOT: the byte size of a multi-word value most recently pushed *by value* (as opposed to by address), still sitting on top of the real stack, for a following LOOKUP_ARRAY/DOT to extract a sub-range from -- 0 means "no pending value-block; use the ordinary address-based path." */
    private long lastValueBlockSize = 0;
    private boolean pushedWordWithTag = false; // the previous line was an 8-byte PUSH that passed a pending value tag on (see the line emitter prologue)
    private String currentFuncName;
    private int internalLabelCounter = 0;

    private String newInternalLabel(String prefix) {
        return "L_cg_" + prefix + "_" + (internalLabelCounter++);
    }

    /** "@name" -> "L_name" (a real label can't contain '@'); a bare name with no '@' passes through unchanged -- the identical rule a label *definition* and every JMP/PUSH_LABEL *reference* to it must both apply, so they always agree. */
    // ---- Register-form instructions (R_MOV / R_BIN / R_UN / R_BRF / R_PUSH) ----
    //
    // Emitted by the LowerOrderGenerator's RegisterFormPass (deferred operands). Operands:
    //   #imm  an immediate      $off  an 8-byte frame slot      %tN  a temporary register
    // Temps are only ever live between register-form instructions -- the pass writes every one
    // back to the real stack (R_PUSH) before any other instruction -- so the backend's own
    // sequences (which use rax/rbx/r11-r15 internally) can never collide with a live temp. The
    // pool avoids every register that can be live ACROSS instructions: the argument registers of
    // an enclosing call (both ABIs), r10 (INVOKE target / float-result stash) and xmm registers.
    // r15 is the spare scratch, used only inside a single register-form instruction.

    private static final String[] RF_TEMP_REGS = { "rax", "rbx", "r11", "r12" };
    private static final String RF_SCRATCH = "r15";
    /** %v0..%v2: promoted variables (RegVarPromotionPass), callee-saved, so finishCalleeSaved saves/restores them like any other.
     *  %v3..%v5 (r8, r9, r10) are caller-saved: the promotion only puts a variable there that is never live across a call or any
     *  line that could clobber them. %v6, %v7 (rsi, rdi: SysV argument registers 1 and 0; callee-saved on win64, where
     *  finishCalleeSaved saves them when the text mentions them) follow the same rule and are never live inside a call's argument bracket.
     *  %v8, %v9 (rdx, rcx: SysV argument registers 2 and 3, SysV only) too, and are never live at a constant divide (rdx) / variable-count shift (rcx). */
    private static final String[] RF_VAR_REGS = { "r13", "r14", "r12", "r8", "r9", "r10", "rsi", "rdi", "rdx", "rcx" };
    /** set when this function uses a %vN; checked against rfClobberSeen at FUNC_END */
    private boolean rfVarUsed = false;
    /** bit i set when this function uses %vi (i < 3: r13, r14, r12), see finishCalleeSaved / V1314_SAVE */
    private int rfVarMask = 0;
    /** set when this function has an instruction whose code uses r13/r14 internally (see RegVarPromotionPass.R13_R14_USERS) */
    private boolean rfClobberSeen = false;
    private static final java.util.Set<String> RF_R13_R14_USERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "NEW_FROM_USTRING", "CLONE", "CLONE_DYN", "RESIZE", "URESIZE", "DOT", "LOOKUP_ARRAY", "ASM_START"));

    private String rfReg(String tok) {
        if (tok.equals("%s")) {
            return RF_SCRATCH; // the spare scratch register, usable as an operand only inside the backend
        }
        if (tok.startsWith("%v")) {
            int vi = Integer.parseInt(tok.substring(2));
            if (vi < 3) {
                rfVarMask |= 1 << vi;
                rfVarUsed = true; // %v3..%v5 are r8/r9/r10: caller-saved, no save/restore, no clash with the r13/r14 scratch uses
            }
            return RF_VAR_REGS[vi];
        }
        return RF_TEMP_REGS[Integer.parseInt(tok.substring(2))];
    }

    /** a register operand: a temp, the scratch register or a promoted variable */
    private static boolean rfIsTemp(String tok) {
        return tok.startsWith("%t") || tok.startsWith("%v") || tok.equals("%s");
    }

    private static boolean rfIsVar(String tok) {
        return tok.startsWith("%v");
    }

    private static boolean rfIsImm(String tok) {
        return tok.startsWith("#");
    }

    private static boolean rfIsSlot(String tok) {
        return tok.startsWith("$");
    }

    private static long rfImm(String tok) {
        String t = tok.substring(1);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Long.parseUnsignedLong(t);
        }
    }

    private static long rfSlot(String tok) {
        return Long.parseLong(tok.substring(1));
    }

    private static boolean rfFitsImm32(long v) {
        return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    private String rfRegText(String reg) {
        return ("%" + reg);
    }

    private String rfMemText(long off) {
        return (off + "(%rbp)");
    }

    /** Source-operand text for a two-operand instruction; a 64-bit immediate that will not fit imm32 goes through the scratch register. */
    private String rfSrc(String tok) {
        if (rfIsTemp(tok)) {
            return rfRegText(rfReg(tok));
        }
        if (rfIsSlot(tok)) {
            return rfMemText(rfSlot(tok));
        }
        long v = rfImm(tok);
        if (!rfFitsImm32(v)) {
            movImmToReg(RF_SCRATCH, v);
            return rfRegText(RF_SCRATCH);
        }
        return ("$" + v);
    }

    /** "op src, dst" in the active syntax (AT&T gets the q suffix and reversed operand order). */
    private void rfOp2(String op, String dstText, String srcText) {
        raw(("    " + op + "q " + srcText + ", " + dstText));
    }

    private void rfRegToReg(String dst, String src) {
        if (!dst.equals(src)) {
            raw(("    movq %" + src + ", %" + dst));
        }
    }

    private void rfMov(String dst, String src) {
        if (rfIsTemp(dst)) {
            String d = rfReg(dst);
            if (rfIsImm(src)) {
                movImmToReg(d, rfImm(src));
            } else if (rfIsSlot(src)) {
                movMemToReg(d, rfSlot(src));
            } else {
                rfRegToReg(d, rfReg(src));
            }
            return;
        }
        long off = rfSlot(dst);
        if (rfIsTemp(src)) {
            movRegToMem(rfReg(src), off);
        } else if (rfIsImm(src)) {
            long v = rfImm(src);
            if (rfFitsImm32(v)) {
                raw(("    movq $" + v + ", " + off + "(%rbp)"));
            } else {
                movImmToReg(RF_SCRATCH, v);
                movRegToMem(RF_SCRATCH, off);
            }
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(src));
            movRegToMem(RF_SCRATCH, off);
        }
    }

    /**
     * "R_BIN SHL|SHR|SAR size %tD a b" -- %tD = a shifted by b, with the same single semantics as the stack form (see its SHL case):
     * the count is the unsigned value of the operand's own width, a count >= the width gives 0 (SHL/SHR) or the sign fill (SAR).
     * A constant count is resolved here at compile time (no %cl, no clamp unless it is >= 64); a variable count goes through %rcx,
     * which is an argument register that may be live, so it is saved and restored around the shift.
     */
    /**
     * %tD = rotate of the low `size` bytes of a by b (bits_rotl / bits_rotr): `rol`/`ror` on the sub-register of the destination, the count taken
     * modulo the width (a constant count is reduced here; a variable count goes through %rcx, saved and restored like rfShift). The value is
     * zero-extended first for 1/2-byte widths (the rotate leaves the upper bits of the sub-register alone); a 4-byte rotate zero-extends by itself.
     */
    private void rfRotate(String op, int size, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        boolean aIsD = rfIsTemp(aTok) && rfReg(aTok).equals(d);
        String mn = op.equals("ROTL") ? "rol" : "ror";
        if (rfIsImm(bTok)) {
            long c = rfExtImm(rfImm(bTok), size, false) & (size * 8L - 1);
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            if (c != 0) {
                if (size < 4) {
                    zeroExtendReg(d, size);
                }
                raw("    " + mn + movSuffix(size) + " $" + c + ", %" + sizedReg(d, size));
            }
            zeroExtendReg(d, size);
            return;
        }
        pushReg("rcx");
        if (rfIsTemp(bTok)) {
            rfRegToReg("rcx", rfReg(bTok));
        } else {
            movMemToReg("rcx", rfSlot(bTok));
        }
        if (!aIsD) {
            rfMov(dstTok, aTok);
        }
        zeroExtendReg(d, size);
        raw("    " + mn + movSuffix(size) + " %cl, %" + sizedReg(d, size));
        popReg("rcx");
    }

    private void rfShift(String op, int size, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        boolean aIsD = rfIsTemp(aTok) && rfReg(aTok).equals(d);
        if (rfIsImm(bTok)) {
            long c = rfExtImm(rfImm(bTok), size, false);
            boolean wide = Long.compareUnsigned(c, 63) > 0;
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            if (wide && !op.equals("SAR")) {
                // shifted out entirely
                raw(("    xorl %" + sizedReg(d, 4) + ", %" + sizedReg(d, 4)));
                return;
            }
            if (wide) {
                c = 63;
            }
            if (op.equals("SHR")) {
                zeroExtendReg(d, size);
            } else if (op.equals("SAR")) {
                signExtendReg(d, size);
            }
            if (c != 0) {
                String sop = op.equals("SHL") ? "shl" : op.equals("SHR") ? "shr" : "sar";
                raw(("    " + sop + "q $" + c + ", %" + d));
            }
            if (!op.equals("SHR")) {
                zeroExtendReg(d, size);
            }
            return;
        }
        if (bmi2 && !op.equals("SAR")) {
            // BMI2: the count goes into the scratch register (b may sit in d's own register, which the move of `a` below overwrites); no %rcx traffic.
            // rfMov into a temp never touches the scratch register, so the count survives it.
            if (rfIsTemp(bTok)) {
                rfRegToReg(RF_SCRATCH, rfReg(bTok));
            } else {
                movMemToReg(RF_SCRATCH, rfSlot(bTok));
            }
            zeroExtendReg(RF_SCRATCH, size);
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            emitShiftBmi2(op, size, d, RF_SCRATCH);
            return;
        }
        // variable count: it goes into %rcx first (b may sit in d's own register, which the move of `a` below overwrites)
        pushReg("rcx");
        if (rfIsTemp(bTok)) {
            rfRegToReg("rcx", rfReg(bTok));
        } else {
            movMemToReg("rcx", rfSlot(bTok));
        }
        zeroExtendReg("rcx", size);
        if (!aIsD) {
            rfMov(dstTok, aTok);
        }
        emitShiftCore(op, size, d);
        popReg("rcx");
    }

    /**
     * %tD = a / k or a % k for an unsigned 64-bit a and a constant k >= 3 that is not a power of two, without a divide instruction
     * (Granlund-Montgomery: t = mulhi(m, a); q = (t + ((a - t) >> 1)) >> (l - 1), with l = ceil(log2 k) and m = floor(2^64 (2^l - k) / k) + 1;
     * the remainder is a - q*k). The dividend goes to the scratch register r15; rax (a live temp when %tD is another register) is
     * parked in %tD for the duration and swapped back; rdx is clobbered, as by the stack form's divq.
     */
    private void rfDivConst(boolean mod, int size, String dstTok, String aTok, long k) {
        String d = rfReg(dstTok);
        long[] ms0 = divMagicSimple(k);
        if (ms0 != null && !mod) {
            // a plain quotient needs no copy of the dividend: it goes straight into rdx (shifted by the pre-shift), before rax is parked in d
            if (rfIsTemp(aTok)) {
                rfRegToReg("rdx", rfReg(aTok));
            } else if (rfIsImm(aTok)) {
                movImmToReg("rdx", rfExtImm(rfImm(aTok), size, false));
            } else {
                movMemToReg("rdx", rfSlot(aTok));
            }
            if (size < 8 && !rfIsImm(aTok)) {
                zeroExtendReg("rdx", size);   // a narrow dividend: only its low `size` bytes count (the register may hold more)
            }
            if (ms0[0] > 0) {
                raw("    shrq $" + ms0[0] + ", %rdx");
            }
            boolean inRax0 = d.equals("rax");
            if (!inRax0) {
                raw("    xchgq %rax, %" + d);
            }
            movImmToReg("rax", ms0[1]);
            raw("    mulq %rdx");
            if (ms0[2] > 0) {
                raw("    shrq $" + ms0[2] + ", %rdx");
            }
            raw("    movq %rdx, %rax");
            if (!inRax0) {
                raw("    xchgq %rax, %" + d);
            }
            return;
        }
        if (rfIsTemp(aTok)) {
            rfRegToReg(RF_SCRATCH, rfReg(aTok));
        } else if (rfIsImm(aTok)) {
            movImmToReg(RF_SCRATCH, rfExtImm(rfImm(aTok), size, false));
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(aTok));
        }
        if (size < 8 && !rfIsImm(aTok)) {
            zeroExtendReg(RF_SCRATCH, size);
        }
        boolean inRax = d.equals("rax");
        long[] ms = divMagicSimple(k);
        if (ms != null) {
            // q = mulhi(a >> pre, magic) >> post, no fix-up: the dividend a is kept in r15, the shifted copy goes to rdx (mulq may read the register it overwrites)
            int pre = (int) ms[0];
            int post = (int) ms[2];
            if (!inRax) {
                raw("    xchgq %rax, %" + d);
            }
            String src = RF_SCRATCH;
            if (pre > 0) {
                raw("    movq %" + RF_SCRATCH + ", %rdx");
                raw("    shrq $" + pre + ", %rdx");
                src = "rdx";
            }
            movImmToReg("rax", ms[1]);
            raw("    mulq %" + src);
            if (post > 0) {
                raw("    shrq $" + post + ", %rdx");
            }
            if (mod) {
                if (rfFitsImm32(k)) {
                    raw("    imulq $" + k + ", %rdx, %rax");
                } else {
                    movImmToReg("rax", k);
                    raw("    imulq %rdx, %rax");
                }
                raw("    subq %rax, %" + RF_SCRATCH);
                raw("    movq %" + RF_SCRATCH + ", %rax");
            } else {
                raw("    movq %rdx, %rax");
            }
            if (!inRax) {
                raw("    xchgq %rax, %" + d);
            }
            return;
        }
        int l = 64 - Long.numberOfLeadingZeros(k - 1);
        java.math.BigInteger two64 = java.math.BigInteger.ONE.shiftLeft(64);
        java.math.BigInteger bk = new java.math.BigInteger(Long.toUnsignedString(k));
        java.math.BigInteger m = java.math.BigInteger.ONE.shiftLeft(l).subtract(bk).shiftLeft(64).divide(bk).add(java.math.BigInteger.ONE);
        long magic = m.mod(two64).longValue();
        if (!inRax) {
            raw("    xchgq %rax, %" + d);
        }
        movImmToReg("rax", magic);
        raw("    mulq %" + RF_SCRATCH);
        raw("    movq %" + RF_SCRATCH + ", %rax");
        raw("    subq %rdx, %rax");
        raw("    shrq $1, %rax");
        raw("    addq %rdx, %rax");
        if (l > 1) {
            raw("    shrq $" + (l - 1) + ", %rax");
        }
        if (mod) {
            movImmToReg("rdx", k);
            raw("    imulq %rdx, %rax");
            raw("    movq %" + RF_SCRATCH + ", %rdx");
            raw("    subq %rax, %rdx");
            if (inRax) {
                raw("    movq %rdx, %rax");
            } else {
                raw("    movq %" + d + ", %rax");
                raw("    movq %rdx, %" + d);
            }
        } else if (!inRax) {
            raw("    xchgq %rax, %" + d);
        }
    }

    /**
     * Single-multiply unsigned division by k (not a power of two, k >= 3): {pre, magic, post} with q = mulhi(a >> pre, magic) >> post for every
     * 64-bit a, or null when no 64-bit magic number exists (the add-fix-up form is used then). Granlund-Montgomery Theorem 4.2: for x < 2^N and
     * m = ceil(2^(N+l) / k'), m*k' - 2^(N+l) <= 2^l gives floor(x*m / 2^(N+l)) = floor(x / k'). An even k first drops its trailing zero bits
     * from the dividend (a >> pre, N = 64 - pre, k' = k >> pre), which makes a 64-bit magic number likely; pre = 0 is tried as well. The smallest
     * l >= pre with m < 2^64 that meets the condition is taken; post = N + l - 64 = l - pre.
     */
    static long[] divMagicSimple(long k) {
        java.math.BigInteger bk0 = new java.math.BigInteger(Long.toUnsignedString(k));
        int tz = Long.numberOfTrailingZeros(k);
        int[] pres = tz > 0 ? new int[] {tz, 0} : new int[] {0};
        java.math.BigInteger limit = java.math.BigInteger.ONE.shiftLeft(64);
        for (int pre : pres) {
            java.math.BigInteger kk = bk0.shiftRight(pre);
            if (kk.compareTo(java.math.BigInteger.ONE) <= 0) {
                continue;
            }
            int n = 64 - pre;
            for (int l = pre; l <= 64; l++) {
                java.math.BigInteger p = java.math.BigInteger.ONE.shiftLeft(n + l);
                java.math.BigInteger m = p.add(kk).subtract(java.math.BigInteger.ONE).divide(kk);
                if (m.compareTo(limit) >= 0) {
                    break;
                }
                java.math.BigInteger e = m.multiply(kk).subtract(p);
                if (e.compareTo(java.math.BigInteger.ONE.shiftLeft(l)) <= 0) {
                    return new long[] {pre, m.longValue(), l - pre};
                }
            }
        }
        return null;
    }

    private void rfBin(String op, int size, String dstTok, String aTok, String bTok) {
        if (op.equals("SHL") || op.equals("SHR") || op.equals("SAR")) {
            rfShift(op, size, dstTok, aTok, bTok);
            return;
        }
        if (op.equals("ROTL") || op.equals("ROTR")) {
            rfRotate(op, size, dstTok, aTok, bTok);
            return;
        }
        String d = rfReg(dstTok);
        boolean commutative = op.equals("ADD") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR") || op.equals("EQ") || op.equals("NEQ");
        if ((op.equals("ADD") || op.equals("MUL") || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR")) && rfIsImm(aTok) && !rfIsImm(bTok)) {
            // a constant on the left of a commutative operation: swap, so the constant forms (shift / lea / add imm / sized imm) apply
            String sw = aTok;
            aTok = bTok;
            bTok = sw;
        }
        if (op.equals("MUL") && rfIsImm(bTok) && !rfIsImm(aTok) && rfMulByBigConst(d, aTok, rfImm(bTok))) {
            return;
        }
        if (op.equals("MUL") && size == 8 && rfIsImm(bTok) && !rfIsImm(aTok) && rfFitsImm32(rfImm(bTok)) && !mulByConstShape(rfImm(bTok))
                && !(rfIsTemp(aTok) && rfReg(aTok).equals(d)) && !(rfIsTemp(bTok) && rfReg(bTok).equals(d))) {
            // d = a * imm32 in one instruction (imul r64, r/m64, imm32) instead of `mov a, d; imul imm, d`
            raw("    imulq $" + rfImm(bTok) + ", " + (rfIsTemp(aTok) ? rfRegText(rfReg(aTok)) : rfMemText(rfSlot(aTok))) + ", " + rfRegText(d));
            return;
        }
        String bText = null;
        if (rfIsTemp(bTok) && rfReg(bTok).equals(d) && !(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            // The destination register is b's own register: a plain "d = a; d op= b" would overwrite b first.
            if (commutative) {
                String t = aTok;
                aTok = bTok;
                bTok = t;
            } else {
                rfRegToReg(RF_SCRATCH, d);
                bText = rfRegText(RF_SCRATCH);
            }
        }
        boolean narrowCmp = size < 8 && !(op.equals("ADD") || op.equals("SUB") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR"));
        boolean signedCmp = narrowCmp && op.charAt(0) == 'S';
        if (narrowCmp) {
            // A narrow compare looks at the low `size` bytes only: constants are cut down at compile time,
            // registers extended (the stack form's zeroExtendReg/signExtendReg on both operands).
            if (rfIsImm(aTok)) {
                aTok = "#" + rfExtImm(rfImm(aTok), size, signedCmp);
            }
            if (rfIsImm(bTok)) {
                bTok = "#" + rfExtImm(rfImm(bTok), size, signedCmp);
            }
        }
        if (bText == null && op.equals("BAND") && size == 8 && rfIsImm(bTok) && rfIsTemp(aTok) && !rfReg(aTok).equals(d)
                && (rfImm(bTok) == 0xFFFFFFFFL || rfImm(bTok) == 0xFFFFL || rfImm(bTok) == 0xFFL)) {
            // x & 0xFF / 0xFFFF / 0xFFFFFFFF into another register: one zero-extending move instead of a copy and a mask
            long mk = rfImm(bTok);
            if (mk == 0xFFFFFFFFL) {
                raw("    movl %" + sizedReg(rfReg(aTok), 4) + ", %" + sizedReg(d, 4));
            } else {
                raw("    movz" + (mk == 0xFFL ? "b" : "w") + "l %" + sizedReg(rfReg(aTok), mk == 0xFFL ? 1 : 2) + ", %" + sizedReg(d, 4));
            }
            return;
        }
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        if (bText == null && op.equals("BAND") && size == 8 && rfIsImm(bTok) && rfImm(bTok) == 0xFFFFFFFFL) {
            // x & 0xFFFFFFFF: a 32-bit self-move zero-extends (no 10-byte constant through the scratch register)
            raw("    movl %" + sizedReg(d, 4) + ", %" + sizedReg(d, 4));
            return;
        }
        if (bText == null) {
            bText = rfSrc(bTok);
        }
        if (narrowCmp) {
            rfExtend(d, size, signedCmp);
            if (bText.equals(rfRegText(RF_SCRATCH))) {
                rfExtend(RF_SCRATCH, size, signedCmp);
            } else if (rfIsTemp(bTok) && !rfReg(bTok).equals(d)) {
                rfExtend(rfReg(bTok), size, signedCmp);
            }
        }
        String dText = rfRegText(d);
        switch (op) {
            case "ADD":
                rfOp2("add", dText, bText);
                return;
            case "SUB":
                rfOp2("sub", dText, bText);
                return;
            case "MUL":
                if (bText.startsWith("$") && rfMulByConst(d, Long.parseLong(bText.substring(1)))) {
                    return;
                }
                rfOp2("imul", dText, bText);
                return;
            case "BAND":
            case "BOR":
            case "BXOR":
                // bitwise and / or / xor (BAND is also x % 2^n); the mask is a small immediate or a register. A narrow result is
                // cut back to the operand width, so the temp holds the zero-extended value (the entry is marked zx by the pass).
                rfOp2(op.equals("BAND") ? "and" : op.equals("BOR") ? "or" : "xor", dText, bText);
                zeroExtendReg(d, size);
                return;
            case "AND":
            case "OR": {
                String o = op.equals("AND") ? "and" : "or";
                if (size == 1) {
                    // Booleans: both operands are register-held 0/1 results; byte-wide, like the stack form.
                    String bReg = rfIsTemp(bTok) ? rfReg(bTok) : d;
                    raw("    " + o + "b %" + sizedReg(bReg, 1) + ", %" + sizedReg(d, 1));
                    
                } else {
                    rfOp2(o, dText, bText);
                }
                return;
            }
            default:
                break;
        }
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT": setcc = "seta"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "SLT": setcc = "setl"; break;
            case "SLT_EQ": setcc = "setle"; break;
            case "SGT": setcc = "setg"; break;
            case "SGT_EQ": setcc = "setge"; break;
            default:
                throw new IllegalStateException("unknown R_BIN operator '" + op + "'");
        }
        rfOp2("cmp", dText, bText);
        String d8 = sizedReg(d, 1);
        raw("    " + setcc + " %" + d8);
        raw("    movzbq %" + d8 + ", %" + d);
        
    }

    /** the constants rfMulByConst turns into shifts / lea / shift+add (kept in step with it) */
    private static boolean mulByConstShape(long k) {
        if (k < 2 || k > (1L << 31)) {
            return false;
        }
        return Long.bitCount(k) == 1 || k == 3 || k == 5 || k == 9 || Long.bitCount(k - 1) == 1 || Long.bitCount(k + 1) == 1;
    }

    /**
     * `d = a * k` for k = 2^n + 1 or 2^n - 1 above 2^31 (too big for an imm32 imul: it would take a 10-byte `movabs` plus a 3-cycle `imul`), as
     * `mov a, d; shl n, d; add|sub a, d` (a is read again, so a == d goes through the scratch register). The low 64 bits equal the imul's.
     * Returns false (nothing emitted) for any other k.
     */
    private boolean rfMulByBigConst(String d, String aTok, long k) {
        if (k <= (1L << 31)) {
            return false;
        }
        boolean plus = Long.bitCount(k - 1) == 1;
        if (!plus && Long.bitCount(k + 1) != 1) {
            return false;
        }
        int sh = Long.numberOfTrailingZeros(plus ? k - 1 : k + 1);
        String mn = plus ? "addq" : "subq";
        if (rfIsTemp(aTok) && rfReg(aTok).equals(d)) {
            raw("    movq %" + d + ", %" + RF_SCRATCH);
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " %" + RF_SCRATCH + ", %" + d);
        } else if (rfIsTemp(aTok)) {
            rfRegToReg(d, rfReg(aTok));
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " %" + rfReg(aTok) + ", %" + d);
        } else {
            movMemToReg(d, rfSlot(aTok));
            raw("    shlq $" + sh + ", %" + d);
            raw("    " + mn + " " + rfMemText(rfSlot(aTok)) + ", %" + d);
        }
        return true;
    }

    /**
     * `d *= k` for a constant k as shifts and adds instead of an `imul` (3 cycles of latency; the shifts are 1 + 1): a power of two is one
     * `shl`, 3, 5 and 9 are one `lea`, 2^n + 1 and 2^n - 1 are `mov scratch; shl; add|sub scratch` (so `x * 31` is `(x << 5) - x`). The low 64 bits
     * are the same as the imul's, which is all a register holds (narrow products are never masked there anyway). Returns false for any other k.
     */
    private boolean rfMulByConst(String d, long k) {
        if (k < 2 || k > (1L << 31)) {
            return false;
        }
        String r = "%" + d;
        if (Long.bitCount(k) == 1) {
            raw("    shlq $" + Long.numberOfTrailingZeros(k) + ", " + r);
            return true;
        }
        if (k == 3 || k == 5 || k == 9) {
            raw("    leaq (" + r + "," + r + "," + (k - 1) + "), " + r);
            return true;
        }
        boolean plus = Long.bitCount(k - 1) == 1;
        if (!plus && Long.bitCount(k + 1) != 1) {
            return false;
        }
        int sh = Long.numberOfTrailingZeros(plus ? k - 1 : k + 1);
        raw("    movq " + r + ", %" + RF_SCRATCH);
        raw("    shlq $" + sh + ", " + r);
        raw("    " + (plus ? "addq" : "subq") + " %" + RF_SCRATCH + ", " + r);
        return true;
    }

    /** The conditional jump taken when `a OP b` is false (unsigned and signed compares; shared by R_BRC and R_BRCM). */
    private static String rfFalseJump(String op) {
        switch (op) {
            case "EQ": return "jne";
            case "NEQ": return "je";
            case "LT": return "jae";
            case "LT_EQ": return "ja";
            case "GT": return "jbe";
            case "GT_EQ": return "jb";
            case "SLT": return "jge";
            case "SLT_EQ": return "jg";
            case "SGT": return "jle";
            case "SGT_EQ": return "jl";
            default:
                throw new IllegalStateException("unknown R_BRC operator '" + op + "'");
        }
    }

    /**
     * R_BRCM: jump to `label` when NOT (a op [base]), where [base] is the 8-byte word at the address held in the register `base`
     * (the safe dynarray length word: `cmpq (%base), %a`). `a` is a %t/%v register or a frame slot.
     */
    private void rfBrcMem(String op, String aTok, String baseTok, String label) {
        String cc = rfFalseJump(op);
        String aText;
        if (rfIsSlot(aTok)) {
            rfMov("%s", aTok); // cmp takes one memory operand: a frame-slot index goes through the scratch register
            aText = rfRegText(RF_SCRATCH);
        } else {
            aText = rfRegText(rfReg(aTok));
        }
        String bText = "(" + rfRegText(rfReg(baseTok)) + ")";
        rfOp2("cmp", aText, bText);
        raw("    " + cc + " " + label);
    }

    /** Jump to `label` when NOT (a op b), for an 8-byte compare; a and b are %t/%v registers, $off slots or #imm (never both #imm). */
    /** the jump condition after swapping the two compared operands (a < b is b > a) */
    private static String rfMirrorJump(String cc) {
        switch (cc) {
            case "jae": return "jbe";
            case "ja": return "jb";
            case "jbe": return "jae";
            case "jb": return "ja";
            case "jge": return "jle";
            case "jg": return "jl";
            case "jle": return "jge";
            case "jl": return "jg";
            default: return cc; // je / jne are symmetric
        }
    }

    /**
     * R_BRC of a 1, 2 or 4 byte compare: one sized cmp (cmpb/cmpw/cmpl) of the low bytes of two registers or a register and an immediate,
     * then the jump taken when the comparison is false. Unsigned operators use the unsigned conditions and S* the signed ones, which on a
     * sized cmp look at exactly the low `size` bytes (what the unfused R_BIN did by extending both operands first). Frame-slot operands are
     * never fused (BranchFusionPass).
     */
    private String rfBrcNarrow(String op, int size, String aTok, String bTok, String label) {
        String cc = rfFalseJump(op);
        if (rfIsImm(aTok)) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = rfMirrorJump(cc);
        }
        if (!rfIsTemp(aTok) || !(rfIsTemp(bTok) || rfIsImm(bTok))) {
            throw new IllegalStateException("narrow R_BRC needs register or immediate operands: " + aTok + " " + bTok);
        }
        String suffix = size == 1 ? "b" : size == 2 ? "w" : "l";
        String aText = "%" + sizedReg(rfReg(aTok), size);
        String bText = rfIsImm(bTok) ? "$" + rfTrunc(rfImm(bTok), size) : "%" + sizedReg(rfReg(bTok), size);
        raw("    cmp" + suffix + " " + bText + ", " + aText);
        if (label != null) {
            raw("    " + cc + " " + label);
        }
        return cc;
    }

    private String rfBrc(String op, String aTok, String bTok, String label) {
        String cc = rfFalseJump(op); // the jump taken when the comparison is false
        if (rfIsImm(aTok)) {
            // "cmp imm, x" does not exist: swap the operands and mirror the condition.
            String t = aTok;
            aTok = bTok;
            bTok = t;
            cc = rfMirrorJump(cc);
        }
        String aText;
        if (rfIsTemp(aTok)) {
            aText = rfRegText(rfReg(aTok));
        } else if (rfIsSlot(aTok) && !rfIsSlot(bTok)) {
            aText = rfMemText(rfSlot(aTok));
        } else {
            // both operands are slots (cmp takes at most one memory operand): a goes through the scratch register
            rfMov("%s", aTok);
            aText = rfRegText(RF_SCRATCH);
        }
        String bText = rfSrc(bTok); // a huge immediate goes through the scratch register (a is then never in it: a slot stays in memory)
        rfOp2("cmp", aText, bText);
        if (label != null) {
            raw("    " + cc + " " + label);
        }
        return cc;
    }

    /** the condition-code suffix that is TRUE exactly when the false-jump `jcc` is not taken ("jne" -> "e") */
    private static String rfTrueSuffix(String falseJump) {
        switch (falseJump) {
            case "jne": return "e";
            case "je": return "ne";
            case "jae": return "b";
            case "jb": return "ae";
            case "ja": return "be";
            case "jbe": return "a";
            case "jge": return "l";
            case "jl": return "ge";
            case "jg": return "le";
            case "jle": return "g";
            default:
                throw new IllegalStateException("unknown condition '" + falseJump + "'");
        }
    }

    /** `mov imm, reg` that leaves the flags alone (never the xor idiom) */
    private void rfMovImmKeepFlags(String reg, long v) {
        if (v >= 0 && v <= 0xFFFFFFFFL) {
            raw("    movl $" + v + ", %" + sizedReg(reg, 4));
        } else if (rfFitsImm32(v)) {
            raw("    movq $" + v + ", %" + reg);
        } else {
            raw("    movabsq $" + v + ", %" + reg);
        }
    }

    /**
     * "R_CMOV OP n a b %vK X [Y]" -- the select made by the LowerOrderGenerator's ConditionalMovePass: %vK = (a OP b) ? X : Y (Y absent = %vK keeps its
     * value). X and Y are a register or an immediate (already cut to the variable's width). One compare, then `mov Y` (flags untouched) and `cmovCC X`.
     */
    private void rfCmov(List<BytecodeToken> line) {
        String op = line.get(1).text;
        int size = (int) Long.parseLong(line.get(2).text);
        String a = line.get(3).text, b = line.get(4).text, v = line.get(5).text, x = line.get(6).text;
        String y = line.size() > 7 ? line.get(7).text : null;
        String dst = rfReg(v);
        String cc = size < 8 ? rfBrcNarrow(op, size, a, b, null) : rfBrc(op, a, b, null);
        if (y != null && !y.equals(v)) {
            if (rfIsImm(y)) {
                rfMovImmKeepFlags(dst, rfImm(y));
            } else {
                rfRegToReg(dst, rfReg(y));
            }
        }
        String xr;
        if (rfIsImm(x)) {
            rfMovImmKeepFlags(RF_SCRATCH, rfImm(x));
            xr = RF_SCRATCH;
        } else {
            xr = rfReg(x);
        }
        raw("    cmov" + rfTrueSuffix(cc) + "q %" + xr + ", %" + dst);
    }

    private static long rfExtImm(long v, int size, boolean signed) {
        if (size >= 8) {
            return v;
        }
        long t = rfTrunc(v, size);
        return signed ? t : (t & ((1L << (8 * size)) - 1));
    }

    private void rfExtend(String reg, int size, boolean signed) {
        if (signed) {
            signExtendReg(reg, size);
        } else {
            zeroExtendReg(reg, size);
        }
    }

    private void rfUn(String op, int size, String dstTok, String aTok) {
        String d = rfReg(dstTok);
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        switch (op) {
            case "INC":
                raw(("    incq %" + d));
                return;
            case "DEC":
                raw(("    decq %" + d));
                return;
            case "NEG":
                raw(("    negq %" + d));
                return;
            case "NOT":
                raw(("    xorb $1, %" + sizedReg(d, 1)));
                return;
            case "BNOT":
                // bitwise complement cut back to the operand width (u8: ~0 = 255)
                raw(("    notq %" + d));
                zeroExtendReg(d, size);
                return;
            default:
                throw new IllegalStateException("unknown R_UN operator '" + op + "'");
        }
    }

    // ---- register-form: memory, address, float, read-modify-write helpers ----------------------------------------

    private static boolean rfIsGlobal(String tok) {
        return tok.startsWith("&");
    }

    /** address of a frame slot ("$off") or a global ("&sym") into reg. */
    private void rfAddrToReg(String reg, String tok) {
        if (rfIsGlobal(tok)) {
            leaGlobalToReg(reg, tok.substring(1));
        } else {
            leaMemToReg(reg, rfSlot(tok));
        }
    }

    /** An integer argument register; an index beyond the register-passed ones would fall back to rax (a temp), which the pass never produces. */
    private String rfArgReg(int idx) {
        String reg = argReg(idx);
        if (reg.equals("rax")) {
            throw new CodegenException("codegen", "<register-form>", 0, "R_ARG index " + idx + " is beyond the register-passed arguments");
        }
        return reg;
    }

    private void rfLoad(int n, String dstTok, String srcTok) {
        String d = rfReg(dstTok);
        if (rfIsSlot(srcTok)) {
            loadSizedFromFrame(d, rfSlot(srcTok), n);
        } else if (rfIsGlobal(srcTok)) {
            // one instruction, `mov sym+off(%rip), %d`, not `leaq sym(%rip), %d; mov (%d), %d`
            rfMemOverride = globalRipOperand(srcTok.substring(1));
            try {
                loadSizedFromAddr(d, d, n);
            } finally {
                rfMemOverride = null;
            }
        } else {
            loadSizedFromAddr(d, rfReg(srcTok), n);
        }
    }

    /** "(disp)(%base)" memory text for a register base plus a constant displacement */
    private String rfDispMem(String baseTok, long disp) {
        return (disp != 0 ? String.valueOf(disp) : "") + "(%" + rfReg(baseTok) + ")";
    }

    /** %tD = n bytes (1, 2, 4 or 8) at base + disp, zero-extended */
    private void rfLoadDisp(int n, String dstTok, String baseTok, long disp) {
        String d = rfReg(dstTok);
        String prev = rfMemOverride;
        rfMemOverride = rfDispMem(baseTok, disp);
        try {
            loadSizedFromAddr(d, d, n);
        } finally {
            rfMemOverride = prev;
        }
    }

    /** n bytes (1, 2, 4 or 8) at base + disp = src (#imm or a register) */
    private void rfStoreDisp(int n, String baseTok, long disp, String srcTok) {
        String mem = rfDispMem(baseTok, disp);
        if (rfIsImm(srcTok)) {
            raw("    mov" + movSuffix(n) + " $" + rfTrunc(rfImm(srcTok), n) + ", " + mem);
            return;
        }
        raw("    mov" + movSuffix(n) + " %" + sizedReg(rfReg(srcTok), n) + ", " + mem);
    }

    private void rfLoadXDisp(String xmm, String baseTok, long disp, int n) {
        raw("    " + (n == 8 ? "movsd" : "movss") + " " + rfDispMem(baseTok, disp) + ", %" + xmm);
    }

    private void rfStoreXDisp(String baseTok, long disp, String xmm, int n) {
        raw("    " + (n == 8 ? "movsd" : "movss") + " %" + xmm + ", " + rfDispMem(baseTok, disp));
    }

    private static long rfTrunc(long v, int n) {
        if (n >= 8) {
            return v;
        }
        int sh = 64 - 8 * n;
        return (v << sh) >> sh;
    }

    private void rfStore(int n, String addrTok, String srcTok) {
        String areg = null; // register holding the address; null = a frame slot
        String ripMem = null; // a global stored with one instruction, `mov %r, sym+off(%rip)`
        if (rfIsGlobal(addrTok) && !isOddSize(n)) {
            ripMem = globalRipOperand(addrTok.substring(1));
        } else if (rfIsGlobal(addrTok)) {
            leaGlobalToReg(RF_SCRATCH, addrTok.substring(1));
            areg = RF_SCRATCH;
        } else if (!rfIsSlot(addrTok)) {
            areg = rfReg(addrTok);
        }
        if (rfIsImm(srcTok)) {
            long v = rfTrunc(rfImm(srcTok), n);
            String mem;
            mem = ripMem != null ? ripMem : areg == null ? (rfSlot(addrTok) + "(%rbp)") : ("(%" + areg + ")");
            raw("    mov" + movSuffix(n) + " $" + v + ", " + mem);
            
            return;
        }
        String reg = rfReg(srcTok);
        if (ripMem != null) {
            raw("    mov" + movSuffix(n) + " %" + sizedReg(reg, n) + ", " + ripMem);
        } else if (areg == null) {
            storeSizedToFrame(reg, rfSlot(addrTok), n);
        } else {
            storeSizedToAddr(reg, areg, n);
        }
    }

    /** When set, loadSizedFromAddr / storeSizedToAddr address "(base + index*scale)" instead of "(base)" (R_LDI / R_STI only; sizes 1, 2, 4, 8). */
    private String rfIdxReg = null;
    private long rfIdxScale = 1;

    /** constant displacement added to the next indexed memory operand (a frame-slot base: `off(%rbp,%idx,scale)`) */
    private long rfIdxDisp = 0;

    /** When set, the memory operand of the next sized load/store is this text (a RIP-relative global), whatever the address register. */
    private String rfMemOverride = null;

    private String rfMemOperand(String baseReg) {
        if (rfMemOverride != null) {
            return rfMemOverride;
        }
        if (rfIdxReg == null) {
            return ("(%" + baseReg + ")");
        }
        return (rfIdxDisp != 0 ? String.valueOf(rfIdxDisp) : "") + "(%" + baseReg + ",%" + rfIdxReg + "," + rfIdxScale + ")";
    }

    /**
     * Base of an indexed access (R_LDI/R_STI/R_LDXI/R_STXI): a global is loaded into `scratch`, a frame slot is addressed
     * off rbp with a displacement, a temp or variable register is used as it is. Sets rfIdxDisp; the caller resets it.
     */
    private String rfIndexedBase(String baseTok, String scratch) {
        rfIdxDisp = 0;
        if (rfIsGlobal(baseTok)) {
            leaGlobalToReg(scratch, baseTok.substring(1));
            return scratch;
        }
        if (rfIsSlot(baseTok)) {
            rfIdxDisp = rfSlot(baseTok);
            return "rbp";
        }
        if (rfIsTemp(baseTok)) {
            return rfReg(baseTok);
        }
        throw new IllegalStateException("malformed indexed base " + baseTok);
    }

    private static boolean rfHwScale(long s) {
        return s == 1 || s == 2 || s == 4 || s == 8;
    }

    /**
     * Index register + scale of an indexed access. A hardware scale (1/2/4/8) is used as it is; any other stride (a struct element: 24, 56 ...)
     * is multiplied into the scratch register first (`imulq $scale, %idx, %r15`, the variable is not touched) and the operand becomes
     * `disp(%base,%r15,1)`. The base of such an access must not be a global (a global base address already needs the scratch register).
     */
    private void rfSetIndex(String baseTok, String idxTok, long scale) {
        if (rfHwScale(scale)) {
            rfIdxReg = rfReg(idxTok);
            rfIdxScale = scale;
            return;
        }
        if (rfIsGlobal(baseTok) || scale <= 0 || !rfFitsImm32(scale)) {
            throw new IllegalStateException("malformed indexed access (stride " + scale + ", base " + baseTok + ")");
        }
        raw("    imulq $" + scale + ", %" + rfReg(idxTok) + ", %" + RF_SCRATCH);
        rfIdxReg = RF_SCRATCH;
        rfIdxScale = 1;
    }

    private void rfLoadIndexed(int n, String dstTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDI");
        }
        String d = rfReg(dstTok);
        String b = rfIndexedBase(baseTok, d);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            loadSizedFromAddr(d, b, n);
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    private void rfStoreIndexed(int n, String baseTok, String idxTok, long scale, String srcTok, long disp) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            if (rfIsImm(srcTok)) {
                long v = rfTrunc(rfImm(srcTok), n);
                raw("    mov" + movSuffix(n) + " $" + v + ", " + rfMemOperand(b));
                
            } else {
                storeSizedToAddr(rfReg(srcTok), b, n);
            }
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    /** xmm register = the float at global + index*scale (R_LDXI): the base address goes through the scratch register. */
    private void rfLoadXIndexed(int n, String xTok, String baseTok, String idxTok, long scale, long disp) {
        if (!(n == 4 || n == 8) || (rfHwScale(scale) && scale != n) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDXI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(("    " + mn + " " + mem + ", %" + xmm));
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    /** the float in an xmm register stored at base + index*scale (R_STXI). */
    private void rfStoreXIndexed(int n, String baseTok, String idxTok, long scale, String xTok, long disp) {
        if (!(n == 4 || n == 8) || (rfHwScale(scale) && scale != n) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STXI");
        }
        String b = rfIndexedBase(baseTok, RF_SCRATCH);
        rfIdxDisp += disp;
        rfSetIndex(baseTok, idxTok, scale);
        try {
            String mem = (rfMemOperand(b));
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(("    " + mn + " %" + xmm + ", " + mem));
        } finally {
            rfIdxReg = null;
            rfIdxDisp = 0;
        }
    }

    private void rfAddImm(String reg, long v) {
        if (v == 0) {
            return;
        }
        if (rfFitsImm32(v)) {
            raw(("    addq $" + v + ", %" + reg));
        } else {
            movImmToReg(RF_SCRATCH, v);
            raw(("    addq %" + RF_SCRATCH + ", %" + reg));
        }
    }

    private void rfLea(String dstTok, String baseTok, String idxTok, long scale, long extra) {
        String d = rfReg(dstTok);
        boolean baseIsT = rfIsTemp(baseTok);
        String bReg = baseIsT ? rfReg(baseTok) : null;
        if (rfIsImm(idxTok)) {
            long disp = rfImm(idxTok) * scale + extra;
            if (rfIsSlot(baseTok)) {
                long total = rfSlot(baseTok) + disp;
                if (rfFitsImm32(total)) {
                    leaMemToReg(d, total);
                } else {
                    leaMemToReg(d, rfSlot(baseTok));
                    rfAddImm(d, disp);
                }
            } else if (rfIsGlobal(baseTok)) {
                leaGlobalToReg(d, baseTok.substring(1));
                rfAddImm(d, disp);
            } else {
                rfRegToReg(d, bReg);
                rfAddImm(d, disp);
            }
            return;
        }
        String x;
        boolean prescaled = false;
        if (rfIsTemp(idxTok)) {
            x = rfReg(idxTok);
            if (!(scale == 1 || scale == 2 || scale == 4 || scale == 8) && rfFitsImm32(scale)) {
                // scale * idx in one 3-operand imul into the scratch register (no copy first, never in place on a variable)
                raw(("    imulq $" + scale + ", %" + x + ", %" + RF_SCRATCH));
                x = RF_SCRATCH;
                prescaled = true;
            } else if (rfIsVar(idxTok) && !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                rfRegToReg(RF_SCRATCH, x); // the multiply below works in place; never on a variable
                x = RF_SCRATCH;
            }
            if (x.equals(d) && !(baseIsT && bReg.equals(d))) {
                rfRegToReg(RF_SCRATCH, x); // the index lives in the destination register: keep it before the base overwrites it
                x = RF_SCRATCH;
            }
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(idxTok));
            x = RF_SCRATCH;
        }
        if (rfIsSlot(baseTok)) {
            leaMemToReg(d, rfSlot(baseTok));
        } else if (rfIsGlobal(baseTok)) {
            leaGlobalToReg(d, baseTok.substring(1));
        } else {
            rfRegToReg(d, bReg);
        }
        boolean fitsDisp = rfFitsImm32(extra);
        if (scale == 1 || scale == 2 || scale == 4 || scale == 8) {
            if (extra != 0 && fitsDisp) {
                raw(("    leaq " + extra + "(%" + d + ",%" + x + "," + scale + "), %" + d));
            } else {
                raw(("    leaq (%" + d + ",%" + x + "," + scale + "), %" + d));
                if (extra != 0) {
                    rfAddImm(d, extra);
                }
            }
        } else {
            if (!prescaled) {
                raw(("    imulq $" + scale + ", %" + x + ", %" + x));
            }
            raw(("    addq %" + x + ", %" + d));
            if (extra != 0) {
                rfAddImm(d, extra);
            }
        }
    }

    /** Scratch xmm registers for register-form float ops: never an argument register (SysV passes floats in xmm0-7; win64 in xmm0-3) and never a win64 non-volatile one. */
    private String rfXmmA() {
        return isWinAbi() ? "xmm4" : "xmm14";
    }

    private String rfXmmB() {
        return isWinAbi() ? "xmm5" : "xmm15";
    }

    private void rfGprToXmm(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(("    movd %" + sizedReg(gpr64, 4) + ", %" + xmm));
        } else {
            raw(("    movq %" + gpr64 + ", %" + xmm));
        }
    }

    private void rfXmmToGpr(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(("    movd %" + xmm + ", %" + sizedReg(gpr64, 4)));
        } else {
            raw(("    movq %" + xmm + ", %" + gpr64));
        }
    }

    /** a float operand (a temp, an immediate bit pattern or a frame slot of the op's own width) into an xmm register. */
    private void rfLoadXmm(String tok, String xmm, int n) {
        if (rfIsXvar(tok)) {
            rfMovaps(xmm, xvReg(tok));
        } else if (rfIsTemp(tok)) {
            rfGprToXmm(xmm, rfReg(tok), n);
        } else if (rfIsImm(tok)) {
            long v = n == 4 ? (rfImm(tok) & 0xFFFFFFFFL) : rfImm(tok);
            if (v == 0) {
                raw("    xorps %" + xmm + ", %" + xmm);
            } else {
                raw("    mov" + (n == 4 ? "ss" : "sd") + " " + floatPoolMem(v, n) + ", %" + xmm);
            }
            
        } else {
            long off = rfSlot(tok);
            String sfx = n == 4 ? "ss" : "sd";
            raw(("    mov" + sfx + " " + off + "(%rbp), %" + xmm));
        }
    }

    // ---- float variables in xmm registers (RegVarPromotionPass, "float-variables-in-registers: on") ----
    //
    // Variable K lives in xmm(8+K) on SysV and xmm(6+K) on win64 (K < XV_COUNT). Both ranges avoid the argument registers
    // (SysV xmm0-7, win64 xmm0-3) and the register-form scratch pair (SysV xmm14/15, win64 xmm4/5). win64 keeps xmm6-15 across
    // calls, and finishCalleeSaved already saves/restores any of them a function touches (also on a throw). SysV keeps no xmm
    // register across a call, so every call spills all of the function's variables to their home frame slots (declared by
    // R_XVAR) right before it and reloads them right after; the catch entry reloads them too (control gets there from an unwind,
    // after the throwing call's spill). A variable's value is the f32 in the low 32 bits of the register.

    private static final int XV_COUNT = 6;
    private final java.util.Map<Integer, Long> xvHome = new java.util.TreeMap<>();
    private final java.util.Map<Integer, Integer> xvWidth = new java.util.TreeMap<>();

    private static boolean rfIsXvar(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y") || tok.startsWith("%z");
    }

    /** float temporaries %y0..%y3 (FloatTempPass): SysV xmm4-7, win64 xmm12-15 (win64 keeps them; a temp is never live across a call anyway). */
    private static final int YT_COUNT = 4;

    private String yPhys(int k) {
        return "xmm" + ((isWinAbi() ? 12 : 4) + k);
    }

    private String xvPhys(int k) {
        return "xmm" + ((isWinAbi() ? 6 : 8) + k);
    }

    private String xvReg(String tok) {
        int k = Integer.parseInt(tok.substring(2));
        if (tok.startsWith("%z")) { // StaticFloatCachePass: xmm0-3, only inside call-free loops
            if (k < 0 || k >= 4) {
                throw new IllegalStateException("float cache register " + tok + " out of range");
            }
            return "xmm" + k;
        }
        if (tok.startsWith("%y")) {
            if (k < 0 || k >= YT_COUNT) {
                throw new IllegalStateException("float temporary register " + tok + " out of range");
            }
            return yPhys(k);
        }
        if (k < 0 || k >= XV_COUNT) {
            throw new IllegalStateException("float variable register " + tok + " out of range");
        }
        return xvPhys(k);
    }

    private void xvSpillAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " %" + xvPhys(e.getKey()) + ", " + e.getValue() + "(%rbp)");
        }
    }

    private void xvReloadAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " " + e.getValue() + "(%rbp), %" + xvPhys(e.getKey()));
        }
    }

    /** The pool label for a float constant of width n (4 or 8 bytes), creating the entry on first use. */
    private String floatPoolLabel(long bits, int n) {
        long b = n == 4 ? (bits & 0xFFFFFFFFL) : bits;
        String key = n + ":" + b;
        String label = floatPool.get(key);
        if (label == null) {
            label = ".LFC" + floatPool.size();
            floatPool.put(key, label);
        }
        return label;
    }

    /** The memory operand text of a pooled float constant. */
    private String floatPoolMem(long bits, int n) {
        return floatPoolLabel(bits, n) + "(%rip)";
    }

    /** Emits the pool after the last function: read-only, each entry aligned to its own size. */
    private void emitFloatPool() {
        if (floatPool.isEmpty()) {
            return;
        }
        raw(target == CodegenConfig.Target.WINDOWS_GNU_X64 ? ".section .rdata,\"dr\"" : ".section .rodata");
        for (Map.Entry<String, String> e : floatPool.entrySet()) {
            int colon = e.getKey().indexOf(':');
            int n = Integer.parseInt(e.getKey().substring(0, colon));
            long bits = Long.parseLong(e.getKey().substring(colon + 1));
            raw(n == 4 ? "    .p2align 2" : "    .p2align 3");
            raw(e.getValue() + ":");
            raw(n == 4 ? ("    .long " + bits) : ("    .quad " + bits));
        }
    }

    private String xText(String xmm) {
        return ("%" + xmm);
    }

    private void rfMovaps(String dst, String src) {
        if (!dst.equals(src)) {
            raw(("    movaps %" + src + ", %" + dst));
        }
    }

    /** memory operand text for a float at addr (a frame slot, a global or the pointer in a temp), n = 4 or 8 bytes wide. */
    private String rfFloatMem(String addrTok, int n) {
        String ptr = n == 8 ? "qword ptr " : "dword ptr ";
        if (rfIsSlot(addrTok)) {
            return (rfSlot(addrTok) + "(%rbp)");
        } else if (rfIsGlobal(addrTok)) {
            return globalRipOperand(addrTok.substring(1));
        }
        return ("(%" + rfReg(addrTok) + ")");
    }

    /** xmm register = the float (n = 4: f32, 8: f64) at addr. */
    private void rfLoadX(String xmm, String addrTok, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(("    " + mn + " " + mem + ", %" + xmm));
    }

    /** the float in an xmm register stored at addr. */
    private void rfStoreX(String addrTok, String xmm, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(("    " + mn + " %" + xmm + ", " + mem));
    }

    /** "R_FBINX OP n %xK a b": variable K = a OP b (n is always 4). */
    /** For `a * b` where exactly one operand is the immediate 2.0 (f64 or f32 bits per n): the other operand token, else null. */
    private String rfTimesTwoOperand(int n, String aTok, String bTok) {
        long two = n == 8 ? 0x4000000000000000L : 0x40000000L;
        if (rfIsImm(aTok) && (n == 8 ? rfImm(aTok) : (rfImm(aTok) & 0xFFFFFFFFL)) == two && !rfIsImm(bTok)) return bTok;
        if (rfIsImm(bTok) && (n == 8 ? rfImm(bTok) : (rfImm(bTok) & 0xFFFFFFFFL)) == two && !rfIsImm(aTok)) return aTok;
        return null;
    }

    private void rfFloatX(String op, int n, String dstTok, String aTok, String bTok) {
        String xd = xvReg(dstTok);
        String mn;
        switch (op) {
            case "ADD": mn = "add"; break;
            case "SUB": mn = "sub"; break;
            case "MUL": mn = "mul"; break;
            case "DIV": mn = "div"; break;
            default: throw new IllegalStateException("unknown R_FBINX operator '" + op + "'");
        }
        String sfx = n == 4 ? "ss" : "sd";
        if (op.equals("MUL")) {
            // x * 2.0 is exactly x + x in IEEE-754 (zero sign, infinities, NaN included) and an add is shorter than a multiply on the usual critical chain
            String other = rfTimesTwoOperand(n, aTok, bTok);
            if (other != null) {
                if (rfIsXvar(other)) {
                    rfMovaps(xd, xvReg(other));
                } else {
                    rfLoadXmm(other, xd, n);
                }
                raw(("    add" + sfx + " %" + xd + ", %" + xd));
                return;
            }
        }
        if (avx) {
            // VEX three-operand form: xd = a op b with no copy of a into the destination first (b may be the destination's own register)
            String ra;
            if (rfIsXvar(aTok)) {
                ra = xvReg(aTok);
            } else {
                ra = (rfIsXvar(bTok) && xvReg(bTok).equals(xd)) ? rfXmmA() : xd;
                rfLoadXmm(aTok, ra, n);
            }
            String vb;
            if (rfIsXvar(bTok)) {
                vb = xText(xvReg(bTok));
            } else if (rfIsSlot(bTok)) {
                vb = (rfSlot(bTok) + "(%rbp)");
            } else if (rfIsImm(bTok)) {
                vb = floatPoolMem(rfImm(bTok), n);
            } else {
                rfLoadXmm(bTok, rfXmmB(), n);
                vb = xText(rfXmmB());
            }
            raw(("    v" + mn + sfx + " " + vb + ", %" + ra + ", %" + xd));
            return;
        }
        boolean commutative = op.equals("ADD") || op.equals("MUL");
        boolean bIsDst = rfIsXvar(bTok) && xvReg(bTok).equals(xd);
        boolean aIsDst = rfIsXvar(aTok) && xvReg(aTok).equals(xd);
        if (bIsDst && !aIsDst && commutative) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            aIsDst = true;
            bIsDst = false;
        }
        String work = bIsDst ? rfXmmA() : xd; // b lives in the destination register: compute elsewhere, then copy
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, rfXmmB(), n);
            bText = xText(rfXmmB());
        }
        if (rfIsXvar(aTok)) {
            rfMovaps(work, xvReg(aTok));
        } else {
            rfLoadXmm(aTok, work, n);
        }
        raw(("    " + mn + sfx + " " + bText + ", %" + work));
        rfMovaps(xd, work);
    }

    private void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok) {
        rfFloat(compare, op, n, dstTok, aTok, bTok, null);
    }

    /** branchLabel != null (compare only, dstTok null): jump to it when NOT (a op b) instead of producing the 0/1 value. */
    private void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok, String branchLabel) {
        String d = dstTok == null ? null : rfReg(dstTok);
        String xa = rfXmmA();
        if (!compare && op.equals("MUL")) {
            String other = rfTimesTwoOperand(n, aTok, bTok);
            if (other != null) {
                rfLoadXmm(other, xa, n);
                raw("    add" + (n == 4 ? "ss" : "sd") + " %" + xa + ", %" + xa);
                rfXmmToGpr(xa, d, n);
                return;
            }
        }
        String xb = rfXmmB();
        String sfx = n == 4 ? "ss" : "sd";
        boolean aDirect = compare && rfIsXvar(aTok); // a compare never modifies a: read the variable's register in place
        if (!aDirect) {
            rfLoadXmm(aTok, xa, n);
        } else {
            xa = xvReg(aTok);
        }
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = (off + "(%rbp)");
        } else if (rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, xb, n);
            bText = ("%" + xb);
        }
        String aText = ("%" + xa);
        if (!compare) {
            String mn;
            switch (op) {
                case "ADD": mn = "add"; break;
                case "SUB": mn = "sub"; break;
                case "MUL": mn = "mul"; break;
                case "DIV": mn = "div"; break;
                default: throw new IllegalStateException("unknown R_FBIN operator '" + op + "'");
            }
            raw(("    " + mn + sfx + " " + bText + ", " + aText));
            rfXmmToGpr(xa, d, n);
            return;
        }
        raw(("    ucomi" + sfx + " " + bText + ", " + aText));
        if (branchLabel != null) {
            String jcc; // the jump taken when the comparison is false (same flags as the setcc below)
            switch (op) {
                case "EQ": jcc = "jne"; break;
                case "NEQ": jcc = "je"; break;
                case "LT": jcc = "jae"; break;
                case "LT_EQ": jcc = "ja"; break;
                case "GT_EQ": jcc = "jb"; break;
                case "GT": jcc = "jbe"; break;
                default: throw new IllegalStateException("unknown R_BRC float operator '" + op + "'");
            }
            raw("    " + jcc + " " + branchLabel);
            return;
        }
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "GT": setcc = "seta"; break;
            default: throw new IllegalStateException("unknown R_FCMP operator '" + op + "'");
        }
        rfSetcc(setcc, d);
    }

    private void rfSetcc(String setcc, String d) {
        String d8 = sizedReg(d, 1);
        raw("    " + setcc + " %" + d8);
        raw("    movzbq %" + d8 + ", %" + d);
        
    }

    private void rfRmw(String op, String slotTok, String srcTok) {
        if (rfIsVar(slotTok)) {
            String vr = rfReg(slotTok);
            switch (op) {
                case "INC":
                    raw(("    incq %" + vr));
                    return;
                case "DEC":
                    raw(("    decq %" + vr));
                    return;
                case "ADD":
                case "SUB": {
                    String mn = op.equals("ADD") ? "add" : "sub";
                    if (rfIsSlot(srcTok)) {
                        movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                        rfOp2(mn, rfRegText(vr), rfRegText(RF_SCRATCH));
                    } else {
                        rfOp2(mn, rfRegText(vr), rfSrc(srcTok));
                    }
                    return;
                }
                default:
                    throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
            }
        }
        String mem = rfMemText(rfSlot(slotTok));
        switch (op) {
            case "INC":
                raw(("    incq " + mem));
                return;
            case "DEC":
                raw(("    decq " + mem));
                return;
            case "ADD":
            case "SUB": {
                String mn = op.equals("ADD") ? "add" : "sub";
                if (rfIsSlot(srcTok)) {
                    movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                    rfOp2(mn, mem, rfRegText(RF_SCRATCH));
                } else {
                    rfOp2(mn, mem, rfSrc(srcTok));
                }
                return;
            }
            default:
                throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
        }
    }

    private String mangleLabel(String text) {
        return text.replace("@", "L_");
    }

    private void movRegToRegRbpFromRsp() {
        raw("    movq %rsp, %rbp");
        
    }

    /**
     * The real function epilogue -- restore %rsp from %rbp (discarding
     * this frame's own ALLOC reservation and any leftover stack-machine
     * push/pop noise in one move, the same way FUNC_START's prologue
     * reservation is undone regardless of how it was used), restore the
     * caller's own %rbp, then the real hardware `ret`. Emitted once at
     * FUNC_END (a function's true textual end) and again at every RET/
     * RET_FLOAT site (a function's every real, control-flow-reachable
     * exit point) -- see those mnemonics' own doc comments for why a
     * single copy at FUNC_END alone isn't enough. A RET immediately
     * followed by FUNC_END (the common, non-nested case) simply makes
     * FUNC_END's own copy dead, unreachable code -- harmless.
     */

    /**
     * Called at FUNC_END. Finds which callee-saved registers this function's text touches, grows the function's single
     * ALLOC by their save area (kept a multiple of 16), stores them right after it, and turns every CSR_MARK (one per
     * exit path, including GT_UNWIND) into the matching restores. A function that touches none is left as it was.
     */
    /** frame slot of the saved copy of r13, r14, r12 (null: the function keeps no variable there) */
    private final String[] vSlots = new String[3];

    /**
     * Expands every V1314 mark pair: a variable register is saved and restored around an instruction only when the instruction's own code
     * mentions that register (most scratch users touch none of them); pairs that need nothing disappear.
     */
    private int varMarksNeeded(int start) {
        String[] regs = {"r13", "r14", "r12"};
        String sm = V1314_SAVE + "\n";
        String rm = V1314_REST + "\n";
        int mask = 0;
        int from = start;
        int i;
        while ((i = out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = out.indexOf(rm, segStart);
            if (j < 0) {
                break;
            }
            String seg = out.substring(segStart, j);
            for (int vi = 0; vi < 3; vi++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + regs[vi] + "[dwb]?(?![A-Za-z0-9_])").matcher(seg).find()) {
                    mask |= 1 << vi;
                }
            }
            from = j + rm.length();
        }
        return mask;
    }

    private void expandVarMarks(int start) {
        String[] regs = {"r13", "r14", "r12"};
        String sm = V1314_SAVE + "\n";
        String rm = V1314_REST + "\n";
        int from = start;
        int i;
        while ((i = out.indexOf(sm, from)) >= 0) {
            int segStart = i + sm.length();
            int j = out.indexOf(rm, segStart);
            if (j < 0) {
                throw new RuntimeException("codegen internal error: unbalanced register-save marks in '" + currentFuncName + "'");
            }
            String seg = out.substring(segStart, j);
            StringBuilder sv = new StringBuilder();
            StringBuilder rs = new StringBuilder();
            for (int vi = 0; vi < 3; vi++) {
                if (vSlots[vi] != null && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + regs[vi] + "[dwb]?(?![A-Za-z0-9_])").matcher(seg).find()) {
                    sv.append("    movq %").append(regs[vi]).append(", ").append(vSlots[vi]).append("\n");
                    rs.append("    movq ").append(vSlots[vi]).append(", %").append(regs[vi]).append("\n");
                }
            }
            out.replace(j, j + rm.length(), rs.toString());
            out.replace(i, i + sm.length(), sv.toString());
            from = i + sv.length();
        }
    }

    private void finishCalleeSaved() {
        if (csrFuncStart < 0) {
            return;
        }
        long frameBytes = csrAllocSum;
        String body = out.substring(csrFuncStart);
        java.util.List<String[]> regs = new java.util.ArrayList<>(); // {name, kind gpr|xmm}
        java.util.regex.Pattern nb = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rbx|ebx|bx|bl|bh)(?![A-Za-z0-9_])");
        if (nb.matcher(body).find()) regs.add(new String[] {"rbx", "gpr"});
        for (int r = 12; r <= 15; r++) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])r" + r + "[dwb]?(?![A-Za-z0-9_])").matcher(body).find()) {
                regs.add(new String[] {"r" + r, "gpr"});
            }
        }
        if (isWinAbi()) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rdi|edi|di|dil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rdi", "gpr"});
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rsi|esi|si|sil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rsi", "gpr"});
            for (int x = 6; x <= 15; x++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])xmm" + x + "(?![0-9A-Za-z_])").matcher(body).find()) {
                    regs.add(new String[] {"xmm" + x, "xmm"});
                }
            }
        }
        String restoreText = "";
        java.util.Arrays.fill(vSlots, null);
        if (!regs.isEmpty()) {
            if (csrAllocPos < 0) {
                // no locals at all (no ALLOC line): make the frame ourselves, right after the prologue
                csrAllocPos = csrFuncStart;
                csrAllocBytes = 0;
                csrAllocLine = "";
            }
            String beforeAlloc = out.substring(csrFuncStart, csrAllocPos);
            for (String[] r : regs) {
                java.util.regex.Pattern any = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + (r[1].equals("xmm") ? r[0] : (r[0].equals("rbx") ? "(?:rbx|ebx|bx|bl|bh)" : r[0].equals("rdi") ? "(?:rdi|edi|di|dil)" : r[0].equals("rsi") ? "(?:rsi|esi|si|sil)" : r[0] + "[dwb]?")) + "(?![0-9A-Za-z_])");
                if (any.matcher(beforeAlloc.replace(CSR_MARK, "")).find()) {
                    throw new RuntimeException("codegen internal error: function '" + currentFuncName + "' uses " + r[0] + " before its frame is allocated");
                }
            }
            long cur = csrAllocBytes;
            StringBuilder saves = new StringBuilder();
            StringBuilder restores = new StringBuilder();
            for (String[] r : regs) {
                boolean x = r[1].equals("xmm");
                cur += x ? 16 : 8;
                String st, ld;
                String mem = "-" + cur + "(%rbp)";
                st = "    " + (x ? "movups" : "movq") + " %" + r[0] + ", " + mem;
                ld = "    " + (x ? "movups" : "movq") + " " + mem + ", %" + r[0];
                
                saves.append(st).append('\n');
                restores.append(ld).append('\n');
            }
            // r13/r14/r12 variables are saved to frame slots around the scratch users (see V1314_SAVE)
            java.util.Arrays.fill(vSlots, null);
            int needMask = varMarksNeeded(csrFuncStart);
            for (int vi = 0; vi < 3; vi++) {
                if ((rfVarMask & needMask & (1 << vi)) != 0) {
                    cur += 8;
                    vSlots[vi] = "-" + cur + "(%rbp)";
                }
            }
            long total = (cur + 15) & ~15L;
            frameBytes = csrAllocSum - csrAllocBytes + total;
            String newSub = ("    subq $" + total + ", %rsp\n");
            if (!out.substring(csrAllocPos, csrAllocPos + csrAllocLine.length()).equals(csrAllocLine)) {
                throw new RuntimeException("codegen internal error: ALLOC line moved in '" + currentFuncName + "'");
            }
            out.replace(csrAllocPos, csrAllocPos + csrAllocLine.length(), newSub + saves);
            restoreText = restores.toString();
        }
        expandVarMarks(csrFuncStart);
        int from = csrFuncStart;
        String marked = CSR_MARK + "\n";
        int i;
        while ((i = out.indexOf(marked, from)) >= 0) {
            out.replace(i, i + marked.length(), restoreText);
            from = i + restoreText.length();
        }
        String rspMarked = RSP_MARK + "\n";
        String rspFix = frameBytes == 0 ? ("    movq %rbp, %rsp\n")
                : (("    leaq -" + frameBytes + "(%rbp), %rsp\n"));
        from = csrFuncStart;
        while ((i = out.indexOf(rspMarked, from)) >= 0) {
            out.replace(i, i + rspMarked.length(), rspFix);
            from = i + rspFix.length();
        }
        csrFuncStart = -1;
    }

    private void emitFunctionEpilogue() {
        raw(CSR_MARK);
        raw("    movq %rbp, %rsp");
        
        popReg("rbp");
        raw("    ret");
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> textsOf(List<BytecodeToken> line) {
        List<String> out = new ArrayList<>();
        for (BytecodeToken t : line) {
            out.add(t.text);
        }
        return out;
    }
}
