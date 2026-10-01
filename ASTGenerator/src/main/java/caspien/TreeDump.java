package caspien;

import java.util.List;

/** Debug-only recursive dump of the parser's token tree structure. */
public class TreeDump {

    public static void dumpLines(StringBuilder out, List<Token> lines, int indent) {
        for (Token lineTok : lines) {
            pad(out, indent);
            out.append("LINE:\n");
            dumpFlat(out, lineTok.childs, indent + 1);
        }
    }

    private static void dumpFlat(StringBuilder out, List<Token> flat, int indent) {
        for (Token t : flat) {
            dumpToken(out, t, indent);
        }
    }

    private static void dumpToken(StringBuilder out, Token t, int indent) {
        pad(out, indent);
        out.append(t.type).append("(").append(t.text).append(")");
        if (t.unary) {
            out.append(" [unary]");
        }
        out.append(" [").append(t.file).append(":").append(t.line).append("]");
        if (t.resolvedType != null) {
            out.append(" : ").append(t.resolvedType);
        }
        if (!t.pinnedComments.isEmpty()) {
            out.append(" //").append(t.pinnedComments.size()).append(" comment(s)");
        }
        out.append("\n");

        if (!t.sub.isEmpty()) {
            pad(out, indent + 1);
            out.append("sub:\n");
            dumpFlat(out, t.sub, indent + 2);
        }

        if (!t.childs.isEmpty()) {
            // A childs list is either a list of LINE tokens (a statement
            // sequence -- a '{' block, or a gathered if/loop/func/struct
            // body) or a flat expression/param/member token list. Detect
            // which by looking at the first element.
            if (t.childs.get(0).type == TokenType.LINE) {
                dumpLines(out, t.childs, indent + 1);
            } else {
                dumpFlat(out, t.childs, indent + 1);
            }
        }

        if (t.type == TokenType.OPERATOR) {
            // left/right are this operator's tree operands.
            if (t.left != null) {
                pad(out, indent + 1);
                out.append("left:\n");
                dumpToken(out, t.left, indent + 2);
            }
            if (t.right != null) {
                pad(out, indent + 1);
                out.append("right:\n");
                dumpToken(out, t.right, indent + 2);
            }
        } else if (t.right != null) {
            // Non-operator use of .right: the next branch in an
            // if/elseif/else chain.
            pad(out, indent);
            out.append("-- chained branch --\n");
            dumpToken(out, t.right, indent);
        }
    }

    private static void pad(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }
}
