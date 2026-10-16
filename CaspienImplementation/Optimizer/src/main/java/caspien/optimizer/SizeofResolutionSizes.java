package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Struct sizes from the STRUCT_START ... STRUCT_END declarations. */
final class SizeofResolutionSizes {
    /** struct name -> its members' canonical types, with padding as a negative marker (-n). */
    private final Map<String, List<Object>> decls = new HashMap<>();
    private final Map<String, Long> cache = new HashMap<>();

    SizeofResolutionSizes(BytecodeProgram lines) { }

    /** Set by sizeStep when the size of a struct (times `needMult`) is still needed to finish the type. */
    private String needStruct;
    private long needMult;

    /**
     * Size of the named struct, or -1 if it has no declaration (or it is nested deeper than 64 levels). Nested structs are
     * summed with an explicit stack of frames {struct, depth, next member, sum so far, multiplier waiting for the child}.
     */
    long structSize(String name, int depth) { return 0L; }

    /**
     * Size of a canonical type, except that when it ends in a struct the answer is left to the caller: `needStruct` is that
     * struct and `needMult` the product of the array lengths around it (the return value is then meaningless).
     */
    private long sizeStep(String canonical) { return 0L; }
}
