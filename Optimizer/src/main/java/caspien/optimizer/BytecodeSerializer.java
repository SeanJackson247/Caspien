package caspien.optimizer;

import java.util.List;

/** The inverse of BytecodeParser -- joins each line's tokens back with a single space, one line per entry. */
public class BytecodeSerializer {

    public String serialize(List<List<BytecodeToken>> lines) {
        StringBuilder sb = new StringBuilder();
        for (List<BytecodeToken> line : lines) {
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
