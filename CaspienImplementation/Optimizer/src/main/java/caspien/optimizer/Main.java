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

    public static void main(String[] args) { }

    private static void run(String[] args) throws IOException { }

    private static void writeFile(String path, String content) throws IOException { }
}
