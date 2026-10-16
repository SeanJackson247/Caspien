package caspien.lowerorder;

/** one candidate construction: the index of its ADDR line, the split point of its two expressions, the ASSIGN index */
final class RangeEndHintSite {
    int addr;
    int split;   // first line of the second expression
    int assign;
    long off;
}
