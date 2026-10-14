package caspien.importresolver;

import caspien.token.Token;
import java.nio.file.Path;

/** One file an import line brings in (a wildcard import makes one per file). */
final class ImportJob {
    final Token importTok;
    final String currentFilePath;
    final Path importerDir;
    final String importPath;

    ImportJob(Token importTok, String currentFilePath, Path importerDir, String importPath) {
        this.importTok = importTok;
        this.currentFilePath = currentFilePath;
        this.importerDir = importerDir;
        this.importPath = importPath;
    }
}
