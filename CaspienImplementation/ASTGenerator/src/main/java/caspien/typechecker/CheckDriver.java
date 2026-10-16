package caspien.typechecker;

import java.util.ArrayDeque;
import java.util.Deque;

/** Runs CheckFrames on an explicit stack: the replacement for the checker's mutual recursion (see CheckFrame). */
public final class CheckDriver {
    private final TypeChecker hub;

    CheckDriver(TypeChecker hub) { }

    public TypeInfo run(CheckFrame root) { return null; }

    private int step(CheckFrame f) { return 0; }
}
