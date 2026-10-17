import caspien.bytecode.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** serialize(parse(text)) must give the text back, and statements/tokens must come out as expected. */
public class BytecodeRoundTrip {
    public static void main(String[] a) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8);
        BytecodeProgram p = new BytecodeParser().parse(lines, a[0]);
        int bad = 0;
        if (p.size() != lines.size()) { System.out.println("FAIL statement count " + p.size() + " vs " + lines.size()); bad++; }
        if (!new BytecodeSerializer().serialize(p).equals(text)) { System.out.println("FAIL round trip differs"); bad++; }
        BytecodeStatement s = p.get(1);
        if (!s.opcode().equals("STRING") || s.size() != 3 || s.get(2).kind != BytecodeTokenKind.STRING || !s.text(2).equals("\"a b  \\\" c\"")) { System.out.println("FAIL string token: " + s); bad++; }
        s = p.get(2);
        if (s.size() != 3 || !s.text(1).equals("' '") || s.sourceLine() != 3) { System.out.println("FAIL char token: " + s); bad++; }
        if (!p.get(3).isEmpty() || !p.get(3).opcode().equals("")) { System.out.println("FAIL empty statement"); bad++; }
        if (bad == 0) System.out.println("PASS parse/serialize round trip (" + p.size() + " statements)");
        System.exit(bad == 0 ? 0 : 1);
    }
}
