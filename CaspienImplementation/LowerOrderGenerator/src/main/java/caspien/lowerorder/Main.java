package caspien.lowerorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

    public static void main(String[] args) { }

    private static void run(String[] args) throws IOException { }

    private static void writeFile(String path, String content) throws IOException { }
}
