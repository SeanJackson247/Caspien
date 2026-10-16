package caspien.postpasses;

final class AuditUse {
    final String file;
    final int line;
    final boolean block;
    final String tags;
    final String text;     // the braces' content for a block, otherwise the source line
    final int firstLine;   // line number of the first content line
    AuditUse(String file, int line, boolean block, String tags, String text, int firstLine) {
        this.file = file; this.line = line; this.block = block; this.tags = tags; this.text = text; this.firstLine = firstLine;
    }
}
