package caspien.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

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
            config = Files.readString(Paths.get("codegen.config"));
        } catch (IOException e) {
            throw new CodegenException("config", "codegen.config", 0,
                    "required config file 'codegen.config' could not be read: " + e.getMessage());
        }
        String input = Files.readString(Paths.get(inputPath));

        CodegenResult result = Codegen.generate(input, inputPath, config);
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
        Files.writeString(Paths.get(path), content);
    }
}
