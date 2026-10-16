package caspien.postpasses;

import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Debug-only dump of the parser's token tree structure (an explicit work stack, no recursion). */
public class TreeDump {



    public static void dumpLines(StringBuilder out, List<Token> lines, int indent) { }

    private static void run(StringBuilder out, TreeDumpItem first) { }

    private static void stepToken(StringBuilder out, Token t, int indent, List<TreeDumpItem> next) { }

    private static void pad(StringBuilder out, int indent) { }
}
