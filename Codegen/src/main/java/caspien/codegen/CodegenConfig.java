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
 * Format is deliberately minimal for this first version: one
 * "key value" pair per non-comment, non-blank line ('#' starts a
 * comment). The only key read so far is "target", one of "windows",
 * "windows_gnu", or "linux" -- "current target is x86 windows, but
 * whether windows or linux is the target is down to the config,"
 * confirmed directly; "windows_gnu" was added once it became clear the
 * real Windows toolchain in use is a GNU one (gcc/mingw-w64, assembled
 * with GNU `as`), not MASM/ml64 -- those two need genuinely different
 * assembly *syntax* (GAS AT&T vs MASM Intel) even though they share the
 * exact same win64 *ABI* (argument registers, 32-byte shadow space, no
 * SysV varargs %al convention), which is why they're separate `Target`
 * values rather than a single "windows" with a syntax flag bolted on --
 * every existing "windows" assumption in this backend already conflated
 * the two, so splitting them as distinct targets was the safer change.
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
        WINDOWS_X64,      // MASM/ml64 syntax, win64 ABI -- structurally implemented, still execution-unverified (no MASM toolchain in this sandbox)
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
                    target = Target.WINDOWS_X64;
                } else if (value.equalsIgnoreCase("windows_gnu") || value.equalsIgnoreCase("windows-gnu") || value.equalsIgnoreCase("mingw")) {
                    target = Target.WINDOWS_GNU_X64;
                } else if (value.equalsIgnoreCase("linux")) {
                    target = Target.LINUX_X64;
                } else {
                    throw new CodegenException("config", path, i + 1,
                            "unknown target '" + value + "' -- expected 'windows', 'windows_gnu', or 'linux'");
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
