package caspien.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * CLI entry point for the (new, just-starting) caspien-codegen stage --
 * "begin building the fourth program... this will generate assembly
 * instructions from the lower order bytecode. The code generator will
 * use a config file as necessary. Current target is x86 windows, but
 * whether windows or linux is the target is down to the config,"
 * confirmed directly.
 *
 * Usage: codegen -i input.txt output.s
 *
 * `input.txt` is expected to be the sibling caspien-lowerordergenerator
 * project's own plain-text output. `codegen.config` (fixed path,
 * resolved relative to the current working directory) selects the
 * target -- see `CodegenConfig`'s own doc comment.
 */
public class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (CodegenException ce) {
            System.err.println(ce.getMessage());
            System.exit(1);
        } catch (IOException ioe) {
            System.err.println("[io error] " + ioe.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3 || !args[0].equals("-i")) {
            System.err.println("Usage: codegen -i <input.txt> <output.s>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        String config;
        try {
            config = Files.readString(Paths.get("codegen.config"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CodegenException("config", "codegen.config", 0,
                    "required config file 'codegen.config' could not be read: " + e.getMessage());
        }
        String input = Files.readString(Paths.get(inputPath), StandardCharsets.UTF_8);

        Codegen.Result result = Codegen.generate(input, inputPath, config);
        for (String msg : result.messages) {
            System.err.println(msg);
        }
        if (result.failed) {
            System.exit(1);
        }
        String assembly = result.asm;

        System.out.print(assembly);
        writeFile(outputPath, assembly);

        System.err.println("\n[info] wrote assembly to " + outputPath + " (" + assembly.lines().count() + " lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
