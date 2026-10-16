package caspien.config;

import java.util.ArrayList;
import java.util.List;

/** One named entry under "CallingConventions:" -- every field mirrors the config file's own key names, in the same order they're documented there. `cleanup`/`alignment`/the return-register fields are parsed and validated but not yet consulted by bytecode emission (same "abstract stack-machine IR, real register assignment is the not-yet-built assembly stage's job" reasoning the rest of this compiler's bytecode format already follows); `argumentRegisters.size()`/`argumentRegistersFloat.size()` (this convention's own per-bank "n") and `sharedArgumentPosition` are read directly by `BytecodeEmitter.emitCallSequence` to decide, per call argument, which bank it draws from and whether it's still register-transferred -- see `sharedArgumentPosition`'s own doc comment for why that split exists at all. */
public class CallingConvention {
    public String name;
    public String cleanup;
    public List<String> argumentRegisters = new ArrayList<>();
    public List<String> argumentRegistersFloat = new ArrayList<>();
    public String returnRegister;
    public String returnRegisterFloat;
    public int alignment;
    public int shadowStack;
    /** True only for a convention where an integer argument and a float argument advance one shared running position counter (win64's own documented behavior: "f(int, float, int)" passes its 2nd argument in XMM1, not XMM0, because it's the 2nd argument overall, not the 1st float one) rather than two genuinely independent per-bank counters (SysV, arm64's AAPCS64 -- a float argument never "uses up" an integer register slot there, or vice versa). A real, fixed ABI fact about the convention, confirmed directly against win64's own calling convention rather than inferred from the two register lists' lengths (which happen to both be 4 for win64, but that coincidence isn't what makes it shared -- arm64's own lists are both 8, equally coincidentally, and arm64 is independently-counted). Defaults false (independent counters) when the config omits this key entirely, matching every convention but win64. */
    public boolean sharedArgumentPosition;
}
