package caspien.bytecodeemitter;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

/** Runs EmitFrames on an explicit stack: the replacement for the emitter's mutual recursion (see EmitFrame). */
final class EmitDriver {
    private final BytecodeEmitter hub;

    EmitDriver(BytecodeEmitter hub) {
        this.hub = hub;
    }

    void run(EmitFrame root) {
        Deque<EmitFrame> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            EmitFrame f = stack.peek();
            int r = step(f);
            if (r == EmitFrame.CALL) {
                stack.push(f.pending);
                f.pending = null;
            } else if (r == EmitFrame.TAIL) {
                stack.pop();
                stack.push(f.pending);
                f.pending = null;
            } else {
                stack.pop();
                if (!stack.isEmpty()) {
                    stack.peek().gotFrame = f;
                }
            }
        }
    }

    private int step(EmitFrame f) {
        switch (f.kind) {
            case EmitFrame.SCRIPT:
                return stepScript(f);
            case EmitFrame.EXPR:
                return hub.statements.stepExpr(f);
            case EmitFrame.ARRAY_LITERAL:
                return hub.statements.stepArrayLiteral(f);
            case EmitFrame.TRY_GUARDED:
                return hub.core.stepTryGuardedCall(f);
            case EmitFrame.ASSIGN:
                return hub.operations.stepAssign(f);
            case EmitFrame.DESTRUCT_OLD:
                return hub.operations.stepDestructOld(f);
            case EmitFrame.ASSIGN_TARGET:
                return hub.statements.stepAssignTarget(f);
            case EmitFrame.RVO_ASSIGN:
                return hub.operations.stepRvoAssign(f);
            case EmitFrame.NEW_FROM_RVO:
                return hub.stepNewFromStructRvoCall(f);
            case EmitFrame.NEW:
                return hub.stepNew(f);
            case EmitFrame.INSTANTIATE:
                return hub.stepInstantiate(f);
            case EmitFrame.OWNS_TEMP:
                return hub.stepOwnsTemporary(f);
            case EmitFrame.CLONE:
                return hub.operations.stepClone(f);
            case EmitFrame.DYN:
                return hub.operations.stepDyn(f);
            case EmitFrame.RESIZE:
                return hub.operations.stepResize(f);
            case EmitFrame.ASYNC:
                return hub.operations.stepAsyncCall(f);
            case EmitFrame.ASSUME_RESOLVES:
                return hub.statements.stepAssumeResolves(f);
            case EmitFrame.INLINE_NULLOUT:
                return hub.statements.stepInlineNullOut(f);
            case EmitFrame.SHORT_CIRCUIT:
                return hub.operations.stepShortCircuit(f);
            case EmitFrame.CALL_SEQUENCE:
                return hub.operations.stepCallSequence(f);
            case EmitFrame.ARG_TRANSFER:
                return hub.operations.stepArgTransfer(f);
            case EmitFrame.ARG_WORD:
                return hub.operations.stepArgWord(f);
            case EmitFrame.BLOCK:
                return hub.core.stepBlock(f);
            case EmitFrame.IF_CHAIN:
                return hub.statements.stepIfChain(f);
            case EmitFrame.MATCH_CHAIN:
                return hub.statements.stepMatchChain(f);
            case EmitFrame.MATCH_COND:
                return hub.statements.stepMatchCondition(f);
            case EmitFrame.LOOP:
                return hub.statements.stepLoop(f);
            case EmitFrame.FOR_LOOP:
                return hub.statements.stepForLoop(f);
            case EmitFrame.DESTRUCT_LIST:
                return hub.core.stepDestructList(f);
            case EmitFrame.UNLOCK_LIST:
                return hub.core.stepUnlockList(f);
            default:
                throw new IllegalStateException("unknown emit frame kind " + f.kind);
        }
    }

    /** A script: its lines and nested emissions in order; `i` is the next op. */
    private int stepScript(EmitFrame f) {
        while (f.i < f.ops.size()) {
            EmitOp op = f.ops.get(f.i++);
            switch (op.kind) {
                case EmitOp.LINE:
                    hub.core.line(op.text);
                    break;
                case EmitOp.EXPR:
                    return f.callExpr(op.tok);
                case EmitOp.TARGET:
                    return f.callTarget(op.tok);
                case EmitOp.NULLOUT:
                    return f.call(hub.statements.nullOutFrame(op.tok));
                case EmitOp.REQUIRE:
                    hub.core.requireGhostTableFunctionPresent(op.text, op.tok);
                    break;
                case EmitOp.DESTRUCT: {
                    EmitFrame destructs = hub.core.destructListFrame(op.names, op.tok);
                    if (destructs != null) {
                        return f.call(destructs);
                    }
                    break;
                }
                case EmitOp.UNLOCK: {
                    EmitFrame unlocks = hub.core.unlockListFrame(op.toks, op.tok);
                    if (unlocks != null) {
                        return f.call(unlocks);
                    }
                    break;
                }
                case EmitOp.BLOCK:
                    return f.call(hub.core.blockFrame(op.toks, op.flag));
                case EmitOp.RVO_FORWARD:
                    return f.call(hub.operations.rvoCallFrame(op.tok, op.text, Collections.singletonList("PUSH $ret_dest " + op.text)));
                default:
                    throw new IllegalStateException("unknown emit op " + op.kind);
            }
        }
        return EmitFrame.DONE;
    }
}
