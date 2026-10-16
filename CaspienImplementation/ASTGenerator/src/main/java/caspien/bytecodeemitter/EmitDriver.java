package caspien.bytecodeemitter;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

/** Runs EmitFrames on an explicit stack: the replacement for the emitter's mutual recursion (see EmitFrame). */
final class EmitDriver {
    private final BytecodeEmitter hub;

    EmitDriver(BytecodeEmitter hub) { }

    void run(EmitFrame root) { }

    private int step(EmitFrame f) { return 0; }

    /** A script: its lines and nested emissions in order; `i` is the next op. */
    private int stepScript(EmitFrame f) { return 0; }
}
