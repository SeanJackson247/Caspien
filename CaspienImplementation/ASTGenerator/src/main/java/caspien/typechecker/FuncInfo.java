package caspien.typechecker;

import caspien.token.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FuncInfo {
    public String name;
    /** Defaults to `name`; overridden for impl methods. */
    public String mangledName;
    public final List<String> paramNames = new ArrayList<>();
    public final List<TypeInfo> paramTypes = new ArrayList<>();
    public TypeInfo returnType;
    public Token funcToken;
    /** Root-list position (impl method: its impl's), for the globals "referenced before declaration" check; MAX_VALUE = no restriction. */
    public int declOrder = Integer.MAX_VALUE;
    /** "unsafe"/"safe"/"indeterminate" from the enclosing wrapper block; never null. */
    public String safetyTag = "indeterminate";
    /** Marked by "export foo": BytecodeEmitter emits an EXPORT line. Never true for impl methods. */
    public boolean isExported = false;
    /** Resolved calling convention (decorator or config default); read for every CALL/RECURSIVE_CALL. */
    public String callConvention;
    /** @async: invoked only through par/await, and par/await only accept @async functions (checkCall enforces both directions). */
    public boolean isAsync;
    /** @throws: required, and only allowed, on a function whose body throws; every call needs try/catch (requireThrowsWrapping). */
    public boolean isThrows;
    /** Set when a 'throw' is checked in this body; checkFunctionBody uses it to reject an unneeded @throws. */
    public boolean sawThrow;
    /** @pub; for impl methods also gated by the enclosing ImplInfo's `isPublic`. See docs/TypeChecker.md, "Visibility". */
    public boolean isPublic;
    /** Null for a plain top-level func; else the impl this method belongs to. */
    public ImplInfo enclosingImpl;
    /** Null unless a library function: the library that declared it (for requireLibraryVisibility). */
    public LibraryInfo enclosingLibrary;
    /** Count of bare loop{} statements already exempted in an @event_loop function (only the first is). */
    public int eventLoopSafeLoopCount;
    /** Every owns-typed local of this function, across all scopes, so BytecodeEmitter can null a slot after its scope-end GT_DESTRUCT (see emitDestructList). */
    public Map<String, TypeInfo> ownsLocalTypes = new LinkedHashMap<>();
    /** Argument shape of the real 'main': null, "c_args" or "safe_args" (validateMainFunction). Read by validateTickEventLoop and BytecodeEmitter. */
    public String mainArgShape;
    /** True for "impl constructor for Name(...)" (see collectFunc). */
    public boolean isConstructor;
    /** For a constructor: the struct it builds; read by checkInstantiate's OTC gate and checkNew/checkCall errors. */
    public String constructorConcreteName;
}
