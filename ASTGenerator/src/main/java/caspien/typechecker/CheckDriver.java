package caspien.typechecker;

import java.util.ArrayDeque;
import java.util.Deque;

/** Runs CheckFrames on an explicit stack: the replacement for the checker's mutual recursion (see CheckFrame). */
public final class CheckDriver {
    private final TypeChecker hub;

    CheckDriver(TypeChecker hub) {
        this.hub = hub;
    }

    public TypeCheckerModel.TypeInfo run(CheckFrame root) {
        Deque<CheckFrame> stack = new ArrayDeque<>();
        stack.push(root);
        while (true) {
            CheckFrame f = stack.peek();
            if (step(f) == CheckFrame.CALL) {
                stack.push(f.pending);
                f.pending = null;
                continue;
            }
            stack.pop();
            if (stack.isEmpty()) {
                return f.result;
            }
            stack.peek().got = f.result;
            stack.peek().gotFrame = f;
        }
    }

    private int step(CheckFrame f) {
        switch (f.kind) {
            case CheckFrame.EXPR:
                return hub.expressions.stepExpr(f);
            case CheckFrame.COMMA:
                return hub.expressions.stepComma(f);
            case CheckFrame.ARITH:
                return hub.expressions.stepArithmetic(f);
            case CheckFrame.UNARY_MINUS:
                return hub.expressions.stepUnaryMinus(f);
            case CheckFrame.LOGICAL_NOT:
                return hub.expressions.stepLogicalNot(f);
            case CheckFrame.COMPARISON:
                return hub.expressions.stepComparison(f);
            case CheckFrame.LOGICAL:
                return hub.expressions.stepLogical(f);
            case CheckFrame.INCREMENT:
                return hub.expressions.stepIncrement(f);
            case CheckFrame.REQUIRE_MUTABLE:
                return hub.expressions.stepRequireMutable(f);
            case CheckFrame.EXPR_LIST:
                return hub.expressions.stepExprList(f);
            case CheckFrame.STATIC_METHOD_CALL:
                return hub.locksAndProofs.stepStaticMethodCall(f);
            case CheckFrame.INSTANCEOF:
                return hub.locksAndProofs.stepInstanceof(f);
            case CheckFrame.IMPLEMENTS:
                return hub.locksAndProofs.stepImplements(f);
            case CheckFrame.AS:
                return hub.locksAndProofs.stepAs(f);
            case CheckFrame.ARRAY_LITERAL:
                return hub.locksAndProofs.stepArrayLiteral(f);
            case CheckFrame.LOOKUP:
                return hub.locksAndProofs.stepLookup(f);
            case CheckFrame.ASSIGN:
                return hub.expressions.stepAssign(f);
            case CheckFrame.COMPOUND_ASSIGN:
                return hub.expressions.stepCompoundAssign(f);
            case CheckFrame.LET_ASSIGN:
                return hub.statements.stepLetAssign(f);
            case CheckFrame.DOT:
                return hub.accessAndAsync.stepDot(f);
            case CheckFrame.ATOMIC_WRAPPER:
                return hub.accessAndAsync.stepAtomicWrapper(f);
            case CheckFrame.SWAP:
                return hub.accessAndAsync.stepSwap(f);
            case CheckFrame.INSTANTIATE:
                return hub.accessAndAsync.stepInstantiate(f);
            case CheckFrame.NEW:
                return hub.accessAndAsync.stepNew(f);
            case CheckFrame.AWAIT:
                return hub.accessAndAsync.stepAwait(f);
            case CheckFrame.PAR:
                return hub.accessAndAsync.stepPar(f);
            case CheckFrame.IN:
                return hub.stepIn(f);
            case CheckFrame.GUARANTEED_ENUM:
                return hub.stepGuaranteedEnumMembership(f);
            case CheckFrame.WITHIN:
                return hub.stepWithin(f);
            case CheckFrame.ADDRESS_OF:
                return hub.stepAddressOf(f);
            case CheckFrame.MUTABILITY_OF:
                return hub.stepMutabilityOf(f);
            case CheckFrame.RANGE:
                return hub.stepRange(f);
            case CheckFrame.SOME_BUILTIN:
                return hub.calls.stepSomeBuiltin(f);
            case CheckFrame.SIZEOF_BUILTIN:
                return hub.calls.stepSizeofBuiltin(f);
            case CheckFrame.RANGE_BUILTIN:
                return hub.calls.stepRangeBuiltin(f);
            case CheckFrame.DEREF_BUILTIN:
                return hub.calls.stepDerefBuiltin(f);
            case CheckFrame.CLONE_BUILTIN:
                return hub.calls.stepCloneBuiltin(f);
            case CheckFrame.CONVERT_BUILTIN:
                return hub.calls.stepConvertBuiltin(f);
            case CheckFrame.CALL_EXPR:
                return hub.calls.stepCall(f);
            case CheckFrame.EXTERN_CALL:
                return hub.calls.stepExternCall(f);
            case CheckFrame.SLEEP_CALL:
                return hub.calls.stepSleepCall(f);
            case CheckFrame.BUILTIN_CALL:
                return hub.calls.stepBuiltinCall(f);
            case CheckFrame.LEN_BUILTIN:
                return hub.calls.stepLenBuiltin(f);
            case CheckFrame.CALL_BUILTIN:
                return hub.calls.stepCallBuiltin(f);
            case CheckFrame.BITS_BUILTIN:
                return hub.calls.stepBitsBuiltin(f);
            case CheckFrame.MEMCOPY_BUILTIN:
                return hub.calls.stepMemcopyBuiltin(f);
            case CheckFrame.DYN_BUILTIN:
                return hub.calls.stepDynBuiltin(f);
            case CheckFrame.RESIZE_BUILTIN:
                return hub.calls.stepResizeBuiltin(f);
            case CheckFrame.UNSAFE_DYN_WRAPPER:
                return hub.calls.stepUnsafeDynWrapper(f);
            case CheckFrame.METHOD_CALL:
                return hub.calls.stepMethodCall(f);
            case CheckFrame.GENERIC_METHOD_CALL:
                return hub.calls.stepGenericMethodCall(f);
            case CheckFrame.INTERFACE_DISPATCH:
                return hub.calls.stepInterfaceDispatch(f);
            case CheckFrame.LIBRARY_CALL:
                return hub.impls.stepLibraryCall(f);
            case CheckFrame.FUNCTION_BODY:
                return hub.statements.stepFunctionBody(f);
            case CheckFrame.LINES:
                return hub.statements.stepLines(f);
            case CheckFrame.LOOP_BODY:
                return hub.statements.stepLoopBody(f);
            case CheckFrame.LINES_IN_SCOPE:
                return hub.statements.stepLinesInScope(f);
            case CheckFrame.STATEMENT:
                return hub.statements.stepStatement(f);
            case CheckFrame.TRY_BLOCK:
                return hub.statements.stepTryBlock(f);
            case CheckFrame.MATCH_CHAIN:
                return hub.statements.stepMatchChain(f);
            case CheckFrame.ASSUME_COND:
                return hub.statements.stepAssumeCond(f);
            case CheckFrame.ASSUME_FIELD:
                return hub.statements.stepAssumeField(f);
            case CheckFrame.RECURSIVE_LOWER:
                return hub.statements.stepRecursiveLower(f);
            case CheckFrame.IF_CHAIN:
                return hub.controlFlow.stepIfChain(f);
            case CheckFrame.CASE_MATCH:
                return hub.controlFlow.stepCaseMatch(f);
            case CheckFrame.FOR_LOOP:
                return hub.controlFlow.stepForLoop(f);
            case CheckFrame.MATCH_CONDITION:
                return hub.controlFlow.stepMatchCondition(f);
            case CheckFrame.RETURN:
                return hub.controlFlow.stepReturn(f);
            case CheckFrame.THROW:
                return hub.controlFlow.stepThrow(f);
            case CheckFrame.TRY:
                return hub.controlFlow.stepTry(f);
            case CheckFrame.ENUM_FOR_MATCH:
                return hub.controlFlow.stepEnumForMatch(f);
            case CheckFrame.ENUM_MATCH:
                return hub.controlFlow.stepEnumMatch(f);
            case CheckFrame.FLOAT_MATCH:
                return hub.controlFlow.stepFloatMatch(f);
            case CheckFrame.SOME_INDEX_BOUNDS:
                return hub.controlFlow.stepSomeIndexBoundsCondition(f);
            case CheckFrame.LOWER_ENUM_FOR:
                return hub.controlFlow.stepLowerEnumForMatch(f);
            case CheckFrame.LOWER_ENUM_MATCH:
                return hub.controlFlow.stepLowerEnumMatch(f);
            case CheckFrame.SUBST_POLICY:
                return hub.locksAndProofs.stepSubstPolicy(f);
            case CheckFrame.LOCK_MATCH:
                return hub.locksAndProofs.stepLockMatch(f);
            default:
                throw new IllegalStateException("unknown check frame kind " + f.kind);
        }
    }
}
