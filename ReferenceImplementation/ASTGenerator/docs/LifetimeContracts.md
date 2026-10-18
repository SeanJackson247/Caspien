# Lifetime contracts (v1) -- implemented in the front end (stage 1); the Caspien-compiled front end is not involved

Goal: let a `ref some` (a plain address, no ghost-table id) be stored in a struct member, so linked/borrowing structures stop paying for nullable-`ref` ids.
Measured motive: the nullable-`ref` variants of graph, binarytrees and lru are 11x-40x slower than their index twins; in the Caspien Codegen executable
`gt_ref_id` + `gt_ref_resolve` are about 28% of the executed instructions (callgrind, spectralnorm_opt LOB).

Today (`validateNoRefSomeStructMembers`): "'ref some' is not allowed as a struct member type". A `ref some` local or parameter is an address whose
"alive here" proof (`ProofKills`) ends at any event that may destroy its target type.

## Syntax

A function may state how the lifetimes of its parameters relate. The contract sits in the signature, nothing is inferred across function boundaries:

    @lock match(lifetime(a) >= lifetime(b))
    func wire(a: ref some mut A, b: ref some mut B) void{
        b.a = a
    }

- `lifetime(x)` names the region (scope or owner lifetime) the object `x` refers to lives in. Only `lifetime(<parameter or local>)` is allowed inside the
  contract; no arithmetic, no value expressions. It is a compile-time fact, never evaluated.
- One canonical direction: the longer-lived side is on the left. Operators: `>=` (non-strict: equal lifetimes satisfy it, e.g. two locals of one scope,
  which die together), `>` (strict: the left is destroyed strictly after the right), `==` (both die together).
- Chains are allowed and mean pairwise comparisons: `lifetime(a) >= lifetime(b) >= lifetime(c)`.
- Grep: every relation contains the literal `lifetime(`.
- Inside a function body nothing is annotated: relations between locals follow from scope nesting and declaration order.

## Rules (v1)

1. **`ref some` as a struct member is allowed.** A struct with such a member (or a member of a scoped struct type, recursively) is a *scoped type*.
2. **Storing** a `ref some` into a scoped value (`b.a = ref a`, `B{a = ref a}`, a call that does so) needs `lifetime(a) >= lifetime(b)`:
   for locals it is checked from scope depth/declaration order, for parameters it must be stated by the function's contract (and the caller proves it at the
   call site from the same facts). Violation: error naming the exact contract line to add.
3. **A scoped value cannot escape the region of what it borrows:** it cannot be returned, thrown, moved into a longer-lived owner, stored in a `let static`,
   or sent across `par`/`await`. (Rust's "borrowed value does not live long enough", without lifetime parameters.)
4. **Using a scoped value uses the proof of every `ref some` it holds.** The existing `ProofKills` events end it: if something that may destroy the
   target's type happens between the store and a later use of the scoped value (or of its member), that use is an error. This is the "freeze" of the
   borrow, enforced at the use instead of at the destroying event.
5. **Targets must have a stable address:** a `ref some` into a dynarray element is not storable (a `resize` moves the block).
6. **Strictness for `@drop`:** if the scoped type's `@drop` reads through the member, `lifetime(target) > lifetime(value)` is required (the value must be
   dropped strictly first); `>=` is not enough.
7. A scoped type cannot be `clone`d in v1, cannot be an element of a dynarray in v1, and has no nullable-`ref` interplay (a nullable `ref` stays an id).

Not in v1: returning a value that borrows from a parameter (needs a way to name the relation on the result, the same `lifetime(result) <= lifetime(a)` form
could do it), a whole-program lifetime (`'static`), lifetimes through generics.

## Checker design

- Reuse `ProofKills`: an implicit proof is created at the store (keyed by the member path, e.g. `b.a`), read at every use of the member, killed by the
  existing event list. Parameters of scoped type start with implicit proofs for their `ref some` members, exactly as `ref some` parameters do.
- Scope depth: each `Scope` knows its depth; each local records its declaring scope and a declaration serial. `lifetime(x)` of a local = (depth, serial).
- The contract is a new `Decorator` shape beside `@lock(match i in ...)`: parsed in `ParserLines` (like `parseLockMatchQualifier`), validated in
  `TypeCheckerLocksAndProofs`, stored on `FuncInfo`, discharged at call sites.
- Emission: a `ref some` member is an 8-byte address; reads never emit `GT_REF_RESOLVE`; drop glue ignores it (not owned).

## Implementation (where it lives)

- `TypeCheckerLifetimes` (hub part `hub.lifetimes`): scoped-type computation, positions/regions, store checks (`onStore`), call-site discharge (`onCall`),
  contract closure (`contractOf`, transitive, strict if any link is strict) and validation.
- `Scope`: `depth()`, `declSerial`, `positionOf(name)`, `borrowRegion` (per local: innermost position of anything it borrows).
- `ProofKills.memberRead`: a read of a `ref some` member (path key such as `b.a`) is a use of the implicit proof made when it was stored.
- Parser: `ParserLines.parseLifetimeContract`; surface forms `@lock match(lifetime(a) >= lifetime(b))` and `@lock(match lifetime(a) >= lifetime(b))`
  both produce a `Decorator` named `lifetime` (`lifetimeTerms`, `lifetimeOps`).
- Hooks: `assignPlainLet` / `stepAssign` (stores), `dotTail` (member reads), the three call-resolution sites in `TypeCheckerCalls` (contracts),
  `requireNoAutoReturn` (contract validation, no scoped return type), `TypeCheckerStatements` (scoped parameters start with member proofs).

What is checked: a store into a local needs the borrowed object declared in an outer scope, or earlier in the same scope (strictly earlier when the
borrower's type has a `@drop`); a store into a parameter needs the contract (error text prints the line to add); a call must satisfy the callee's
contract from the caller's scopes; a scoped type is not returned; a `ref` into an array element is not storable; alive proofs of members end at
the usual `ProofKills` events. Also rejected: `clone` of a scoped type, dynarrays of scoped types (members, parameters, returns, locals), `par`/`await` of a scoped value (safe code already refuses any pointer across `par`; `unsafe async` is the programmer's word). `throw` carries only a string, so nothing scoped can be thrown. A source that is a local derived from a parameter loses its parameter identity (use the parameter directly in the store). Moving a scoped value into a longer-lived owner is checked as a store (region copied).

## Tests

Accept: `lifetime_member_test`, `lifetime_contract_test` (contract, call, chain), `lifetime_same_scope_test`.
Reject: `lifetime_return_error_test`, `lifetime_outer_error_test`, `lifetime_freed_error_test`, `lifetime_moved_error_test`,
`lifetime_param_nocontract_error_test`, `lifetime_contract_call_error_test`, `lifetime_contract_reversed_error_test` (`<=` is a parse error),
`lifetime_array_target_error_test`, `lifetime_drop_strict_error_test`, `lifetime_clone_error_test`, `lifetime_dynarray_error_test`, `lifetime_par_error_test`.
