package caspien.bytecodeemitter;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.typechecker.TypeCheckerModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.typechecker.ExternInfo;
import caspien.typechecker.FuncInfo;
import caspien.typechecker.StructInfo;
import caspien.typechecker.TypeInfo;
import caspien.bytecode.Text;

/**
 * Final front-end stage: walks the type-checked tree and emits the stack-based higher-order bytecode (HOB) as text.
 * Instruction shapes, if/elseif chaining and the ++ / compound-assignment desugaring: see docs/BytecodeEmitter.md, "Instruction shapes".
 */

/** BytecodeEmitterOperations: assignment, ownership moves, arithmetic, comparisons, calls and arguments (part 3 of 4; see README.md in this folder). */
final class BytecodeEmitterOperations {
    private final BytecodeEmitter hub;

    BytecodeEmitterOperations(BytecodeEmitter hub) { }

    /**
     * Before an owning assignment overwrites its target, destructs the old value (gt_destruct is null-safe); never for a fresh 'let'.
     * Result (`result` of the finished frame): true when the target has no flat name: its address is then on the stack (DUP_TOP + GT_DESTRUCT_ADDR)
     * and the assignment must not compute it again. See docs/BytecodeEmitter.md, "Moves and owning assignment". Frame: i = 1 (inline struct drop) or 2 (destruct) once the address was pushed.
     */
    public int stepDestructOld(EmitFrame f) { return 0; }

    /** Frame slots: b1 = lateDestruct, b2 = target address already on the stack, s1 = hidden local holding the early-evaluated right side (or null). */
    public int stepAssign(EmitFrame f) { return 0; }

    /** Strips transparent mut/imut wrappers (and a non-block try) to find the underlying node, e.g. the struct-RVO CALL. */
    public Token unwrapMutWrappers(Token t) { return null; }

    /** The non-block `try` token (of the function being emitted) whose directly wrapped call is `callNode`, or null. */
    public Token ownerTryOf(Token callNode) { return null; }

    /** Caller half of struct RVO: the callee gets the destination's address as a hidden first argument; no PUSH_RET/ASSIGN copy. See docs/BytecodeEmitter.md, "Return and struct return (RVO)". Frame: s1 = destination variable, s2 = its type, s3 = hidden local. */
    public int stepRvoAssign(EmitFrame f) { return 0; }

    /**
     * Shared RVO call site: arguments are transferred as a call sequence would, with the hidden destination pointer (destLines: the lines that push it)
     * in register slot 0. "return foo()" tail forwarding uses this function's own $ret_dest as destination (no address-of, no copy).
     * The staging write happens now; the returned CALL_SEQUENCE frame emits the rest.
     */
    public EmitFrame rvoCallFrame(Token callOp, String rawPtrType, List<String> destLines) { return null; }

    /** Name of the bare-variable destination (a 'let' binding or a plain assignment target). */
    public String destVarNameOf(Token target) { return null; }

    /** x += y desugars to the same bytecode as "x = x + y" -- no dedicated compound-assign opcode. */
    public EmitFrame compoundAssignFrame(Token op) { return null; }

    public EmitFrame binaryArithFrame(Token op) { return null; }

    /** C-style pointer arithmetic: p +/- n and n + p scale n by the pointee size; p - q is (p - q) / sizeof(pointee), signed. */
    public EmitFrame pointerArithFrame(Token op) { return null; }

    public EmitFrame unaryMinusFrame(Token op) { return null; }

    public EmitFrame logicalNotFrame(Token op) { return null; }

    /** x++ / x-- desugar to the same bytecode shape as "x = x .INC": push target, push value, INC (DEC), ASSIGN; on a raw pointer wider than a byte, p = p +/- sizeof(pointee) as C does. */
    public EmitFrame incDecFrame(Token op, boolean increment) { return null; }

    public EmitFrame logicalFrame(Token op, String name) { return null; }

    /** True for a "&&then"/"||then" node specifically -- never a plain "&&"/"||". */
    public boolean isThenVariant(Token node) { return false; }

    public EmitFrame shortCircuitFrame(Token node, String falseLabel, String trueLabel) { return null; }

    /**
     * Cascading CMP/JMP lowering of "&&then"/"||then". Exactly one of falseLabel (s1) / trueLabel (s2) is non-null; the other outcome falls
     * through. No AND/OR is emitted. See docs/BytecodeEmitter.md, "Short-circuit conditions". Frame: s3 = the label an enclosing-jump case creates.
     */
    public int stepShortCircuit(EmitFrame f) { return 0; }

    public EmitFrame comparisonFrame(Token op) { return null; }

    /** "a swap b": ATOMIC_SWAP, a read-modify-write; the left operand is pushed as an address, the result is the previous value. */
    public EmitFrame swapFrame(Token op) { return null; }

    /**
     * A call, or a builtin dispatched by reserved name: lines that precede the first nested emission are written now, the rest is the returned frame
     * (null when the whole thing was lines).
     */
    public EmitFrame callOrBuiltinFrame(Token op) { return null; }

    public Token singleBuiltinArg(Token op) { return null; }

    /** clone(x): the pending catch label is held back while the operand is emitted (a nested call would consume it), then CLONE, GT_REGISTER and the failure check. Frame: s1 = held label, t1 = operand. */
    public int stepClone(EmitFrame f) { return 0; }

    /** dyn(text) / dyn([..]). Frame: s1 = held catch label, t1 = string operand or array literal, toks = literal elements, strs = their temporaries, strs2 = the previous dynLiteralTempNames. */
    public int stepDyn(EmitFrame f) { return 0; }

    /**
     * resize(array, count..): takes ownership of the array and returns a new owns; RESIZE (safe) / URESIZE (unsafe). No CC bracket. see docs/BytecodeEmitter.md, "resize emission".
     * Frame: s1 = held catch label, t1 = array operand, toks = arguments, b1 = unsafe, b2 = null-out deferred, s2/s3/s4 = tailArr/tailCount/tailOld hidden locals, strs = temporaries, i = argument index.
     */
    public int stepResize(EmitFrame f) { return 0; }

    /**
     * Wraps one call in CC_START .. CC_END (first n arguments moved to ARGn registers) and adds PUSH_RET for a non-void result.
     * pushFuncPtr is non-null only for call(); regArgs are the register-transferred arguments; returnTypeOrNull null = void.
     * The convention is the one TypeChecker resolved, never re-derived. varargStartIndex: first true vararg of a variadic extern (-1 = none).
     * see docs/BytecodeEmitter.md, "Call sequence" and "Variadic calls".
     * Frame: t1 = pushFuncPtr, toks = regArgs, s1 = convention, s2 = the call instruction line, s3 = return type, n = varargStartIndex,
     * strs/s4 = (struct RVO only) the lines pushing the hidden destination and its pointer type, i = argument index.
     */
    public EmitFrame callSequenceFrame(Token at, Token pushFuncPtr, List<Token> regArgs, String callInstruction,
            String returnTypeOrNull, String conventionName, int varargStartIndex) { return null; }

    public int stepCallSequence(EmitFrame f) { return 0; }

    /** Runs a call sequence outside the emitter machine (the synthesized C-ABI main wrapper). */
    public void emitCallSequenceTop(Token at, List<Token> regArgs, String callInstruction, String returnTypeOrNull, String conventionName) { }

    /** Transfers one argument into its register(s); a by-value range becomes its two u64 bounds. promoteToDouble: a true f32 vararg. see docs/BytecodeEmitter.md, "Call sequence". */
    public EmitFrame argTransferFrame(Token arg, ArgCounters counters, boolean promoteToDouble) { return null; }

    /** Frame: t1 = the ".." range being transferred word by word. */
    public int stepArgTransfer(EmitFrame f) { return 0; }

    /** Pushes one word and moves it to the next register of its bank; promoteToDouble inserts PROMOTE_F32_TO_F64 for a true f32 vararg. */
    public EmitFrame argWordFrame(Token expr, ArgCounters counters, boolean promoteToDouble) { return null; }

    public int stepArgWord(EmitFrame f) { return 0; }

    /** Pushes a synthesized qualified-name word (e.g. "x.start") and transfers it; never float. */
    public void emitSyntheticArgWord(String qualifiedName, String type, ArgCounters counters) { }

    /** Emits POP ARGn / POP FARGn if the word's bank has a register left, else leaves it on the stack. isTrueVarargFloat additionally duplicates the word into the same-index int register on a shared-position (win64) convention. see docs/BytecodeEmitter.md, "Variadic calls". */
    public void emitArgTransferTail(String type, ArgCounters counters, boolean isTrueVarargFloat) { }

    /** Unwraps transparent grouping parens (the same '(' shape emitExpr's own DELINEATOR case treats as transparent) down to the real expression underneath, so e.g. "foo((x))" still recognizes "x" as the named range value it is. */
    public Token unwrapParens(Token t) { return null; }

    /** Null for void ("imut_void"/"mut_void", exact match) so no PUSH_RET is emitted. */
    public String voidSafeReturnType(Token op) { return null; }

    /** SLEEP sleep_call duration: dedicated mnemonic naming the @sleep function (resolved by TypeChecker) with the duration type. see docs/BytecodeEmitter.md, "Sleep call". */
    public EmitFrame sleepCallFrame(Token op) { return null; }

    /** Extern call; a variadic one carries its pushed-argument count in EXTERN_CALL. see docs/BytecodeEmitter.md, "Variadic calls". */
    public EmitFrame externCallFrame(Token op, ExternInfo info) { return null; }

    public EmitFrame callFrame(Token op) { return null; }

    /**
     * Desugars a par/await site into ordinary ALLOC/NEW/GT_REGISTER/CALL bytecode with a hidden local __async_handle_N (emitted inline, not hoisted).
     * see docs/BytecodeEmitter.md, "par / await desugaring".
     * Frame: s1 = handle local, s2 = handle canonical type, s3 = held catch label, b1 = await, structInfo/entries/i = the handle layout walk,
     * toks = arguments, toks2/strs = moved-in sources / temporaries, toks3/strs2 = the deferral lists to restore, n = null-out index.
     */
    public int stepAsyncCall(EmitFrame f) { return 0; }

    /** Sets the shared `gt_error_message` and jumps to the catch of the `try`/`?` wrapping a 'par'/'await' (same shape as the 'new' out-of-memory exit). */
    public void emitAsyncFailureJump(String catchLabel, String message) { }

    /** Exact-equality void test, as voidSafeReturnType. */
    public boolean isVoidCanonical(String canonical) { return false; }

    /** Canonical text of a bare function reference ("static_imut_func(params)ret"), mirroring TypeInfo.funcPointer; used for the trampoline address push. */
    public String funcPointerCanonicalOf(FuncInfo info) { return null; }

    /** Under gt-suppression a CALL to a gt-reachable function is redirected to its "__" duplicate. see docs/BytecodeEmitter.md, "Call target redirection". */
    public String resolveCallTargetForEmission(String mangledName) { return null; }

    public void collectCommaArgs(Token node, List<Token> out) { }
}
