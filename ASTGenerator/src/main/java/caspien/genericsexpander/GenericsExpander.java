package caspien.genericsexpander;
import caspien.token.TokenCloner;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.token.GenericBoundCheck;

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
                        registerTemplate(structTemplates, "struct", entry.declName().text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "func":
                    if (entry.typeParams != null) {
                        registerTemplate(funcTemplates, "func", entry.declName().text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "interface":
                    if (entry.typeParams != null) {
                        registerTemplate(interfaceTemplates, "interface", entry.declName().text, entry);
                    } else {
                        output.add(lineEntry);
                    }
                    break;
                case "library":
                    if (entry.typeParams != null) {
                        registerTemplate(libraryTemplates, "library", entry.declName().text, entry);
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
        List<Token> templates = implTemplatesByTarget.get(targetName);
        if (templates == null) {
            templates = new ArrayList<>();
            implTemplatesByTarget.put(targetName, templates);
        }
        templates.add(implEntry);
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

    private void scanAnnotationContexts(Token start) {
        java.util.Deque<Token> work = new ArrayDeque<>();
        work.push(start);
        while (!work.isEmpty()) {
            Token entry = work.pop();
            if (entry.type != TokenType.KEYWORD) {
                continue;
            }
            switch (entry.text) {
                case "struct":
                    for (Token memberLine : entry.childs) {
                        scanRawNameTypeGroups(memberLine.childs);
                    }
                    break;
                case "interface":
                case "library":
                    for (Token method : entry.childs) {
                        scanFuncOrMethodSignature(method);
                    }
                    break;
                case "type":
                    // "type NAME <target tokens>": a declaration annotation, never passed through toRpn, so a bare
                    // "List<u64>" target needs this scan to become a .genericArgs-bearing token.
                    scanTypeTokenSpan(entry.sub.subList(1, entry.sub.size()));
                    break;
                case "func":
                    scanFuncOrMethodSignature(entry); // bodies are expression context: nothing to scan
                    break;
                case "impl":
                    for (int i = entry.childs.size() - 1; i >= 0; i--) {
                        work.push(entry.childs.get(i)); // each is a "func" entry
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void scanFuncOrMethodSignature(Token funcOrMethod) {
        // sub = [nameTok, paramsTok, ...returnTypeTokens]
        Token paramsTok = funcOrMethod.paramsTok();
        scanRawNameTypeGroups(paramsTok.childs);
        List<Token> returnTypeTokens = funcOrMethod.returnTypeTokens();
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
        GenericsTask first = new GenericsTask(GenericsTask.SPAN, null);
        first.span = span;
        run(first);
    }

    /** One span: only the first base-type VARREF counts; nested argument groups are queued, then the post step. */
    private void stepSpan(GenericsTask task, List<GenericsTask> out) {
        List<Token> span = task.span;
        for (int i = 0; i < span.size(); i++) {
            Token t = span.get(i);
            if (t.type != TokenType.VARREF) {
                continue;
            }
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
                    out.add(spanTask(group)); // nested generics inside the arg list
                }
                GenericsTask post = new GenericsTask(GenericsTask.SPAN_ANGLE_POST, t);
                post.span = span;
                post.groups = groups;
                post.index = i;
                post.closeIdx = closeIdx;
                out.add(post);
            } else if (i + 1 < span.size() && span.get(i + 1).type == TokenType.DELINEATOR
                    && span.get(i + 1).text.equals("(")) {
                // "dynarray(...)"/"range(...)": a generic reference inside the parentheses; the "(" token's childs are a
                // flat token list (commas included), so they are split into groups, scanned, and written back.
                Token parenTok = span.get(i + 1);
                if (!parenTok.childs.isEmpty()) {
                    List<List<Token>> groups = splitTopLevelCommaGroupsBare(parenTok.childs);
                    for (List<Token> group : groups) {
                        out.add(spanTask(group)); // may remove tokens from `group` itself
                    }
                    GenericsTask post = new GenericsTask(GenericsTask.SPAN_PAREN_POST, parenTok);
                    post.groups = groups;
                    out.add(post);
                }
            }
            return; // exactly one base-type VARREF per span; nothing more to scan
        }
    }

    private GenericsTask spanTask(List<Token> span) {
        GenericsTask t = new GenericsTask(GenericsTask.SPAN, null);
        t.span = span;
        return t;
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

    /** Runs the walk over `node` (every nested generic usage is resolved and instantiated). */
    private void walkAndProcess(Token node) {
        run(new GenericsTask(GenericsTask.WALK, node));
    }

    /** Drives the task stack until it is empty; tasks queue their follow-ups in run order. */
    private void run(GenericsTask first) {
        Deque<GenericsTask> stack = new ArrayDeque<>();
        stack.push(first);
        List<GenericsTask> out = new ArrayList<>();
        while (!stack.isEmpty()) {
            GenericsTask t = stack.pop();
            out.clear();
            step(t, out);
            for (int i = out.size() - 1; i >= 0; i--) {
                stack.push(out.get(i));
            }
        }
    }

    private void step(GenericsTask task, List<GenericsTask> out) {
        switch (task.kind) {
            case GenericsTask.WALK:
            case GenericsTask.WALK_NO_TOP: {
                Token node = task.tok;
                if (node == null) {
                    return;
                }
                boolean top = task.kind == GenericsTask.WALK;
                if (top) {
                    out.add(new GenericsTask(GenericsTask.PROCESS, node));
                }
                out.add(new GenericsTask(GenericsTask.WALK, node.left));
                // A '.' operator's right side is a bare member/method NAME: any genericArgs on it are an explicit
                // method type argument ("recv.m:<U>(..)"), left for TypeChecker.checkMethodCall.
                boolean dot = top && node.type == TokenType.OPERATOR && node.text.equals(".");
                out.add(new GenericsTask(dot ? GenericsTask.WALK_NO_TOP : GenericsTask.WALK, node.right));
                out.add(new GenericsTask(GenericsTask.WALK, node.next));
                if (node.childs != null) {
                    for (Token c : node.childs) {
                        out.add(new GenericsTask(GenericsTask.WALK, c));
                    }
                }
                if (node.sub != null) {
                    for (Token s : node.sub) {
                        out.add(new GenericsTask(GenericsTask.WALK, s));
                    }
                }
                return;
            }
            case GenericsTask.PROCESS: {
                // Resolves t's generic arguments (nested instantiations first), generates its own instantiation if new,
                // and rewrites t into a plain reference to the mangled name. No-op without genericArgs.
                Token t = task.tok;
                if (t.genericArgs == null) {
                    return;
                }
                if (t.type == TokenType.VARREF && (t.text.equals("dyn") || t.text.equals("wrap") || t.text.equals("sat"))) {
                    // `dyn:<T>([])`: not a template; only the argument is resolved and genericArgs stay for TypeChecker.
                    out.add(new GenericsTask(GenericsTask.PROC_DYN, t));
                    return;
                }
                GenericsTask arg = new GenericsTask(GenericsTask.PROC_ARG, t);
                arg.argNames = new ArrayList<>();
                arg.argTokens = new ArrayList<>();
                out.add(arg);
                return;
            }
            case GenericsTask.PROC_DYN: {
                Token t = task.tok;
                if (task.index >= t.genericArgs.size()) {
                    return;
                }
                List<Token> group = t.genericArgs.get(task.index++);
                if (group.size() != 1) {
                    throw new CompilerException("type", t.file, t.line,
                            "generic type argument must be a single type name, found a compound expression");
                }
                out.add(new GenericsTask(GenericsTask.PROCESS, group.get(0)));
                out.add(task);
                return;
            }
            case GenericsTask.PROC_ARG: {
                Token t = task.tok;
                if (task.phase == 1) {
                    // the argument has been resolved: its text is now final
                    Token argTok = t.genericArgs.get(task.index).get(0);
                    task.argNames.add(argTok.text);
                    task.argTokens.add(argTok);
                    task.index++;
                    task.phase = 0;
                }
                if (task.index < t.genericArgs.size()) {
                    List<Token> group = t.genericArgs.get(task.index);
                    if (group.size() != 1) {
                        throw new CompilerException("type", t.file, t.line,
                                "generic type argument must be a single type name, found a compound expression");
                    }
                    task.phase = 1;
                    out.add(new GenericsTask(GenericsTask.PROCESS, group.get(0)));
                    out.add(task);
                    return;
                }
                String templateName = t.text;
                String key = templateName + "<" + String.join(",", task.argNames) + ">";
                String mangled = memo.get(key);
                if (mangled == null) {
                    mangled = mangleName(templateName, task.argNames);
                    memo.put(key, mangled); // reserve first: breaks self-referential instantiation
                    beginInstantiation(templateName, task.argNames, task.argTokens, mangled, t, out);
                }
                GenericsTask set = new GenericsTask(GenericsTask.SET_MANGLED, t);
                set.mangled = mangled;
                out.add(set);
                return;
            }
            case GenericsTask.SET_MANGLED:
                task.tok.text = task.mangled;
                task.tok.genericArgs = null;
                return;
            case GenericsTask.INST_IMPLS: {
                List<Token> implTpls = implTemplatesByTarget.get(task.name);
                if (implTpls != null) {
                    for (Token implTpl : implTpls) {
                        GenericsTask it = new GenericsTask(GenericsTask.IMPL_INST, implTpl);
                        it.argNames = task.argNames;
                        it.argTokens = task.argTokens;
                        it.mangled = task.mangled;
                        out.add(it);
                    }
                }
                return;
            }
            case GenericsTask.IMPL_INST:
                beginImplInstantiation(task.tok, task.argNames, task.argTokens, task.mangled, out);
                return;
            case GenericsTask.IMPL_ADD:
                output.add(wrapAsLine(task.tok));
                out.add(new GenericsTask(GenericsTask.WALK, task.tok));
                return;
            case GenericsTask.SPAN:
                stepSpan(task, out);
                return;
            case GenericsTask.SPAN_ANGLE_POST: {
                // nested groups are scanned: attach them and consume "<...>" out of the span
                task.tok.genericArgs = task.groups;
                for (int k = task.closeIdx; k >= task.index + 1; k--) {
                    task.span.remove(k);
                }
                return;
            }
            case GenericsTask.SPAN_PAREN_POST: {
                // write the (now generic-args-stripped) groups back into the parenthesis, re-joined with fresh commas
                Token parenTok = task.tok;
                List<Token> rebuilt = new ArrayList<>();
                for (int g = 0; g < task.groups.size(); g++) {
                    if (g > 0) {
                        rebuilt.add(new Token(TokenType.OPERATOR, ",", parenTok.line, parenTok.file));
                    }
                    rebuilt.addAll(task.groups.get(g));
                }
                parenTok.childs.clear();
                parenTok.childs.addAll(rebuilt);
                return;
            }
            default:
                throw new IllegalStateException("unknown generics task " + task.kind);
        }
    }

    private String mangleName(String templateName, List<String> argNames) {
        StringBuilder sb = new StringBuilder(templateName);
        for (String a : argNames) {
            sb.append('_').append(a);
        }
        return sb.toString();
    }

    /** The synchronous part of one instantiation (lookups, clone, substitution, output); queues the follow-up walks. */
    private void beginInstantiation(String templateName, List<String> argNames, List<Token> argTokens,
            String mangled, Token usageTok, List<GenericsTask> out) {
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
        // The call site's file is threaded through so a private type passed as an argument stays accessible (see substituteParams).
        Map<String, String> subFileMap = new HashMap<>();
        for (int i = 0; i < template.typeParams.size(); i++) {
            subMap.put(template.typeParams.get(i), argNames.get(i));
            subFileMap.put(template.typeParams.get(i), argTokens.get(i).file);
        }

        Token clone = deepClone(template);
        clone.sub.set(0, renamedCopy(clone.declName(), mangled));
        clone.pendingGenericBoundChecks = buildPendingBoundChecks(template, argNames);
        clone.typeParams = null;
        clone.typeParamBounds = null;
        substituteParams(clone, subMap, subFileMap);
        output.add(wrapAsLine(clone));
        out.add(new GenericsTask(GenericsTask.WALK, clone)); // discover/generate anything the substitution exposed
        if (structTpl != null) {
            GenericsTask impls = new GenericsTask(GenericsTask.INST_IMPLS, null);
            impls.name = templateName;
            impls.argNames = argNames;
            impls.argTokens = argTokens;
            impls.mangled = mangled;
            out.add(impls);
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
    private List<GenericBoundCheck> buildPendingBoundChecks(Token template, List<String> argNames) {
        if (template.typeParamBounds == null) {
            return null;
        }
        List<GenericBoundCheck> checks = new ArrayList<>();
        for (int i = 0; i < template.typeParamBounds.size(); i++) {
            String bound = template.typeParamBounds.get(i);
            if (bound != null) {
                checks.add(new GenericBoundCheck(template.typeParams.get(i), argNames.get(i), bound));
            }
        }
        return checks.isEmpty() ? null : checks;
    }

    /** The synchronous part of one impl instantiation; queues the walks (interface name, then the impl itself). */
    private void beginImplInstantiation(Token implTpl, List<String> structArgNames, List<Token> structArgTokens,
            String mangledStructName, List<GenericsTask> out) {
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
        // Residual type params (not consumed by the target's arguments) are tagged onto the methods that use them.
        tagResidualGenericMethods(implTpl, clone, subMap.keySet());
        Token cloneConcreteTok = clone.sub.get(clone.sub.size() - 1);
        substituteParams(clone, subMap, subFileMap); // method bodies/signatures AND the header tokens
        cloneConcreteTok.text = mangledStructName;
        cloneConcreteTok.genericArgs = null;
        // A constructor method has its name and constructorConcreteName rewritten here: substituteParams cannot reach them.
        for (Token method : clone.childs) {
            method.fromGenericInstance = true;
            if (method.isConstructorDecl) {
                method.declName().text = Parser.CONSTRUCTOR_NAME_PREFIX + mangledStructName;
                method.constructorConcreteName = mangledStructName;
            }
        }
        GenericsTask add = new GenericsTask(GenericsTask.IMPL_ADD, clone);
        if (clone.sub.size() == 2) {
            // the interface name may itself be a (now-substituted) generic reference: resolve it first
            out.add(new GenericsTask(GenericsTask.WALK, clone.declName()));
            out.add(add);
        } else {
            output.add(wrapAsLine(clone));
            out.add(new GenericsTask(GenericsTask.WALK, clone));
        }
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
    private boolean referencesName(Token root, String name) {
        Deque<Token> stack = new ArrayDeque<>();
        pushNonNull(stack, root);
        while (!stack.isEmpty()) {
            Token node = stack.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.VARREF && node.text.equals(name)) {
                return true;
            }
            pushChildrenReversed(node, stack, true);
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
    private void substituteParams(Token root, Map<String, String> subMap, Map<String, String> subFileMap) {
        Deque<Token> stack = new ArrayDeque<>();
        pushNonNull(stack, root);
        while (!stack.isEmpty()) {
            Token node = stack.pop();
            if (node == null) {
                continue;
            }
            if (node.type == TokenType.VARREF && subMap.containsKey(node.text)) {
                String origName = node.text;
                node.text = subMap.get(origName);
                if (subFileMap != null && subFileMap.containsKey(origName)) {
                    node.file = subFileMap.get(origName);
                }
            }
            pushChildrenReversed(node, stack, true);
        }
    }

    /** Pushes left, right, next, childs, sub (and genericArgs, typeBound when asked) so that they pop in that order. */
    private void pushChildrenReversed(Token node, Deque<Token> stack, boolean withTypeParts) {
        if (withTypeParts) {
            if (node.typeBound != null) {
                for (int i = node.typeBound.size() - 1; i >= 0; i--) {
                    pushNonNull(stack, node.typeBound.get(i));
                }
            }
            if (node.genericArgs != null) {
                for (int gi = node.genericArgs.size() - 1; gi >= 0; gi--) {
                    List<Token> group = node.genericArgs.get(gi);
                    for (int i = group.size() - 1; i >= 0; i--) {
                        pushNonNull(stack, group.get(i));
                    }
                }
            }
        }
        if (node.sub != null) {
            for (int i = node.sub.size() - 1; i >= 0; i--) {
                pushNonNull(stack, node.sub.get(i));
            }
        }
        if (node.childs != null) {
            for (int i = node.childs.size() - 1; i >= 0; i--) {
                pushNonNull(stack, node.childs.get(i));
            }
        }
        pushNonNull(stack, node.next);
        pushNonNull(stack, node.right);
        pushNonNull(stack, node.left);
    }

    private static void pushNonNull(Deque<Token> stack, Token t) {
        if (t != null) {
            stack.push(t);
        }
    }

    // ---- deep clone -----------------------------------------------------

    private Token deepClone(Token src) {
        return TokenCloner.syntax(src);
    }
}
