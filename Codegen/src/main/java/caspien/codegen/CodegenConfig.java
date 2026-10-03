package caspien.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads `codegen.config` (fixed path, resolved relative to the current
 * working directory, the same convention `compiler.config` already
 * uses on the compiler/lowerordergenerator side) -- required, missing
 * entirely is a fatal error, same as that file.
 *
 * Format is deliberately minimal: one "key value" pair per
 * non-comment, non-blank line ('#' starts a comment). The only key read
 * is "target", one of "windows_gnu" (gcc/mingw-w64, GNU `as`, win64 ABI)
 * or "linux" (SysV ABI). Both emit GAS AT&T syntax. (An Intel/MASM
 * "windows" target existed once; it was removed on purpose -- Windows
 * builds go through gcc/mingw-w64.)
 * Everything this stage needs to know about a target -- assembly
 * syntax, calling-convention register names, whether a call needs 32
 * bytes of shadow space reserved ahead of it -- is decided by which
 * `Target` this resolves to (see that enum), not read as further
 * key/value pairs here; more configurable knobs can be added the same
 * way `compiler.config` grew its own calling-convention table, once
 * this backend actually needs them.
 */
public class CodegenConfig {

    public enum Target {
        WINDOWS_GNU_X64,  // GAS AT&T syntax, win64 ABI -- for gcc/mingw-w64; verified in this sandbox with a real mingw-w64 cross-toolchain + Wine
        LINUX_X64         // GAS AT&T syntax, SysV ABI -- verified in this sandbox with the real as/gcc/ld toolchain
    }

    public final Target target;

    private CodegenConfig(Target target) {
        this.target = target;
    }

    public static CodegenConfig load(String path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(path));
        } catch (IOException e) {
            throw new CodegenException("config", path, 0,
                    "required config file '" + path + "' could not be read: " + e.getMessage());
        }
        Target target = null;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String[] parts = raw.split("\\s+", 2);
            if (parts.length != 2) {
                throw new CodegenException("config", path, i + 1, "expected 'key value', got: " + raw);
            }
            String key = parts[0];
            String value = parts[1].trim();
            if (key.equals("target")) {
                if (value.equalsIgnoreCase("windows")) {
                    throw new CodegenException("config", path, i + 1,
                            "target 'windows' (MASM/Intel syntax) is no longer supported -- use 'windows_gnu' (gcc/mingw-w64)");
                } else if (value.equalsIgnoreCase("windows_gnu") || value.equalsIgnoreCase("windows-gnu") || value.equalsIgnoreCase("mingw")) {
                    target = Target.WINDOWS_GNU_X64;
                } else if (value.equalsIgnoreCase("linux")) {
                    target = Target.LINUX_X64;
                } else {
                    throw new CodegenException("config", path, i + 1,
                            "unknown target '" + value + "' -- expected 'windows_gnu' or 'linux'");
                }
            } else {
                throw new CodegenException("config", path, i + 1, "unknown config key '" + key + "'");
            }
        }
        if (target == null) {
            throw new CodegenException("config", path, 0, "missing required 'target' key");
        }
        return new CodegenConfig(target);
    }
}
