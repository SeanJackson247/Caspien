package caspien.lowerorder;

import java.util.List;

/** As InMatch.keepLeftPush: the left operand was materialized, so its original bytecode stays. */
final class MembershipLoweringInstanceofMatch {
    final List<String> lines;
    final boolean keepLeftPush;
    MembershipLoweringInstanceofMatch(List<String> lines, boolean keepLeftPush) {
        this.lines = lines;
        this.keepLeftPush = keepLeftPush;
    }
}
