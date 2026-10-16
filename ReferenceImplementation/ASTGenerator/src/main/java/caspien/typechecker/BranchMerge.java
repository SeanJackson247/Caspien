package caspien.typechecker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Move-tracking state shared by the cases of one match (the cases are checked one after the other). */
final class BranchMerge {
    final Set<String> preState;
    final Set<String> union = new HashSet<>();
    final List<Set<String>> survivors = new ArrayList<>();
    Set<String> branchMoved;
    Scope branchScope;

    BranchMerge(Set<String> preState) {
        this.preState = preState;
    }
}
