package caspien.codegen;

/** Which assembler dialect and ABI the backend emits. Chosen by `target` in `codegen.config`. */
public enum CodegenTarget {
    WINDOWS_GNU_X64,  // GAS AT&T syntax, win64 ABI -- for gcc/mingw-w64; verified in this sandbox with a real mingw-w64 cross-toolchain + Wine
    LINUX_X64         // GAS AT&T syntax, SysV ABI -- verified in this sandbox with the real as/gcc/ld toolchain
}
