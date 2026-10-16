package caspien.importresolver;
import caspien.parser.SourceParser;
import caspien.config.CompilerException;
import caspien.config.DepsLog;
import caspien.config.FsPolicy;
import caspien.token.Token;
import caspien.token.TokenType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 5: resolves import statements by replacing each 'import "path"'
 * line with the fully compiled (lexed, parsed, RPN-converted,
 * tree-built) top-level statements of the file it names, recursively.
 *
 * Path resolution: import paths are resolved relative to the directory
 * of the file *containing* the import statement, not the compiler's
 * working directory. This is the standard, least-surprising choice
 * (matches C's "..." includes, Python relative imports) and keeps a
 * file's imports meaningful no matter where the compiler is invoked
 * from.
 *
 * Diamond imports: a file is only ever compiled and spliced in once
 * per whole compilation, even if multiple other files import it (like
 * C's #pragma once, but the default here rather than opt-in) --
 * otherwise reimporting the same file from two places would produce
 * duplicate struct/func/enum definitions and fail the (not yet built)
 * "declared once" logic check for no good reason. A second import of
 * an already-resolved file is simply dropped, silently.
 *
 * Wildcard: 'import "dir/*"' imports every .caspien file in dir (see resolveWildcard).
 *
 * Circular imports are a hard error: the chain of files currently
 * being resolved is tracked, and importing a file already on that
 * chain fails with the full cycle shown.
 *
 * Only the file root is scanned for import statements -- imports are a
 * top-level-only construct in this language (there's no grammar
 * position for them inside a function/block), enforced earlier by
 * Parser.gatherKeywordBlocks' requireRoot check.
 */
public class ImportResolver {

    private final Set<String> resolvedFiles = new LinkedHashSet<>();
    private final List<String> resolutionStack = new ArrayList<>();

    /**
     * Resolves imports in an already-compiled (through tree-building) file's top-level statements, in place.
     * An explicit stack of files being resolved (no recursion): an import line starts a frame for the imported
     * file, and when that frame is done its lines are spliced into its parent's result at the import's place.
     */
    public List<Token> resolve(List<Token> lines, String currentFilePath) { return null; }

    private ImportFrame enter(List<Token> lines, String currentFilePath) { return null; }

    /** The files one import line brings in, in order: one for a plain import, one per .caspien file for a wildcard. */
    private List<ImportJob> importJobs(Token importTok, String currentFilePath) { return null; }

    /**
     * 'import "dir/*"': imports every .caspien file directly inside dir (not subfolders), in file-name order, exactly as if each
     * had its own import line. The folder must exist and hold at least one .caspien file (a typo must not import nothing).
     * The folder itself is recorded as a dependency so that adding or removing a file invalidates the build cache.
     */
    private List<ImportJob> wildcardJobs(Token importTok, String currentFilePath, Path importerDir, String importPath) { return null; }

    /** One file import: null when the file is already resolved elsewhere (dropped), else a new frame for its lines. */
    private ImportFrame startFile(ImportJob job) { return null; }

    private String canonicalize(String path) { return null; }

    private String cycleDescription(String repeatedFile) { return null; }
}
