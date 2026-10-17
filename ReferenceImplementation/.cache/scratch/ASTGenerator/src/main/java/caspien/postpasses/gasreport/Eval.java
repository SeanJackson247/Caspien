package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import caspien.bytecode.Text;

/** Per-function analysis state. */
public final class Eval {
    private final GasReportCore core;
    public final Fn f;
    public final int mt;                                          // GAS, HEAP, COUNT or LIVE
    public final Map<Integer, Loop> loops = new HashMap<>();      // head line -> loop
    public final Map<Long, Val[]> memo = new HashMap<>();         // (context, mode) -> per-line memo

    public final Map<Integer, Long> cred;                         // LIVE: the credits this walk uses (the function's own, or those of a pad analysed from one entry edge)

    public Eval(GasReportCore core, Fn f, int mt) {
        this(core, f, mt, f.credit);
    }

    public Eval(GasReportCore core, Fn f, int mt, Map<Integer, Long> cred) {
        this.core = core;
        this.f = f;
        this.mt = mt;
        this.cred = cred;
        for (Map.Entry<String, Integer> e : f.labels.entrySet()) {
            String lab = e.getKey();
            String kind = lab.startsWith("@for_") && !lab.startsWith("@for_end_") && !lab.startsWith("@for_cont_") ? "for"
                    : lab.startsWith("@loop_") && !lab.startsWith("@loop_end_") ? "loop" : null;
            if (kind == null) {
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(lab.substring(kind.length() + 2));
            } catch (NumberFormatException ex) {
                continue;
            }
            Integer endLine = f.labels.get("@" + kind + "_end_" + (n + 1));
            if (endLine == null) {
                continue;
            }
            Loop lp = new Loop();
            lp.head = e.getValue();
            lp.end = endLine;
            lp.back = -1;
            lp.isFor = kind.equals("for");
            for (int i = endLine - 1; i > lp.head; i--) {
                if (core.ln[i].equals("JMP " + lab)) {
                    lp.back = i;
                    break;
                }
            }
            if (lp.back < 0) {
                continue;
            }
            Long spin = lp.isFor ? null : SPIN.get(f.name + "|" + lab);
            if (!lp.isFor) {
                lp.why = null;      // classified in solveLoop (needs to know whether the body can leave the loop)
                if (spin != null && spin > 0) {
                    lp.count = BigInteger.valueOf(spin).add(BigInteger.ONE);     // retries 1..limit, then the give-up test breaks on the next pass
                } else if (spin != null) {
                    String at = POS.get(f.name + "|" + lab);
                    lp.why = R(FIN, "`match @lock` retries a number of times only known at run time (`default(n)` with a run-time n)" + (at != null ? " (" + at + ")" : ""));
                }
            } else {
                String type = null;
                for (int i = lp.head + 1; i < lp.back && i < lp.head + 6; i++) {
                    if (core.ln[i].startsWith("PUSH $for_range_")) {
                        String[] p = Text.split(core.ln[i], ' ', 0);
                        type = p[p.length - 1];
                        break;
                    }
                }
                BigInteger c = null;
                if (type != null) {
                    int a = type.indexOf("range(");
                    int comma = type.lastIndexOf(',');
                    if (a >= 0 && comma > a && type.endsWith(")")) {
                        String lo = type.substring(a + 6, comma), hi = type.substring(comma + 1, type.length() - 1);
                        if (Text.isSignedDigits(lo) && Text.isSignedDigits(hi)) {
                            c = new BigInteger(hi).subtract(new BigInteger(lo)).max(BigInteger.ZERO);
                        }
                    }
                }
                if (c == null && type != null && f.args != null) {
                    c = core.hub.rangeCount(f, lp, type);
                }
                lp.count = c;
                if (c == null) {
                    String dep = null;
                    if (type != null && type.indexOf(',') > 0 && type.endsWith(")")) {
                        String hi = Text.stripMutPrefix(type.substring(type.lastIndexOf(',') + 1, type.length() - 1));
                        if (Text.isDottedName(hi)) {
                            dep = hi;
                        }
                    }
                    String at = POS.get(f.name + "|" + lab);
                    lp.why = R(FIN, "`for` runs a number of times only known at run time" + (dep != null ? ": depends on `" + dep + "`" : " (" + (type == null ? lab : type) + ")") + (at != null ? " (" + at + ")" : ""));
                }
            }
            loops.put(lp.head, lp);
        }
    }

    public void solveLoop(Loop lp) {
        runWalk(solveFrame(lp));
    }

    private GasWalkFrame solveFrame(Loop lp) {
        GasWalkFrame fr = new GasWalkFrame(GasWalkFrame.SOLVE_LOOP, this);
        fr.lp = lp;
        return fr;
    }

    /** Costs of a whole loop statement: exitCost (normal exit or `break`; the code after the loop is added by the caller) and retCost (a `return` inside). */
    private GasWalkFrame stepSolveLoop(GasWalkFrame fr) {
        Loop lp = fr.lp;
        switch (fr.phase) {
            case 0:
                if (lp.done || lp.computing) {
                    return fr.finish(null);
                }
                lp.computing = true;
                fr.phase = 1;
                return walkFrame(lp.head + 1, lp.head, CONT);   // header + body up to the back edge
            case 1:
                fr.cont = fr.got;
                fr.phase = 2;
                return walkFrame(lp.head + 1, lp.head, BRK);    // ... up to a `break` / the final header test
            case 2:
                fr.brk = fr.got;
                fr.phase = 3;
                return walkFrame(lp.head + 1, lp.head, RET);    // ... up to a return/throw/exit
            default:
                break;
        }
        Val cont = fr.cont, brk = fr.brk, ret = fr.got;
        Val hdr = lp.isFor ? headerCost(lp) : Val.ZERO;
        BigInteger n;
        if (lp.count != null) {
            n = lp.count;
            lp.ownClass = 0;
        } else {
            // a count or a live-heap figure does not depend on the bound of a loop that does not grow it
            String why = lp.why;
            if (!lp.isFor && why == null) {
                boolean exit = !brk.neg || !ret.neg;     // a reachable `break`, `return` or `throw` (also from a call that can throw) inside the body
                why = exit ? R(UNB, "`loop` with no static bound, it can leave through `break`/`return`/`throw` (" + labelOf(lp) + ")")
                        : R(core.hub.runsFirst(f, lp.head) ? NONT : DIV, "`loop` with no `break`, `return` or `throw` that leaves it (" + labelOf(lp) + ")");
            }
            lp.ownClass = why.charAt(0) - '0';
            if (!lp.isFor && lp.why == null) {
                lp.ownClass = (!brk.neg || !ret.neg) ? UNB : NONT;
            }
            if (mt == GAS || (!cont.neg && cont.n.signum() > 0)) {
                f.ub[mt].add(why);
            }
            n = BigInteger.ONE;        // lower bound: one iteration
        }
        if (n.signum() == 0) {
            lp.exitCost = hdr;
        } else {
            BigInteger nm1 = n.subtract(BigInteger.ONE);
            Val c0 = cont.neg ? Val.ZERO : cont;
            Val full = lp.isFor || !cont.neg ? Val.seq(Val.rep(n, c0), hdr) : Val.NEG;
            if (!lp.isFor) {
                full = brk.neg ? cont : Val.NEG;   // a `loop` only ends through `break`
            }
            lp.exitCost = Val.alt(full, brk.neg ? Val.NEG : Val.seq(Val.rep(nm1, c0), brk));
            if (lp.exitCost.neg) {
                lp.exitCost = c0;
            }
            lp.retCost = ret.neg ? Val.NEG : Val.seq(Val.rep(nm1, c0), ret);
        }
        lp.done = true;
        lp.computing = false;
        return fr.finish(null);
    }

    public String labelOf(Loop lp) {
        String label = core.ln[lp.head].substring(0, core.ln[lp.head].length() - 1);
        String pos = POS.get(f.name + "|" + label);
        return pos != null ? pos : label;      // source file:line when the front end told us, else the HOB label
    }

    public Val headerCost(Loop lp) {
        Val c = Val.ZERO;
        for (int i = lp.head + 1; i < lp.back; i++) {
            String o = op(core.ln[i]);
            c = Val.seq(c, lineCost(i));
            if (o.equals("JMP") && core.ln[i - 1].equals("CMP")) {
                break;
            }
        }
        return c;
    }

    public Val walk(int i, int ctx, int mode) {
        return runWalk(walkFrame(i, ctx, mode));
    }

    private GasWalkFrame walkFrame(int start, int ctx, int mode) {
        GasWalkFrame fr = new GasWalkFrame(GasWalkFrame.WALK_MEMO, this);
        long memoKey = ((long) ctx << 3) | mode;
        Val[] memoRow = memo.get(memoKey);
        if (memoRow == null) {
            memoRow = new Val[core.ln.length + 1];
            memo.put(memoKey, memoRow);
        }
        fr.m = memoRow;
        fr.i = start;
        fr.ctx = ctx;
        fr.mode = mode;
        fr.run = new ArrayList<>();
        return fr;
    }

    private GasWalkFrame blockFrame(Loop lp, int ctx, int mode) {
        GasWalkFrame fr = new GasWalkFrame(GasWalkFrame.BLOCK, this);
        fr.lp = lp;
        fr.ctx = ctx;
        fr.mode = mode;
        return fr;
    }

    private GasWalkFrame padFrame(int edge, int target, int ctx, int mode) {
        GasWalkFrame fr = new GasWalkFrame(GasWalkFrame.PAD_TARGET, this);
        fr.edge = edge;
        fr.target = target;
        fr.ctx = ctx;
        fr.mode = mode;
        return fr;
    }

    private GasWalkFrame targetFrame(int target, int ctx, int mode) {
        GasWalkFrame fr = new GasWalkFrame(GasWalkFrame.WALK_TARGET, this);
        fr.target = target;
        fr.ctx = ctx;
        fr.mode = mode;
        return fr;
    }

    /** One step of a frame owned by this Eval: returns a child frame to run first, or null when the frame has finished (its answer is in `result`). */
    public GasWalkFrame step(GasWalkFrame fr) {
        switch (fr.kind) {
            case GasWalkFrame.WALK_MEMO: return stepWalkMemo(fr);
            case GasWalkFrame.BLOCK: return stepBlock(fr);
            case GasWalkFrame.SOLVE_LOOP: return stepSolveLoop(fr);
            case GasWalkFrame.PAD_TARGET: return stepPadTarget(fr);
            default: return stepWalkTarget(fr);
        }
    }

    /** A whole inner loop met while walking: its exit continues after the loop; a return inside it is a terminal path. */
    private GasWalkFrame stepBlock(GasWalkFrame fr) {
        Loop lp = fr.lp;
        switch (fr.phase) {
            case 0:
                fr.phase = 1;
                return solveFrame(lp);
            case 1:
                fr.phase = 2;
                return walkFrame(lp.end, fr.ctx, fr.mode);
            default: {
                Val r = Val.seq(lp.exitCost, fr.got);
                if (!lp.retCost.neg && (fr.ctx < 0 || fr.mode == RET)) {
                    r = Val.alt(r, lp.retCost);
                }
                return fr.finish(r);
            }
        }
    }

    /** The way into a catch block / landing pad from the edge at line `edge`: with the credits of that pad analysed from the state at that edge. */
    private GasWalkFrame stepPadTarget(GasWalkFrame fr) {
        if (fr.phase == 0) {
            Map<Integer, Long> cm = mt == LIVE ? f.edgeCredit.get(fr.edge) : null;
            fr.phase = 1;
            if (cm == null || cm == cred) {
                return targetFrame(fr.target, fr.ctx, fr.mode);
            }
            String padSig = cm.toString();
            Eval e = f.padEvals.get(padSig);
            if (e == null) {
                e = new Eval(core, f, mt, cm);
                f.padEvals.put(padSig, e);
            }
            return e.targetFrame(fr.target, fr.ctx, fr.mode);
        }
        Val v = fr.got;
        Integer call = mt == LIVE ? f.edgeCall.get(fr.edge) : null;
        if (call != null && !v.neg) {
            // the callee that unwinds also frees the owned arguments moved into it
            v = Val.seq(new Val(BigInteger.valueOf(-cred.getOrDefault(call, 0L)), BigInteger.ZERO, false), v);
        }
        return fr.finish(v);
    }

    private GasWalkFrame stepWalkTarget(GasWalkFrame fr) {
        int target = fr.target, ctx = fr.ctx, mode = fr.mode;
        Val term = terminal(target, ctx, mode);
        if (term != null) {
            return fr.finish(term);
        }
        if (ctx >= 0 && target == loops.get(ctx).head) {
            return fr.finish(mode == CONT ? Val.ZERO : Val.NEG);     // `continue`: the iteration is over
        }
        Loop lp = loops.get(target);
        if (lp != null && target != ctx) {
            return fr.tailCall(blockFrame(lp, ctx, mode));
        }
        return fr.tailCall(walkFrame(target, ctx, mode));
    }

    /** Walks forward from fr.i collecting a run of plain lines; a branch, a label or a loop asks for child walks, then the run is costed backwards into the memo. */
    private GasWalkFrame stepWalkMemo(GasWalkFrame fr) {
        List<Integer> run = fr.run;
        int ctx = fr.ctx, mode = fr.mode;
        int i = fr.i;
        Val tail = null;
        switch (fr.phase) {
            case 0:
                break;
            case 1:     // an inner loop block was walked
                tail = fr.got;
                fr.phase = 9;
                break;
            case 2: {   // JMP: the target is walked
                fr.a = fr.got;
                boolean cond = i > 0 && core.ln[i - 1].equals("CMP");
                if (cond) {
                    fr.phase = 3;
                    return walkFrame(i + 1, ctx, mode);
                }
                run.add(i);
                tail = Val.alt(fr.a, Val.NEG);
                fr.phase = 9;
                break;
            }
            case 3:     // JMP: the fall-through is walked
                run.add(i);
                tail = Val.alt(fr.a, fr.got);
                fr.phase = 9;
                break;
            case 4:     // PUSH_LABEL: the pad is walked
                fr.a = fr.got;
                fr.phase = 5;
                return walkFrame(i + 1, ctx, mode);
            default:    // 5: PUSH_LABEL: the continuation is walked
                run.add(i);
                tail = Val.alt(fr.a, fr.got);
                fr.phase = 9;
                break;
        }
        boolean asm = fr.asm;
        while (fr.phase != 9) {
            if (i >= f.end) {
                tail = ctx < 0 ? Val.ZERO : Val.NEG;
                break;
            }
            if (fr.m[i] != null) {
                tail = fr.m[i];
                break;
            }
            Val term = terminal(i, ctx, mode);
            if (term != null) {
                tail = term;
                break;
            }
            String line = core.ln[i];
            String o = op(line);
            if (asm) {
                if (o.equals("ASM_END")) {
                    asm = false;
                } else {
                    run.add(i);
                    i++;
                    continue;
                }
            }
            if (isLabel(line)) {
                Loop lp = loops.get(i);
                if (lp != null && i != ctx) {
                    fr.i = i;
                    fr.asm = asm;
                    fr.phase = 1;
                    return blockFrame(lp, ctx, mode);
                }
                run.add(i);
                i++;
                continue;
            }
            if (o.equals("ASM_START")) {
                asm = true;
                f.notModelled.add("inline assembly");
                run.add(i);
                i++;
                continue;
            }
            if (o.equals("JMP")) {
                Integer t = f.labels.get(arg(line));
                boolean cond = i > 0 && core.ln[i - 1].equals("CMP");
                if (t == null) {
                    run.add(i);
                    i++;
                    continue;
                }
                fr.i = i;
                fr.asm = asm;
                fr.phase = 2;
                return !cond && arg(line).startsWith("@catch_") ? padFrame(i, t, ctx, mode) : targetFrame(t, ctx, mode);
            }
            if (o.equals("RET") || o.equals("THROW") || o.equals("EXIT") || o.equals("EXIT_THREAD")) {
                run.add(i);
                tail = ctx < 0 || mode == RET ? (mt == LIVE && !o.equals("RET") ? Val.NONET : Val.ZERO) : Val.NEG;
                break;
            }
            if (o.equals("PUSH_LABEL")) {
                Integer t = f.labels.get(arg(line));
                if (t != null && !arg(line).startsWith("@for") && !arg(line).startsWith("@loop")) {
                    // a call after this point may unwind to that catch block / landing pad: its cost is an alternative continuation
                    fr.i = i;
                    fr.asm = asm;
                    fr.phase = 4;
                    return padFrame(i, t, ctx, mode);
                }
            }
            run.add(i);
            i++;
        }
        Val acc = tail;
        for (int k = run.size() - 1; k >= 0; k--) {
            int idx = run.get(k);
            acc = Val.seq(lineCost(idx), acc);
            fr.m[idx] = acc;
        }
        return fr.finish(run.isEmpty() ? tail : fr.m[run.get(0)]);
    }

    /** Terminal value of reaching line i in a loop context, or null when i is an ordinary line. */
    public Val terminal(int i, int ctx, int mode) {
        if (ctx < 0) {
            return null;
        }
        Loop cl = loops.get(ctx);
        if (i == cl.back) {
            return mode == CONT ? lineCost(i) : Val.NEG;
        }
        if (i == cl.end) {
            return mode == BRK ? Val.ZERO : Val.NEG;
        }
        return null;
    }









    public Val lineCost(int idx) {
        String line = core.ln[idx];
        String o = op(line);
        if (isLabel(line) && !(mt == LIVE && f.okLabelAlloc.containsKey(idx))) {
            return Val.ZERO;
        }
        Val c;
        if (mt == LIVE) {
            Integer allocAt = f.okLabelAlloc.get(idx);
            if (isLabel(line) && allocAt == null) {
                return Val.ZERO;
            }
            int ai = allocAt != null ? allocAt : idx;
            String ao = op(core.ln[ai]);
            if (isAlloc(ao) && (allocAt != null || !f.okLabelAlloc.containsValue(ai))) {
                // charged on the success edge (the label after the null check) when there is one: a failed allocation holds no block
                long cr = cred.getOrDefault(ai, 0L);
                BigInteger b = core.hub.allocBytes(ai, f);
                // the new block exists before the old one of a `resize` is released: the peak sees both (every resize may move), the net only the difference
                c = new Val(b.subtract(BigInteger.valueOf(cr)), b, false);
            } else if (o.equals("GT_DESTRUCT")) {
                c = new Val(BigInteger.valueOf(-cred.getOrDefault(idx, 0L)), BigInteger.ZERO, false);
            } else {
                c = Val.ZERO;
            }
        } else {
            c = Val.of(mt == GAS ? BigInteger.valueOf(cost(o)) : !isAlloc(o) ? BigInteger.ZERO : mt == COUNT ? BigInteger.ONE : core.hub.allocBytes(idx, f));
        }
        switch (o) {
            case "CALL": {
                f.calls.add(arg(line));
                Fn cf = f.calleeAt.get(idx);
                if (cf == null) {
                    cf = core.fns.get(arg(line));
                }
                if (cf != null && cf.val[mt] != null) {
                    if (mt == LIVE) {
                        // the callee's net is what it leaves live; the arguments it frees (known by the caller) are credited here
                        BigInteger cr = BigInteger.valueOf(cred.getOrDefault(idx, 0L));
                        return new Val(cf.val[mt].subtract(cr), cf.livePeak.max(cf.val[mt].subtract(cr)), false);
                    }
                    return Val.seq(c, Val.of(cf.val[mt]));
                }
                break;
            }
            case "EXTERN_CALL":
                f.notModelled.add("external call " + arg(line));
                break;
            case "MEMCOPY":
                f.notModelled.add("`memcopy` (size not counted)");
                break;
            case "SLEEP": case "YIELD": case "STACK_LOCK":
                f.notModelled.add(o.toLowerCase() + " (waits)");
                break;
            case "ATOMIC_PUSH":
                f.notModelled.add("thread operation");
                break;
            case "INVOKE":
                f.ub[mt].add(R(UNK, "indirect call (INVOKE), the callee is not known"));
                break;
            default:
                break;
        }
        return c;
    }
}
