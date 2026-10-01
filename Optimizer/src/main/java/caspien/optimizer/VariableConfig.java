package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public VariableConfig(boolean elision, boolean shifting) {
        this(elision, shifting, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking) {
        this(elision, shifting, unpacking, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow) {
        this(elision, shifting, unpacking, deadFlow, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, allocReorder, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder, boolean structReorder) {
        this.structReorder = structReorder;
        this.allocReorder = allocReorder;
        this.unusedDecls = unusedDecls;
        this.deadFunctions = deadFunctions;
        this.deadFlow = deadFlow;
        this.elision = elision;
        this.shifting = shifting;
        this.unpacking = unpacking;
    }

    public static VariableConfig disabled() {
        return new VariableConfig(false, false, false, false, false, false, false, false);
    }

    public static VariableConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static VariableConfig parse(List<String> lines, String path) {
        boolean elision = false, shifting = false, unpacking = false, deadFlow = false, deadFunctions = false, unusedDecls = false, allocReorder = false, structReorder = false;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue;
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            if (!key.equals("variable-elision") && !key.equals("variable-shifting") && !key.equals("struct-unpacking") && !key.equals("dead-control-flow-removal") && !key.equals("dead-function-removal") && !key.equals("unused-declaration-removal") && !key.equals("variable-allocation-reordering") && !key.equals("struct-member-reordering")) {
                continue;
            }
            String val = t.substring(colon + 1).trim();
            boolean on;
            if (val.equals("on")) {
                on = true;
            } else if (val.equals("off")) {
                on = false;
            } else {
                throw new RuntimeException(path + ":" + (n + 1) + ": '" + key + "' must be on or off, found '" + val + "'");
            }
            if (key.equals("variable-elision")) elision = on;
            else if (key.equals("variable-shifting")) shifting = on;
            else if (key.equals("struct-unpacking")) unpacking = on;
            else if (key.equals("dead-control-flow-removal")) deadFlow = on;
            else if (key.equals("dead-function-removal")) deadFunctions = on;
            else if (key.equals("unused-declaration-removal")) unusedDecls = on;
            else if (key.equals("variable-allocation-reordering")) allocReorder = on;
            else structReorder = on;
        }
        return new VariableConfig(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, allocReorder, structReorder);
    }

    @Override
    public String toString() {
        return "variable-elision " + (elision ? "on" : "off") + "; variable-shifting " + (shifting ? "on" : "off") + "; struct-unpacking " + (unpacking ? "on" : "off") + "; dead-control-flow-removal " + (deadFlow ? "on" : "off") + "; dead-function-removal " + (deadFunctions ? "on" : "off") + "; unused-declaration-removal " + (unusedDecls ? "on" : "off") + "; variable-allocation-reordering " + (allocReorder ? "on" : "off") + "; struct-member-reordering " + (structReorder ? "on" : "off");
    }
}
