package caspien.config;

public final class DocComment {
    public final String key, text, file, kind;     // kind: the keyword it labels (unsafe / loop / for / func)
    public final int commentLine, targetLine;
    public DocComment(String key, String text, String file, String kind, int commentLine, int targetLine) {
        this.key = key; this.text = text; this.file = file; this.kind = kind; this.commentLine = commentLine; this.targetLine = targetLine;
    }
}
