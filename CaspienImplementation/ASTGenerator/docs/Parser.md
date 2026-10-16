# Parser

Reference notes for `Parser.java` (design, formats and rules). Design history is in [history.md](history.md).

## Pipeline and scoping

Covers: class `Parser`, `parse()`, `nest()`, `mergeLetInLine`, `collapseImportInLine`, whitespace removal.

`Parser` is stage 2 of the front end. It takes the Lexer's flat token list and runs, in the order given by `parse()`:

1. `nest()` - builds the `childs` of every delineator token; the matching close token is consumed and discarded. Unmatched/mismatched delineators are parse errors.
2. `gatherTemplateStringsDeep()` - collapses template-string markers (see "Template strings").
3. `splitIntoLines()` - groups each statement-sequence scope (file root, every `{` block) into LINE tokens, splitting on TERMINATOR (terminators are discarded).
4. `mergeLetInLine` - `let` absorbs the variable name token (and `static`, `const`, a `:<Type>` bound) into itself.
5. `collapseImportInLine` - an import line becomes one token, comments pinned within the line are preserved on it.
6. `stripDecoratorsDeep` - see "Decorator lines".
7. `removeWhitespaceInLines` - strips WHITESPACE everywhere.
8. `mergeThenOperators` - see "&&then / ||then".
9. `collapseGenericInstantiationsInLines` - see "Generic instantiation".
10. `insertCallLookupInLines` - see "CALL / LOOKUP insertion".
11. `gatherKeywordBlocks` - see "Keyword-block token shapes".

RPN conversion, tree building, imports and type checking happen in later stages, not in `Parser`.

Scoping rule: `{` delineators are statement-sequence scopes (their childs become LINE tokens). `(` and `[` are expression scopes: their childs stay a flat token list; a TERMINATOR found there (from a newline inside a call or array literal) carries no meaning and is removed like whitespace (`removeTerminatorsInFlat`), otherwise a multi-line call/array literal fails downstream expecting an operator.

`let` details: `let const` / `let static const` - `const` comes right after `static` (if present) and before the `:<Type>` bound. `const` stays a KEYWORD token everywhere (never retyped MODIFIER) because the top-level `const NAME EXPR` declaration owns the same word; the two are told apart by position. `let:<Type> name = expr` and `let static:<Type> name = expr` carry the bound in `typeBound`; the pass runs before whitespace removal, so it skips whitespace by hand.

## Template strings

Covers: `gatherTemplateStringsDeep`, `removeWhitespaceFromExprGroup`.

Example: `` let msg = `Important Config file(${CONFIG_FILE}) not found on ${__FILENAME}:${__LINE}` ``.

The lexer emits a flat marker sequence TEMPLATE_STRING_START / TEMPLATE_STRING_PART / TEMPLATE_EXPR_START / `{...}` (already grouped by `nest()` like any other brace group) / TEMPLATE_STRING_END. `gatherTemplateStringsDeep` collapses it into one TEMPLATE_STRING token whose `childs` alternate literal-text pieces and one nested expression group per `${...}`. Nothing downstream (splitIntoLines, RpnConverter, TreeBuilder) sees the markers. It recurses over the whole tree because a template string may appear anywhere an expression can.

Interior whitespace of a `${...}` group is stripped immediately (`removeWhitespaceFromExprGroup`, recursing into `(` and `[` groups only; a `{` struct literal cannot appear in a compile-time-only expression), because every later pass that strips whitespace, collapses generics or inserts CALL markers only recurses into plain `(`/`[`/`{` DELINEATORs, never into a TEMPLATE_STRING's segments. Without this `${A + B}` reached RpnConverter/TreeBuilder with WHITESPACE tokens. `insertCallLookupInFlat` additionally walks the `(` segments of a TEMPLATE_STRING so `${f()}` gets a CALL marker and fails with the dedicated "a compile-time constant expression cannot call a function" error from `evaluateConstantOperator` rather than a raw parse error from `toRpn`.

## Line splitting

Covers: `splitIntoLines`, `BLOCK_HEADER_KEYWORDS`, `lineStartsWithBlockHeaderKeyword`, `splitNestedBlocks`, `forEachLineDeep`.

`splitIntoLines` splits on TERMINATOR and also forces a line break immediately after a `{` token, but only when the line starts with a block-header keyword (`if elseif else loop func struct enum interface impl for library`). A closed block therefore always ends its line: `if x { ret } else { ret }` splits into two lines as if `else` were on its own line. A `{` on any other line is presumed to be a struct-literal instantiation (`Point{ x= 1, y= 2 }`) and must not split, or `let point = Point{...}` would be cut after the opening brace. `(` and `[` never force a break (whatever follows a call or subscript on the same line is the same statement, e.g. `foo() + 1`). After splitting, every `{` block found in a line has its childs split recursively; `(`/`[` childs stay flat but are walked in case a `{` is nested in them.

`forEachLineDeep` / `scanForBlockLines` visit every LINE token anywhere in the tree (descending into `{` blocks only).

## Decorator lines

Covers: `stripDecoratorsDeep`, `parseDecoratorLine`, `isPubBlockShape`, `isDecoratorWord`.

A line whose first token is `@` is a decorator that runs to the end of the line. Each run of consecutive decorator lines is removed from the line list and attached, in stacking order, as parsed `Decorator` objects to the first token of the next line; every later `gather*` method that wraps that raw token copies `.decorators` across. The pass runs deep (into every nested `{`), so a decorator may precede any construct at root or inside a function body (for/loop statements etc). Which decorators are valid on which construct is the TypeChecker's job; the parser only recognises the shapes `@name` and `@name(arg,arg)`. Arguments must be a string literal, a bare identifier, or a whole number (`@unroll(4)`); anything else is an error. A decorator with nothing after it in the block is an error ("decorates nothing").

Decorator names and arguments are bare words and may also be reserved keywords (VARREF or KEYWORD token): decorator names live in their own namespace.

`@pub{ ... }` is a separate shape (groups several struct fields as public): handled entirely in `stripDecoratorsDeep` rather than `parseDecoratorLine` (which rejects anything after the name/args). `isPubBlockShape` accepts exactly `@pub` followed by a trailing `{` (not `@pub(...)`). The block is unwrapped: its inner lines (already line-split by the earlier recursive pass) are spliced into the result in place of the `@pub{...}` line, each one's first token marked `isPubBlockMember`, and each inner line is run through this same pass (so a decorator on one field inside the block works, and decorators pending before the `@pub{` line attach to the first member).

## @lock(match) qualifiers

Covers: `parseLockMatchQualifier`, `parseLockEnumQualifier`, `parseLockInQualifier`.

`@lock(match ...)` has its own grammar, parsed from the raw tokens inside the parens (separate from the ordinary identifier/string argument path). The word after `match` selects the shape; once chosen only that shape's grammar is accepted:

- Enum style, `@lock(match self.x : X)`: `self`, `.`, field name, `:`, then one or more `|`-chained variant names (e.g. `TRUE|FALSE`), collected in order into `lockFieldName` / `lockVariants`.
- "in" style, `@lock(match i in self.x2 [and i2 in self.x3]*)`: one or more `and`-chained `PARAM OP self.FIELD` clauses (`lockInParams/Fields/Operators/SomeWrapped`). OP may be `in`, `within` or `into`, recorded per clause so later validation uses the operator actually written. `Some(i)` wraps the parameter name (the index-bounds-plus-element-alive sugar of the bare-match/for-match forms); "Some" followed by `(` means a wrapped name, not a parameter literally named Some. The parenthesised content is read from the `(` token's childs (nest() already moved it there).

Both shapes are usable on a struct and on a method. Nothing may follow the closing paren of the decorator.

## &&then / ||then

Covers: `mergeThenOperators`, `mergeThenInFlat`.

`&&then` and `||then` are lazy-chain binary OPERATOR tokens. They are not desugared in the parser (unlike `?`): they survive RpnConverter/TreeBuilder/TypeChecker as first-class nodes and only get their short-circuit instruction shape in BytecodeEmitter. The parser only unifies spelling: `&&then` and `&& then` both tokenize (once whitespace is gone) as OPERATOR `&&`/`||` followed by VARREF `then`; the pass merges that pair into one synthetic OPERATOR with text `&&then`/`||then`.

`then` is not a reserved keyword (same structural, non-reserved precedent as `lock` in `isLockStatementStart`): a bare VARREF `then` stays a legal variable/function name elsewhere; only the adjacency `&&`/`||` + VARREF `then` is merged. Accepted trade-off: `a && then` where `then` is a real boolean variable is merged into a valueless `&&then` and fails downstream with a generic "missing operand" error.

Ordering: runs right after `removeWhitespaceInLines` and before `insertCallLookupInLines`; afterwards `&&then(x)` would have `then(` read as a CALL, corrupting the merge target.

## Generic instantiation (':<...>')

Covers: `collapseGenericInstantiationsInLines/InFlat`, `collapseOneGenericSpan`, `splitTopLevelCommaGroups`, `splitTopLevelCommaGroupsBare`.

In expression position a generic instantiation is written `Name:<A, B>`. `collapseGenericInstantiationsInFlat` finds `VARREF ':<' ... '>'` spans and collapses each into one VARREF-typed token at the base name's position carrying `.genericArgs` (a `List<List<Token>>`, one raw token group per comma-separated type argument). Keeping type VARREF means every later stage that looks for a plain VARREF (RpnConverter operand handling, `insertCallLookupInFlat`'s "VARREF followed by `(`", TreeBuilder's struct-literal-brace detection) works unmodified.

`collapseOneGenericSpan` scans forward with a depth counter: each further `:<` opens a level (a nested generic inside a function body must also use the colon form, e.g. the inner `List:<u64>` in `List:<List:<u64>>{...}`, so a bare `<` never appears in the span). The argument tokens are split on top-level commas (`splitTopLevelCommaGroups` tracks `:<`/`>` depth), nested spans are collapsed recursively per group, and the whole span is replaced in place. An empty argument list or a missing `>` is a parse error.

Skipped: lines starting with `struct enum interface type extern export ASM` (declared-type contexts, never expressions; generic annotations there use the bare colon-less form, parsed by the `gather*` methods and by GenericsExpander), and `func`/`impl`/`library` headers, where processing jumps straight to the trailing `{` body.

## Type-parameter lists

Covers: `parseOptionalTypeParams`, `parseOptionalTypeArgsRaw`, `splitTopLevelCommaGroupsBare`, `TypeParamParseResult`, `TypeArgParseResult`.

Used only from header positions (struct/func/interface/impl names), which never go through RpnConverter, so a bare `<` is unambiguous with the comparison operator. `parseOptionalTypeParams` parses a declaration-site list `<Name[: Bound], Name[: Bound], ...>`; `names` is null if there is no `<`, `bounds` is parallel to `names` (null entries = no bound; whole list null if no bound anywhere). `func f<T: SomeInterface>(...)` is the generic-bound syntax; the bound is a single bare interface name per type parameter (no `SomeInterface<X>` generic bound, no `|`-chained bounds). An empty list, a non-name, or a missing `>`/`,` is a parse error.

`parseOptionalTypeArgsRaw` parses a usage list `<TypeExpr, ...>` right after a name in a header (e.g. the `List<T>` half of `impl<T> List<T>{...}`), with bare-`<` depth tracking (`splitTopLevelCommaGroupsBare`).

## CALL / LOOKUP insertion

Covers: `insertCallLookupInLines`, `insertCallLookupInFlat`.

A VARREF immediately followed by `(` gets a synthetic OPERATOR `CALL` inserted between them; a VARREF, STRING or `[...]` group followed by `[` gets `LOOKUP`.

Why declared-type contexts are skipped: `VARREF` followed by `[` is ambiguous in raw tokens between a subscript (`x[5]`) and an array-length type annotation (`field: u64[50]` in a struct member, parameter or return type). Struct/enum/interface/type/extern/export/ASM lines and a func/impl/library header (name, params, return type) are never expression-shaped (declared types are parsed by `TypeChecker.resolveTypeAnnotation`, not RpnConverter/TreeBuilder), so they are never scanned; for a func line the pass jumps straight to its trailing `{` body. This also applies to `static func NAME(...) Type{...}` in impl/interface blocks (otherwise `origin(` in `static func origin() mut u64{...}` gets a spurious CALL marker that breaks `gatherFunc`'s index-based header parsing).

Special cases in the loop:
- `const NAME EXPR`: NAME (index 1 after `const`) is never a call target, even if EXPR starts with `(`; the rest of EXPR gets ordinary processing.
- `sleep(...)`: `sleep` is a KEYWORD but gets the same CALL marker so it reaches `TypeChecker.checkCall` in the normal shape (its dedicated KEYWORD branch resolves it to the function carrying `@sleep`, see `checkSleepCall`). `yield`/`join` take no arguments (RpnConverter handles them as bare keywords) and `par`/`await` are prefix operators wrapping a separate call expression, so they need no marker.
- A `[...]` DELINEATOR group is itself a LOOKUP target, which is what makes chained indexing (`grid[1][1]`) work: after the first `[1]` is one DELINEATOR in the flat list, followed directly by the second, with no VARREF/STRING between them.
- A string literal followed by `[` is a LOOKUP target (only a direct literal, never a variable holding one, has a compile-time-known length that can be indexed without a `match` under the bounds-checking default; see `TypeChecker.checkLookup`).

## Keyword-block token shapes

Covers: `gatherKeywordBlocks`, `gatherStatementLines`.

`gatherKeywordBlocks(lines, isRoot)` collapses each recognised `keyword [tokens] { block }` into one token, recursively and bottom-up (a block's contents are gathered before the construct that owns it). Fields not mentioned stay default/empty.

- `if / elseif / else`: `.sub` = raw (not yet RPN) condition tokens, empty for `else`; `.childs` = body as LINE tokens; `.right` = next branch of the chain (elseif/else) or null. `.right` is reused here (it means "right operand" in an RPN node); if/elseif/else never take part in an expression tree so there is no collision. A chain is represented only by its head `if` token; chained branches never appear as separate statements.
- `loop`: `.childs` = body; `.sub` empty (no condition). Takes no condition (error otherwise).
- `func`: `.sub` = `[ nameToken, paramsToken, ...returnTypeTokens ]`; `paramsToken` is the original `(` DELINEATOR whose `.childs` is the untouched flat, comma-separated parameter list (parsed at type-check time); return-type tokens are whatever sits between `)` and `{` (empty = void). `.childs` = body.
- `struct` (root only): `.sub = [nameToken]`, `.childs` = member declaration lines (`name : type` tokens, untouched, interpreted at type-check time).
- `enum` (root only): `.sub = [nameToken]`, `.childs` = flat list of variant name VARREF tokens (the commas are dropped).
- `for`: `.sub` = the condition (an `in` expression), `.childs` = body, `isForMatch` (see "for match"). The left of `in` introduces a fresh loop variable; that is TypeChecker's concern (`checkForLoop`).
- `unsafe` / `safe`: like `loop` (trailing block, no condition) but recursing with the current `isRoot`, so at root the block can hold declarations. `unsafe` may list tags between the keyword and the block (`unsafe deref extern{`, with a `name:qualifier` pair like `udyn:owns` kept as one tag) stored in `unsafeTags`; the TypeChecker validates them. `safe` takes no tags. The "unsafe inside safe" nesting restriction is TypeChecker's.
- `assume`: `assume match EXPR;` or `assume match EXPR{ ... }` (`hasBlock`); the block is optional: without it the assumption holds for the rest of the enclosing scope. `assume match x.field : A|B` is split at the `:` and the variant chain stored in `assumeFieldVariants` right here at raw-token level, because `:` is otherwise committed to self-bolt syntax (`x:method()`) in the expression pipeline (see `Token.assumeFieldVariants`).
- `match`, `if`, `@lock` statements go through `gatherMatchChain` / `gatherIfChain` / `gatherLockStatement` (`match @lock EXPR{...}`; `lock` is deliberately never a reserved KEYWORD, the form is detected structurally by `isLockStatementStart`, which rejects a plain `lock EXPR{...}`).
- Root-only declarations (`requireRoot`): `func extern export type const struct enum interface impl library import`. `ASM` has no `requireRoot`: it is legal at root and inside functions.
- `impl default match @lock` is dispatched to `gatherDefaultLockMatchImpl`; constructor impls to `gatherConstructorImpl`. Obsolete spellings `impl default lock match` and `impl default match lock` are errors pointing at `impl default match @lock`; the tolerated form with a separate `@` token has that token removed first.
- `elseif`/`else`/`elsematch` without a preceding `if`/`match`, and `catch` without `try`, are errors.
- Lines starting with other keywords (`return`, `let`, `import`, a bare expression statement) pass through unchanged; their expression tokens are handled by the RPN stage.

`gatherStatementLines` is the public entry point for TypeChecker to gather a deep-cloned, still-raw statement list (never at root, `isRoot` false). It is used once per `CLOSED:default` / `CLOSED:default(args)` use site of an `impl default match @lock` template: that declaration keeps its body raw (it is a template, not a statement), and a fresh clone is gathered at each use, the way an ordinary function body is gathered once at its declaration.

## try / catch span

Covers: the `try` handling in `gatherKeywordBlocks`, `gatherTryCatchSpan`, `isTryBlock`.

`try EXPR catch(e){ BLOCK }` is an expression, so `try` need not be the first token of a line (`let x = try foo() catch{ ... }` is as legal as the bare statement). It is found by scanning the whole line, not by dispatching on `first`. The span collapses to one synthetic KEYWORD `try` token (`.sub` = the wrapped call's raw tokens, later RPN-converted/tree-built like any condition; `.childs` = the gathered catch block), replacing the span in place; everything before `try` (e.g. the merged `let x =` prefix) is untouched. RpnConverter/TreeBuilder treat this one KEYWORD shape as an ordinary operand.

`try { ... }` is a different, unrelated construct sharing the keyword: a plain lexical-scope block (no expression, no catch), legal only as a bare statement with `try` first and `{` second. It is told apart from the throwing form by that lookahead, which is done before `gatherTryCatchSpan` (that would scan forward for a `catch` and report "'try' requires a matching 'catch(e) { ... }'"). It is built like `unsafe`/`safe` (no condition, trailing block, current `isRoot`), with `isTryBlock = true` as the flag every later stage reads. `try` with a condition is an error.

## ?catch and ?EXPR sugar

Covers: `pendingOptionalCatchParamName`, `pendingOptionalCatchRawBody`, the `?` handling in `gatherKeywordBlocks`, `gatherCatchClause`, `gatherStatementLines`.

- `?catch(e){ ... }` is a standalone declaration statement: `?` is the line's first token, followed by the `catch` KEYWORD. It is checked before anything else in the loop (otherwise the `try` scan finds no `try` and the line is rejected as "'catch' without a preceding 'try'"). It declares (or replaces) the pending catch block, produces no code, and adds no line to the result. The body is kept genuinely raw (`gatherCatchClause(..., gatherBodyNow=false)`).
- `?EXPR` is sugar for `try EXPR catch(e){ BLOCK }`, where BLOCK is the most recently encountered `?catch` in program order. Legal where the real span is: a bare statement (`?foo();`) or a `let`/assignment right-hand side (`let x = ?foo()`), found anywhere in the line. The wrapped expression is the whole remainder of the line (there is no trailing `catch` to mark its end). Only the first `?` in a line is recognised; a second nested `?` stays in the raw tokens and fails downstream with a generic parse/RPN error. A `?` with no earlier `?catch` in the function, or with no expression after it, is an error.
- Expansion: a fresh `Token.deepCloneRaw()` of the stored raw body is gathered anew at every use site ("store raw, clone-and-gather per use", the same shape as TypeChecker's `impl default match @lock` policy template), the result is a synthetic `try` token (`catchParamName`, decorators and pinned comments carried over) spliced in place of `? ...`. The line then falls through to the ordinary per-line dispatch (no `continue`); the `try` scan is skipped for it via `expandedOptionalHere`, since it would otherwise find this already-built token and try to re-gather it as raw source.
- Scoping: the two instance fields are updated in place by the single sequential depth-first walk of a function body and are never saved/restored around nested blocks (if/loop/match/real catch bodies/`try {}` blocks). A nested block inherits the pending catch, and a `?catch` declared inside a nested block stays in effect after the walk leaves it: the literal, unscoped "most recently encountered" reading, not lexical scoping. The fields are reset to null only where a new function/method body starts (`gatherFunc`); functions never nest, so one unconditional reset prevents leaking into a sibling function.
- Known gap (not fixed): if a `?catch` block itself contains `?catch`/`?EXPR` sugar, that inner sugar is only expanded lazily while gathering each use-site clone, and since gathering shares the same unscoped state, a `?catch` met while gathering one clone stays in effect afterward for code following that `?EXPR` in the enclosing scope. Nested `?` sugar inside a `?catch` block is an unexercised shape.

## for match

Covers: the `for` case in `gatherKeywordBlocks`.

`for i in 0..n{ ... }` is structurally like `if`: a condition followed by a trailing block gathered like any other. `for match i in x{...}` is sugar for `for i in x{...}` with the type assertion that `match i in x{...}` would give inside the loop. It is detected structurally: an optional `match` KEYWORD right after `for` is stripped from the condition's raw tokens before RPN conversion (so `.sub` has the same `i in x` shape as an ordinary `for` and RpnConverter/TreeBuilder need no new logic), and `Token.isForMatch` carries the distinction to `TypeChecker.checkForLoop`.

## Try/catch span and catch clauses
Covers: `gatherTryCatchSpan`, `gatherCatchClause`, `CatchClauseResult`.

- `try EXPR catch(e) { BLOCK }` becomes one synthetic `try` KEYWORD token. `.sub` holds the wrapped expression's raw tokens (the tokens between `try` and `catch`); they are RPN-converted and tree-built later, like an `if`/`for`/`match` condition. `TypeChecker.checkTry` then requires the tree root to be a direct call, since only a call can throw. `.childs` holds the catch block's statement lines, gathered right away through `gatherKeywordBlocks`.
- The whole span must run to the end of `tokens`: nothing may follow the closing `}` on the line (`try foo() catch(e){...} + 1` is not supported; this is explicit so no downstream stage meets a shape it was never taught). A missing `catch` or an empty expression is a parse error.
- `catch` takes exactly one bare, untyped parameter name in parentheses (never a type, never zero or several names; `catch{...}` without parens is a parse error, mirroring a func's required param list). The name is stored in the `try` token's `catchParamName`.
- `gatherCatchClause` is shared by a real `try ... catch(...)` and a standalone `?catch(e){...}` declaration. `trailingContextLabel` only words the "unexpected token after ..." error for the caller. Errors: missing `(`, wrong parameter shape, missing `{ }` block, trailing tokens.
- `gatherBodyNow`: true for a real try/catch (the body is used once, so it is gathered immediately). False for `?catch`: the body stays raw because it is spliced into many `?EXPR` use sites, and gathering, RPN, tree building and type checking all mutate tokens in place. Each use site deep-clones the raw body and gathers its own copy (same precedent as TypeChecker's "impl default lock match" policy template; see `Token.deepCloneRaw`).
- `?catch` state (`pendingOptionalCatchParamName`/`pendingOptionalCatchRawBody`) is reset in `gatherFunc`, the only reset site; functions never nest, so no save/restore is needed, and `gatherImpl`/constructors go through `gatherFunc`.

## if and match chains
Covers: `gatherIfChain`, `gatherMatchChain`, `requireTrailingBlock`.

- Each branch is the last token of its own LINE however it was written (`} else {` on one line or `else` on a fresh line), because `splitIntoLines` breaks the line right after any `{` block closes. That is why the last-token-based `requireTrailingBlock` is safe for if/match chains and for loop/func/struct/enum.
- Chain heads link following branches through `.right`. `elseif`/`elsematch` carry a condition; `else` takes none (`'else' takes no condition` error) and always ends the chain.
- `gatherMatchChain` mirrors `gatherIfChain` (`match` head, `elsematch`, terminal `else`). It is a separate function, not a generalisation, because sharing would thread "if" vs "match"/"elseif" vs "elsematch" through every branch for ~15 duplicated lines.
- Case-block form `match b{ TRUE:{...} FALSE:{...} }`: chosen by `isCaseMatchBody` purely from the body shape (a first case `VARREF ':' '{'`), because whether the condition is enum-typed is unknown until type checking. The result has `isCaseMatch = true`. No `elsematch`/`else` chain is looked for (exhaustiveness/`default` already covers every value); a stray one is left to the outer loop's "'elsematch' without a preceding 'match'" check.

## match @lock
Covers: `isLockStatementStart`, `gatherLockStatement`.

- `match @lock EXPR{...}` is the swap-mutex spin-lock form, case-labelled OPEN/CLOSED like a case-match. It is gathered into a KEYWORD token `lock` with `isCaseMatch = true`; the body is parsed by `gatherMatchCases(..., allowDefaultPolicyRef = true)`. Reusing `isCaseMatch` (rather than a new flag) works because the body shape is identical to an ordinary case-match; the mandatory CLOSED case comes from the normal case-match exhaustiveness check, so there is no parse-time check for it.
- Detection is structural. The `@` is an OPERATOR token (anywhere but a line start, where it would already be stripped as a decorator), which keeps `match lock{...}` and `match lock(x){...}` ordinary matches on a variable or call named `lock`. `lock` is never a keyword.
- The line must end in a `{` block; fewer than 5 tokens is `'match @lock' requires an expression before its '{'`. Removed spellings give errors: `match lock EXPR{...}` (now `match @lock`), `lock match ...` (renamed), and the bare `lock x{...}` statement (removed together with `@guard`; use `match @lock x{...}` or an owned value whose `@drop` hook unlocks). `lock(` stays an ordinary call.
- `CLOSED:default` / `CLOSED:default(args)` (only when `allowDefaultPolicyRef`, never for enum/float case-matches where `default` is the `@non_exhaustive` catch-all): the case body is the wrapped marker (a bare VARREF, or VARREF + `CALL` + `(` group) inside a LINE, with no new Token field. RpnConverter/TreeBuilder need no changes. `TypeChecker.checkLockMatchStatement` reads the raw shape (never resolving `default` as a name) and substitutes the registered policy. For the parenthesized form, stage 6 (`insertCallLookupInFlat`) has already inserted the `CALL` marker between `default` and its `(` group; the parser carries those tokens through (error: `expected '(' after 'default'`).

## Case-match bodies
Covers: `isCaseMatchBody`, `gatherMatchCases`, `labelChainText`.

- Case label shape: `VARREF ('|' VARREF)* ':' '{'`. `isCaseMatchBody` inspects only the first non-empty line, structurally.
- `gatherMatchCases` flattens all already-split lines of the body into one token stream and walks it case by case. Cases may be separated by `,`, `;`, a newline or nothing; two cases on one physical line must still work, which the newline/semicolon-based line splitting alone would not separate. Separators are skipped.
- `|`-chained labels (`A|B|C:{...}`) are handled generically, not float-only; an enum's single label is the one-element case. A case token is a VARREF named after the first label, with `.sub` = all label tokens and `.childs` = the gathered block.
- Errors: a token that is not a label start (`expected a case label ...`), missing `:` (`expected ':' after 'A|B'`), missing `{` (`expected '{' after 'A|B:'`).

## Declaration gatherers
Covers: `requireRoot`, `wrapAsLine`, `gatherFunc`, `gatherExtern`, `gatherExport`, `gatherAsm`.

- `gatherFunc`: `func NAME [<T..>] (params) ret... { body }`; the token's `.sub` is `[nameTok, paramsTok, ...returnTypeTokens]`, with `typeParams`/`typeParamBounds`/`decorators` attached. It is also used directly for impl, interface-default and library methods, bypassing the `isRoot` check (those are deliberate exceptions to "no funcs within funcs").
- `gatherExtern`: `extern name(params) ret` is the header-only half of `gatherFunc`: no type parameters (no monomorphisation of C functions) and no `{` block. It carries decorators attached by `stripDecoratorsDeep` (notably `@call_convention`, the C/OS ABI boundary); `TypeChecker.collectExtern` rejects any decorator outside its narrower allowed set.
- `gatherExport`: `export foo` marks an already-declared top-level func to be emitted under its bare, unmangled name. Existence, signature, no generics/ambiguity and at-most-once are `TypeChecker.collectExport`'s checks.
- `gatherAsm`: `ASM [name] { block }` or `ASM [name] "path"`. The block form arrives as one pre-scanned ASM_BLOCK token (`Lexer.scanAsmBlock` already checked balance and `ASM_END` at character level); the file form is an ordinary string literal whose existence and `ASM_END` scan are validated later by TypeChecker.

## Structs, type aliases, constants, libraries
Covers: `gatherStructLike`, `gatherStruct`, `parseOptionalImplementsClause`, `gatherType`, `gatherConst`, `gatherLibrary`.

- `struct NAME [<T..>] [implements A, B] { members }`: member lines pass through untouched (TypeChecker's `collectStruct` reads them via `parseNameTypeGroup`). A member line starting with `extends` is the parse error "struct 'extends' has been removed; use composition and interfaces". `implements` is a compile-time contract checked against `impl X for Y` blocks anywhere in the unit; it never supplies method bodies.
- `type NAME <target tokens>` is a one-liner with no `{` block; the raw target tokens stay unparsed and are read by TypeChecker.
- `const NAME expr`: `.sub` holds the name, `.childs` the raw expression tokens, which RpnConverter/TreeBuilder process like an `if` node's `.sub`.
- `library NAME<T?>{...}`: header like `gatherStructLike`; body = `extends TypeName` lines (into `extendsNames`) and plain `func` declarations (gathered non-static; a library function has no implicit `self`). Functions are called by bare library name (`Math.sin(10.2)`).

## Enums
Covers: `gatherEnum`, `hasNestedEnumVariant`, `parseNestedEnumVariant`, `enumLiteralValue`.

- Two body grammars, chosen once per enum (never mixed): the flat `NAME[: VALUE]` form, and the nested hierarchy form chosen when any variant line has a `{` as its second token (`hasNestedEnumVariant`).
- Flat form: variants separated by `,`; an optional `:` or `=` after a name, followed by an INTEGER (`enumLiteralValue` -> `explicitEnumValue`) or `INTEGER..INTEGER` (-> `explicitRangeStart/End`, range-valued enum, e.g. `Test{ HIGH:100..2000 LOW:0..100 }`; `COLOR{BLACK:0,WHITE:1}` or `BLACK=0, WHITE=1`). Enum bodies never go through the expression pipeline, so `..` is recognised on raw tokens by shape. Literals are positive only; values beyond 9223372036854775807 are an error (enum values are 63-bit). Kind consistency, uniqueness and no partial mixing are checked in `TypeChecker.collectEnum`.
- Nested form: `NAME` or `NAME{ ... }` lines only, no explicit values. A pre-order walk numbers every variant (leaf or internal) as first visited; start = that index, end = its own index, or the last index inside its block if it has one (`explicitRangeStart/End`). The counter is a one-element array shared across the recursion.

## Interfaces
Covers: `gatherInterface`, `gatherInterfaceMethod`.

- `interface Name[<T..>]{ method(params) ret; ... }`: each bare signature is one LINE (already split) and becomes an `interface_method` token with `.sub = [nameTok, paramsTok, ...returnTypeTokens]` (func's sub shape without a body). That `self` must be a pointer and all types are resolved later in TypeChecker.
- Lines `extends Name` go into `extendsNames`. `static func NAME(...) Ret{...}` (a `@default` static method, via `gatherFunc`) or `static NAME(...) Ret;` (plain static signature) get `isStaticMethod = true`. A line starting with `func` is a default method, gathered by `gatherFunc`; whether `@default` is present is `TypeChecker.registerInterface`'s concern.

## Impl blocks
Covers: `gatherImpl`.

- `impl Iface for Concrete{...}` vs `impl Concrete{...}`, told apart by a `for` keyword after the first name; both names can carry raw type arguments (`genericArgs`), and `impl<T> ...` has its own type-parameter list. `.sub` is `[concrete]` or `[interface, concrete]` (callers use `sub.size()`).
- Only `func`/`static func` lines are allowed inside; each goes through `gatherFunc` (bypassing `isRoot`). For `static`, decorators (e.g. `@realizes`) sit on the line's first token (`static`), so they are copied onto the method token explicitly.

## Constructors
Covers: `CONSTRUCTOR_NAME_PREFIX`, `isConstructorImplStart`, `gatherConstructorImpl`.

- `impl constructor for MyClass(x:u64,y:u64) self { return MyClass{ x:x,y:y } }`: callable as `MyClass(...)` (stack, by value, via the same RVO as any struct-returning function) or `new MyClass(...)` (heap). Several may exist per struct and are overload-resolved like ordinary functions: each is registered as an ordinary `FuncInfo` overload under one synthetic name `CONSTRUCTOR_NAME_PREFIX + concreteName` ("__ctor__" + name) in `TypeChecker.functions`, using the existing duplicate-signature and mangled-name machinery.
- The prefix cannot start with `$`: the function name is emitted verbatim as an assembly symbol, and `$` is an immediate sigil in AT&T syntax (fails with "operand size mismatch for `call'"). The `__` convention (like `__main`, `__trampoline_<name>`) is used instead, accepting the same collision risk (a user function named `__ctor__MyClass`). `Parser` is the only place that builds the name; `TypeChecker` concatenates the same prefix at call sites.
- `isConstructorImplStart` (checked right after `impl`) tolerates an optional `<T,...>` list and then needs `constructor` followed by the keyword `for`. An interface literally named `constructor` is the accepted collision (same trade-off as `default`/`lock` for the policy impl).
- Return type must be exactly the word `self`; it is replaced by a VARREF with the struct's name (carrying `genericArgs` if generic), so after this point it is indistinguishable from an ordinary struct-returning function. The header is delegated to `gatherFunc` by synthesising `func __ctor__Name(params) Name { body }`. For a generic constructor the return type's `genericArgs` is set directly: a one-token return span gives `GenericsExpander.scanTypeTokenSpan` no following `<` to match; `substituteParams` recurses into `.genericArgs` per instantiation.
- Non-generic: result is the bare `func` token (`isConstructorDecl`, `constructorConcreteName`), registered by ordinary `TypeChecker.collectFunc`. Generic (`impl<T> constructor for Slice<T>(...) self {...}`): wrapped in an `impl`-shaped token (`.sub=[concreteNameTok]` with `.genericArgs`, `.typeParams`/`.typeParamBounds`, `.childs=[func]`), so GenericsExpander's impl-template machinery (`registerImplTemplate`/`generateImplInstantiation`) handles it; only `generateImplInstantiation` needed a small patch to rename the cloned constructor's callable name and `constructorConcreteName` to the mangled struct name. The wrapper keeps only impl-level decorators (`@pub`); function-level ones (e.g. `@throws`) stay on the func.
- Errors: generic struct without `impl<...>` header, `impl<...>` with a non-generic target, missing parameter list, return type not `self`.

## Default lock policy
Covers: `isDefaultLockMatchImplStart`, `gatherDefaultLockMatchImpl`.

- `impl default match @lock MyMutex{ CLOSED:(tries:imut u64,...)=>{...} }` is a reusable parameterized backoff/retry policy template for a `match @lock` CLOSED case (`TypeChecker.DefaultLockMatchPolicy`); exactly one case, `CLOSED`. The result is an `impl_default_lock_match` token: `.sub = [typeNameTok, paramsTok]`, `.childs` = the raw, ungathered LINE tokens of the body.
- Everything is kept raw because it is a template: its `break` must target the spin loop that a `CLOSED:default[(args)]` use site generates, which does not exist at declaration time. TypeChecker deep-clones a fresh copy per use site and only the copy is gathered, converted, built and checked.
- Detection is shape-based (`default`, `lock` are ordinary VARREFs; `match` is a KEYWORD): `impl default match lock`. It is only checked right after `impl`, so a real interface named `default` collides with nothing here.
