package caspien.postpasses;

import caspien.token.Token;
import caspien.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Debug-only dump of the parser's token tree structure (an explicit work stack, no recursion). */
public class TreeDump {



    public static void dumpLines(StringBuilder out, List<Token> lines, int indent) {
        run(out, new TreeDumpItem(1, null, lines, null, indent));
    }

    private static void run(StringBuilder out, TreeDumpItem first) {
        Deque<TreeDumpItem> stack = new ArrayDeque<>();
        stack.push(first);
        List<TreeDumpItem> next = new ArrayList<>();
        while (!stack.isEmpty()) {
            TreeDumpItem it = stack.pop();
            next.clear();
            switch (it.kind) {
                case 1:
                    for (Token lineTok : it.list) {
                        next.add(new TreeDumpItem(3, null, null, "LINE:\n", it.indent));
                        next.add(new TreeDumpItem(2, null, lineTok.childs, null, it.indent + 1));
                    }
                    break;
                case 2:
                    for (Token t : it.list) {
                        next.add(new TreeDumpItem(0, t, null, null, it.indent));
                    }
                    break;
                case 3:
                    pad(out, it.indent);
                    out.append(it.label);
                    break;
                default:
                    stepToken(out, it.tok, it.indent, next);
            }
            for (int i = next.size() - 1; i >= 0; i--) {
                stack.push(next.get(i));
            }
        }
    }

    private static void stepToken(StringBuilder out, Token t, int indent, List<TreeDumpItem> next) {
        pad(out, indent);
        out.append(t.type).append("(").append(t.text).append(")");
        if (t.unary) {
            out.append(" [unary]");
        }
        out.append(" [").append(t.file).append(":").append(t.line).append("]");
        if (t.chk.resolvedType != null) {
            out.append(" : ").append(t.chk.resolvedType);
        }
        if (!t.pinnedComments.isEmpty()) {
            out.append(" //").append(t.pinnedComments.size()).append(" comment(s)");
        }
        out.append("\n");

        if (!t.sub.isEmpty()) {
            next.add(new TreeDumpItem(3, null, null, "sub:\n", indent + 1));
            next.add(new TreeDumpItem(2, null, t.sub, null, indent + 2));
        }

        if (!t.childs.isEmpty()) {
            // A childs list is either LINE tokens (a statement sequence) or a flat expression/param/member list.
            if (t.childs.get(0).type == TokenType.LINE) {
                next.add(new TreeDumpItem(1, null, t.childs, null, indent + 1));
            } else {
                next.add(new TreeDumpItem(2, null, t.childs, null, indent + 1));
            }
        }

        if (t.type == TokenType.OPERATOR) {
            if (t.left != null) {
                next.add(new TreeDumpItem(3, null, null, "left:\n", indent + 1));
                next.add(new TreeDumpItem(0, t.left, null, null, indent + 2));
            }
            if (t.right != null) {
                next.add(new TreeDumpItem(3, null, null, "right:\n", indent + 1));
                next.add(new TreeDumpItem(0, t.right, null, null, indent + 2));
            }
        }
        if (t.next != null) {
            next.add(new TreeDumpItem(3, null, null, "-- chained branch --\n", indent));
            next.add(new TreeDumpItem(0, t.next, null, null, indent));
        }
    }

    private static void pad(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }
}
