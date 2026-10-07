package caspien.lowerorder;

import java.util.List;

/**
 * Top-level orchestrator for the real lowering work -- split out of the
 * original combined caspien-optimizer project into its own program,
 * confirmed directly: "the actual work thats currently been done in
 * the optimizer will just be called the LowerOrderGenerator." This
 * project takes the (still fairly high-order-shaped) bytecode text
 * produced by the sibling caspien-optimizer project's own shallow
 * pipeline and turns it into genuinely low-order bytecode: every name
 * resolved to a byte offset, every type erased to a byte size, every
 * struct/membership/ownership-aware construct expanded into its real,
 * addressable form.
 *
 * Pipeline, run once each, in this fixed order (unchanged from the
 * original project's own sequencing, minus the two now-relocated
 * placeholder reordering passes -- see caspien-optimizer's own
 * BytecodeOptimizer class doc for where those ended up and why):
 *
 *   membership lowering (see MembershipLoweringPass's own header)
 *   clone generation (see CloneGenerationPass's own header)
 *   drop-glue generation (see DropGlueGenerationPass's own header)
 *   arg-to-alloc lowering (see ArgToAllocLoweringPass's own header)
 *   address lowering (see AddressLoweringPass's own header)
 *   strength reduction (see StrengthReductionPass's own header) -- always on
 *   register-form (see RegisterFormPass's own header) -- only when
 *     compiler.config says "deferred-operands: on"
 *   register-variable promotion (see RegVarPromotionPass's own header) --
 *     renames the hot scalar locals the Optimizer hinted ("REGVAR") to
 *     variable registers when "variables-in-registers: on"; always strips
 *     the hints
 *   float temporaries (see FloatTempPass's own header)
 *   compare-and-branch fusion (see BranchFusionPass's own header) -- always on
 */
public class LowerOrderGenerator {

    private final MembershipLoweringPass membershipLowering = new MembershipLoweringPass();
    private final CloneGenerationPass cloneGeneration = new CloneGenerationPass();
    private final DropGlueGenerationPass dropGlueGeneration = new DropGlueGenerationPass();
    private final ArgToAllocLoweringPass argToAllocLowering = new ArgToAllocLoweringPass();
    private final AddressLoweringPass addressLowering = new AddressLoweringPass();
    private final RegisterFormPass registerForm = new RegisterFormPass();

    public List<List<BytecodeToken>> generate(List<List<BytecodeToken>> input) {
        List<List<BytecodeToken>> lines = input;
        lines = membershipLowering.run(lines).lines;
        lines = cloneGeneration.run(lines).lines;
        lines = dropGlueGeneration.run(lines).lines;
        lines = argToAllocLowering.run(lines).lines;
        lines = addressLowering.run(lines).lines;
        // Unsigned / and % by a constant power of two -> shift and mask (always on, see StrengthReductionPass).
        lines = new StrengthReductionPass().run(lines).lines;
        // `match i in arr` bounds test of a literal-bounds range without the range copy (always on, see RangeCheckFusionPass).
        lines = new RangeCheckFusionPass().run(lines);
        CompilerConfig config = CompilerConfig.load("compiler.config");
        // The end of a `for` range gets its own register hint (see RangeEndHintPass); only useful when variables can live in registers.
        if (config.deferredOperands && config.variablesInRegisters) {
            lines = new RangeEndHintPass().run(lines);
        }
        if (config.deferredOperands) {
            // 16-byte range constructions and copies become two 8-byte stores each (see RangeWordSplitPass).
            lines = new RangeWordSplitPass().run(lines);
            lines = registerForm.run(lines).lines;
        }
        // Loop-invariant array base pointers get a hidden copy slot (and a register hint) per loop (see LoopHoistPass).
        LoopHoistPass loopHoist = new LoopHoistPass(config.deferredOperands && config.variablesInRegisters && config.hoistArrayBases);
        lines = loopHoist.run(lines);
        // Always run: strips the REGHINT lines, and promotes variables only when both switches are on.
        lines = new RegVarPromotionPass(config.deferredOperands && config.variablesInRegisters, config.floatVariablesInRegisters, config.variablesInAllocFunctions, config.variablesInArgRegisters).run(lines);
        lines = loopHoist.finish(lines);
        // f32 temporaries in xmm registers (a no-op unless "float-temporaries-in-registers: on", which needs deferred-operands).
        lines = new FloatTempPass(config.deferredOperands && config.floatTemporariesInRegisters).run(lines);
        // libm sqrt/sqrtf on xmm operands: one sqrtsd/sqrtss instead of a call with spills (needs the float registers).
        lines = new FloatIntrinsicPass().run(lines);
        // Read-only float statics in spare xmm registers (%z0..%z3 = xmm0-3, unused %x) for call-free loops (needs float variables).
        lines = new StaticFloatCachePass(config.deferredOperands && config.variablesInRegisters && config.floatVariablesInRegisters).run(lines);
        // A comparison that only feeds a jump compares and jumps (always on; a no-op without register-form lines).
        lines = new BranchFusionPass().run(lines);
        // Jump chains left by `if c { break }` and similar: dead jumps, jumps to the next label, conditional jump over a jump.
        lines = new JumpCleanupPass().run(lines);
        // A global array element addressed by a variable register: one lea fewer (R_LEA + R_LD/R_ST -> R_LDI/R_STI).
        lines = new IndexedAccessPass().run(lines);
        // A global array element at a constant index: one RIP-relative load/store (R_LEA &sym #k + R_LD/R_ST/R_LDX/R_STX -> `&sym+N` operand).
        lines = new GlobalConstAddrPass().run(lines);
        // The safe dynarray length load folds into the bounds compare (R_BRCM; last, only the backend reads it).
        lines = new LengthCompareFusionPass(config.deferredOperands && config.fuseLengthCompare).run(lines);
        // A field access through a pointer in a register is one instruction with a displacement (R_LEA #k + R_LD/R_ST -> R_LDD/R_STD; last, only the backend reads it).
        lines = new FieldDisplacementPass().run(lines);
        // A value computed into a temporary and copied into a variable register on the next line is computed there instead; dead initialisations of variable registers go (always on, last).
        lines = new DestForwardingPass().run(lines);
        // The exit test of a for/loop is copied to the bottom: one conditional jump per iteration (`loop-rotation: on`; last, the copy is final register form).
        lines = new ConditionalMovePass(config.deferredOperands && config.conditionalMove).run(lines);
        lines = new CopyForwardPass(config.deferredOperands && config.copyForward).run(lines);
        lines = new LoopRotationPass(config.deferredOperands && config.loopRotation).run(lines);
        return lines;
    }
}
