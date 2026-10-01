package caspien.lowerorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for the LowerOrderGenerator stage -- see
 * LowerOrderGenerator's own class doc for the pipeline this runs.
 *
 * Usage: lowerordergenerator -i input.txt output.txt
 *
 * `input.txt` is expected to be the sibling caspien-optimizer project's
 * own plain-text output (bytecode that has already reached the shallow
 * pipeline's fixed point) -- this project does not re-run any of that
 * project's own passes itself.
 *
 * AddressLoweringPass reads `compiler.config` (fixed path, resolved
 * relative to the current working directory) for calling-convention
 * data, the same way caspien-compiler's own Main does -- required,
 * missing entirely is a fatal error there, unchanged from before the
 * split.
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
            System.err.println("Usage: lowerordergenerator -i <input.txt> <output>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        List<String> rawLines = Files.readAllLines(Paths.get(inputPath), StandardCharsets.UTF_8);

        BytecodeParser parser = new BytecodeParser();
        List<List<BytecodeToken>> parsed = parser.parse(rawLines, inputPath);

        LowerOrderGenerator generator = new LowerOrderGenerator();
        List<List<BytecodeToken>> result = generator.generate(parsed);

        BytecodeSerializer serializer = new BytecodeSerializer();
        String bytecode = serializer.serialize(result);

        System.out.print(bytecode);

        writeFile(outputPath, bytecode);
        writeFile("output.txt", bytecode);

        System.err.println("\n[info] wrote low-order bytecode to " + outputPath + " and output.txt ("
                + bytecode.lines().count() + " instructions/lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
