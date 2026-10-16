package caspien.bytecodeemitter;

import caspien.config.CallingConvention;

/** Per-call-site counters choosing the register and bank (int/float) of each argument word: SysV counts the banks independently, win64 shares one position counter. A stack-passed word still advances its counter. see docs/BytecodeEmitter.md, "Call sequence". */
public final class ArgCounters {
    public final boolean shared;
    public final int intCount;
    public final int floatCount;
    public int sharedIdx;
    public int intIdx;
    public int floatIdx;

    public ArgCounters(CallingConvention convention) { }

    /** Advances this bank's own counter (or the shared one) and returns the real register-table index for the next word of the given type, or -1 if that bank's registers are already exhausted (stack-passed instead). */
    public int next(boolean isFloat) { return 0; }

    /** Float-bank registers really used (clamped to floatCount) for the SysV %al rule; not the raw floatIdx, which keeps counting stack-passed floats. */
    public int floatRegistersUsed() { return 0; }

    /** True for win64's shared position counter. */
    public boolean isShared() { return false; }
}
