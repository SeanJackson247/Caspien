package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenConfig;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Argument registers, aligned calls with stack-passed arguments, and the mnemonic dispatcher (emitLine, first mnemonic group) (see X86Generator for how the parts fit together). */
final class X86Calls {
    private final X86Backend hub;
    private final X86Core core;

    X86Calls(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    /**
     * The byte count of the MEMCOPY on the current line when it is a literal of at most 64 bytes, else -1. The line shape is
     * "[dest expr] PUSH 8 <size> [src expr] MEMCOPY" where the source expression is one value ("PUSH 8 $x", optionally followed by
     * "ADDR_OF ..."): walk back over the lines after the size push and require that they leave exactly one value.
     */
    long memcopyConstantSize() {
        int values = 0;
        for (int back = 1; back <= 4; back++) {
            int idx = core.currentLineIndex - back;
            BytecodeStatement l = core.allLines == null || idx < 0 ? null : core.allLines.get(idx);
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

    void movRegToXmm(String gpr64, String xmmReg) {
        hub.emit.raw(("    movq %" + gpr64 + ", %" + xmmReg));
    }

    void movXmmToReg(String xmmReg, String gpr64) {
        hub.emit.raw(("    movq %" + xmmReg + ", %" + gpr64));
    }

    /** "ss" (single-precision, 4-byte) vs "sd" (double-precision, 8-byte) SSE instruction suffix for a given byte size. `f32` is the only floating-point type this language has at all (checked directly, not assumed -- see FLOAT_CHECK's own doc comment below), so every real call site passes 4 here; the 8-byte ("sd") branch is kept only so this doesn't silently mishandle a size this pass has never actually seen, not because anything in the language can produce one today. */
    String sseSuffix(int size) {
        return size <= 4 ? "ss" : "sd";
    }

    /** reads `size` bytes at (%addrReg64) and pushes them: a single sized load+push for size<=8, or a whole-word-at-a-time block push (lowest source offset first, highest last/top) for a larger aggregate -- the read-side counterpart of DOT/LOOKUP_ARRAY/LOOKUP_DYN's own "_LHS" (address-only) variants, generalized to structs/array elements bigger than one register. `addrReg64` is clobbered. */
    void pushSizedOrBlockFromAddr(String addrReg64, int size) {
        if (size <= 8) {
            hub.emit.loadSizedFromAddr("rax", addrReg64, size);
            hub.emit.pushReg("rax");
            return;
        }
        long words = (size + 7) / 8;
        // The word is loaded into r11, never rax: callers such as
        // LOOKUP_DYN pass "rax" as the address register, and loading
        // the first word into it destroyed the address before the
        // second word was read (every dynarray element wider than one
        // word -- any struct -- read back garbage).
        for (long i = 0; i < words; i++) {
            hub.emit.loadSizedFromAddr("r11", addrReg64, 8);
            if (i < words - 1) {
                hub.emit.raw(("    addq $8, %" + addrReg64));
            }
            hub.emit.pushReg("r11");
        }
    }

    String argReg(int index) {
        String[] table = hub.emit.isWinAbi() ? X86Core.WIN_ARG_REGS : X86Core.LINUX_ARG_REGS; // register choice is an ABI question, not a syntax one -- WINDOWS_GNU_X64 uses these same win64 registers, just named/referenced in AT&T syntax
        if (index < table.length) {
            return table[index];
        }
        // Beyond the register-passed args: this first version doesn't
        // marshal stack-passed arguments -- no fixture this pass was
        // verified against needs a 5th+ (Linux) / 5th+ (Windows) arg.
        return "rax";
    }

    /** The float-bank counterpart of `argReg` -- `ARGn`/`FARGn`'s own real per-bank index already accounts for the two conventions' different counting rules (see `AddressLoweringPass.resolveArgWordSlots`/`CompilerConfig.CallingConvention.sharedArgumentPosition` on the sibling lowering project), so this is a plain, dumb table lookup, exactly like `argReg` -- no convention-mode awareness needed here at all. */
    String argFloatReg(int index) {
        String[] table = hub.emit.isWinAbi() ? X86Core.WIN_ARG_REGS_FLOAT : X86Core.LINUX_ARG_REGS_FLOAT;
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
    int alignPadFor(long stackArgBytes) {
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
    void emitStackArgRestore(long stackArgBytes) {
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
        hub.emit.raw(("    movq " + stackArgBytes + "(%rsp), %rsp"));
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
    String buildStackArgReservation(long stackArgBytes) {
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
    void reverseStackArgWords(long stackArgBytes) {
        long words = stackArgBytes / 8;
        for (long i = 0; i < words / 2; i++) {
            long lo = i * 8;
            long hi = (words - 1 - i) * 8;
            hub.emit.raw("    movq " + lo + "(%rsp), %rax");
            hub.emit.raw("    movq " + hi + "(%rsp), %r11");
            hub.emit.raw("    movq %r11, " + lo + "(%rsp)");
            hub.emit.raw("    movq %rax, " + hi + "(%rsp)");
            
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
    void resolveStackArgBytesAndCall(Runnable callBody) {
        if (core.argTrackDeltaStack.isEmpty() || core.callBufferStack.isEmpty()) {
            // No open "CC_START" bracket at all -- shouldn't happen for a
            // real bytecode-level "CALL"/"INVOKE" (every one is always
            // wrapped in its own "CC_START"/"CC_END"), but falls back to
            // the always-safe, zero-stack-arg path rather than guessing.
            emitAlignedCall(callBody);
            return;
        }
        long stackArgBytes = core.argTrackDeltaStack.peek();
        if (stackArgBytes > 0) {
            core.callBufferStack.peek().insert(0, buildStackArgReservation(stackArgBytes));
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
            boolean winAbi = hub.emit.isWinAbi();
            core.argTrackSuspendDepth++;
            try {
                reverseStackArgWords(stackArgBytes);
                if (winAbi) {
                    hub.emit.raw("    subq $32, %rsp");
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
                    hub.emit.raw("    movl $" + hub.emit.consumeVarargsXmmCount() + ", %eax");
                }
                callBody.run();
                if (winAbi) {
                    hub.emit.raw("    addq $32, %rsp");
                }
            } finally {
                core.argTrackSuspendDepth--;
            }
            emitStackArgRestore(stackArgBytes);
        } else {
            emitAlignedCall(callBody);
        }
    }

    void emitAlignedCall(Runnable body) {
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
        core.argTrackSuspendDepth++;
        try {
            boolean winAbi = hub.emit.isWinAbi(); // shadow space vs SysV varargs %al -- an ABI question, independent of syntax
            hub.emit.raw("    movq %rsp, %rax");
            hub.emit.raw("    andq $-16, %rsp");
            hub.emit.raw("    subq $16, %rsp"); // reserved slot for the pre-align rsp -- keeps 16-alignment (subtracting a multiple of 16)
            hub.emit.raw("    movq %rax, 8(%rsp)");
            if (winAbi) {
                hub.emit.raw("    subq $32, %rsp"); // win64 shadow space (WINDOWS_GNU_X64, AT&T syntax)
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
                hub.emit.raw("    movl $" + hub.emit.consumeVarargsXmmCount() + ", %eax");
            }
            body.run();
            if (winAbi) {
                hub.emit.raw("    addq $32, %rsp"); // undo shadow space -- rsp is back at the reserved slot
            }
            hub.emit.raw("    movq 8(%rsp), %rsp"); // load the pre-align rsp straight into rsp -- also discards the reserved slot
            
        } finally {
            core.argTrackSuspendDepth--;
        }
    }

    void emitCallByName(String name) {
        hub.gen.xvSpillAll();
        hub.emit.raw(("    call " + name));
        hub.gen.xvReloadAll();
    }

    void emitCallIndirect(String reg64) {
        hub.gen.xvSpillAll();
        hub.emit.raw(("    call *%" + reg64));
        hub.gen.xvReloadAll();
    }

    void emitLine(BytecodeStatement line) {
        if (!line.isEmpty() && core.currentLineIndex > core.asmSkipUntilIndex && line.size() >= 1 && !(line.size() == 1 && line.get(0).text.endsWith(":"))
                && X86Core.RF_R13_R14_USERS.contains(line.get(0).text) && !line.get(0).text.equals("ASM_START") && core.csrFuncStart >= 0) {
            hub.emit.raw(X86Core.V1314_SAVE);
            emitLineInner(line);
            hub.emit.raw(X86Core.V1314_REST);
            return;
        }
        emitLineInner(line);
    }

    void emitLineInner(BytecodeStatement line) {
        if (line.isEmpty()) {
            return;
        }
        if (core.currentLineIndex <= core.asmSkipUntilIndex) {
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
            hub.emit.raw(hub.gen.mangleLabel(first.substring(0, first.length() - 1)) + ":");
            if (first.contains("catch_") && core.csrFuncStart >= 0) {
                hub.emit.raw(X86Core.RSP_MARK); // a catch entry: the unwind left rsp wherever the callee's frame ended (finishCalleeSaved fixes it)
            }
            return;
        }

        boolean wasCmp = core.lastWasCmp;
        core.lastWasCmp = false;

        // See "value-block" handling in PUSH/LOOKUP_ARRAY/DOT below:
        // captured and reset by default on every instruction, and only
        // restored where it needs to survive (an intervening small
        // PUSH, e.g. an index or a field offset).
        long pendingBlockSize = core.lastValueBlockSize;
        core.lastValueBlockSize = 0;
        // A pending value tag survives ONE intervening PUSH of a word (the index of `PUSH arr; PUSH idx; LOOKUP_ARRAY`), not two: in
        // `z + d[i].a` the small `PUSH 4 z` before `PUSH 8 d; PUSH 8 i` used to tag the pointer lookup as an in-register small array (a float
        // sum silently kept its old value with the register-form passes off). A word PUSH that passed a tag on is remembered; a second
        // PUSH right after it drops the tag.
        if (first.equals("PUSH") && line.size() > 1 && line.get(1).text.matches("\\d+")) {
            if (pendingBlockSize != 0 && core.pushedWordWithTag) {
                pendingBlockSize = 0;
            }
            core.pushedWordWithTag = pendingBlockSize != 0 && line.get(1).text.equals("8");
        } else {
            core.pushedWordWithTag = false;
        }

        String mnemonic = first;
        if (mnemonic.equals("ASM_START")) {
            core.rfClobberSeen = true; // inline assembly may use anything; the other scratch users are bracketed by V1314 marks
        }
        
        if (emitCases1(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesA.emitCases2(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesA.emitCases3(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesB.emitCases4(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.casesB.emitCases5(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.regForm.emitCases6(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        if (hub.regForm.emitCases7(mnemonic, line, first, wasCmp, pendingBlockSize)) {
            return;
        }
        hub.emit.comment("TODO(codegen): '" + String.join(" ", X86Core.textsOf(line)) + "' not yet implemented");
                return;
    }

    boolean emitCases1(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "FUNC_START": {
                String name = line.get(1).text;
                core.currentFuncName = name;
                core.rfVarUsed = false;
                core.rfVarMask = 0;
                core.rfClobberSeen = false;
                core.xvHome.clear();
                core.xvWidth.clear();
                hub.emit.raw("");
                hub.emit.raw(".globl " + name);
                hub.emit.raw(name + ":");
                
                hub.emit.pushReg("rbp");
                hub.gen.movRegToRegRbpFromRsp();
                core.csrFuncStart = core.out.length();
                core.csrAllocSum = 0;
                core.csrAllocPos = -1;
                core.csrAllocLine = null;
                return true;
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
                hub.emit.movImmToReg("rax", 0);
                hub.gen.emitFunctionEpilogue();
                if (core.rfVarUsed && core.rfClobberSeen) {
                    throw new RuntimeException("codegen internal error: '" + core.currentFuncName
                            + "' keeps variables in r13/r14 but also has an instruction that uses them as scratch");
                }
                hub.gen.finishCalleeSaved();
                return true;
            }
            
case "ALLOC": {
                long size = Long.parseLong(line.get(1).text);
                long aligned = (size + 15) & ~15L;
                core.csrAllocSum += aligned;
                if (core.csrAllocPos < 0 && core.callBufferStack.isEmpty() && core.csrFuncStart >= 0) {
                    core.csrAllocPos = core.out.length();
                    core.csrAllocBytes = aligned;
                    core.csrAllocLine = ("    subq $" + aligned + ", %rsp\n");
                }
                hub.emit.raw("    subq $" + aligned + ", %rsp");
                
                return true;
            }
            
case "ADDR": {
                // "ADDR size $offset" -> push that address.
                // "ADDR size globalName" -> push that global's address.
                String operand = line.get(2).text;
                if (operand.startsWith("$")) {
                    hub.emit.leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                } else {
                    hub.emit.leaGlobalToReg("rax", operand);
                }
                hub.emit.pushReg("rax");
                return true;
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
                    hub.emit.comment("TODO(codegen): PUSH (2-token form) '" + String.join(" ", X86Core.textsOf(line)) + "' not yet implemented");
                    return true;
                }
                int size = (int) Long.parseLong(line.get(1).text);
                String operand = line.get(2).text;
                if (operand.startsWith("$") || operand.startsWith("%x")) {
                    boolean fromXvar = operand.startsWith("%x"); // a float variable promoted to an xmm register (RegVarPromotionPass)
                    long offset = fromXvar ? 0 : Long.parseLong(operand.substring(1));
                    if (size <= 8) {
                        if (fromXvar) {
                            hub.gen.rfXmmToGpr(hub.gen.xvReg(operand), "rax", size == 8 ? 8 : 4); // movd/movq: the variable's bits, zero-extended -- what the frame load gave
                        } else {
                            hub.emit.loadSizedFromFrame("rax", offset, size);
                        }
                        hub.emit.storeConstructionOrPlainPush("rax", size);
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
                            core.lastValueBlockSize = -size;
                        } else {
                            // A small push right after a pushed value-block
                            // is always the index/field-offset that a
                            // following LOOKUP_ARRAY/PUSH_FIELDNAME+DOT
                            // consumes *alongside* that block -- preserve
                            // the pending block size across it rather than
                            // treating it as unrelated.
                            core.lastValueBlockSize = pendingBlockSize;
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
                        hub.emit.pushBlockFromFrameConstructionAware(offset, size);
                        core.lastValueBlockSize = size;
                    }
                    return true;
                } else if (operand.equals("null")) {
                    hub.emit.movImmToReg("rax", 0);
                } else if (X86Core.isInteger(operand)) {
                    hub.emit.movImmToReg("rax", operand.startsWith("-") ? Long.parseLong(operand) : Long.parseUnsignedLong(operand));
                } else if (X86Core.isFloatLiteral(operand)) {
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
                    hub.emit.movImmToReg("rax", bits);
                } else if (operand.matches("\\d+\\.(start|end)")) {
                    // A synthesized pseudo-member push against an
                    // *incomplete* range value ("5.start"/"5.end") --
                    // part of the same WITHIN/incomplete-range gap this
                    // pass already documents elsewhere (the lowering
                    // pipeline leaves this operand as a symbolic,
                    // non-address form even at "low order"); flagged
                    // rather than mismangled into a bogus symbol.
                    hub.emit.comment("TODO(codegen): PUSH of incomplete-range pseudo-member '" + operand + "' not yet implemented -- pushing 0 as a placeholder");
                    hub.emit.movImmToReg("rax", 0);
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
                    hub.emit.raw(("    movq %" + argReg(idx) + ", %rax"));
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
                } else if (core.stringLiterals.containsKey(operand)) {
                    // A string-pool id -- always its address (a string
                    // literal decays to a pointer to its own .rodata
                    // bytes, exactly like a real array/struct global;
                    // there's no separate "value" to push instead,
                    // confirmed directly against every real string-
                    // literal PUSH site, e.g. printf's format-string
                    // argument and NEW_FROM_STRING's source operand).
                    hub.emit.leaGlobalToReg("rax", operand);
                } else if (core.globalsSizeInit.containsKey(operand) || core.globalAliasOffset.containsKey(operand)) {
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
                    if (hub.emit.lineAtOffsetIsAtomicSwap(2)) {
                        hub.emit.leaGlobalToReg("rax", operand);
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
                        hub.emit.leaGlobalToReg("rax", operand);
                        pushSizedOrBlockFromAddr("rax", size);
                        core.lastValueBlockSize = size;
                        return true;
                    } else {
                        hub.emit.leaGlobalToReg("rax", operand);
                        hub.emit.loadSizedFromAddr("rax", "rax", size);
                        if (pendingBlockSize == 0 && size < 8) {
                            // Same tag as the local "PUSH size $offset" case:
                            // a narrow value may be a whole small static array
                            // about to feed LOOKUP_ARRAY (see that case).
                            core.lastValueBlockSize = -size;
                            hub.emit.storeConstructionOrPlainPush("rax", size);
                            return true;
                        }
                    }
                } else if (core.declaredFunctionNames.contains(operand)) {
                    // A real function's own address -- see
                    // "declaredFunctionNames"'s own doc comment. Never
                    // dereferenced afterward, unlike a global: the value
                    // *is* the address.
                    hub.emit.leaGlobalToReg("rax", operand);
                } else if (X86Core.isCharLiteral(operand)) {
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
                    hub.emit.movImmToReg("rax", X86Core.decodeCharLiteral(line.get(2)));
                } else {
                    hub.emit.comment("TODO(codegen): PUSH of '" + operand + "' (not a declared global) not yet implemented -- pushing 0 as a placeholder");
                    hub.emit.movImmToReg("rax", 0);
                }
                // Any of these forms (a literal index, "null", an ARG
                // read, a global address) can legitimately be the
                // index/offset half of a LOOKUP_ARRAY/DOT chain, so a
                // pending value-block from just before is preserved
                // through it, same as the small "$offset" branch above.
                core.lastValueBlockSize = pendingBlockSize;
                hub.emit.storeConstructionOrPlainPush("rax", size);
                return true;
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
                if (core.constructionScopedAssign.contains(core.currentLineIndex)) {
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
                    hub.emit.assignConstructionBlock(size);
                } else if (line.size() > 4) {
                    // A stack struct literal whose field values were not all plain pushes (an expression, a call, ...): every
                    // field was pushed as a whole 8-byte word, so the block-copy paths below would misread the stack. The layout
                    // descriptor LowerOrderGenerator appended tells us where each field really goes.
                    hub.emit.emitAssignRepack(size, line);
                } else if (size <= 8) {
                    hub.emit.popReg("rax"); // value
                    hub.emit.popReg("rbx"); // address
                    hub.emit.storeSizedToAddr("rax", "rbx", size);
                } else {
                    // Not a recognized construction -- an ordinary
                    // whole-value block copy (`let b = a`, passing a
                    // struct/array by value, ...), whose own pushed image
                    // came from `pushBlockFromFrame`'s uniform, always-
                    // whole-8-byte-word convention instead. Handled the
                    // old way, popping one word at a time.
                    hub.emit.assignBlock(size);
                }
                return true;
            }
            
case "R_MOV": {
                // Register-form (see the LowerOrderGenerator's RegisterFormPass): "R_MOV 8 dst src".
                hub.regForm.rfMov(line.get(2).text, line.get(3).text);
                return true;
            }
            
case "R_BIN": {
                // "R_BIN OP size %tD a b" -- %tD = a OP b.
                hub.gen.rfBin(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return true;
            }
            
case "R_DIVC": {
                // "R_DIVC DIV|MOD 8 %tD a #k" -- unsigned division / remainder by a constant, by multiplying with the reciprocal.
                hub.regForm.rfDivConst(line.get(1).text.equals("MOD"), (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, X86Core.rfImm(line.get(5).text));
                return true;
            }
            
case "R_UN": {
                // "R_UN OP size %tD a" -- %tD = OP a.
                hub.gen.rfUn(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text);
                return true;
            }
            
case "R_BRF": {
                // "R_BRF %tN @label" -- jump if the temp is zero (replaces CMP + JMP).
                String reg = hub.regForm.rfReg(line.get(1).text);
                hub.emit.raw(("    testq %" + reg + ", %" + reg));
                hub.emit.raw("    je " + hub.gen.mangleLabel(line.get(2).text));
                return true;
            }
            
case "R_BRC": {
                // "R_BRC OP 8 a b @label" -- compare a with b and jump to the label when NOT (a OP b) (the fused form of
                // R_BIN cmp + R_BRF, made by the LowerOrderGenerator's BranchFusionPass; the compare result never exists).
                if (line.get(2).text.startsWith("F")) { // "R_BRC OP F4|F8 a b @label": float compare (ucomiss/ucomisd) + jump
                    hub.gen.rfFloat(true, line.get(1).text, Integer.parseInt(line.get(2).text.substring(1)), null, line.get(3).text, line.get(4).text,
                            hub.gen.mangleLabel(line.get(5).text));
                    return true;
                }
                int brcSize = (int) Long.parseLong(line.get(2).text);
                if (brcSize < 8) {
                    hub.gen.rfBrcNarrow(line.get(1).text, brcSize, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
                    return true;
                }
                hub.gen.rfBrc(line.get(1).text, line.get(3).text, line.get(4).text, hub.gen.mangleLabel(line.get(5).text));
                return true;
            }
            
case "R_CMOV": {
                hub.gen.rfCmov(line);
                return true;
            }
            
            default:
                return false;
        }
    }
}
