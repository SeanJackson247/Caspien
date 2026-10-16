package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;


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
    private final DropGlueGenerationPass dropGlueGeneration;
    private final ArgToAllocLoweringPass argToAllocLowering = new ArgToAllocLoweringPass();
    private final AddressLoweringPass addressLowering;
    private final RegisterFormPass registerForm = new RegisterFormPass();
    private final CompilerConfig config;

    /** The config is read once by the caller (Main); no pass reads a file. */
    public LowerOrderGenerator(CompilerConfig config) { }

    public BytecodeProgram generate(BytecodeProgram input) { return null; }
}
