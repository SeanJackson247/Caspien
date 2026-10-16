package caspien.bytecode;

/** BytecodeProgram -> HOB text: each statement's tokens joined by single spaces, one line per statement. */
public final class BytecodeSerializer {

    public String serialize(BytecodeProgram lines) {
        StringBuilder sb = new StringBuilder();
        for (BytecodeStatement line : lines) {
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
