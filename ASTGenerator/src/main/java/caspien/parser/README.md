# parser

Turns the lexer tokens into nested lines and gathers keyword blocks (func, struct, enum, interface, impl, match, try/catch ...). Design notes: ASTGenerator/docs/Parser.md.

## Why this is a folder of classes

`Parser` was one file of thousands of lines. Java has no partial classes, so it is split into **three classes held together by composition** (no inheritance): `Parser` (public, the hub) owns one instance each of `ParserLines` and `ParserBlocks`, and each of those holds a `hub` reference back to it:

    Parser (hub: public API, owns `lines` and `blocks`)
      |- ParserLines  (state, nested types, statics, line splitting)
      '- ParserBlocks (keyword-block gathering)

- A part reaches another part as `hub.lines.x` / `hub.blocks.x` (hub members as `hub.x`); the hub reaches its parts as `lines.x` / `blocks.x`. Nested types and statics are qualified by their class (`ParserLines.TypeParamParseResult`).
- Callers use `Parser` only: `parse(..)`, `gatherStatementLines(..)` and `CONSTRUCTOR_NAME_PREFIX` are delegated there. To expose another part member to outside code, add a delegating method to `Parser`.
- The output is byte-identical to the old class chain (checked on every test, example and benchmark source in three configs).

## Parts

| File | Lines | Holds |
|---|---:|---|
| `ParserLines.java` | 1171 | line splitting, decorators, normalisation passes |
| `ParserBlocks.java` | 1333 | keyword-block gathering: statements, structs, enums, interfaces, impls |
| `Parser.java` | 244 | methods of Parser |
