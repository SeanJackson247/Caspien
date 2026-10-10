package caspien.codegen.x86backend;

import caspien.codegen.BytecodeToken;
import caspien.codegen.CodegenConfig;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State and static helpers shared by every part of the x86-64 backend (see X86Backend for the overview).
 * The backend is one class split over a chain of abstract classes, because Java has no partial classes:
 * X86Core (fields, constants, static helpers) <- X86Emit <- X86Calls <- X86CasesA <- X86CasesB <- X86RegForm <- X86Backend. A part may call a method defined in a later part
 * through an abstract declaration at the top of its own class.
 */
abstract class X86Core {

    X86Core(CodegenConfig.Target target, boolean bmi2) {
        this.target = target;
        this.bmi2 = bmi2;
    }

    final CodegenConfig.Target target;


    final StringBuilder out = new StringBuilder();


    // ---- callee-saved register preservation (per function) ----
    // Any generated function that touches a callee-saved register of the target ABI (SysV: rbx, r12-r15; win64 also
    // rdi, rsi, xmm6-15) saves it in its frame and restores it on every way out (RET, the FUNC_END fall-through and
    // GT_UNWIND). The uses are only known once the whole body is emitted, so every exit emits CSR_MARK and FUNC_END
    // (finishCalleeSaved) replaces each mark with the restores, or with nothing, and grows the single ALLOC.
    static final String CSR_MARK = "@@CSR@@";


    int csrFuncStart = -1;


    int csrAllocPos = -1;


    long csrAllocBytes = 0;


    String csrAllocLine = null;


    /** Sum of every ALLOC (16-aligned) of the current function: the frame size below rbp, once the callee-saved area is added (finishCalleeSaved). */
    long csrAllocSum = 0;


    /** A catch landing is reached by an unwind that skipped the call's own rsp restore (and any operand words pushed): the mark becomes `rsp = rbp - frame` once the frame size is final. */
    static final String RSP_MARK = "@@RSP@@";


    // Declarations collected on a first pass so they can be emitted
    // into proper .data/.rodata/.bss sections ahead of .text, instead
    // of wherever they happen to sit in the linear bytecode stream
    // (GLOBAL/STRING/ALLOC_STATIC/EXTERN lines are legal anywhere --
    // interleaved with function bodies -- but real assembly sections
    // can't be interleaved the same way).
    final Map<String, long[]> globalsSizeInit = new LinkedHashMap<>();


 // name -> [size, init] (init may be absent -> bss)
    final Map<String, Boolean> globalsHasInit = new LinkedHashMap<>();


    final Map<String, String> stringLiterals = new LinkedHashMap<>();


 // id -> text
    // Float-constant pool (AT&T targets only): every float immediate a register-form float op needs is stored once, in a
    // read-only section, and used as a memory operand (`mulsd .LFC3(%rip), %xmm0`) or loaded with one `movsd`, instead of
    // `movabs $bits, %r15 ; movq %r15, %xmm` on every use. Key "n:bits" -> label; n = 4 or 8.
    final Map<String, String> floatPool = new LinkedHashMap<>();


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
    final java.util.Set<String> declaredFunctionNames = new java.util.HashSet<>();


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
    final Map<String, Long> globalAliasOffset = new LinkedHashMap<>();


 // dotted name -> byte offset into its parent
    final Map<String, List<long[]>> aliasInits = new LinkedHashMap<>();


 // parent -> [offset, size, bits] of each initialised element
    final Map<String, String> globalAliasParent = new LinkedHashMap<>();


 // dotted name -> parent global name
    // Set only while generate() is walking the line list, so a bare
    // global-name PUSH can peek a couple of lines ahead to tell whether
    // it's feeding an ATOMIC_SWAP (needs the global's address) or
    // anything else (needs its value) -- see that PUSH case's own doc
    // comment for why both are genuinely needed here.
    List<List<BytecodeToken>> allLines;


    int currentLineIndex;


    /** Set by the "ASM_START" case to the index of the "ASM_END" line that
     * closes the block it just copied verbatim, so every raw line in
     * between (already emitted as part of that one case) is skipped by
     * the main per-line dispatch instead of falling through to the
     * generic TODO comment a second time. -1 when no block is open. */
    int asmSkipUntilIndex = -1;


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
    final Map<Integer, Long> pushStoreOffsetByLine = new java.util.HashMap<>();


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
    final java.util.Deque<Long> argTrackDeltaStack = new java.util.ArrayDeque<>();


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
    int argTrackSuspendDepth = 0;


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
    final java.util.Deque<StringBuilder> callBufferStack = new java.util.ArrayDeque<>();


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
    final java.util.Deque<java.util.List<String>> loadedArgRegsStack = new java.util.ArrayDeque<>();


    /**
     * The registers each currently-open nested call saved at its own "CC_START"
     * (the enclosing call's already-loaded argument registers, in push order),
     * one list per open bracket, popped at the matching "CC_END".
     */
    final java.util.Deque<java.util.List<String>> savedOuterArgRegsStack = new java.util.ArrayDeque<>();


    /**
     * True when the last "CC_END" had to restore xmm0 (the float-bank
     * argument 0 register) that a just-finished nested call may also have
     * used to *return* a float: the possible float result was stashed in
     * r10 first, and the very next `PUSH_RET_FLOAT` reads it from there
     * instead of xmm0.
     */
    boolean floatResultInR10 = false;


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
    int pendingVarargsXmmCount = -1;


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
    final Set<Integer> constructionScopedAssign = new java.util.HashSet<>();


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
    final Set<Integer> packedNewLines = new java.util.HashSet<>();


    /** Plain data holder for {@link #collectRunEntries}: parallel lists of each direct entry's own size and its store-instruction line index (-1 when it has none of its own -- a `STACK_LOCK` padding gap, or a nested `NEW`'s own opaque pointer). */
    static final class RunEntries {
        final List<Long> sizes = new ArrayList<>();
        final List<Integer> storeLines = new ArrayList<>();
    }


    final boolean bmi2;


    /** `avx on`: R_FBINX uses VEX 3-operand scalar forms */
    public boolean avx;


    // ---- common subexpression: the stride multiply `imulq $stride, %idx, %r15` of an indexed access (rfSetIndex) ----
    // After the multiply r15 holds idx*stride. A following access with the same index register and stride reuses it when every line emitted in
    // between is a plain data move / float arithmetic whose destination is neither r15 nor (any alias of) the index register, in the same sink.
    // Anything else (label, jump, call, lea, integer arithmetic ..., a buffer append) forgets it. Flags: only the skipped imul would have set them.
    int strideCseHits = 0;


    StringBuilder strideKeySink = null;


    String strideKeyIdx = null;


    long strideKeyScale = 0;


    static final java.util.Set<String> STRIDE_KEEP_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "movq", "movl", "movw", "movb", "movzbq", "movzwq", "movzbl", "movzwl", "movslq", "movsbq", "movswq", "movsbl", "movswl",
            "movsd", "movss", "movd", "movaps", "movapd", "addsd", "addss", "subsd", "subss", "mulsd", "mulss", "divsd", "divss",
            "addq", "subq", "addl", "subl", "leaq", "andq", "orq", "xorq", "shlq", "shrq", "sarq", "incq", "decq", "negq", "notq",
            "sqrtsd", "sqrtss", "ucomisd", "ucomiss", "comisd", "comiss", "cvtss2sd", "cvtsd2ss", "xorps", "xorpd", "andps", "andpd"));


    /** whether the register spelling `r` (rax, eax, ax, al, r13d, r13b ...) names (part of) the 64-bit register `full` */
    static boolean regAliasOf(String r, String full) {
        if (r.equals(full)) {
            return true;
        }
        if (full.matches("r\\d+")) {
            return r.equals(full + "d") || r.equals(full + "w") || r.equals(full + "b");
        }
        String base = full.substring(1);     // rax -> ax, rsi -> si
        if (full.equals("rax") || full.equals("rbx") || full.equals("rcx") || full.equals("rdx")) {
            String l = base.substring(0, 1);
            return r.equals("e" + base) || r.equals(base) || r.equals(l + "l") || r.equals(l + "h");
        }
        return r.equals("e" + base) || r.equals(base) || r.equals(base + "l");
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
    static int roundUpToLoadableWidth(int size) {
        return size <= 2 ? size : size <= 4 ? 4 : 8;
    }


    /** 3, 5, 6 and 7 bytes: no single x86 store has these widths. */
    static boolean isOddSize(int size) {
        return size == 3 || (size >= 5 && size <= 7);
    }


    static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }


    /** "'a'", "'\n'", "'\0'", ... -- a char-literal PUSH operand, exactly as `BytecodeEmitter.escapeForBytecode` serializes one: an opening single quote, one real character's worth of content, a closing single quote. Deliberately checked structurally (quote ... quote) rather than by first ruling out every other operand shape, since a bare identifier can never itself start *and* end with `'`. */
    static boolean isCharLiteral(String s) {
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
    static long decodeCharLiteral(BytecodeToken operandTok) {
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


    // ---- Calling convention ------------------------------------------------

    static final String[] LINUX_ARG_REGS = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};


    static final String[] WIN_ARG_REGS = {"rcx", "rdx", "r8", "r9"};


    static final String[] LINUX_ARG_REGS_FLOAT =
            {"xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7"};


    static final String[] WIN_ARG_REGS_FLOAT = {"xmm0", "xmm1", "xmm2", "xmm3"};


    // ---- Main per-line dispatch --------------------------------------------

    /**
     * Instructions whose code uses r12/r13/r14 as scratch (NEW*, CLONE*, RESIZE*, DOT, LOOKUP_ARRAY) are bracketed by two marks;
     * finishCalleeSaved turns them into saves/restores of the variable registers (%v0..%v2 = r13, r14, r12) of the functions that
     * actually keep a variable there, and deletes them from every other function (whose text is then exactly what it was without marks).
     */
    static final String V1314_SAVE = "@@V1314S@@";


    static final String V1314_REST = "@@V1314R@@";


    /** Bytes pushed on top of RESIZE's wide fill block while its fill loop runs (the saved old block start). */
    int resizeExtraStack = 0;


    boolean lastWasCmp = false;


    /** See the "value-block" handling threaded through PUSH/LOOKUP_ARRAY/DOT: the byte size of a multi-word value most recently pushed *by value* (as opposed to by address), still sitting on top of the real stack, for a following LOOKUP_ARRAY/DOT to extract a sub-range from -- 0 means "no pending value-block; use the ordinary address-based path." */
    long lastValueBlockSize = 0;


    boolean pushedWordWithTag = false;


 // the previous line was an 8-byte PUSH that passed a pending value tag on (see the line emitter prologue)
    String currentFuncName;


    int internalLabelCounter = 0;


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

    static final String[] RF_TEMP_REGS = { "rax", "rbx", "r11", "r12" };


    static final String RF_SCRATCH = "r15";


    /** %v0..%v2: promoted variables (RegVarPromotionPass), callee-saved, so finishCalleeSaved saves/restores them like any other.
     *  %v3..%v5 (r8, r9, r10) are caller-saved: the promotion only puts a variable there that is never live across a call or any
     *  line that could clobber them. %v6, %v7 (rsi, rdi: SysV argument registers 1 and 0; callee-saved on win64, where
     *  finishCalleeSaved saves them when the text mentions them) follow the same rule and are never live inside a call's argument bracket.
     *  %v8, %v9 (rdx, rcx: SysV argument registers 2 and 3, SysV only) too, and are never live at a constant divide (rdx) / variable-count shift (rcx). */
    static final String[] RF_VAR_REGS = { "r13", "r14", "r12", "r8", "r9", "r10", "rsi", "rdi", "rdx", "rcx" };


    /** set when this function uses a %vN; checked against rfClobberSeen at FUNC_END */
    boolean rfVarUsed = false;


    /** bit i set when this function uses %vi (i < 3: r13, r14, r12), see finishCalleeSaved / V1314_SAVE */
    int rfVarMask = 0;


    /** set when this function has an instruction whose code uses r13/r14 internally (see RegVarPromotionPass.R13_R14_USERS) */
    boolean rfClobberSeen = false;


    static final java.util.Set<String> RF_R13_R14_USERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "NEW_FROM_USTRING", "CLONE", "CLONE_DYN", "RESIZE", "URESIZE", "DOT", "LOOKUP_ARRAY", "ASM_START"));


    /** a register operand: a temp, the scratch register or a promoted variable */
    static boolean rfIsTemp(String tok) {
        return tok.startsWith("%t") || tok.startsWith("%v") || tok.equals("%s");
    }


    static boolean rfIsVar(String tok) {
        return tok.startsWith("%v");
    }


    static boolean rfIsImm(String tok) {
        return tok.startsWith("#");
    }


    static boolean rfIsSlot(String tok) {
        return tok.startsWith("$");
    }


    static long rfImm(String tok) {
        String t = tok.substring(1);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Long.parseUnsignedLong(t);
        }
    }


    static long rfSlot(String tok) {
        return Long.parseLong(tok.substring(1));
    }


    static boolean rfFitsImm32(long v) {
        return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
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


    /** the constants rfMulByConst turns into shifts / lea / shift+add (kept in step with it) */
    static boolean mulByConstShape(long k) {
        if (k < 2 || k > (1L << 31)) {
            return false;
        }
        return Long.bitCount(k) == 1 || k == 3 || k == 5 || k == 9 || Long.bitCount(k - 1) == 1 || Long.bitCount(k + 1) == 1;
    }


    /** The conditional jump taken when `a OP b` is false (unsigned and signed compares; shared by R_BRC and R_BRCM). */
    static String rfFalseJump(String op) {
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


    /** Jump to `label` when NOT (a op b), for an 8-byte compare; a and b are %t/%v registers, $off slots or #imm (never both #imm). */
    /** the jump condition after swapping the two compared operands (a < b is b > a) */
    static String rfMirrorJump(String cc) {
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


    /** the condition-code suffix that is TRUE exactly when the false-jump `jcc` is not taken ("jne" -> "e") */
    static String rfTrueSuffix(String falseJump) {
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


    static long rfExtImm(long v, int size, boolean signed) {
        if (size >= 8) {
            return v;
        }
        long t = rfTrunc(v, size);
        return signed ? t : (t & ((1L << (8 * size)) - 1));
    }


    // ---- register-form: memory, address, float, read-modify-write helpers ----------------------------------------

    static boolean rfIsGlobal(String tok) {
        return tok.startsWith("&");
    }


    static long rfTrunc(long v, int n) {
        if (n >= 8) {
            return v;
        }
        int sh = 64 - 8 * n;
        return (v << sh) >> sh;
    }


    /** When set, loadSizedFromAddr / storeSizedToAddr address "(base + index*scale)" instead of "(base)" (R_LDI / R_STI only; sizes 1, 2, 4, 8). */
    String rfIdxReg = null;


    long rfIdxScale = 1;


    /** constant displacement added to the next indexed memory operand (a frame-slot base: `off(%rbp,%idx,scale)`) */
    long rfIdxDisp = 0;


    /** When set, the memory operand of the next sized load/store is this text (a RIP-relative global), whatever the address register. */
    String rfMemOverride = null;


    static boolean rfHwScale(long s) {
        return s == 1 || s == 2 || s == 4 || s == 8;
    }


    // ---- float variables in xmm registers (RegVarPromotionPass, "float-variables-in-registers: on") ----
    //
    // Variable K lives in xmm(8+K) on SysV and xmm(6+K) on win64 (K < XV_COUNT). Both ranges avoid the argument registers
    // (SysV xmm0-7, win64 xmm0-3) and the register-form scratch pair (SysV xmm14/15, win64 xmm4/5). win64 keeps xmm6-15 across
    // calls, and finishCalleeSaved already saves/restores any of them a function touches (also on a throw). SysV keeps no xmm
    // register across a call, so every call spills all of the function's variables to their home frame slots (declared by
    // R_XVAR) right before it and reloads them right after; the catch entry reloads them too (control gets there from an unwind,
    // after the throwing call's spill). A variable's value is the f32 in the low 32 bits of the register.

    static final int XV_COUNT = 6;


    final java.util.Map<Integer, Long> xvHome = new java.util.TreeMap<>();


    final java.util.Map<Integer, Integer> xvWidth = new java.util.TreeMap<>();


    static boolean rfIsXvar(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y") || tok.startsWith("%z");
    }


    /** float temporaries %y0..%y3 (FloatTempPass): SysV xmm4-7, win64 xmm12-15 (win64 keeps them; a temp is never live across a call anyway). */
    static final int YT_COUNT = 4;


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
    final String[] vSlots = new String[3];


    static boolean isInteger(String s) {
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


    static List<String> textsOf(List<BytecodeToken> line) {
        List<String> out = new ArrayList<>();
        for (BytecodeToken t : line) {
            out.add(t.text);
        }
        return out;
    }
}
