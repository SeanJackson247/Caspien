package caspien;

import caspien.lexer.Lexeme;
import caspien.lexer.Lexer;
import caspien.parser.Parser;
import caspien.rpnconverter.RpnConverter;
import caspien.treebuilder.TreeBuilder;

import java.util.List;

/**
 * Source text (or the Lexer's output) to a tree-built list of root LINE tokens: lex, parse, RPN, tree. The one place that runs these
 * four stages in order; used for the main file, every import, and the Caspien source the type checker writes for compiler glue.
 */
public final class SourceParser {

    private SourceParser() {
    }

    public static List<Token> parse(String source, String file, String displayFile) {
        return parse(new Lexer(source, file, displayFile).tokenize());
    }

    public static List<Token> parse(List<Lexeme> lexemes) {
        List<Token> lines = new Parser().parse(lexemes);
        new RpnConverter().convertLines(lines);
        new TreeBuilder().buildLines(lines);
        return lines;
    }
}
