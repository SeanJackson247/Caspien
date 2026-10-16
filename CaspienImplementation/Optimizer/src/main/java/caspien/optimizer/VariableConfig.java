package caspien.optimizer;

import java.util.List;

/**
 * Switches for the two variable passes, read from the top-level keys of "compiler.config":
 *
 *   variable-elision: on | off      (default off)
 *   variable-shifting: on | off     (default off)
 *   struct-unpacking: on | off      (default off)
 *   dead-control-flow-removal: on | off  (default off)
 *   dead-function-removal: on | off  (default off)
 *   unused-declaration-removal: on | off  (default off)
 *   variable-allocation-reordering: on | off  (default off)
 *   struct-member-reordering: on | off  (default off)
 *
 * A missing file or key means off; any other value stops the compile.
 */
public final class VariableConfig {

    public final boolean elision;
    public final boolean shifting;
    public final boolean unpacking;
    public final boolean deadFlow;
    public final boolean deadFunctions;
    public final boolean unusedDecls;
    public final boolean allocReorder;
    public final boolean structReorder;

    public VariableConfig(boolean elision, boolean shifting) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder) { }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder, boolean structReorder) { }

    public static VariableConfig disabled() { return null; }


    public static VariableConfig parse(List<String> lines, String path) { return null; }

    @Override
    public String toString() { return null; }
}
