package caspien.frontend;

import java.util.List;
import java.util.Map;

/**
 * `hob` = the program (null when `failed`); `messages` = the lines a stand-alone run prints to stderr, in order;
 * `deps` = every source file read; `reports` = optional texts by key: "audit", "fix", "fixHash", "fixBlocks", "viz", "debug".
 */
public final class FrontendResult {
    public final String hob;
    public final List<String> messages;
    public final boolean failed;
    public final List<String> deps;
    public final Map<String, String> reports;

    public FrontendResult(String hob, List<String> messages, boolean failed, List<String> deps, Map<String, String> reports) { }
}
