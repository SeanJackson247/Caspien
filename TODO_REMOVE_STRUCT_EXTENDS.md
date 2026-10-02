# Struct `extends` / `abstract` removal: status (built and swept on Linux, UNCOMMITTED)

Design: composition + interfaces only. Flat `Class` enum (`ENUM Class A 0 B 1 ...`, all non-`@untyped` structs). `x instanceof S`: x interface-typed, S a struct (lowered to one class-id compare). `x implements I`: x struct-typed, I an interface. Interface `extends`, `@realizes/@default/@overrides` unchanged.

Done: `@final` removed from struct decorators; front end (Lexer, Parser, TreeBuilder, RpnConverter, BytecodeEmitter, TypeChecker); LowerOrderGenerator (`Class` id lookup, instanceof lowering, extends-prefix fallback and `allStructNames` removed); Optimizer (`StructMemberReorderingPass` reads `Class`, no extends check); README section rewritten; `docs/examples/11_types`, `tests/structreorder_test`, `tests/structreorder_hob_check.sh` updated. Rebuilt `out/` classes in the repo.
Verified (Linux scratch copy, everything-off config): 62 `tests/*.caspien` + all `docs/examples` + fnv1a/sha256/bits_ops_matrix8 pass; struct-reorder test with the switch ON passes (sizeof Mixed 24); negative cases (struct extends, abstract, instanceof on struct-typed x or interface target, implements with struct name) are rejected. Not run: other bits_ops tests, n-body, benchmarks, Windows, `*_check.sh` other than structreorder.

Remaining / open:
- Stale comments mention extends/abstract/ClassID in TypeChecker, Parser, RpnConverter, BytecodeEmitter, GenericsExpander, Token, Lexer, EnumTable, MembershipLoweringPass, AddressLoweringPass. Reword when convenient.
- `ASTGenerator/examples/` fixtures and the CLAUDE.md files may still mention struct extends.
- `cast` is an unconditional error; the struct `.enum` form is a compile error.
- Constant-case `instanceof`/`implements` (always true/false) is not reported.
- Update the `@lock` note "first inherited member counts as first" (obsolete).
