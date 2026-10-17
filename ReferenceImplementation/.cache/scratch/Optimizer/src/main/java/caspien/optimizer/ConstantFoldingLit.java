package caspien.optimizer;

import java.math.BigInteger;

final class ConstantFoldingLit {
    String type, base;
    BigInteger i;
    double d;      // an f32 is held exactly as a double
    boolean b;
}
