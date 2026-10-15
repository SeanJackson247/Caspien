package caspien.codegen.codegenCaspien.x86backend;

import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.Text;

/** Argument registers, aligned calls with stack-passed arguments, and the mnemonic dispatcher (emitLine, first mnemonic group) (see X86Generator for how the parts fit together). */
final class X86Calls {
    private final X86Backend hub;
    private final X86Core core;

    X86Calls(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    long memcopyConstantSize() {
        return 0;
    }

    // ---- Float (SSE) helpers (README.md, "X86Calls.java") ----

    void movRegToXmm(String gpr64, String xmmReg) {
    }

    void movXmmToReg(String xmmReg, String gpr64) {
    }

    /** "ss" (single-precision, 4-byte) vs "sd" (double-precision, 8-byte) SSE instruction suffix for a given byte size. `f32` is the only floating-point type this language has at all (checked directly, not assumed -- see FLOAT_CHECK's own doc comment below), so every real call site passes 4 here; the 8-byte ("sd") branch is kept only so this doesn't silently mishandle a size this pass has never actually seen, not because anything in the language can produce one today. */
    String sseSuffix(int size) {
        return null;
    }

    /** reads `size` bytes at (%addrReg64) and pushes them: a single sized load+push for size<=8, or a whole-word-at-a-time block push (lowest source offset first, highest last/top) for a larger aggregate -- the read-side counterpart of DOT/LOOKUP_ARRAY/LOOKUP_DYN's own "_LHS" (address-only) variants, generalized to structs/array elements bigger than one register. `addrReg64` is clobbered. */
    void pushSizedOrBlockFromAddr(String addrReg64, int size) {
    }

    String argReg(int index) {
        return null;
    }

    /** The float-bank counterpart of `argReg` -- `ARGn`/`FARGn`'s own real per-bank index already accounts for the two conventions' different counting rules (see `AddressLoweringPass.resolveArgWordSlots`/`CompilerConfig.CallingConvention.sharedArgumentPosition` on the sibling lowering project), so this is a plain, dumb table lookup, exactly like `argReg` -- no convention-mode awareness needed here at all. */
    String argFloatReg(int index) {
        return null;
    }

    // See README.md, "Calls and stack alignment" and "Stack-passed arguments".
    int alignPadFor(long stackArgBytes) {
        return 0;
    }

    void emitStackArgRestore(long stackArgBytes) {
    }

    String buildStackArgReservation(long stackArgBytes) {
        return null;
    }

    void reverseStackArgWords(long stackArgBytes) {
    }

    void resolveStackArgBytesAndCall(boolean indirect, String target) {
    }

    /** Direct call by name, or indirect through register `target`. */
    private void emitCallTarget(boolean indirect, String target) {
    }

    void emitAlignedCallByName(String name) {
    }

    void emitAlignedCall(boolean indirect, String target) {
    }

    void emitCallByName(String name) {
    }

    void emitCallIndirect(String reg64) {
    }

    void emitLine(BytecodeStatement line) {
    }

    void emitLineInner(BytecodeStatement line) {
    }

    boolean emitCases1(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        return false;
    }
}
