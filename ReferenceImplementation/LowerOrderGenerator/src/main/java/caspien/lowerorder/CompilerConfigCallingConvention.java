package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/** One named entry under "CallingConventions:" -- every field mirrors the config file's own key names. `argumentRegisters.size()`/`argumentRegistersFloat.size()`, `shadowStack`, and `sharedArgumentPosition` are read by `AddressLoweringPass`'s own per-function parameter-word resolution; the rest are parsed and validated (so a malformed config still fails loudly) but not otherwise consumed here. */
public class CompilerConfigCallingConvention {
    public String name;
    public String cleanup;
    public List<String> argumentRegisters = new ArrayList<>();
    public List<String> argumentRegistersFloat = new ArrayList<>();
    public String returnRegister;
    public String returnRegisterFloat;
    public int alignment;
    public int shadowStack;
    /** True only for a convention where an integer argument and a float argument advance one shared running position counter (win64's own documented behavior) rather than two independent per-bank counters (SysV, arm64's AAPCS64) -- see the compiler-side `CompilerConfig`'s own copy of this same field for the full reasoning (a real, fixed ABI fact, not derived from the two register lists' lengths). Defaults false when the config omits this key. */
    public boolean sharedArgumentPosition;
}
