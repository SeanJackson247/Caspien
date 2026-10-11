package caspien.optimizer.hob;

/** HOBProgram -> HOB text: each statement's tokens joined by single spaces, one line per statement. */
public final class HOBSerializer {

    public String serialize(HOBProgram lines) {
        StringBuilder sb = new StringBuilder();
        for (HOBStatement line : lines) {
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(line.get(i).text);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
