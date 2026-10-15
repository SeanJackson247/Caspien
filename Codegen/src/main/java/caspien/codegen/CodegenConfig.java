package caspien.codegen;

import java.util.List;
import caspien.bytecode.Text;

public class CodegenConfig {
    public final CodegenTarget target;

    public final boolean bmi2;

    public final boolean avx;

    private CodegenConfig(CodegenTarget target, boolean bmi2, boolean avx) {
        this.target = target;
        this.bmi2 = bmi2;
        this.avx = avx;
    }

    public static CodegenConfig parse(String text, String path) {
        List<String> lines = text.lines().toList();
        CodegenTarget target = null;
        boolean bmi2 = false;
        boolean avx = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String[] parts = Text.splitSpace(raw, 2);
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
                    target = CodegenTarget.WINDOWS_GNU_X64;
                } else if (value.equalsIgnoreCase("linux")) {
                    target = CodegenTarget.LINUX_X64;
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
