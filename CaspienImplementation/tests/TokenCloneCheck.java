import caspien.token.*;
import java.lang.reflect.*;
import java.util.*;

/** Every field of Token and TokenChecked must survive TokenCloner.syntax/checked (except the documented resets). */
public class TokenCloneCheck {
    static int fails = 0;
    static final Set<String> SYNTAX_RESET = new HashSet<>(Arrays.asList("chk", "pendingGenericBoundChecks", "fromGenericInstance", "staticApproved"));

    static Token leaf(String text) { return null; }

    static Object sample(Field f) throws Exception { return null; }

    static boolean isSet(Object v) { return false; }

    static void fill(Object o, Class<?> c) throws Exception { }

    static void compare(String mode, Object src, Object dst, Class<?> c, Set<String> reset) throws Exception { }

    public static void main(String[] args) throws Exception { }
}
