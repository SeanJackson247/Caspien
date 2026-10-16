package caspien.lowerorder;

/** One resolved "PUSH ARGn"/"PUSH FARGn" parameter word's own real fate -- either a real register-table index (`isRegister` true, `registerIndex` valid, `stackOffset` unused) or a real, positive base-pointer-relative stack offset (`isRegister` false) -- decided once, for the whole function, by `resolveArgWordSlots`. */
/** Resolved fate of one "PUSH ARGn"/"PUSH FARGn" word: a register-table index or a positive stack offset (see resolveArgWordSlots). */
final class AddressLoweringArgWordSlot {
    final boolean isRegister;
    final int registerIndex;
    final long stackOffset;

    private AddressLoweringArgWordSlot(boolean isRegister, int registerIndex, long stackOffset) { }

    static AddressLoweringArgWordSlot register(int index) { return null; }

    static AddressLoweringArgWordSlot stack(long offset) { return null; }
}
