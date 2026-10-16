package caspien;

import caspien.frontend.Frontend;
import caspien.frontend.FrontendResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Stand-alone front end: `java caspien.Main -i main.caspien out.txt [--fs-report] [--audit] [--viz]`, run from the ASTGenerator folder.
 * Reads compiler.config (required) and fs.config / platform.config / trace.config (optional) from the working directory, calls
 * {@link caspien.frontend.Frontend#compile}, and prints / writes the results. The environment variables CASPIEN_AUDIT_FILE, CASPIEN_VIZ_FILE and
 * CASPIEN_FIX_* name where reports go and pass the --fix options (the orchestrator calls the library directly and does not use them).
 */
public class Main {

    public static void main(String[] args) { }

    private static String optionalFile(String name) throws IOException { return null; }

    private static void put(Map<String, String> options, String key, String value) { }

    private static void run(String[] args) throws IOException { }

    private static void toFileOrStderr(String target, String text) throws IOException { }

    private static void writeFile(String path, String content) throws IOException { }
}
