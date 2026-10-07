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
    /** `bmi2 on`: variable shifts use shlx/shrx (needs a BMI2 CPU: Intel Haswell 2013+, AMD Excavator/Zen+). Off by default. */
    public final boolean bmi2;
    /** `avx on`: scalar float arithmetic on register variables uses the 3-operand VEX forms (vaddsd ...), which need no copy of the first operand (Sandy Bridge 2011+, AMD Bulldozer+). Off by default. */
    public final boolean avx;

    private CodegenConfig(Target target, boolean bmi2, boolean avx) {
        this.target = target;
        this.bmi2 = bmi2;
        this.avx = avx;
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
        boolean bmi2 = false;
        boolean avx = false;
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
            } else if (key.equals("bmi2")) {
                if (value.equalsIgnoreCase("on")) {
                    bmi2 = true;
                } else if (value.equalsIgnoreCase("off")) {
                    bmi2 = false;
                } else {
                    throw new CodegenException("config", path, i + 1, "bmi2 must be 'on' or 'off', got '" + value + "'");
                }
            } else if (key.equals("avx")) {
                if (!value.equalsIgnoreCase("on") && !value.equalsIgnoreCase("off")) {
                    throw new CodegenException("config", path, i + 1, "avx must be 'on' or 'off', got '" + value + "'");
                }
                avx = value.equalsIgnoreCase("on");
            } else if (key.equals("jcc-padding")) {
                // read by the Compiler (it adds an option to the assembler call); Codegen only validates it
                if (!value.equalsIgnoreCase("on") && !value.equalsIgnoreCase("off")) {
                    throw new CodegenException("config", path, i + 1, "jcc-padding must be 'on' or 'off', got '" + value + "'");
                }
            } else {
                throw new CodegenException("config", path, i + 1, "unknown config key '" + key + "'");
            }
        }
        if (target == null) {
            throw new CodegenException("config", path, 0, "missing required 'target' key");
        }
        return new CodegenConfig(target, bmi2, avx);
    }
}
