package caspien.frontend;

import caspien.bytecode.BytecodeProgram;

import java.util.List;
import java.util.Map;

/**
 * `hob` = the program (null when `failed`); `messages` = the lines a stand-alone run prints to stderr, in order;
 * `program` = the same program as the in-memory BytecodeProgram (null when `failed`; the orchestrator hands it to the Optimizer as it is);
 * `deps` = every source file read; `reports` = optional texts by key: "audit", "fix", "fixHash", "fixBlocks", "viz", "debug".
 */
public final class FrontendResult {
    public final String hob;
    public final BytecodeProgram program;
    public final List<String> messages;
    public final boolean failed;
    public final List<String> deps;
    public final Map<String, String> reports;

    public FrontendResult(String hob, BytecodeProgram program, List<String> messages, boolean failed, List<String> deps, Map<String, String> reports) {
        this.hob = hob;
        this.program = program;
        this.messages = messages;
        this.failed = failed;
        this.deps = deps;
        this.reports = reports;
    }
}
