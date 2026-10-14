package caspien.importresolver;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.List;

/** One file being resolved: its lines, how far it got, the lines it has produced, and the files its current import line still has to bring in. */
final class ImportFrame {
    final List<Token> lines;
    final String currentFilePath;
    final List<Token> result = new ArrayList<>();
    int idx;
    List<ImportJob> jobs;
    int jobIdx;

    ImportFrame(List<Token> lines, String currentFilePath) {
        this.lines = lines;
        this.currentFilePath = currentFilePath;
    }
}
