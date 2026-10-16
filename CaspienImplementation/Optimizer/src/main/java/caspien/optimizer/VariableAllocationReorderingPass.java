package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variable-allocation reordering (`variable-allocation-reordering: on|off`, default off).
 *
 * Frame offsets are assigned by the LowerOrderGenerator's AddressLoweringPass purely from the order of a function's ALLOC lines: each slot goes
 * immediately below the previous one, rounded DOWN to its own alignment, and the gap this leaves above it is wasted. So `u8, u64, u8, u64`
 * loses 7 bytes twice. This pass reorders the function's hoisted ALLOC run so the gaps disappear:
 *
 *   1. alignment, largest first (8, 4, 2, 1). Every slot's size is a multiple of its alignment (structs are padded to their alignment, an
 *      array's is its element's), so once the slots are sorted the only padding left is the final round-up of the frame to 16.
 *   2. inside one alignment class, the slot with the larger use weight first (the same weight RegVarHintPass uses: each PUSH/ADDR mention
 *      counts 8^loopDepth, depth capped at 4), so hot variables sit nearest rbp, where an x86 displacement fits one byte.
 *   3. the original order (a stable sort), so equal keys never move.
 *
 * What stays put: `gt_routine_address` (rbp-8) and `gt_error_message` (rbp-16) must be the first two slots of every frame that has them
 * (GT_UNWIND and its MSG copy address them by those offsets) and are pinned there. Parameters are not ALLOCs yet at this point in the
 * pipeline (`ARG` lines): ArgToAllocLoweringPass, which runs afterwards in the LowerOrderGenerator, inserts their slots right behind the
 * pinned ones, so they stay ahead of every reordered local. Only the leading, contiguous ALLOC run is touched (ALLOC lines that appear later,
 * such as the inline temporary of an async `par` call, keep their place). A function with an ASM block, or with an ALLOC of one of the two
 * pinned names anywhere but the very front of the run, is left alone.
 *
 * Correctness needs no analysis: a local is only ever mentioned by name, offsets are computed after this pass, nothing else depends on the
 * slot order (the language does not define the layout of locals), and this pass never adds, removes or renames a line. The alignment table
 * below mirrors AddressLoweringPass.Sizes.alignOf (storage pointers 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/
 * string/range/dynarray 8, a fixed array its element's, a struct the max over its members, anything unknown 8); a wrong estimate could only
 * cost some padding, never correctness.
 */
public class VariableAllocationReorderingPass {

    private final boolean enabled;

    public VariableAllocationReorderingPass() { }

    public VariableAllocationReorderingPass(boolean enabled) { }

    public PassResult run(BytecodeProgram lines) { return null; }

    static boolean isMnemonic(BytecodeStatement line, String m) { return false; }

    private static boolean isAlloc(BytecodeStatement l) { return false; }

    /** The function's lines with the leading ALLOC run reordered, or null when nothing changes. */
    private BytecodeProgram reorderFunction(BytecodeProgram fn, VariableAllocationReorderingAlign table) { return null; }

    private static int compareSlots(VariableAllocationReorderingSlot a, VariableAllocationReorderingSlot b) { return 0; }

    /** Stable insertion sort of the list by compareSlots. */
    private static void sortSlots(List<VariableAllocationReorderingSlot> list) { }
}
