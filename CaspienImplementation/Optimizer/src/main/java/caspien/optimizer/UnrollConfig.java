package caspien.optimizer;

import java.util.List;

/**
 * Loop-unrolling settings, read from the top-level keys of "compiler.config" (the same file, with the same
 * mirrored copy, the other stages read). No other key of that file is looked at, and the file is optional
 * here: a missing file, or a missing 'loop-unrolling' key, means unrolling is off (this stage never needed a
 * config before).
 *
 *   loop-unrolling: off | conservative | balanced | aggressive      (default off)
 *   loop-unroll-factor: N                 partial unroll: N copies of the body per pass (0 or 1 = never partial)
 *   loop-unroll-full-max-trips: N         fully unroll a loop of at most N known iterations (0 = never full)
 *   loop-unroll-max-body-lines: N         never copy a loop body longer than N bytecode lines
 *   loop-unroll-max-growth: N             at most N added bytecode lines per function, over the whole run
 *   loop-unroll-nested: on | off          a fully unrolled loop whose body holds another loop still gets its induction variable replaced by
 *                                         the literal in each copy (default off): the inner loop's bounds then fold to literals and the
 *                                         usual budgets decide whether it unrolls too; array indexes become constant displacements
 *
 * The preset supplies all four numbers; any of the four keys after it overrides just that number. The four
 * keys do nothing while the preset is off. A malformed value stops the compile (never silently ignored).
 */
public final class UnrollConfig {

    public boolean enabled = false;
    public String preset = "off";
    public int factor = 0;
    public int fullMaxTrips = 0;
    public int maxBodyLines = 0;
    public long maxGrowth = 0;
    /** substitute the induction variable into a body that holds a nested loop (acts only with the preset on; an @unroll loop does it regardless) */
    public boolean nested = false;

    public static UnrollConfig disabled() { return null; }

    /** Loads "compiler.config" from the working directory if it exists; otherwise unrolling is off. */

    public static UnrollConfig parse(List<String> lines, String path) { return null; }

    private void set(int factor, int fullMaxTrips, int maxBodyLines, long maxGrowth) { }

    private static long number(String path, int lineIdx, String key, String val) { return 0L; }

    private static RuntimeException bad(String path, int lineIdx, String msg) { return null; }

    @Override
    public String toString() { return null; }
}
