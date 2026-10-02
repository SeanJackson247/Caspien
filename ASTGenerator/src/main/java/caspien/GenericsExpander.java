package caspien;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * New pipeline stage, runs on the fully import-resolved, dup-expanded root
 * list, before TypeChecker.
 *
 * Monomorphizes every generic struct/func/interface/impl into one concrete,
 * mangled copy per unique combination of type arguments actually used
 * anywhere in the program, and rewrites every usage site (both the ":<...>"
 * expression form and the bare "<...>" annotation form) to a plain
 * reference to the mangled name. By the time TypeChecker runs, no generic
 * syntax survives anywhere -- same "expand away entirely" approach already
 * used for 'dup' and for 'for'-loops in this codebase, and consistent with
 * "resolve generics, then the rest of type checking": an unbound template
 * is never emitted and never type-checked.
 *
 * Known, deliberate scope limits for this first version (flagged rather
 * than silently glossed over):
 *  - No generic-function overloading: at most one generic template may be
 *    registered per name, for func/struct/interface/library alike -- a
 *    second one sharing a name is a hard, direct compile error
 *    (`registerTemplate`), not merely "unsupported": a real, previously-
 *    latent bug here let a second same-named template silently overwrite
 *    the first in this class's own registration map, with zero warning,
 *    since a plain `Map.put` never rejects a collision on its own --
 *    confirmed directly by testing, not assumed, before this check
 *    existed (two same-arity `func f<T>(...)` overloads compiled with no
 *    error at all, one of them simply vanishing; a later, unrelated call
 *    site then failed with a confusing "no overload matches"/"expects N
 *    type arguments" error that gave no hint the real cause was an
 *    earlier, silently-discarded declaration). Plain (non-generic)
 *    overloads are unaffected and still handled entirely by TypeChecker
 *    as before.
 *  - No unification/inference: every instantiation site must already carry
 *    fully concrete (or already-generic-and-resolvable) type arguments.
 *  - Function-pointer-shaped type annotations are not scanned for nested
 *    generic type arguments (their own inner parameter/return types are
 *    left untouched) -- follow-up item if that combination is ever needed.
 *  - Substitution of a bound type-parameter name inside a template's body
 *    is a blanket VARREF text-replace, not scope-aware -- a generic
 *    parameter name should not be reused as a member/parameter/local name
 *    inside that same template's own body, or it will be incorrectly
 *    substituted too. Given this language's existing "no compile-time
 *    constant beyond literal arithmetic" minimalism, this is treated as an
 *    acceptable, documented restriction rather than solved with a full
 *    scoped-substitution pass.
 */
public class GenericsExpander {

    private final Map<String, Token> structTemplates = new HashMap<>();
    private final Map<String, Token> funcTemplates = new HashMap<>();
    private final Map<String, Token> interfaceTemplates = new HashMap<>();
    private final Map<String, Token> libraryTemplates = new HashMap<>();
    /** impl templates, keyed by the concrete-side base name they target (List, not List<T>). */
    private final Map<String, List<Token>> implTemplatesByTarget = new LinkedHashMap<>();

    /** "TemplateName<arg1,arg2,...>" -> already-generated mangled name. Reserved before generation to break cycles. */
    private final Map<String, String> memo = new HashMap<>();

    private final List<Token> output = new ArrayList<>();

    /**
     * NOTE ON SHAPE: every entry in `rootEntries` (and every entry this
     * method places into `output`) is a LINE token wrapping exactly one
     * real declaration in `.childs.get(0)` -- the same "wrapAsLine" shape
     * `Parser` already produces for every root-level construct, and the
     * exact shape `TypeChecker.check`/`collectDeclarations` already expects
     * (`lineTok.childs.get(0)`). This method unwraps to inspect/register
     * each declaration, but always keeps (or re-creates) that LINE wrapper
     * around anything placed into `output`, so the list handed to
     * TypeChecker looks structurally identical to what it already expects,
     * just with every generic construct fully monomorphized away.
     */
    public List<Token> expand(List<Token> rootEntries) {
        // Step A: scan every declaration's own annotation positions (struct
        // members, func/interface method params+return types, impl headers)
        // for bare "Name<Args>" usages, stripping the raw "<...>" tokens and
        // attaching an equivalent .genericArgs to the base VARREF -- putting
        // every generic usage, annotation-site or expression-site, into the
        // one uniform shape the rest of this class works with.
        for (Token lineEntry : rootEntries) {
            scanAnnotationContexts(lineEntry.childs.get(0));
        }

        // Step B: partition into templates vs. plain (already-concrete)
        // declarations. Templates are held back entirely -- never placed in
        // `output`, never seen by TypeChecker in unbound form.
        for (Token lineEntry : rootEntries) {
            Token entry = lineEntry.childs.get(0);
            if (entry.type != TokenType.KEYWORD) {
                output.add(lineEntry);
                continue;
            }
            switch (entry.text) {
                case "struct":
                    if (entry.typeParams != null) {
                        registerTemplate(structTemplates, "struct", entry.sub.get(0).text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "func":
                    if (entry.typeParams != null) {
                        registerTemplate(funcTemplates, "func", entry.sub.get(0).text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "interface":
                    if (entry.typeParams != null) {
                        registerTemplate(interfaceTemplates, "interface", entry.sub.get(0).text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "library":
                    if (entry.typeParams != null) {
                        registerTemplate(libraryTemplates, "library", entry.sub.get(0).text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "impl": {
                    Token concreteNameTok = entry.sub.get(entry.sub.size() - 1);
                    boolean isTemplate = entry.typeParams != null || concreteNameTok.genericArgs != null;
                    if (isTemplate) {
                        if (entry.typeParams != null && concreteNameTok.genericArgs == null) {
                            // The impl declares its own type parameter(s), but
                            // the target struct/enum itself isn't generic at
                            // all -- every declared param is therefore
                            // "residual," existing purely for this impl's own
                            // methods (the "impl with independent type
                            // params" feature -- see this class's own doc
                            // comment on Token.typeParams' new, deliberate
                            // exception), never consumed matching against a
                            // generic target. This impl is already concrete
                            // (nothing to monomorphize per-target-
                            // instantiation), so it's processed directly here
                            // rather than deferred to generateImplInstantiation,
                            // which only ever runs once per *struct*
                            // instantiation and would never otherwise be
                            // reached for a non-generic target at all.
                            processImplWithOnlyResidualParams(lineEntry);
                        } else {
                            registerImplTemplate(entry, concreteNameTok);
                        }
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                }
                default:
                    output.add(lineEntry);
            }
        }

        // Step C: seed processing from every already-concrete declaration
        // placed in `output` -- this transitively triggers generation of
        // every reachable instantiation (structs, funcs, interfaces, and
        // any impls attached to a generic struct that gets instantiated).
        // Deliberately index-based, not an enhanced for-loop: generating an
        // instantiation appends new entries to this same `output` list
        // mid-iteration (an enhanced for-loop's fail-fast iterator would
        // throw ConcurrentModificationException on that). Revisiting a
        // freshly-appended clone here too is harmless, just redundant --
        // processGenericToken is a no-op on anything already resolved.
        for (int i = 0; i < output.size(); i++) {
            walkAndProcess(output.get(i));
        }

        return output;
    }

    /**
     * Registers a generic struct/func/interface/library template into its
     * own by-name map, rejecting outright a second template sharing that
     * exact name (generic-function/struct/interface/library "overloading"
     * -- more than one generic template per name -- is not supported; see
     * this class's own doc comment for the real, previously-silent bug
     * this closes). `kindLabel` is purely for the error message.
     */
    private void registerTemplate(Map<String, Token> templates, String kindLabel, String name, Token entry) {
        Token existing = templates.get(name);
        if (existing != null) {
            throw new CompilerException("type", entry.file, entry.line,
                    "generic '" + kindLabel + " " + name + "' is already declared (at "
                            + existing.file + ":" + existing.line + ") -- generic-"
                            + kindLabel + " overloading (more than one generic template sharing a "
                            + "name) is not supported");
        }
        templates.put(name, entry);
    }

    private Token wrapAsLine(Token declaration) {
        Token lineTok = new Token(TokenType.LINE, "line", declaration.line, declaration.file);
        lineTok.childs.add(declaration);
        return lineTok;
    }

    private void registerImplTemplate(Token implEntry, Token concreteNameTok) {
        // v1 only supports the standard "impl<T> Generic<T>{...}" shape,
        // where an impl's type arguments are exactly its own declared type
        // parameters -- this makes the impl apply uniformly to *every*
        // instantiation of the target struct. An impl targeting one single,
        // already-concrete instantiation and nothing else (e.g. a bare,
        // non-generic "impl List<u64>{...}") is deliberately rejected here
        // rather than silently (and incorrectly) attached to every
        // instantiation of List -- follow-up item if that shape is ever
        // actually needed.
        if (implEntry.typeParams == null) {
            throw new CompilerException("type", concreteNameTok.file, concreteNameTok.line,
                    "an 'impl' targeting a generic type must itself be generic (e.g. 'impl<T> "
                            + concreteNameTok.text + "<T>{...}') -- an impl fixed to one single concrete "
                            + "instantiation is not supported yet");
        }
        validateImplTargetPattern(implEntry, concreteNameTok);
        String targetName = concreteNameTok.text;
        implTemplatesByTarget.computeIfAbsent(targetName, k -> new ArrayList<>()).add(implEntry);
    }

    private void validateImplTargetPattern(Token implEntry, Token targetTok) {
        for (List<Token> group : targetTok.genericArgs) {
            if (group.size() != 1 || group.get(0).type != TokenType.VARREF
                    || !implEntry.typeParams.contains(group.get(0).text)) {
                throw new CompilerException("type", targetTok.file, targetTok.line,
                        "impl's type arguments for '" + targetTok.text + "' must be exactly its own "
                                + "declared type parameters (" + implEntry.typeParams + ")");
            }
        }
    }

    // ---- Step A: annotation-context scanning --------------------------

    private void scanAnnotationContexts(Token entry) {
        if (entry.type != TokenType.KEYWORD) {
            return;
        }
        switch (entry.text) {
            case "struct":
                for (Token memberLine : entry.childs) {
                    scanRawNameTypeGroups(memberLine.childs);
                }
                break;
            case "interface":
                for (Token method : entry.childs) {
                    scanFuncOrMethodSignature(method);
                }
                break;
            case "library":
                for (Token func : entry.childs) {
                    scanFuncOrMethodSignature(func);
                }
                break;
            case "type":
                // "type NAME <target type tokens>" -- entry.sub = [nameTok,
                // ...targetTokens], never passed through
                // resolveNestedGroups/toRpn at all (the same "declaration
                // annotation, not expression" treatment a struct member's or
                // func param's own type gets -- see gatherType's own doc
                // comment), so a bare "List<u64>" target needs this same
                // scan to ever become a .genericArgs-bearing token at all.
                scanTypeTokenSpan(entry.sub.subList(1, entry.sub.size()));
                break;
            case "func":
                scanFuncOrMethodSignature(entry);
                for (Token line : entry.childs) {
                    // bodies are expression-context; nothing to scan here --
                    // generic usages there were already collapsed by Parser.
                }
                if (entry.childs != null) {
                    // still need to recurse into nested blocks in case a
                    // func body contains a nested struct-literal type
                    // annotation-shaped construct -- none exist in this
                    // language (no local type decls), so nothing further
                    // to do here.
                }
                break;
            case "impl":
                for (Token method : entry.childs) {
                    scanAnnotationContexts(method); // each is a "func" entry
                }
                break;
            default:
                break;
        }
    }

    private void scanFuncOrMethodSignature(Token funcOrMethod) {
        // sub = [nameTok, paramsTok, ...returnTypeTokens]
        Token paramsTok = funcOrMethod.sub.get(1);
        scanRawNameTypeGroups(paramsTok.childs);
        List<Token> returnTypeTokens = funcOrMethod.sub.subList(2, funcOrMethod.sub.size());
        scanTypeTokenSpan(returnTypeTokens);
    }

    /** Splits on top-level ',' / TERMINATOR into "name : type..." groups, mirroring TypeChecker's own splitIntoRawGroups. */
    /**
     * Splits a flat "name : type [, name : type ...]" token list (a struct
     * member line, or a func/method's raw parameter list) into individual
     * segments on top-level ',' / TERMINATOR, and scans each segment's type
     * tokens for a bare generic-argument list.
     *
     * Segments are found and processed strictly left-to-right using a live
     * `flat.subList(...)` view for whichever segment is currently being
     * scanned -- not a copy -- specifically so that when
     * `scanTypeTokenSpan` removes the consumed "<...>" tokens from that
     * view, the removal genuinely propagates back into `flat` itself
     * (the same list object `TypeChecker` will read later). Because a
     * removal shrinks `flat`, the position of the *next* separator is
     * re-located fresh after each segment rather than computed once
     * up front.
     */
    private void scanRawNameTypeGroups(List<Token> flat) {
        int start = 0;
        while (true) {
            int sepIdx = findNextSeparator(flat, start);
            int end = (sepIdx == -1) ? flat.size() : sepIdx;
            // segment = [nameTok, ":", ...typeTokens] -- 'self' has no ':'
            // in interface signatures in some shapes, but generics never
            // apply to 'self' itself, so a malformed/short segment is
            // simply left alone here (TypeChecker's own parseOneNameType
            // reports the real error later if it's genuinely malformed).
            if (end - start >= 3) {
                scanTypeTokenSpan(flat.subList(start + 2, end));
            }
            if (sepIdx == -1) {
                break;
            }
            // re-locate the separator: scanTypeTokenSpan may have shrunk
            // `flat` if this segment turned out to be generic.
            start = findNextSeparator(flat, start) + 1;
        }
    }

    private int findNextSeparator(List<Token> flat, int from) {
        for (int i = from; i < flat.size(); i++) {
            Token t = flat.get(i);
            if ((t.type == TokenType.OPERATOR && t.text.equals(",")) || t.type == TokenType.TERMINATOR) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Scans one flat type-annotation token span (MODIFIER* VARREF
     * ['<' args '>'] DELINEATOR('[')*) for a bare generic-argument list
     * following the base-type VARREF, consuming it out of `span` in place
     * and attaching the parsed groups to that VARREF's .genericArgs --
     * after this call, `span` looks exactly like an ordinary, non-generic
     * type annotation to every later stage (TypeChecker.resolveTypeAnnotation
     * needs zero changes).
     */
    private void scanTypeTokenSpan(List<Token> span) {
        for (int i = 0; i < span.size(); i++) {
            Token t = span.get(i);
            if (t.type == TokenType.VARREF) {
                if (i + 1 < span.size() && span.get(i + 1).type == TokenType.OPERATOR && span.get(i + 1).text.equals("<")) {
                    int depth = 1;
                    int j = i + 2;
                    int closeIdx = -1;
                    for (; j < span.size(); j++) {
                        Token u = span.get(j);
                        if (u.type == TokenType.OPERATOR && u.text.equals("<")) {
                            depth++;
                        } else if (u.type == TokenType.OPERATOR && u.text.equals(">")) {
                            depth--;
                            if (depth == 0) {
                                closeIdx = j;
                                break;
                            }
                        }
                    }
                    if (closeIdx < 0) {
                        throw new CompilerException("type", t.file, t.line,
                                "unterminated type-argument list for '" + t.text + "'");
                    }
                    List<Token> raw = new ArrayList<>(span.subList(i + 2, closeIdx));
                    List<List<Token>> groups = splitTopLevelCommaGroupsBare(raw);
                    for (List<Token> group : groups) {
                        scanTypeTokenSpan(group); // nested generics inside the arg list
                    }
                    t.genericArgs = groups;
                    for (int k = closeIdx; k >= i + 1; k--) {
                        span.remove(k);
                    }
                } else if (i + 1 < span.size() && span.get(i + 1).type == TokenType.DELINEATOR
                        && span.get(i + 1).text.equals("(")) {
                    // "dynarray(...)"/"range(...)" -- a
                    // composite-type-constructor shape, confirmed by
                    // example to need exactly this recursion: a real,
                    // separate gap found while building a generic
                    // HashMap -- this whole method was written before
                    // "dynarray" existed, and only ever looked for a
                    // bare "Name<Args>" pattern directly in the flat
                    // span; a nested generic reference living *inside*
                    // one of these parenthesized argument positions
                    // (e.g. "dynarray(imut HashMapEntry<T>)") was never
                    // discovered/rewritten at all, so it reached
                    // TypeChecker's own annotation parser as raw,
                    // unprocessed tokens it has no way to interpret as
                    // a generic reference (that's this pass's job, not
                    // TypeChecker's). The "(" token's own `.childs` is
                    // a *flat* token list (comma operators included,
                    // same shape splitTopLevelCommaGroupsBare's own
                    // callers already expect just above) -- not
                    // pre-split groups -- so it needs the exact same
                    // splitting before each resulting group can be
                    // scanned.
                    Token parenTok = span.get(i + 1);
                    if (!parenTok.childs.isEmpty()) {
                        List<List<Token>> groups = splitTopLevelCommaGroupsBare(parenTok.childs);
                        for (List<Token> group : groups) {
                            scanTypeTokenSpan(group); // may remove tokens from `group` itself
                        }
                        // splitTopLevelCommaGroupsBare returns brand-new
                        // List objects -- removing a group's own now-
                        // consumed "<...>" tokens above never touched
                        // parenTok.childs itself at all (a different
                        // List instance holding the same Token objects
                        // by reference). Write the (now generic-args-
                        // stripped) groups back into it, re-joined with
                        // fresh comma tokens wherever groups were
                        // originally separated, so a later multi-
                        // argument case (e.g. "range(a,b)")
                        // stays correct too, not just this one-argument
                        // "dynarray(...)" case.
                        List<Token> rebuilt = new ArrayList<>();
                        for (int g = 0; g < groups.size(); g++) {
                            if (g > 0) {
                                rebuilt.add(new Token(TokenType.OPERATOR, ",", parenTok.line, parenTok.file));
                            }
                            rebuilt.addAll(groups.get(g));
                        }
                        parenTok.childs.clear();
                        parenTok.childs.addAll(rebuilt);
                    }
                }
                break; // exactly one base-type VARREF per span; nothing more to scan
            }
        }
    }

    private List<List<Token>> splitTopLevelCommaGroupsBare(List<Token> tokens) {
        List<List<Token>> groups = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token t : tokens) {
            if (t.type == TokenType.OPERATOR && t.text.equals("<")) {
                depth++;
            } else if (t.type == TokenType.OPERATOR && t.text.equals(">")) {
                depth--;
            }
            if (depth == 0 && t.type == TokenType.OPERATOR && t.text.equals(",")) {
                groups.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        groups.add(current);
        return groups;
    }

    // ---- generic walk + per-token processing ---------------------------

    private void walkAndProcess(Token node) {
        if (node == null) {
            return;
        }
        processGenericToken(node);
        walkAndProcess(node.left);
        // A '.' operator's own right side is always a bare member/method
        // NAME, never a type-instantiation target -- so if it carries
        // genericArgs at all, that's an explicit generic-method-call type
        // argument ("receiver.method:<U>(...)", the impl-own-type-param
        // feature's own call-site syntax), not a struct/func/interface/
        // library reference. processGenericToken has no way to resolve
        // that (it has no idea what concrete struct the receiver even is
        // -- this whole pass is purely syntactic, with no symbol table),
        // so it's deliberately left completely untouched here, genericArgs
        // and all, for TypeChecker.checkMethodCall to resolve once the
        // receiver's own concrete type is actually known (post-generics-
        // expansion). This is the one deliberate exception to "TypeChecker
        // never sees a non-null genericArgs" -- see Token.genericArgs' own
        // doc comment.
        if (node.type == TokenType.OPERATOR && node.text.equals(".")) {
            walkAndProcessWithoutTopLevelGenericResolution(node.right);
        } else {
            walkAndProcess(node.right);
        }
        if (node.childs != null) {
            for (Token c : node.childs) {
                walkAndProcess(c);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                walkAndProcess(s);
            }
        }
        // Note: node.genericArgs is intentionally not walked here -- by the
        // time processGenericToken returns, it has already fully resolved
        // and cleared node.genericArgs (recursing into each argument group
        // itself as part of that resolution), so there is nothing left in
        // it to visit afterward.
    }

    /**
     * Same recursive walk as walkAndProcess, except it never calls
     * processGenericToken on `node` itself -- used only for a '.'
     * operator's own right-hand side (see walkAndProcess' own doc
     * comment). A method/member name is always a bare VARREF with no
     * left/right/childs/sub of its own, so this recursion is a no-op in
     * practice; kept general (rather than just returning immediately)
     * purely for structural symmetry, in case that ever changes.
     */
    private void walkAndProcessWithoutTopLevelGenericResolution(Token node) {
        if (node == null) {
            return;
        }
        walkAndProcess(node.left);
        walkAndProcess(node.right);
        if (node.childs != null) {
            for (Token c : node.childs) {
                walkAndProcess(c);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                walkAndProcess(s);
            }
        }
    }

    /**
     * If `t` carries generic arguments, resolves them (recursively
     * generating whatever nested instantiations they themselves require),
     * generates the concrete monomorphized declaration for `t` itself if
     * it doesn't already exist, and rewrites `t` in place into a plain,
     * non-generic reference to the mangled name. A no-op for any token
     * with no generic arguments.
     */
    private void processGenericToken(Token t) {
        if (t.genericArgs == null) {
            return;
        }
        // `dyn:<T>([])` -- the typed empty-dynarray form. `dyn` is a builtin,
        // not a generic template, so there is nothing to monomorphize: its
        // type argument is just resolved (in case it is itself a generic
        // instantiation) and the genericArgs are deliberately LEFT on the
        // `dyn` VARREF for TypeChecker.checkDynBuiltinCore to read -- the
        // second exception, after `receiver.method:<U>(...)`, to "TypeChecker
        // never sees a non-null genericArgs".
        if (t.type == TokenType.VARREF && (t.text.equals("dyn") || t.text.equals("wrap") || t.text.equals("sat"))) {
            for (List<Token> group : t.genericArgs) {
                if (group.size() != 1) {
                    throw new CompilerException("type", t.file, t.line,
                            "generic type argument must be a single type name, found a compound expression");
                }
                processGenericToken(group.get(0));
            }
            return;
        }
        String templateName = t.text;
        List<String> argNames = new ArrayList<>();
        List<Token> argTokens = new ArrayList<>();
        for (List<Token> group : t.genericArgs) {
            if (group.size() != 1) {
                throw new CompilerException("type", t.file, t.line,
                        "generic type argument must be a single type name, found a compound expression");
            }
            Token argTok = group.get(0);
            processGenericToken(argTok); // resolves it first if it's itself generic
            argNames.add(argTok.text);
            argTokens.add(argTok);
        }
        String key = templateName + "<" + String.join(",", argNames) + ">";
        String mangled = memo.get(key);
        if (mangled == null) {
            mangled = mangleName(templateName, argNames);
            memo.put(key, mangled); // reserve first: breaks self-referential recursion
            generateInstantiation(templateName, argNames, argTokens, mangled, t);
        }
        t.text = mangled;
        t.genericArgs = null;
    }

    private String mangleName(String templateName, List<String> argNames) {
        StringBuilder sb = new StringBuilder(templateName);
        for (String a : argNames) {
            sb.append('_').append(a);
        }
        return sb.toString();
    }

    private void generateInstantiation(String templateName, List<String> argNames, List<Token> argTokens,
            String mangled, Token usageTok) {
        Token structTpl = structTemplates.get(templateName);
        Token funcTpl = funcTemplates.get(templateName);
        Token ifaceTpl = interfaceTemplates.get(templateName);
        Token libTpl = libraryTemplates.get(templateName);
        Token template = structTpl != null ? structTpl : (funcTpl != null ? funcTpl : (ifaceTpl != null ? ifaceTpl : libTpl));
        if (template == null) {
            throw new CompilerException("type", usageTok.file, usageTok.line,
                    "'" + templateName + "' is not a known generic struct, func, interface, or library");
        }
        if (template.typeParams.size() != argNames.size()) {
            throw new CompilerException("type", usageTok.file, usageTok.line,
                    "'" + templateName + "' expects " + template.typeParams.size()
                            + " type argument(s), got " + argNames.size());
        }
        Map<String, String> subMap = new HashMap<>();
        // Threads the real, real call site's own file through the
        // substitution -- see substituteParams' own doc comment for why
        // this exists at all: without it, every reference to a substituted
        // type parameter inside the template's own body keeps the
        // *template's* declaring file (from deepClone below), so a private
        // struct passed in as a type argument from a different file was
        // incorrectly reported as inaccessible from its own template's file.
        Map<String, String> subFileMap = new HashMap<>();
        for (int i = 0; i < template.typeParams.size(); i++) {
            subMap.put(template.typeParams.get(i), argNames.get(i));
            subFileMap.put(template.typeParams.get(i), argTokens.get(i).file);
        }

        Token clone = deepClone(template);
        clone.sub.set(0, renamedCopy(clone.sub.get(0), mangled));
        clone.wasGenericTemplate = true;
        clone.pendingGenericBoundChecks = buildPendingBoundChecks(template, argNames);
        clone.typeParams = null;
        clone.typeParamBounds = null;
        substituteParams(clone, subMap, subFileMap);
        output.add(wrapAsLine(clone));
        walkAndProcess(clone); // discover/generate anything the substitution exposed

        if (structTpl != null) {
            List<Token> implTpls = implTemplatesByTarget.get(templateName);
            if (implTpls != null) {
                for (Token implTpl : implTpls) {
                    generateImplInstantiation(implTpl, argNames, argTokens, mangled);
                }
            }
        }
    }

    /**
     * Builds the "must implement this interface" checks a freshly
     * monomorphized instantiation still owes, one per type parameter the
     * template actually declared a bound on (`func f<T: SomeInterface>`) --
     * enforcement itself happens later, in TypeChecker.validateGenericBounds,
     * once every struct/impl is fully collected and `structImplementsInterface`
     * is reliable; this only records *what* needs checking, right at the
     * one instantiation site GenericsExpander already has both pieces of
     * information (which concrete type was substituted for which bound
     * parameter) in hand at the same time.
     */
    private List<Token.GenericBoundCheck> buildPendingBoundChecks(Token template, List<String> argNames) {
        if (template.typeParamBounds == null) {
            return null;
        }
        List<Token.GenericBoundCheck> checks = new ArrayList<>();
        for (int i = 0; i < template.typeParamBounds.size(); i++) {
            String bound = template.typeParamBounds.get(i);
            if (bound != null) {
                checks.add(new Token.GenericBoundCheck(template.typeParams.get(i), argNames.get(i), bound));
            }
        }
        return checks.isEmpty() ? null : checks;
    }

    private void generateImplInstantiation(Token implTpl, List<String> structArgNames, List<Token> structArgTokens,
            String mangledStructName) {
        Token concreteNameTok = implTpl.sub.get(implTpl.sub.size() - 1);
        Map<String, String> subMap = new HashMap<>();
        Map<String, String> subFileMap = new HashMap<>();
        if (implTpl.typeParams != null) {
            if (concreteNameTok.genericArgs.size() != structArgNames.size()) {
                throw new CompilerException("type", concreteNameTok.file, concreteNameTok.line,
                        "impl's type-argument count for '" + concreteNameTok.text + "' doesn't match "
                                + "its declared arity");
            }
            for (int i = 0; i < concreteNameTok.genericArgs.size(); i++) {
                String implParamName = concreteNameTok.genericArgs.get(i).get(0).text;
                subMap.put(implParamName, structArgNames.get(i));
                subFileMap.put(implParamName, structArgTokens.get(i).file);
            }
        }

        Token clone = deepClone(implTpl);
        // Any of implTpl's own declared type params NOT consumed matching
        // the target struct's own generic arguments (subMap's own keys) is
        // "residual" -- exists purely for this impl's own methods (the
        // "impl with independent type params" feature) -- tagged onto
        // whichever individual method tokens actually reference it, rather
        // than surviving on the impl declaration itself (which always ends
        // up with typeParams cleared to null here, same as before).
        tagResidualGenericMethods(implTpl, clone, subMap.keySet());
        Token cloneConcreteTok = clone.sub.get(clone.sub.size() - 1);
        substituteParams(clone, subMap, subFileMap); // substitutes inside method bodies/signatures AND the header tokens below
        cloneConcreteTok.text = mangledStructName;
        cloneConcreteTok.genericArgs = null;
        // A constructor method (Parser.gatherConstructorImpl's
        // "impl<T> constructor for X<T>(...) self {...}" shape) is a
        // plain "func" token tagged with `isConstructorDecl`/
        // `constructorConcreteName` -- neither is reachable by
        // `substituteParams`'s VARREF-text-matching walk (the method's
        // own name text, "__ctor__X", never matches a type-param name in
        // `subMap`, and `constructorConcreteName` is a plain String
        // field, not a Token tree node at all), so both are rewritten
        // here explicitly: the method's callable name must become
        // "__ctor__<mangledStructName>" (matching the exact
        // "CONSTRUCTOR_NAME_PREFIX + concreteName" key
        // TypeChecker.collectFunc/checkCall register/look candidates up
        // under for the now-concrete, monomorphized struct), and
        // `constructorConcreteName` itself must name the mangled struct,
        // not the original generic template name -- otherwise
        // TypeChecker's own RVO/struct-exists checks for this
        // constructor would look up a struct that was never actually
        // declared (the generic template name is never a real,
        // instantiable struct on its own).
        for (Token method : clone.childs) {
            method.fromGenericInstance = true;
            if (method.isConstructorDecl) {
                method.sub.get(0).text = Parser.CONSTRUCTOR_NAME_PREFIX + mangledStructName;
                method.constructorConcreteName = mangledStructName;
            }
        }
        if (clone.sub.size() == 2) {
            Token cloneInterfaceTok = clone.sub.get(0);
            // interfaceNameTok may itself be a (now-substituted) generic
            // reference, e.g. "Container<T>" -> "Container<Point>" --
            // resolve/generate it the same uniform way.
            walkAndProcess(cloneInterfaceTok);
        }
        output.add(wrapAsLine(clone));
        walkAndProcess(clone);
    }

    /**
     * An impl declaring its own type parameter(s) whose target isn't
     * generic at all (or, if it is generic, doesn't consume every one of
     * the impl's own declared params) -- every param never consumed is
     * "residual," used purely inside this impl's own methods (the "impl
     * with independent type params" feature). This impl itself is already
     * concrete (nothing to monomorphize per-target-instantiation, since the
     * target struct isn't being instantiated here at all), so it's placed
     * directly into `output`, with each qualifying method tagged the same
     * way `generateImplInstantiation` tags one for its own residual params.
     */
    private void processImplWithOnlyResidualParams(Token lineEntry) {
        Token entry = lineEntry.childs.get(0);
        tagResidualGenericMethods(entry, entry, Collections.emptySet());
        output.add(lineEntry);
        walkAndProcess(entry);
    }

    /**
     * Computes which of implTpl's own declared type params were not
     * consumed by `consumedNames` (the impl's own param names that were
     * successfully matched against the target struct's own generic
     * arguments -- empty when the target isn't generic at all), and tags
     * each method token in implClone.childs whose own subtree actually
     * references one or more of those residual names with its own
     * .typeParams (just the referenced subset, in implTpl's own declared
     * order) and .typeParamBounds -- a deliberate, new exception to "every
     * method token TypeChecker ever sees has typeParams == null" (see
     * Token.typeParams' own doc comment): here it instead marks "this
     * specific method is itself a residual-type-param template," consumed
     * later by TypeChecker at each explicit ":<...>" call site. A method
     * that doesn't reference any residual param at all is left completely
     * untouched -- an ordinary, non-generic method, unaffected by any of
     * this. `implTpl`/`implClone` may be the same object (see
     * processImplWithOnlyResidualParams, which never clones at all) -- this
     * method always finishes reading implTpl's own fields before writing
     * anything back, so that's safe either way. implTpl.typeParams itself
     * is always cleared to null on implClone at the end -- a residual param
     * only ever lives on the individual methods that use it from here on,
     * never on the impl declaration itself.
     */
    private void tagResidualGenericMethods(Token implTpl, Token implClone, Set<String> consumedNames) {
        if (implTpl.typeParams == null) {
            implClone.typeParams = null;
            return;
        }
        List<String> residualNames = new ArrayList<>();
        List<String> residualBounds = new ArrayList<>();
        for (int i = 0; i < implTpl.typeParams.size(); i++) {
            String pname = implTpl.typeParams.get(i);
            if (!consumedNames.contains(pname)) {
                residualNames.add(pname);
                residualBounds.add(implTpl.typeParamBounds == null ? null : implTpl.typeParamBounds.get(i));
            }
        }
        if (!residualNames.isEmpty() && implClone.childs != null) {
            for (Token methodTok : implClone.childs) {
                List<String> used = new ArrayList<>();
                List<String> usedBounds = new ArrayList<>();
                for (int i = 0; i < residualNames.size(); i++) {
                    if (referencesName(methodTok, residualNames.get(i))) {
                        used.add(residualNames.get(i));
                        usedBounds.add(residualBounds.get(i));
                    }
                }
                if (!used.isEmpty()) {
                    methodTok.typeParams = used;
                    boolean anyBound = false;
                    for (String b : usedBounds) {
                        if (b != null) {
                            anyBound = true;
                            break;
                        }
                    }
                    methodTok.typeParamBounds = anyBound ? usedBounds : null;
                }
            }
        }
        implClone.typeParams = null;
    }

    /**
     * Recursively scans `node`'s own subtree (left/right/childs/sub,
     * genericArgs, typeBound) for a VARREF whose text is exactly `name` --
     * used to decide whether a given impl method actually uses one of its
     * own impl's residual type parameters at all, so a plain, non-generic
     * sibling method on the same impl is left completely untouched.
     */
    private boolean referencesName(Token node, String name) {
        if (node == null) {
            return false;
        }
        if (node.type == TokenType.VARREF && node.text.equals(name)) {
            return true;
        }
        if (referencesName(node.left, name) || referencesName(node.right, name)) {
            return true;
        }
        if (node.childs != null) {
            for (Token c : node.childs) {
                if (referencesName(c, name)) {
                    return true;
                }
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                if (referencesName(s, name)) {
                    return true;
                }
            }
        }
        if (node.genericArgs != null) {
            for (List<Token> group : node.genericArgs) {
                for (Token g : group) {
                    if (referencesName(g, name)) {
                        return true;
                    }
                }
            }
        }
        if (node.typeBound != null) {
            for (Token tb : node.typeBound) {
                if (referencesName(tb, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Token renamedCopy(Token nameTok, String newName) {
        Token copy = new Token(nameTok.type, newName, nameTok.line, nameTok.file);
        return copy;
    }

    /** Blanket VARREF-text substitution -- see class doc for the documented shadowing caveat. */
    /**
     * `subFileMap` (template param name -> the real call site's own file,
     * i.e. the file the actual type-argument token was written in) is a
     * known, narrow limitation fix -- "a generic stdlib function's own
     * monomorphized tokens carry the template's declaring file... not the
     * real call site's," confirmed directly as the root cause of a
     * false-positive private-member-access error when a generic stdlib
     * function is instantiated with a caller's own private struct as its
     * type argument. Every substituted VARREF's own `.file` is corrected
     * to the real call site's file alongside its `.text`, so a later
     * privacy check (`checkTypeNameVisibility`, keyed on `at.file`) sees
     * the reference as if it were written at the real call site, not
     * inside the template's own body. `null` is accepted (some callers --
     * `impl_default_lock_match`'s own use of a structurally similar
     * substitution helper elsewhere, or any future caller with no
     * real per-argument file to thread through -- have none to give), in
     * which case only `.text` is substituted, exactly as before this fix.
     */
    private void substituteParams(Token node, Map<String, String> subMap, Map<String, String> subFileMap) {
        if (node == null) {
            return;
        }
        if (node.type == TokenType.VARREF && subMap.containsKey(node.text)) {
            String origName = node.text;
            node.text = subMap.get(origName);
            if (subFileMap != null && subFileMap.containsKey(origName)) {
                node.file = subFileMap.get(origName);
            }
        }
        substituteParams(node.left, subMap, subFileMap);
        substituteParams(node.right, subMap, subFileMap);
        if (node.childs != null) {
            for (Token c : node.childs) {
                substituteParams(c, subMap, subFileMap);
            }
        }
        if (node.sub != null) {
            for (Token s : node.sub) {
                substituteParams(s, subMap, subFileMap);
            }
        }
        if (node.genericArgs != null) {
            for (List<Token> group : node.genericArgs) {
                for (Token g : group) {
                    substituteParams(g, subMap, subFileMap);
                }
            }
        }
        if (node.typeBound != null) {
            for (Token t : node.typeBound) {
                substituteParams(t, subMap, subFileMap);
            }
        }
    }

    // ---- deep clone -----------------------------------------------------

    private Token deepClone(Token src) {
        Token copy = new Token(src.type, src.text, src.line, src.file);
        copy.literalValue = src.literalValue;
        copy.quoteDelimiter = src.quoteDelimiter;
        copy.pinnedComments = src.pinnedComments; // comments carry no semantic meaning past this point
        copy.unary = src.unary;
        copy.typeParams = src.typeParams == null ? null : new ArrayList<>(src.typeParams);
        copy.typeParamBounds = src.typeParamBounds == null ? null : new ArrayList<>(src.typeParamBounds);
        // A real, pre-existing gap found and fixed while implementing
        // @guard: decorators were never copied at all, so any decorator
        // on a generic declaration (struct/func/interface) was silently
        // lost the instant it was monomorphized -- never caught before,
        // since nothing previously depended on a decorator surviving
        // generic expansion (a plain generic struct with @unpadded, or
        // a generic func with @recursive, would have silently lost the
        // decorator too, not just @guard specifically).
        copy.decorators = src.decorators;
        // A third, separate instance of the exact same class of gap,
        // found while building @pub{}: a struct member's own
        // `isPubBlockMember` flag (set by Parser.stripDecoratorsDeep,
        // read by TypeChecker.flattenStruct to populate a struct's own
        // `publicMembers`) was never copied either, so any @pub{}-
        // marked member of a *generic* struct silently reverted to
        // private the instant it was monomorphized -- confirmed
        // directly by testing DynamicArray<T>'s own "len" field, which
        // compiles fine as plain source but failed the moment
        // DynamicArray<u64> was actually instantiated.
        copy.isPubBlockMember = src.isPubBlockMember;
        // A second, separate instance of the exact same class of gap,
        // found while building a generic hash function: a bound-type
        // "let:<...>" declaration's own bound was never copied either,
        // so it silently vanished the instant its enclosing function
        // was monomorphized -- "let:<raw mut u8> b = null" would
        // compile fine standalone, but inside *any* generic function
        // body, its own bound was already gone by the time
        // TypeChecker ever saw it, making a legitimate "null" RHS look
        // unbound and indeterminate instead. Deep-cloned, *not*
        // shallow-copied like typeParams just above -- typeParams is a
        // list of plain strings (nothing can ever mutate one in place),
        // but typeBound is a list of real Tokens, and substituteParams
        // (below) mutates a VARREF's own `.text` in place -- sharing
        // the same Token objects across two different instantiations of
        // one template (e.g. "DynamicArray<u64>" and
        // "DynamicArray<Point>") would let substituting the first
        // corrupt the still-unprocessed template copy meant for the
        // second.
        if (src.typeBound == null) {
            copy.typeBound = null;
        } else {
            copy.typeBound = new ArrayList<>();
            for (Token t : src.typeBound) {
                copy.typeBound.add(deepClone(t));
            }
        }
        copy.left = src.left == null ? null : deepClone(src.left);
        copy.right = src.right == null ? null : deepClone(src.right);
        copy.childs = new ArrayList<>();
        if (src.childs != null) {
            for (Token c : src.childs) {
                copy.childs.add(deepClone(c));
            }
        }
        copy.sub = new ArrayList<>();
        if (src.sub != null) {
            for (Token s : src.sub) {
                copy.sub.add(deepClone(s));
            }
        }
        if (src.genericArgs != null) {
            copy.genericArgs = new ArrayList<>();
            for (List<Token> group : src.genericArgs) {
                List<Token> copiedGroup = new ArrayList<>();
                for (Token g : group) {
                    copiedGroup.add(deepClone(g));
                }
                copy.genericArgs.add(copiedGroup);
            }
        }
        // A third, separate instance of the exact same class of gap,
        // found while adding static-method support: a "static func"
        // declaration's own `isStaticMethod` flag was never copied
        // either, so "static func fromValue(...)..." inside
        // "impl<T> Holder<T>{...}" silently became an ordinary instance
        // method the moment "Holder<T>" was monomorphized -- confirmed
        // directly ("Holder:<u64>.fromValue(...)" failed as "'fromValue'
        // is an instance method... not a static method"), not assumed.
        copy.isStaticMethod = src.isStaticMethod;
        // A fourth and fifth instance of the exact same class of gap
        // (decorators, isPubBlockMember, typeBound, isStaticMethod, all
        // found and fixed above in earlier rounds): "extends"/
        // "implements" were never copied at all, so a generic
        // interface/library with its own "extends"
        // clause silently lost every one of its parents the instant it
        // was monomorphized -- confirmed directly this needed fixing
        // for real while adding generic library support, since a
        // generic library extending another one is exactly the shape
        // that first exercises this path. Plain lists of strings, like
        // typeParams just above -- nothing ever mutates one of these in
        // place, so a shallow copy of the list itself (not a deep clone
        // of its elements) is sufficient.
        copy.extendsNames = src.extendsNames == null ? null : new ArrayList<>(src.extendsNames);
        copy.implementsNames = src.implementsNames == null ? null : new ArrayList<>(src.implementsNames);
        // A sixth instance of the exact same class of gap (decorators,
        // isPubBlockMember, typeBound, isStaticMethod, extends/implements,
        // all found and fixed above): a "try ... catch(e){...}" node's own
        // `catchParamName` (set once, by Parser.gatherTryCatchSpan, and
        // otherwise never recomputed -- see its own doc comment) was never
        // copied either, so a generic function/method with a "try...catch"
        // in its own body silently lost its catch parameter's name the
        // instant it was monomorphized: `TypeChecker.checkTry` still ran
        // (nothing about it is generic-specific), but
        // `catchBodyScope.vars.put(tryTok.catchParamName, ...)` bound a
        // `null` key instead of "e", so `throw e` inside the very same
        // catch body -- reading the value this catch just caught, straight
        // back off its own bound parameter -- failed as "use of
        // undeclared variable 'e'". Confirmed directly by reproducing:
        // `Box:<T>.make(...)`'s own "let result = try new Box:<T>{...}
        // catch(e){ throw e }" compiled fine as plain, non-generic source
        // but failed exactly this way once `Box<T>` was actually
        // instantiated with a concrete type argument.
        copy.catchParamName = src.catchParamName;
        // A seventh instance of the exact same class of gap (decorators,
        // isPubBlockMember, typeBound, isStaticMethod, extends/implements,
        // catchParamName, all found and fixed above): a constructor
        // method's own `isConstructorDecl`/`constructorConcreteName`
        // (set by Parser.gatherConstructorImpl for "impl constructor for
        // X(...) self {...}") were never copied either, so
        // "impl<T> constructor for X<T>(...) self {...}" would silently
        // clone into an ordinary instance method named "__ctor__X" the
        // instant "X<T>" was monomorphized -- TypeChecker.collectImpl's
        // constructor-detection branch checks `methodTok.isConstructorDecl`
        // on the *cloned* token, so losing this flag here would make the
        // clone fall through to ordinary (non-overloading) method
        // registration instead, and `constructorConcreteName` (a plain
        // String field, not reachable by substituteParams's VARREF-based
        // rewrite) would still name the original *generic* struct rather
        // than the mangled concrete one.
        copy.isConstructorDecl = src.isConstructorDecl;
        copy.constructorConcreteName = src.constructorConcreteName;
        return copy;
    }
}
