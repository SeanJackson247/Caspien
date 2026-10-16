# codegenCaspien: the Caspien port (skeleton)

Every Java file of `../` has a `.caspien` twin here (the Java copies were removed). Constructors are real; method bodies are being converted leaf-first (the rest are empty stubs), so `Main.caspien` compiles and does nothing yet. Compile it with a Linux-configured scratch tree, as for any program: `java Compiler -i .../codegenCaspien/Main.caspien out`. The text below the line is the Java documentation, still describing the behaviour to port.

## Type mapping
| Java | Caspien |
|---|---|
| `int`, `long`, `boolean`, `char` | `s32`, `s64`, `bool`, `char` |
| `final` field / non-final field | `imut` / `mut` member (a pointer member's `mut` is its target's) |
| `String` | stdlib `String`: `ref imut String` parameter, `owns mut String` return or nullable field |
| `StringBuilder` | `String` (`owns some mut` field, `ref mut` parameter or return) |
| `long[]` | `DynamicArray<s64>` |
| `Integer`/`Long` sets, maps, deques | stdlib `HashSet<s32>`, `HashMap<s32, s64>`, `TreeMap`, `DynamicArray<s64>` |
| lists/deques of Strings or objects, `String[]` field | `ObjectList<T>` (`collections.caspien`) |
| `HashSet<String>` | `StringSet` (`collections.caspien`) |
| `LinkedHashMap<String, V>` | `StringMap<V>` (object values) or `StringScalarMap<V>` (bool, s64) |
| `static final` constants | root-level `let static X86Core_NAME`; the two `HashSet<String>` constants are string arrays |
| `private` | no `@pub` (but see the private fields below) |

`collections.caspien` types and `bytecode/` (`BytecodeToken`, `BytecodeStatement`, `BytecodeTokenKind`: data members only, no methods) are stand-ins: the stdlib has no String-keyed map or set, and `DynamicArray` cannot hold an element that owns memory.

## Where the port differs from the Java
- Imports cannot be circular, so the structs of `X86Backend` and its six parts live together in `x86backend/X86Structs.caspien`; each part file holds its constructor and methods.
- A constructor cannot pass `this`: `new X86Backend(..)` leaves the parts null and `backend.wire()` creates them. The `(target)` constructor repeats the body instead of delegating.
- The parts' `private final hub/core` fields are public (a struct with a private member cannot be built by a constructor).
- `X86Core.vSlots` (`new String[3]` of nulls) holds three empty Strings.
- `raw()` of `X86Emit` is `raw_` (`raw` is a keyword).
- `Main.run/writeFile` lose `throws IOException` (`@throws` needs a real `throw`); `main` takes the safe-args form. The four `caspien.bytecode.Text` helpers used here are in `Text.caspien` (a `suffixChars` argument is a `String`).
- Method results: a literal-only result is `static imut string` (promote with `String.from(..)` where an owned `String` is needed), anything built at run time is `owns some mut String`; string parameters are `ref some imut String` (`static imut string` where the callee only compares or returns literals, via `String.literalsEqual`).
- `throw` takes only a literal, so the dynamic text of Java messages is dropped (`rfFalseJump`: "unknown R_BRC operator"; `rfTrueSuffix`: "unknown condition"); number parsing throws "NumberFormatException".
- `isInteger` accepts ASCII digits only (Java `Character.isDigit` also takes other Unicode digits).
- `StringSet`/`StringMap`/`StringScalarMap` search linearly (Java hashes): a known performance follow-up. `ObjectList<T>` owns its items (`get` returns a `ref`, `add` moves the item in).
- Port-only helpers: `X86Backend.wire`, `X86Core.rfVarRegName/rfTempRegName/xmmName` (register tables, indexed by if-chain), `X86Core.sliceEq/tokenIs/tokenIsDigits/tokenS64/partCopy` (token tests without copying), `X86Core.isStrideKeepOp` (a `let static` list cannot be read from safe code), `X86Emit.sizedRegLiteral`, `Text.containsWordLit/usesRbx/usesRdi/usesRsi/lowerChar`.
- `FloatText.bits` (exact decimal -> IEEE bits through `BigInt`) replaces `Float/Double.parseFloat` in `initBits`; `String.charCode` (stdlib) gives a char's byte value.
- Messages of thrown errors lose their dynamic part and the `CodegenException` path/line (`CodegenConfig.parse`: "config: unknown config key" etc.).
- Negative register indexes: Java `argReg(-1)` throws ArrayIndexOutOfBounds; the port returns the fallback ("rax"/"xmm0"), no caller passes one.
- `Main.writeFile` is still a stub: `stdlib/fs.caspien` writes only literal text to a single-component name, the Java writes a dynamic String to a path. `python3 tests/port_parity_check.py` lists any method that exists in only one of a Java class and its twin (run it after touching either side; 16 Oct: the Java `X86RegForm` gained `gtSaveLoadedArgRegs/gtRestoreLoadedArgRegs`, stubbed here).
- Javadoc comments of the Java files are not copied; they stay in the Java originals and the README below.

---

# `caspien.codegen` (stage 4 entry package)

The library and stand-alone entry points of the code generator. The x86-64 backend itself is in `x86backend/` (see `Codegen/CLAUDE.md`). The source files here carry no comments; what they used to say is below.

## Files

| File | Role |
|---|---|
| `Codegen` | The library entry. Low-order bytecode (LOB) text in, assembly text out; no files, no process state. The orchestrator (`Compiler.java`) calls it in its own JVM; `Main` is the same thing as a stand-alone program. Phases: parse the whole LOB into a `BytecodeProgram`, generate the assembly from it. |
| `CodegenConfig` | Parses the text of `codegen.config`. Reads no file. |
| `CodegenTarget` | Which assembler dialect and ABI the backend emits. Chosen by `target` in `codegen.config`. |
| `CodegenResult` | The result of `Codegen.generate`. |
| `CodegenException` | The one exception type for every fatal codegen error (mirrors the compiler's own `CompilerException`). Its message reads `[kind error] file:line - message`. |
| `Main` | The CLI entry point of the stage. |

## `Codegen.generate(lobText, origin, configText)`

- `lobText`: the LowerOrderGenerator's output.
- `origin`: the name shown in diagnostics for the input (a file name).
- `configText`: the contents of `codegen.config` (required).
- Returns a `CodegenResult`. A `CodegenException` is caught and turned into a failed result; nothing is thrown to the caller for a codegen error.

The backend is built as `new X86Backend(config.target, config.bmi2)` and gets `avx` from the config before `generate` runs.

## `CodegenResult`

- `asm`: the assembly text (null when `failed`).
- `messages`: the lines a stand-alone run prints to stderr.
- `failed`: a fatal codegen error happened.

## `codegen.config`

A fixed path, resolved relative to the current working directory (the same convention `compiler.config` uses on the compiler and LowerOrderGenerator side). It is required: a missing file is a fatal error, as with that file. In the full toolchain the text comes from the `===codegen.config===` section of the root `toolchain.config`.

Format: one `key value` pair per non-comment, non-blank line; `#` starts a comment. An unknown key, a line that is not `key value`, or a missing `target` is an error that names the line.

| Key | Values | Meaning |
|---|---|---|
| `target` | `linux`, `windows_gnu` (also `windows-gnu`, `mingw`; case-insensitive) | Required. `linux` = GAS AT&T syntax, SysV ABI. `windows_gnu` = GAS AT&T syntax, win64 ABI (gcc/mingw-w64). `windows` is an error: the Intel/MASM target existed once and was removed on purpose; Windows builds go through gcc/mingw-w64. |
| `bmi2` | `on`, `off` | Default off. Variable shifts use `shlx`/`shrx`. Needs a BMI2 CPU (Intel Haswell 2013+, AMD Excavator/Zen+). |
| `avx` | `on`, `off` | Default off. Scalar float arithmetic on register variables uses the 3-operand VEX forms (`vaddsd` ...), which need no copy of the first operand. Needs Sandy Bridge 2011+ or AMD Bulldozer+. |
| `jcc-padding` | `on`, `off` | Read by `Compiler.java`, which adds an option to the assembler call. Codegen only validates it. |

Everything this stage needs to know about a target (assembly syntax, calling-convention register names, whether a call needs 32 bytes of shadow space ahead of it) is decided by which `CodegenTarget` the `target` key resolves to, not read as further key/value pairs. More knobs can be added the same way `compiler.config` grew its own calling-convention table, once the backend needs them.

## `CodegenTarget`

- `WINDOWS_GNU_X64`: GAS AT&T syntax, win64 ABI, for gcc/mingw-w64. Verified in the development sandbox with a real mingw-w64 cross-toolchain and Wine.
- `LINUX_X64`: GAS AT&T syntax, SysV ABI. Verified in the sandbox with the real `as`/`gcc`/`ld` toolchain.

## `Main` (stand-alone)

Usage: `codegen -i input.txt output.s`

- `input.txt` is the plain-text output of the LowerOrderGenerator stage.
- `codegen.config` is read from the current working directory and selects the target.
- Writes the assembly to stdout and to `output.s`, then prints `[info] wrote assembly to <path> (<n> lines)` to stderr.
- A `CodegenException` prints its message to stderr and exits 1; an I/O error prints `[io error] ...` and exits 1; bad usage prints the usage line and exits 1.
- Origin: the fourth program of the toolchain, started from the instruction "begin building the fourth program... this will generate assembly instructions from the lower order bytecode. The code generator will use a config file as necessary. Current target is x86 windows, but whether windows or linux is the target is down to the config" (the Windows target is now `windows_gnu`).
