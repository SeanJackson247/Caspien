package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;


/**
 * Resolves the front end's symbolic {@code SIZEOF TypeName <type>} into {@code PUSH n <type>}.
 *
 * The front end folds {@code sizeof} for primitives, pointers and ranges (their size never
 * changes) but leaves a struct, or an array of structs, symbolic, and does the same for the scale
 * of {@code raw Struct} pointer arithmetic. A struct's real size (the hidden {@code ___type}
 * word, member padding, trailing padding) is only settled here, in the STRUCT declarations the
 * program actually carries. Resolving it late lets struct member reordering run first and
 * constant folding (which needs the PUSH) run afterwards, so the size {@code unsafe} code
 * passes to malloc or memcopy, and the stride of a pointer step, always match the layout the
 * Lower Order Generator will use.
 *
 * Size of a struct = the sum of its STRUCT_MEMBER sizes and STRUCT_PADDING bytes, read from the
 * declaration as it stands now. Member sizes follow the same rules as the Lower Order
 * Generator's SizeCalculator: any storage-keyword type is an 8-byte pointer, u8/s8/bool/char 1,
 * u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, range 16, dynarray 8, a fixed array
 * is element size times its length, a known struct is its own size (a mangled generic name is
 * looked up whole), anything else (an enum) 8.
 *
 * Always on: the SIZEOF mnemonic must never reach the Lower Order Generator. A SIZEOF naming a
 * struct with no declaration is an internal error.
 */
public class SizeofResolutionPass {

    public PassResult run(BytecodeProgram lines) { return null; }

    static boolean isMnemonic(BytecodeStatement line, String m) { return false; }

}
