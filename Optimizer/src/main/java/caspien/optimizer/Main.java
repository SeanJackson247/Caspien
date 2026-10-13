package caspien.optimizer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The Optimizer as a stand-alone program (the orchestrator calls {@link Optimizer} directly instead).
 * Reads the whole input file as text, hands it to the library, writes the result as text.
 *
 * Usage: optimizer -i input.txt output.txt   (reads compiler.config in the working directory if present)
 */
public class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException ioe) {
            System.err.println("[io error] " + ioe.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3 || !args[0].equals("-i")) {
            System.err.println("Usage: optimizer -i <input.txt> <output>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        String input = Files.readString(Paths.get(inputPath), StandardCharsets.UTF_8);
        Path configPath = Paths.get("compiler.config");
        String config = Files.exists(configPath) ? Files.readString(configPath, StandardCharsets.UTF_8) : null;

        OptimizerResult result = Optimizer.optimize(input, inputPath, config);
        for (String m : result.messages) {
            System.err.println(m);
        }

        System.out.print(result.hob);
        writeFile(outputPath, result.hob);
        writeFile("output.txt", result.hob);

        System.err.println("\n[info] wrote optimized bytecode to " + outputPath + " and output.txt ("
                + result.hob.lines().count() + " instructions/lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
