package caspien.lowerorder;

enum RegisterFormKind {
    /** a constant (integer, or a float's bit pattern) */ K,
    /** a value still sitting in a frame slot (size 1/2/4/8) */ M,
    /** an address: of a frame slot ("$off") or of a global ("&sym") */ A,
    /** a value held in a temp register, zero-extended to 64 bits */ T
}
