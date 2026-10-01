package caspien.optimizer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for the (now standalone) "shallow methods" optimizer
 * stage -- split out of the original combined caspien-optimizer project
 * on request ("separate the optimizer into two programs -- the shallow
 * methods architecture at the start of the optimizer will be extracted
 * and be the optimizer, and the actual work thats currently been done
 * in the optimizer will just be called the LowerOrderGenerator").
 *
 * This project now contains only the fixed-point, still-bytecode-to-
 * bytecode shallow rewrite passes that ran at the very top of the old
 * combined pipeline (struct unpacking, constant folding, variable
 * elision, variable shifting, dead control flow removal, dead function
 * removal, loop unrolling, function inlining), plus the two
 * placeholder reordering passes (struct member reordering, variable
 * allocation reordering) -- see BytecodeOptimizer's own class doc for
 * why those two ended up here rather than in LowerOrderGenerator.
 *
 * None of the real lowering work (membership lowering, clone
 * generation, drop-glue generation, ARG-to-ALLOC lowering, address
 * lowering) lives in this project any more -- that's
 * caspien-lowerordergenerator's job now, run as a separate program
 * against this program's own plain-text output, the same
 * plain-text-interchange convention caspien-compiler -> (this project)
 * already used.
 *
 * Usage: optimizer -i input.txt output.txt
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

        List<String> rawLines = Files.readAllLines(Paths.get(inputPath), StandardCharsets.UTF_8);

        BytecodeParser parser = new BytecodeParser();
        List<List<BytecodeToken>> parsed = parser.parse(rawLines, inputPath);

        UnrollConfig unrollConfig = UnrollConfig.loadFromWorkingDirectory();
        FoldConfig foldConfig = FoldConfig.loadFromWorkingDirectory();
        VariableConfig variableConfig = VariableConfig.loadFromWorkingDirectory();
        InlineConfig inlineConfig = InlineConfig.loadFromWorkingDirectory();
        System.err.println("[info] optimizer: " + unrollConfig + "; " + foldConfig + "; " + variableConfig + "; " + inlineConfig);
        BytecodeOptimizer optimizer = new BytecodeOptimizer(unrollConfig, foldConfig, variableConfig, inlineConfig);
        List<List<BytecodeToken>> result = optimizer.optimize(parsed);

        BytecodeSerializer serializer = new BytecodeSerializer();
        String bytecode = serializer.serialize(result);

        System.out.print(bytecode);

        writeFile(outputPath, bytecode);
        writeFile("output.txt", bytecode);

        System.err.println("\n[info] wrote optimized bytecode to " + outputPath + " and output.txt ("
                + bytecode.lines().count() + " instructions/lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
