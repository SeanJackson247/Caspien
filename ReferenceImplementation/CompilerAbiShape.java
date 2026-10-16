/** One calling convention's ABI shape -- the four facts that actually matter to real, physical argument marshalling (see validateAbiConsistency's own doc comment for why exactly these four and no others). */
final class CompilerAbiShape {
    final int intRegs;
    final int floatRegs;
    final int shadowStack;
    final boolean sharedPosition;
    final String abiName; // only set on a requiredAbiShapeFor() result, for the error message; null for one parsed off a named convention

    CompilerAbiShape(int intRegs, int floatRegs, int shadowStack, boolean sharedPosition, String abiName) {
        this.intRegs = intRegs;
        this.floatRegs = floatRegs;
        this.shadowStack = shadowStack;
        this.sharedPosition = sharedPosition;
        this.abiName = abiName;
    }

    boolean matches(CompilerAbiShape other) {
        return intRegs == other.intRegs && floatRegs == other.floatRegs
                && shadowStack == other.shadowStack && sharedPosition == other.sharedPosition;
    }

    String describe() {
        return intRegs + " int / " + floatRegs + " float argument register" + (intRegs == 1 && floatRegs == 1 ? "" : "s")
                + ", " + shadowStack + "-byte shadow space, "
                + (sharedPosition ? "shared" : "independent") + " argument-position counting";
    }
}
