package caspien.typechecker;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StructInfo {
    public final String name;
    /** Final member set (a struct has no ancestors). Populated by the flatten pass -- empty/partial before it runs. */
    public final LinkedHashMap<String, TypeInfo> members = new LinkedHashMap<>();
    /** Declared interface conformance ("implements A,B") -- a compile-time contract, checked once all impls are collected. */
    public List<String> implementsNames = new ArrayList<>();
    /** Own (non-"extends") member declaration lines, kept until the flatten pass consumes them. */
    public List<Token> rawMemberLines;
    /** Memoization/cycle-detection marker for the flatten pass. */
    public boolean flattened;
    public Token declTok;
    /** Gates the struct itself: a private struct is not nameable outside its declaring file. See docs/TypeChecker.md, "Visibility". */
    public boolean isPublic;
    /** Members marked public through a @pub{} block; all others are private. Meaningful only when `isPublic`. */
    public final Set<String> publicMembers = new HashSet<>();
    /** Each member's original declaring file, for the private-member file-locality check. */
    public final Map<String, String> memberDeclFile = new HashMap<>();
    /** For "@lock(match self.x : X)" on a struct: `lockFieldName` is x (always freely readable), `lockVariants` the |-chained variants. */
    public String lockFieldName;
    public List<String> lockVariants;
    /** Name of the struct's single 'swap'-tagged mutex member (a mut atomic OPEN/CLOSED enum), validated by validateSwapMutexField; must equal `lockFieldName` when both are set. */
    public String swapFieldName;
    /** The struct's "impl default match @lock" policy template (at most one). Only deep-cloned copies per use site are checked. See DefaultLockMatchPolicy. */
    public DefaultLockMatchPolicy defaultLockMatchPolicy;
    /** Class id in the flat `Class` enum (generateClassHierarchyEnums); null for an @untyped struct. Populates the hidden "___type" field. */
    public Long classId;
    public StructInfo(String name) { }
}
