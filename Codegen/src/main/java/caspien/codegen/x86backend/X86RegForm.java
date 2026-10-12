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

/** Last stack-form mnemonic groups, allocation/resize helpers, and the first register-form (R_*) helpers (see X86Backend for how the parts fit together). */
abstract class X86RegForm extends X86CasesB {
    X86RegForm(CodegenConfig.Target target, boolean bmi2) {
        super(target, bmi2);
    }

    boolean emitCases6(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "GT_INIT": {
                // The compiler's own ghost-table bootstrap opcode --
                // "gt_init" is itself a real, already-compiled function
                // sitting elsewhere in this exact same low-order file
                // (from the imported stdlib/gt/gt_init.caspien), with no
                // ordinary CALL to it anywhere in the bytecode -- so
                // this opcode's job is to call it directly, confirmed
                // directly by the exact, unmistakable name match (and
                // the identical pattern for GT_ALIVE_CHECK/GT_DESTRUCT
                // below).
                emitAlignedCall(() -> emitCallByName("gt_init"));
                return true;
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
                return true;
            }
            
case "GT_ALIVE_CHECK": {
                popReg("rax"); // the pointer being checked
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_alive_check"));
                pushReg("rax"); // its bool result
                return true;
            }
            
case "GT_REF_ID": {
                popReg("rax"); // an address (ref some / owns) being stored into a nullable ref
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_ref_id"));
                pushReg("rax"); // its 64-bit id (0 for null / dead)
                return true;
            }
            
case "GT_REF_RESOLVE": {
                popReg("rax"); // an id
                raw(("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_ref_resolve"));
                pushReg("rax"); // the object's address, null when it is no longer registered
                return true;
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
                return true;
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
                return true;
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
                return true;
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
                return true;
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
                return true;
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
                return true;
            }
            
case "TRACE_CAPTURE": {
                // Stack traces (stack_trace.md, 3.4). Walk the rbp chain from this frame: each traced frame keeps `(id << 1) | isRoot` at
                // rbp-24; stop at the first root (never following a root's saved rbp), counting frames. The root's rbp-32 holds the address of its
                // staging array. Second pass: store the innermost min(count, DEPTH) ids, then a 0 terminator. Nothing else is touched: the
                // scratch registers are saved and restored, because variable registers may be live across this instruction.
                int depth = Integer.parseInt(line.get(1).text);
                raw("    pushq %rax");
                raw("    pushq %rcx");
                raw("    pushq %rdx");
                raw("    pushq %rsi");
                raw("    pushq %r11");
                raw("    movq %rbp, %rcx");
                raw("    xorl %edx, %edx");
                raw("1:");
                raw("    incq %rdx");
                raw("    movq -24(%rcx), %rax");
                raw("    testb $1, %al");
                raw("    jnz 2f");
                raw("    movq (%rcx), %rcx");
                raw("    jmp 1b");
                raw("2:");
                raw("    movq -32(%rcx), %rsi");
                raw("    movq $" + depth + ", %rax");
                raw("    cmpq %rax, %rdx");
                raw("    cmova %rax, %rdx");
                raw("    movq %rbp, %rcx");
                raw("    xorl %eax, %eax");
                raw("3:");
                raw("    cmpq %rdx, %rax");
                raw("    jae 4f");
                raw("    movq -24(%rcx), %r11");
                raw("    shrq $1, %r11");
                raw("    movq %r11, (%rsi,%rax,8)");
                raw("    incq %rax");
                raw("    movq (%rcx), %rcx");
                raw("    jmp 3b");
                raw("4:");
                raw("    movq $0, (%rsi,%rdx,8)");
                raw("    movq %rsi, -32(%rbp)");   // this frame's trace pointer = the root's array (a catch in this same frame reads it)
                raw("    popq %r11");
                raw("    popq %rsi");
                raw("    popq %rdx");
                raw("    popq %rcx");
                raw("    popq %rax");
                return true;
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
                // "GT_UNWIND MSG TRACE" (stack traces on): the trace pointer slot (rbp-32) travels up the same way; rcx is free here
                // (a variable in rcx lives only in a call-free region, and an unwind always follows a call).
                boolean carryTrace = carryMessage && line.size() > 2 && "TRACE".equals(line.get(2).text);
                if (carryMessage) {
                    movMemToReg("rax", -16);
                }
                if (carryTrace) {
                    movMemToReg("rcx", -32);
                }
                raw("    movq %rbp, %rsp");
                
                popReg("rbp");
                raw("    addq $8, %rsp");
                
                if (carryMessage) {
                    movRegToMem("rax", -16); // the caller's gt_error_message slot
                }
                if (carryTrace) {
                    movRegToMem("rcx", -32); // the caller's gt_trace_ptr slot
                }
                movMemToReg("rax", -8); // the caller's own gt_routine_address slot
                raw("    jmp *%rax");
                
                return true;
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
                return true;
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
                return true;
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
                return true;
            }
            
            default:
                return false;
        }
    }

    boolean emitCases7(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
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
                return true;
            }
            
case "CALL": {
                String name = line.get(1).text;
                if ((name.startsWith("__drop_") || name.startsWith("__clone_")) && callBufferStack.isEmpty()) {
                    emitDropGlueCall(name);
                    return true;
                }
                resolveStackArgBytesAndCall(() -> emitCallByName(name));
                return true;
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
                return true;
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
                return true;
            }
            
case "PUSH_LABEL": {
                String name = line.get(1).text;
                leaGlobalToReg("rax", mangleLabel(name));
                pushReg("rax");
                return true;
            }
            
case "PUSH_RET_INT": {
                pushReg("rax"); // the call result already sitting in rax
                return true;
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
                return true;
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
                    BytecodeStatement candidate = allLines.get(end);
                    if (candidate.size() == 1 && candidate.get(0).text.equals("ASM_END")) {
                        break;
                    }
                    end++;
                }
                for (int i = currentLineIndex + 1; i < end; i++) {
                    raw(String.join(" ", textsOf(allLines.get(i))));
                }
                asmSkipUntilIndex = end; // skips the raw lines and ASM_END itself
                return true;
            }
            
case "ASM_END":
                // Reached only for a malformed/unpaired block (no matching
                // ASM_START skipped it first) -- nothing to emit either way.
                return true;
            
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
                return true;
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
                return true;
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
                strideKeySink = null;
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
                return true;
            }
            
case "EXPORT":
                // EXPORT's own target function already gets a real
                // .globl/PUBLIC from its own FUNC_START.
                return true;
            
case "GLOBAL":
            case "ALLOC_STATIC":
            case "STRING":
            case "EXTERN":
                // Already handled in the data-section pre-pass -- ELF
                // resolves an EXTERN'd symbol via the linker automatically,
                // with no explicit declaration needed in GAS.
                return true;
            
            default:
                return false;
        }
    }

    void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size) {
        raw("    movq $" + value + ", " + offset + "(%" + baseReg64 + ")");
        
    }

    void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size) {
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
    void emitDropGlueCall(String name) {
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

    /** Push `reg`, pass it as the first argument to the ghost table function `fn`, restore `reg`. */
    void emitGtCall(String fn, String reg) {
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
    void emitResizeGtUpdate() {
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
    void emitResizeWideFillCopy(int elemSize, int words) {
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

    void emitMallocCall(long size) {
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

    String newInternalLabel(String prefix) {
        return "L_cg_" + prefix + "_" + (internalLabelCounter++);
    }

    String rfReg(String tok) {
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

    String rfRegText(String reg) {
        return ("%" + reg);
    }

    String rfMemText(long off) {
        return (off + "(%rbp)");
    }

    /** Source-operand text for a two-operand instruction; a 64-bit immediate that will not fit imm32 goes through the scratch register. */
    String rfSrc(String tok) {
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
    void rfOp2(String op, String dstText, String srcText) {
        raw(("    " + op + "q " + srcText + ", " + dstText));
    }

    void rfRegToReg(String dst, String src) {
        if (!dst.equals(src)) {
            raw(("    movq %" + src + ", %" + dst));
        }
    }

    void rfMov(String dst, String src) {
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
    void rfRotate(String op, int size, String dstTok, String aTok, String bTok) {
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

    void rfShift(String op, int size, String dstTok, String aTok, String bTok) {
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
    void rfDivConst(boolean mod, int size, String dstTok, String aTok, long k) {
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
}
