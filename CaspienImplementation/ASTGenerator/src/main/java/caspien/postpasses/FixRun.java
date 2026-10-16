package caspien.postpasses;

/** What a run found: the text report, and the unsafe blocks that would change ("file\tline" per line, for the per-block fallback). */
public final class FixRun {
    public final String report;
    public final String blocks;

    FixRun(String report, String blocks) { }
}
