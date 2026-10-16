package caspien.lowerorder;

import caspien.bytecode.BytecodeStatement;

final class RegisterFormEntry {
    RegisterFormKind kind;
    /** the original line (K, M, A) so a flush can re-emit it verbatim */
    BytecodeStatement orig;
    /** K: literal text; M: the "$off" text; A: "$off" or "&sym" */
    String text;
    /** M/A(frame): parsed frame offset */
    long off;
    /** T: which temp */
    int temp = -1;
    /** width in bytes of an M value or a loaded T (arithmetic results are 8) */
    int size = 8;
    /** A only: the address is a global symbol */
    boolean global;
    /** T only: the register holds the value ZERO-EXTENDED to 64 bits (a load of `size` bytes). Arithmetic results are not: a narrow ADD leaves carry bits above its width. */
    boolean zx;
}
