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
