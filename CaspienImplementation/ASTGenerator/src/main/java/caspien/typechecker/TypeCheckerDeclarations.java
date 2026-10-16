package caspien.typechecker;
import caspien.config.CompilerException;
import caspien.token.Token;
import caspien.token.TokenType;
import caspien.config.TraceConfig;
import caspien.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import caspien.token.Decorator;

/**
 * Stage 6: type and logic checking over the import-resolved, tree-built program.
 * Type equality in comparisons is base-type only; see docs/TypeChecker.md, "Design decisions".
 */

/** TypeCheckerDeclarations: entry point, root declaration collection, struct/enum/interface flattening (part 2 of 11; see README.md in this folder). */
public final class TypeCheckerDeclarations {
    private final TypeChecker hub;

    TypeCheckerDeclarations(TypeChecker hub) { }

    /** Struct name a `ref` points at, for ProofKills (null when it is not a plain struct: then any destroying event ends the proof). */
    public String pointeeOf(TypeInfo t) { return null; }

    /** Names of the types an object of type t can contain by ownership (itself, owned members, inline members, transitively); null = unknown. */
    public Set<String> destroyedTypes(TypeInfo t) { return null; }

    public void collectDestroyed(TypeInfo root, Set<String> out) { }

    /** An inline (no storage keyword) struct value that owns memory through some member: it has to be dropped at scope end and moved, not copied. */
    public boolean isInlineOwningStruct(TypeInfo t) { return false; }

    /** `Holder[2][3]` -> `Holder`: a fixed array of inline owning structs is an owned slot too (dropped element by element, moved as a whole). */
    public String stripArrayDims(String baseType) { return null; }

    /** True for a fixed array type (`Holder[2]`). */
    public boolean isFixedArrayTypeText(String baseType) { return false; }

    /** True when a dynarray element, given as the canonical text in a dynarray type (`Holder`, `owns_some_mut_World`), owns memory. */
    public boolean elementTextOwnsMemory(String elemText) { return false; }

    /** The unsafe tag an operation on this unsafe dynarray needs: `udyn:owns` when its elements own memory (the programmer destructs them by hand), else `udyn`. */
    public String udynTag(TypeInfo unsafeDynArrayType) { return null; }

    /** Called where `unsafe` bypasses a rule: true in unsafe context, and records `tag` on the enclosing tagged block. Call only when the bypass is actually exercised. */
    public boolean unsafeBypass(Scope scope, String tag) { return false; }

    /** Local ASM names (scope chain) first, then root-level; null if unknown. */
    public AsmInfo lookupAsmName(String name, Scope scope) { return null; }

    public void recordUnavailable(String key, String shownName, String concreteName, boolean ctor, DeferredSignatureException e) { }

    public String unavailableNote(String key) { return null; }

    /** For BytecodeEmitter: the resolved declarations this checker collected, valid after check() runs. */
    public Map<String, StructInfo> getStructs() { return null; }
    public Map<String, EnumInfo> getEnums() { return null; }
    public Map<String, List<FuncInfo>> getFunctions() { return null; }
    public Map<String, InterfaceInfo> getInterfaces() { return null; }
    public Map<String, List<ImplInfo>> getImpls() { return null; }
    public Map<String, LibraryInfo> getLibraries() { return null; }
    public Map<String, ExternInfo> getExterns() { return null; }
    public List<String> getGlobalOrder() { return null; }
    public Map<String, TypeInfo> getGlobals() { return null; }
    public FuncInfo getEventLoopFunc() { return null; }
    public Map<String, Token> getGlobalRhs() { return null; }

    /** For BytecodeEmitter: the (at most one) function decorated with the given ghost-table decorator name, or null if none exists in this compilation unit. */
    public FuncInfo getGhostTableFunction(String decoratorName) { return null; }
    public boolean usesOwnsRefDynNew() { return false; }
    /** True when the program throws, or when stack tracing is on (it needs the throw-style frame layout in every function). */
    public boolean usesThrow() { return false; }

    /** Does every path through a 'match @lock' CLOSED body end in continue / return / throw / break? */
    public boolean closedCaseTerminates(List<Token> lines) { return false; }

    /** True once "structName" has at least one declared constructor. */
    public boolean hasConstructors(String structName) { return false; }

    /**
     * Flattens root lines through unsafe/safe wrappers into declarations tagged with their safety context;
     * an 'unsafe' nested in 'safe' is an error (the reverse is allowed).
     */
    public List<FlatDecl> flattenRootLines(List<Token> lines, String currentSafety) { return null; }

    /** Entry point: type/logic-checks a fully resolved program's top-level statements. */
    public void check(List<Token> rootLines) { }

    /** All top-level funcs (any overload) with the given decorator; @tick/@event_loop/@make_safe_args are found by decorator, not by name. */
    public List<FuncInfo> findFuncsWithDecorator(String decoratorName) { return null; }

    /** Requires "owns some mut" of a concrete struct; returns the struct name so callers can compare "same T". */
    public String requireOwnsSomeMutStruct(TypeInfo t, String context, Token at) { return null; }

    /** Validates @with_tick, @tick and @event_loop together with main. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    public void validateTickEventLoop(FuncInfo main, boolean withTick) { }

    /** Exactly one 'main' with a valid signature, over the final import-flattened `functions` map. See docs/TypeChecker.md, "main, @tick, @event_loop, @make_safe_args". */
    public void validateMainFunction() { }

    /** Resolves main's argument shape: none (null), C-ABI "c_args", or the wrapped "safe_args". */
    public String validateAndResolveMainArgShape(FuncInfo main, Token at) { return null; }

    /** Type of the safe main argument: owns some mut dynarray of imut dynarrays of imut char. */
    public TypeInfo safeArgsType() { return null; }

    /** @make_safe_args must exist exactly once iff main uses safe_args, with signature (imut s32, raw mut raw mut char) returning safeArgsType(). */
    public void validateMakeSafeArgs(FuncInfo main) { }

    // ---- pass 1: collect and validate top-level declarations ------------

    public void collectDeclarations(List<Token> rootLines) { }

    /** "ASM name {...}"/"ASM name \"path\"" at the root -- see collectDeclarations' Pass 1e. */
    public void collectRootAsmDeclaration(Token t, String safety) { }

    /** A bare "asm_name;" line at the root -- see collectDeclarations' Pass 1e. */
    public void collectRootAsmInvocation(Token t, String safety) { }

    /** Returns the "=" assign node if `lineTok` is a root-level "let static NAME = ..." global, else null. */
    public Token globalLetAssignOf(Token lineTok) { return null; }

    /**
     * "let static NAME = ..." at the root: the RHS follows the local-static rules but may name an earlier global.
     * See docs/TypeChecker.md, "Top-level declaration collection".
     */
    public void collectGlobal(Token assignNode, int declOrder) { }

    public void checkImplementsContract(StructInfo info) { }

    public Token requireTopLevelDecl(Token lineTok) { return null; }

    /**
     * "type NAME <target>": stores the raw target tokens. Collision with globals is checked from collectGlobal's
     * side.
     */
    public void registerAlias(Token t) { }

    /**
     * "const NAME EXPR": registered raw and evaluated lazily on first reference (consts are forward-declarable,
     * see resolveConstValue). See docs/TypeChecker.md, "Top-level declaration collection".
     */
    public void registerConst(Token t) { }

    /**
     * Raw registration of a library name (shares the struct/enum/interface namespace); its functions are built
     * later in collectLibrary.
     */
    public void registerLibraryName(Token t) { }

    public void registerStructLike(Token t) { }

    /**
     * Resolves `name`'s final member set (a struct has only its own
     * members -- struct inheritance does not exist) and validates its
     * `swap` mutex field and struct-level `@lock` clause.
     */
    public void flattenStruct(String name, Set<String> visiting) { }

    /**
     * The swap member of a "match @lock" target must be a two-variant enum named exactly OPEN and CLOSED (codegen
     * builds ATOMIC_SWAP calls against those names).
     */
    public void validateSwapMutexField(StructInfo structInfo, String structName, String fieldName, Token at) { }

    /**
     * Validates "@lock(match self.x : X)": the field exists, is enum-typed, and every variant belongs to that
     * enum. Shared by struct-level and method-level use.
     */
    public void validateLockEnumClause(Decorator d, StructInfo structInfo, String structName) { }

    /**
     * Validates every "@lock(match ...)" decorator on one method (impl method or interface signature); struct
     * receivers only. See docs/TypeChecker.md, "Method-level @lock".
     */
    public void validateMethodLocks(List<Decorator> decorators, List<String> paramNames,
            List<TypeInfo> paramTypes, String concreteName) { }

    /**
     * Validates "@lock(match x in|within self.f)" against the typing rules of checkIn / checkWithin (chosen per
     * clause by d.lockInOperators).
     */
    public void validateLockInClause(Decorator d, StructInfo si, String concreteName,
            List<String> paramNames, List<TypeInfo> paramTypes) { }

    public void collectEnum(Token t) { }

    /**
     * "interface Name{...}": signatures stay raw (self is not concrete yet) and are only checked structurally;
     * resolved per impl in collectImpl.
     */
    public void registerInterface(Token t) { }

    /**
     * Body-bearing interface methods require @default, signature-only ones forbid it; @overrides/@realizes belong
     * to impl methods only.
     */
    public void validateInterfaceMethodDecorators(Token methodTok, boolean isDefault, String interfaceName) { }

    /** Validates just the decorator arg-shapes on a func-shaped token, without building a real FuncInfo (used for interface default methods, which aren't registered as callable functions). */
    public void buildFuncInfoDecoratorsOnly(Token t) { }

    /**
     * Rejects 'self' anywhere in a signature or body: used for @default methods and static methods (`context`
     * names which, for the message).
     */
    public void forbidSelfReference(Token methodTok, String context) { }

    public void forbidSelfReferenceIn(Token root, String context) { }

    /** Resolves an interface's flattened method set (any number of extends; name collisions are errors). */
    public void flattenInterface(String rootName, Set<String> visiting) { }

    public void validateSelfIsPointer(RawMethodSig sig, String interfaceName) { }

    public void checkSelfStorageInTypeTokens(List<Token> typeTokens, String interfaceName) { }

    /**
     * "impl default match @lock S{ CLOSED:(tries:imut u64,...)=>{...} }": registers a retry policy, validated
     * eagerly at its declaration (only the parameter shape; the body is checked at each use site). See
     * docs/TypeChecker.md, "Impl collection".
     */
    public void collectDefaultLockMatchPolicy(Token t) { }
}
