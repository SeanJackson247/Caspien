package caspien;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    /** Resolves imports in an already-compiled (through tree-building) file's top-level statements, in place. */
    public List<Token> resolve(List<Token> lines, String currentFilePath) {
        String canonical = canonicalize(currentFilePath);
        resolutionStack.add(canonical);
        resolvedFiles.add(canonical);

        List<Token> result = new ArrayList<>();
        for (Token lineTok : lines) {
            List<Token> tokens = lineTok.childs;
            if (tokens.size() == 1 && tokens.get(0).type == TokenType.KEYWORD
                    && tokens.get(0).text.equals("import")) {
                result.addAll(resolveImport(tokens.get(0), currentFilePath));
            } else {
                result.add(lineTok);
            }
        }

        resolutionStack.remove(resolutionStack.size() - 1);
        return result;
    }

    private List<Token> resolveImport(Token importTok, String currentFilePath) {
        Token pathTok = importTok.childs.get(0);
        String importPath = pathTok.literalValue != null ? pathTok.literalValue : pathTok.text;

        Path importerDir = Paths.get(currentFilePath).toAbsolutePath().getParent();
        Path resolvedPath = (importerDir != null ? importerDir : Paths.get("."))
                .resolve(importPath).normalize();
        String resolvedPathStr = resolvedPath.toString();
        String canonical = canonicalize(resolvedPathStr);

        if (resolutionStack.contains(canonical)) {
            throw new CompilerException("import", currentFilePath, importTok.line,
                    "circular import: " + cycleDescription(canonical));
        }
        if (resolvedFiles.contains(canonical)) {
            return new ArrayList<>(); // already resolved and spliced in elsewhere -- drop this import
        }

        String source;
        try {
            source = new String(Files.readAllBytes(resolvedPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompilerException("import", currentFilePath, importTok.line,
                    "could not read imported file '" + importPath + "' (resolved to "
                            + resolvedPathStr + "): " + e.getMessage());
        }

        Lexer lexer = new Lexer(source, resolvedPathStr, importPath);
        List<Token> importedTokens = lexer.tokenize();
        Parser parser = new Parser();
        List<Token> importedLines = parser.parse(importedTokens);
        new RpnConverter().convertLines(importedLines);
        new TreeBuilder().buildLines(importedLines);

        return resolve(importedLines, resolvedPathStr);
    }

    private String canonicalize(String path) {
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }

    private String cycleDescription(String repeatedFile) {
        StringBuilder sb = new StringBuilder();
        for (String f : resolutionStack) {
            sb.append(f).append(" -> ");
        }
        sb.append(repeatedFile);
        return sb.toString();
    }
}
