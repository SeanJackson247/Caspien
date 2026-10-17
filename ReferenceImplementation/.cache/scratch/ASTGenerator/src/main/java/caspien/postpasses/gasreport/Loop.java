package caspien.postpasses.gasreport;

import static caspien.postpasses.gasreport.GasReportCore.*;

import java.math.BigInteger;

public final class Loop {
    public int head, end, back;        // label line, exit label line, the unconditional JMP back to the head
    public BigInteger count;           // null = not a literal bound
    public String why;
    public boolean isFor;
    public Val exitCost;               // cost of the paths that leave the loop and continue after it (add the tail); NEG impossible
    public Val retCost = Val.NEG;      // cost of the paths that return/throw/exit from inside the loop (no tail)
    public boolean done, computing;
    public int ownClass;               // the loop's own termination class (not its callees'): 0 bound, FIN run-time `for`, UNB `loop` with an exit, NONT `loop` with none
}
