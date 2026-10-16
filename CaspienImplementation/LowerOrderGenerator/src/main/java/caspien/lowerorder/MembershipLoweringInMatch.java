package caspien.lowerorder;

import java.util.List;

/** Replacement text plus whether each trailing operand push (left, right) must stay because it was materialized into a temp. */
final class MembershipLoweringInMatch {
    final List<String> lines;
    final boolean keepLeftPush;
    final boolean keepRightPush;
    MembershipLoweringInMatch(List<String> lines, boolean keepLeftPush, boolean keepRightPush) { }
}
