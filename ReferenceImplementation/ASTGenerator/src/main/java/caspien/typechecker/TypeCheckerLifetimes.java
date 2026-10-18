package caspien.typechecker;

import caspien.config.CompilerException;
import caspien.token.Decorator;
import caspien.token.Token;
import caspien.token.TokenType;

import java.util.*;

/**
 * Lifetime contracts, slice 1 (docs/LifetimeContracts.md): `ref some` as a struct member.
 * A struct holding a `ref some` member (directly or through a member struct) is a scoped type. This part checks
 *   - stores: the object a `ref some` member points at must be declared before (shallower scope, or earlier in the same scope than) the value it is stored into,
 *   - escapes: a scoped type is not returned, thrown, kept in a static/global or placed in a dynarray,
 *   - uses: every `ref some` member path of a scoped local gets an implicit "alive" proof when it is stored, ProofKills ends it.
 * Position of a local = {scope depth, declaration serial}; "region" of a value = the innermost position of anything it borrows.
 */
public final class TypeCheckerLifetimes {
    private final TypeChecker hub;
    /** Sentinel serial for a destination that is being declared right now: everything visible was declared before it. */
    private static final long NOW = Long.MAX_VALUE;

    public TypeCheckerLifetimes(TypeChecker hub) {
        this.hub = hub;
    }

    // ---- scoped types ----

    public boolean isRefSome(TypeInfo t) {
        return t != null && "ref".equals(t.storage) && t.isSome;
    }

    public boolean isScoped(TypeInfo t) {
        if (t == null || t.baseType == null) {
            return false;
        }
        return isScopedStruct(hub.declarations.stripArrayDims(t.baseType), new HashSet<>());
    }

    private boolean isScopedStruct(String name, Set<String> visiting) {
        StructInfo si = hub.model.structs.get(name);
        if (si == null) {
            return false;
        }
        if (!visiting.add(name)) {
            return false;
        }
        boolean r = false;
        for (TypeInfo mt : si.members.values()) {
            if (isRefSome(mt) || (mt.baseType != null && isScopedStruct(hub.declarations.stripArrayDims(mt.baseType), visiting))) {
                r = true;
                break;
            }
        }
        visiting.remove(name);
        return r;
    }

    /** Member paths ("a", "inner.a") of every `ref some` reachable by value from a scoped type, with the pointee type of each. */
    public void refSomePaths(TypeInfo t, String prefix, Map<String, String> out, int depth) {
        if (t == null || t.baseType == null || depth > 6) {
            return;
        }
        StructInfo si = hub.model.structs.get(hub.declarations.stripArrayDims(t.baseType));
        if (si == null) {
            return;
        }
        for (Map.Entry<String, TypeInfo> m : si.members.entrySet()) {
            TypeInfo mt = m.getValue();
            if (isRefSome(mt)) {
                out.put(prefix + m.getKey(), hub.declarations.pointeeOf(mt));
            } else if (isScoped(mt) && !"ref".equals(mt.storage) && !"raw".equals(mt.storage)) {
                refSomePaths(mt, prefix + m.getKey() + ".", out, depth + 1);
            }
        }
    }

    // ---- escapes ----

    /** Scoped types may not be dynarray elements (a resize moves the elements, the borrow proofs are per path) -- checked after all structs are known. */
    public boolean dynarrayOfScoped(TypeInfo t) {
        if (t == null || t.baseType == null || !(t.baseType.startsWith("dynarray(") || t.baseType.startsWith("unsafe_dynarray("))) {
            return false;
        }
        for (String name : hub.model.structs.keySet()) {
            int from = 0;
            while (true) {
                int i = t.baseType.indexOf(name, from);
                if (i < 0) {
                    break;
                }
                boolean lb = i == 0 || !Character.isLetterOrDigit(t.baseType.charAt(i - 1));
                int e = i + name.length();
                boolean rb = e >= t.baseType.length() || !Character.isLetterOrDigit(t.baseType.charAt(e));
                if (lb && rb && isScopedStruct(name, new HashSet<>())) {
                    return true;
                }
                from = i + 1;
            }
        }
        return false;
    }

    /** Post-collection pass: struct members, parameters and return types may not be dynarrays of scoped values. */
    public void validateNoScopedMemberElements() {
        for (StructInfo si : hub.model.structs.values()) {
            for (Map.Entry<String, TypeInfo> m : si.members.entrySet()) {
                if (dynarrayOfScoped(m.getValue())) {
                    throw new CompilerException("type", si.declTok.file, si.declTok.line,
                            "'" + si.name + "." + m.getKey() + "' is a dynarray of a scoped type (it holds a 'ref some'): not supported, a resize moves the elements");
                }
            }
        }
    }

    public void validateNoScopedFuncElements(FuncInfo f) {
        {
            if (f == null || f.funcToken == null) {
                return;
            }
            for (TypeInfo pt : f.paramTypes) {
                if (dynarrayOfScoped(pt)) {
                    throw new CompilerException("type", f.funcToken.file, f.funcToken.line,
                            "'" + f.name + "' takes a dynarray of a scoped type (it holds a 'ref some'): not supported");
                }
            }
            if (dynarrayOfScoped(f.returnType)) {
                throw new CompilerException("type", f.funcToken.file, f.funcToken.line,
                        "'" + f.name + "' returns a dynarray of a scoped type (it holds a 'ref some'): not supported");
            }
        }
    }

    public void requireNoScopedReturn(FuncInfo f) {
        if (f.returnType != null && isScoped(f.returnType) && !"ref".equals(f.returnType.storage)) {
            throw new CompilerException("type", f.funcToken.file, f.funcToken.line,
                    "'" + f.name + "' returns '" + f.returnType.canonical() + "', a scoped type (it holds a 'ref some'): the value borrows from locals "
                            + "of this function and its proofs cannot survive the return. Build and use it in the caller instead");
        }
        if (f.returnType != null && isRefSome(f.returnType) == false && "ref".equals(f.returnType.storage) && isScoped(f.returnType)) {
            return;
        }
    }

    public void requireNotStaticScoped(TypeInfo t, Token at, String what) {
        if (isScoped(t)) {
            throw new CompilerException("type", at.file, at.line,
                    what + " has the scoped type '" + t.canonical() + "' (it holds a 'ref some'): a value that borrows cannot live in a static or global");
        }
    }

    // ---- positions and regions ----

    private static boolean before(long[] target, long[] dest, boolean destHasDrop) {
        if (target[0] != dest[0]) {
            return target[0] < dest[0];
        }
        return target[1] < dest[1] || (!destHasDrop && target[1] != dest[1]);
    }

    private static long[] later(long[] a, long[] b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (a[0] > b[0] || (a[0] == b[0] && a[1] >= b[1])) ? a : b;
    }

    private Token rootVar(Token e) {
        while (e != null && e.type == TokenType.OPERATOR && ".".equals(e.text) && e.left != null) {
            e = e.left;
        }
        if (e != null && e.type == TokenType.DELINEATOR && "(".equals(e.text) && !e.childs.isEmpty()) {
            return rootVar(e.childs.get(0));
        }
        return e != null && e.type == TokenType.VARREF ? e : null;
    }

    private boolean isLiteral(Token e) {
        return e != null && e.type == TokenType.OPERATOR && !".".equals(e.text) && e.left != null && e.left.type == TokenType.VARREF
                && hub.model.structs.containsKey(e.left.text) && e.right != null && e.right.childs != null && !e.right.childs.isEmpty()
                && e.right.childs.get(0).childs != null && !e.right.childs.get(0).childs.isEmpty();
    }

    /** The innermost position `e` borrows from, or null when it borrows nothing. */
    public long[] regionOf(Token e, Scope scope) {
        if (e == null) {
            return null;
        }
        if (e.type == TokenType.OPERATOR && e.unary && "ref".equals(e.text) && e.left != null) {
            Token bad = e.left;
            for (Token x = e.left; x != null; x = (x.type == TokenType.OPERATOR && x.left != null) ? x.left : null) {
                if (x.type == TokenType.OPERATOR && ("LOOKUP".equals(x.text) || "[".equals(x.text))) {
                    bad = x;
                    throw new CompilerException("type", bad.file, bad.line,
                            "a 'ref' into an array element cannot be stored: a resize moves the block, so the address would dangle");
                }
            }
            Token root = rootVar(e.left);
            long[] pos = root == null ? null : scope.positionOf(root.text);
            if (pos == null) {
                throw new CompilerException("type", e.file, e.line, "cannot tell how long the object behind this 'ref' lives (it is not a local)");
            }
            return pos;
        }
        if (e.type == TokenType.OPERATOR && e.unary && e.left != null) {
            return regionOf(e.left, scope);          // new / ? / mut prefixes
        }
        if (e.type == TokenType.DELINEATOR && "(".equals(e.text) && !e.childs.isEmpty()) {
            return regionOf(e.childs.get(0), scope);
        }
        if (isLiteral(e)) {
            long[] r = null;
            for (Token line : e.right.childs) {
                Token asg = line.childs.get(0);
                if (asg.right != null) {
                    r = later(r, regionOf(asg.right, scope));
                }
            }
            return r;
        }
        Token root = rootVar(e);
        if (root != null) {
            TypeInfo vt = scope.lookup(root.text);
            if (vt != null && (isRefSome(vt) || isScoped(vt))) {
                Scope d = scope.declaringScope(root.text);
                long[] reg = d.borrowRegion.get(root.text);
                if (reg != null) {
                    return reg;
                }
                if (isRefSome(vt)) {
                    return scope.positionOf(root.text);     // proven/matched somewhere here: it is only known to live as long as its own binding
                }
            }
        }
        return null;
    }

    private boolean hasDrop(TypeInfo t) {
        return t != null && t.baseType != null && hub.model.dropHookOwners.containsKey(hub.declarations.stripArrayDims(t.baseType));
    }

    private boolean isParam(String name, Scope scope, FuncInfo func) {
        return func != null && func.paramNames.contains(name) && scope.declaringScope(name) == scope.functionRootScope;
    }

    private String fixHint(Token valueExpr, Token rootTok) {
        return "";
    }

    /**
     * A value was stored: `let name = value` (isLet), or `target = value`. Checks the borrow against where the destination lives, records the
     * destination's region, and gives the `ref some` members of the destination fresh implicit proofs.
     */
    public void onStore(Token target, Token nameTok, Token value, TypeInfo destType, boolean isLet, Scope scope, FuncInfo func) {
        if (hub.model.synthesizingGlueBody) {
            return;
        }
        boolean memberStore = !isLet && target.type == TokenType.OPERATOR && ".".equals(target.text);
        if (dynarrayOfScoped(destType)) {
            Token at = isLet ? nameTok : target;
            throw new CompilerException("type", at.file, at.line,
                    "a dynarray of a scoped type (it holds a 'ref some') is not supported: a resize moves the elements");
        }
        TypeInfo checkType = destType;
        if (!(isScoped(checkType) || isRefSome(checkType))) {
            return;
        }
        String rootName = isLet ? nameTok.text : (rootVar(target) != null ? rootVar(target).text : null);
        if (rootName == null) {
            return;
        }
        long[] region = regionOf(value, scope);
        TypeInfo rootType = scope.lookup(rootName);
        if (!isLet) {
            Scope decl = scope.declaringScope(rootName);
            if (decl == null) {
                throw new CompilerException("type", target.file, target.line,
                        "'" + rootName + "' is a global: a value that borrows cannot be stored in a global");
            }
            boolean bareRefSomeLocal = target.type == TokenType.VARREF && isRefSome(destType);
            if (!bareRefSomeLocal && region != null) {
                if (isParam(rootName, scope, func)) {
                    requireContractForParamStore(target, value, rootName, rootType, scope, func);
                    return;
                }
                long[] dest = scope.positionOf(rootName);
                if (!before(region, dest, hasDrop(rootType))) {
                    throw new CompilerException("type", target.file, target.line,
                            "'" + rootName + "' would outlive the object it borrows: the stored reference points at something declared "
                                    + (region[0] > dest[0] ? "in an inner scope" : "after '" + rootName + "' in the same scope")
                                    + (region[0] > dest[0] ? "" : " (its @drop would see a destroyed object)")
                                    + ". Declare the borrowed object before (or in an outer scope of) '" + rootName + "'");
                }
            }
            if (bareRefSomeLocal) {
                decl.borrowRegion.remove(rootName);
                if (region != null) {
                    decl.borrowRegion.put(rootName, region);
                }
            } else if (region != null) {
                long[] old = decl.borrowRegion.get(rootName);
                decl.borrowRegion.put(rootName, later(old, region));
            }
        } else if (region != null) {
            scope.declScope().borrowRegion.put(rootName, region);
        }
        // proofs
        if (memberStore) {
            if (isRefSome(destType)) {
                String key = hub.expressions.slotKeyOf(target);
                if (key != null) {
                    ProofKills.implicit(key, hub.declarations.pointeeOf(destType));
                }
            } else if (isScoped(destType)) {
                implicitAll(hub.expressions.slotKeyOf(target), destType);
            }
        } else if (isScoped(destType)) {
            implicitAll(rootName, destType);
        }
    }

    public void implicitAll(String key, TypeInfo t) {
        if (key == null) {
            return;
        }
        Map<String, String> paths = new LinkedHashMap<>();
        refSomePaths(t, "", paths, 0);
        for (Map.Entry<String, String> p : paths.entrySet()) {
            ProofKills.implicit(key + "." + p.getKey(), p.getValue());
        }
    }

    /** A `ref some` member was read: it must still be covered by the proof made when it was stored. */
    public void onMemberRead(Token dot, Scope scope, TypeInfo memberType) {
        if (!isRefSome(memberType) || dot == hub.model.assignTargetVar) {
            return;
        }
        String key = hub.expressions.slotKeyOf(dot);
        if (key != null) {
            ProofKills.memberRead(dot, key, scope.activeMatchPatterns);
        }
    }

    // ---- contracts ----

    /** Pairwise relations of a function's contract, closed under transitivity: key "L>R" -> true when the relation is strict. */
    public Map<String, Boolean> contractOf(FuncInfo f) {
        Map<String, Boolean> rel = new LinkedHashMap<>();
        if (f == null || f.funcToken == null || f.funcToken.decorators == null) {
            return rel;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Decorator d : f.funcToken.decorators) {
            if (d.lifetimeTerms == null) {
                continue;
            }
            for (int i = 0; i + 1 < d.lifetimeTerms.size(); i++) {
                String l = d.lifetimeTerms.get(i);
                String r = d.lifetimeTerms.get(i + 1);
                String op = d.lifetimeOps.get(i);
                names.add(l);
                names.add(r);
                put(rel, l, r, op.equals(">"));
                if (op.equals("==")) {
                    put(rel, r, l, false);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String a : names) {
                for (String b : names) {
                    for (String c : names) {
                        Boolean ab = rel.get(a + ">" + b);
                        Boolean bc = rel.get(b + ">" + c);
                        if (ab != null && bc != null && !a.equals(c)) {
                            boolean strict = ab || bc;
                            Boolean old = rel.get(a + ">" + c);
                            if (old == null || (strict && !old)) {
                                rel.put(a + ">" + c, strict);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        return rel;
    }

    private static void put(Map<String, Boolean> rel, String l, String r, boolean strict) {
        Boolean old = rel.get(l + ">" + r);
        if (old == null || (strict && !old)) {
            rel.put(l + ">" + r, strict);
        }
    }

    /** Every `lifetime(x)` must name a parameter of the function it sits on. */
    public void validateContract(FuncInfo f) {
        if (f == null || f.funcToken == null || f.funcToken.decorators == null) {
            return;
        }
        for (Decorator d : f.funcToken.decorators) {
            if (d.lifetimeTerms == null) {
                continue;
            }
            for (String t : d.lifetimeTerms) {
                if (!f.paramNames.contains(t)) {
                    throw new CompilerException("type", d.file, d.line,
                            "'lifetime(" + t + ")' in the contract of '" + f.name + "': '" + t + "' is not a parameter of it");
                }
            }
        }
    }

    private void sourceRoots(Token e, Scope scope, List<Token> out) {
        if (e == null) {
            return;
        }
        if (e.type == TokenType.OPERATOR && e.unary && "ref".equals(e.text) && e.left != null) {
            Token r = rootVar(e.left);
            if (r != null) {
                out.add(r);
            }
        } else if (e.type == TokenType.OPERATOR && e.unary && e.left != null) {
            sourceRoots(e.left, scope, out);
        } else if (e.type == TokenType.DELINEATOR && "(".equals(e.text) && !e.childs.isEmpty()) {
            sourceRoots(e.childs.get(0), scope, out);
        } else if (isLiteral(e)) {
            for (Token line : e.right.childs) {
                sourceRoots(line.childs.get(0).right, scope, out);
            }
        } else {
            Token r = rootVar(e);
            TypeInfo t = r == null ? null : scope.lookup(r.text);
            if (r != null && t != null && (isRefSome(t) || isScoped(t))) {
                out.add(r);
            }
        }
    }

    /** `d.member = value` where d is a parameter: the contract must say the stored object lives at least as long as d. */
    private void requireContractForParamStore(Token target, Token value, String destParam, TypeInfo destType, Scope scope, FuncInfo func) {
        Map<String, Boolean> rel = contractOf(func);
        List<Token> roots = new ArrayList<>();
        sourceRoots(value, scope, roots);
        for (Token src : roots) {
            if (src.text.equals(destParam)) {
                continue;
            }
            boolean strict = hasDrop(destType);
            String op = strict ? ">" : ">=";
            if (!isParam(src.text, scope, func)) {
                throw new CompilerException("type", target.file, target.line,
                        "'" + src.text + "' is a local: it dies when '" + func.name + "' returns, before the caller's '" + destParam
                                + "'. A local can only be stored into another local; a parameter can only borrow another parameter that outlives it");
            }
            Boolean have = rel.get(src.text + ">" + destParam);
            if (have == null || (strict && !have)) {
                throw new CompilerException("type", target.file, target.line,
                        "storing '" + src.text + "' into '" + destParam + "' needs a lifetime contract on '" + func.name + "': '" + src.text
                                + "' must live " + (strict ? "strictly longer than" : "at least as long as") + " '" + destParam
                                + (strict ? "' (its @drop reads through the member)" : "'")
                                + ". Add:  @lock match(lifetime(" + src.text + ") " + op + " lifetime(" + destParam + "))");
            }
        }
    }

    private long[] argPos(Token arg, Scope scope) {
        List<Token> roots = new ArrayList<>();
        sourceRoots(arg, scope, roots);
        Token r = roots.isEmpty() ? rootVar(arg) : roots.get(0);
        if (r == null) {
            return null;
        }
        TypeInfo t = scope.lookup(r.text);
        if (t == null) {
            return new long[]{-1, 0};                // a global lives for the whole program
        }
        boolean viaRef = arg.type == TokenType.OPERATOR && arg.unary && "ref".equals(arg.text);
        if (!viaRef && isRefSome(t)) {
            long[] reg = regionOf(arg, scope);
            return reg != null ? reg : scope.positionOf(r.text);
        }
        return scope.positionOf(r.text);
    }

    /** At a call: registers the uses of scoped arguments, checks the callee's contract against the caller's scopes, and records the new borrows. */
    public void onCall(FuncInfo callee, List<Token> argNodes, Scope scope, Token at) {
        if (hub.model.synthesizingGlueBody || callee == null) {
            return;
        }
        for (int i = 0; i < argNodes.size() && i < callee.paramTypes.size(); i++) {
            Token a = argNodes.get(i);
            Token r = rootVar(a);
            TypeInfo t = r == null ? null : scope.lookup(r.text);
            if (t != null && isScoped(t)) {
                String key = hub.expressions.slotKeyOf(a);
                if (key != null) {
                    Map<String, String> paths = new LinkedHashMap<>();
                    refSomePaths(t, "", paths, 0);
                    for (String path : paths.keySet()) {
                        ProofKills.memberRead(a, key + "." + path, scope.activeMatchPatterns);
                    }
                }
            }
        }
        Map<String, Boolean> rel = contractOf(callee);
        for (Map.Entry<String, Boolean> e : rel.entrySet()) {
            String[] lr = e.getKey().split(">");
            int li = callee.paramNames.indexOf(lr[0]);
            int ri = callee.paramNames.indexOf(lr[1]);
            if (li < 0 || ri < 0 || li >= argNodes.size() || ri >= argNodes.size()) {
                continue;
            }
            long[] lp = argPos(argNodes.get(li), scope);
            long[] rp = argPos(argNodes.get(ri), scope);
            if (lp == null || rp == null) {
                throw new CompilerException("type", at.file, at.line,
                        "cannot tell how long the arguments for '" + lr[0] + "' and '" + lr[1] + "' live, but '" + callee.name + "' requires lifetime("
                                + lr[0] + ") " + (e.getValue() ? ">" : ">=") + " lifetime(" + lr[1] + "). Bind them to locals first");
            }
            if (!before(lp, rp, e.getValue())) {
                throw new CompilerException("type", at.file, at.line,
                        "call to '" + callee.name + "' violates its contract lifetime(" + lr[0] + ") " + (e.getValue() ? ">" : ">=") + " lifetime(" + lr[1]
                                + "): the argument for '" + lr[0] + "' is declared " + (lp[0] > rp[0] ? "in an inner scope relative to" : "after")
                                + " the argument for '" + lr[1] + "'");
            }
            Token rr = rootVar(argNodes.get(ri));
            if (rr != null && scope.declaringScope(rr.text) != null) {
                Scope d = scope.declaringScope(rr.text);
                d.borrowRegion.put(rr.text, later(d.borrowRegion.get(rr.text), lp));
                TypeInfo rt = scope.lookup(rr.text);
                if (isScoped(rt)) {
                    implicitAll(rr.text, rt);
                }
            }
        }
    }
}
