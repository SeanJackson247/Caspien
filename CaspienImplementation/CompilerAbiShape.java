/** One calling convention's ABI shape -- the four facts that actually matter to real, physical argument marshalling (see validateAbiConsistency's own doc comment for why exactly these four and no others). */
final class CompilerAbiShape {
    final int intRegs;
    final int floatRegs;
    final int shadowStack;
    final boolean sharedPosition;
    final String abiName; // only set on a requiredAbiShapeFor() result, for the error message; null for one parsed off a named convention

    CompilerAbiShape(int intRegs, int floatRegs, int shadowStack, boolean sharedPosition, String abiName) { }

    boolean matches(CompilerAbiShape other) { return false; }

    String describe() { return null; }
}
