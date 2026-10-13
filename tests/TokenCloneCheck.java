import caspien.*;
import java.lang.reflect.*;
import java.util.*;

/** Every field of Token and TokenChecked must survive TokenCloner.syntax/checked (except the documented resets). */
public class TokenCloneCheck {
    static int fails = 0;
    static final Set<String> SYNTAX_RESET = new HashSet<>(Arrays.asList("chk", "pendingGenericBoundChecks", "fromGenericInstance", "staticApproved"));

    static Token leaf(String text) { return new Token(TokenType.VARREF, text, 3, "f.caspien"); }

    static Object sample(Field f) throws Exception {
        Class<?> t = f.getType();
        if (t == boolean.class) return true;
        if (t == int.class) return 5;
        if (t == long.class) return 7L;
        if (t == char.class) return 'q';
        if (t == String.class) return "s-" + f.getName();
        if (t == Long.class) return 9L;
        if (t == Integer.class) return 4;
        if (t == Token.class) return leaf("leaf-" + f.getName());
        if (t == TokenType.class) return TokenType.KEYWORD;
        if (t == List.class) {
            String g = f.getGenericType().getTypeName();
            if (g.contains("List<java.util.List<caspien.Token>>")) return new ArrayList<>(List.of(new ArrayList<>(List.of(leaf("g")))));
            if (g.contains("List<caspien.Token>")) { return new ArrayList<>(List.of(leaf("a-" + f.getName()))); }
            if (g.contains("List<java.lang.String>")) return new ArrayList<>(List.of("x-" + f.getName()));
            return new ArrayList<>(Arrays.asList(new Object[]{null}));   // records (Decorator, MatchPattern, ...): shared, one placeholder
        }
        return null;
    }

    static boolean isSet(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0;
        if (v instanceof Character) return (Character) v != 0;
        if (v instanceof List) return !((List<?>) v).isEmpty();
        return true;
    }

    static void fill(Object o, Class<?> c) throws Exception {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("chk")) continue;
            if (Modifier.isFinal(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.getName().equals("type") || f.getName().equals("text") || f.getName().equals("line") || f.getName().equals("file")) continue;
            f.set(o, sample(f));
        }
    }

    static void compare(String mode, Object src, Object dst, Class<?> c, Set<String> reset) throws Exception {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("chk")) continue;
            f.setAccessible(true);
            Object a = f.get(src), b = f.get(dst);
            if (reset.contains(f.getName())) {
                if (isSet(b)) { System.out.println("FAIL " + mode + ": " + c.getSimpleName() + "." + f.getName() + " should start fresh"); fails++; }
                continue;
            }
            if (isSet(a) && !isSet(b)) { System.out.println("FAIL " + mode + ": " + c.getSimpleName() + "." + f.getName() + " was not copied"); fails++; }
            if (a instanceof Token && a == b) { System.out.println("FAIL " + mode + ": " + c.getSimpleName() + "." + f.getName() + " token shared, not cloned"); fails++; }
        }
    }

    public static void main(String[] args) throws Exception {
        Token src = new Token(TokenType.KEYWORD, "func", 11, "f.caspien");
        fill(src, Token.class);
        fill(src.chk, TokenChecked.class);
        Token syn = TokenCloner.syntax(src);
        compare("syntax", src, syn, Token.class, SYNTAX_RESET);
        for (Field f : TokenChecked.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (isSet(f.get(syn.chk))) { System.out.println("FAIL syntax: chk." + f.getName() + " should start fresh"); fails++; }
        }
        Token chk = TokenCloner.checked(src);
        compare("checked", src, chk, Token.class, Set.of("chk"));
        compare("checked", src.chk, chk.chk, TokenChecked.class, Set.of());
        // sharing inside the copy survives, a cycle-free DAG stays a DAG
        Token shared = leaf("shared");
        Token root = new Token(TokenType.OPERATOR, "+", 1, "f");
        root.left = shared; root.right = shared;
        Token rc = TokenCloner.syntax(root);
        if (rc.left != rc.right || rc.left == shared) { System.out.println("FAIL: shared sub-tree not preserved"); fails++; }
        // deep tree: no recursion limit
        Token chain = leaf("n0"); Token cur = chain;
        for (int i = 1; i < 200000; i++) { Token n = leaf("n" + i); cur.left = n; cur = n; }
        Token deep = TokenCloner.syntax(chain);
        int len = 0; for (Token x = deep; x != null; x = x.left) len++;
        if (len != 200000) { System.out.println("FAIL: deep chain length " + len); fails++; }
        System.out.println(fails == 0 ? "PASS token_clone_check: every Token/TokenChecked field is copied or reset on purpose; sharing kept; 200000-deep tree" : "FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
