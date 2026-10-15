package caspien.codegen.x86backend;

import caspien.codegen.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import java.util.List;

public final class X86Backend {
    final X86Core core;
    final X86Emit emit;
    final X86Calls calls;
    final X86CasesA casesA;
    final X86CasesB casesB;
    final X86RegForm regForm;
    final X86Generator gen;

    /** AVX three-operand float forms (set before {@link #generate}). */
    public boolean avx;

    public X86Backend(CodegenTarget target) {
        this(target, false);
    }

    public X86Backend(CodegenTarget target, boolean bmi2) {
        core = new X86Core(target, bmi2);
        emit = new X86Emit(this);
        calls = new X86Calls(this);
        casesA = new X86CasesA(this);
        casesB = new X86CasesB(this);
        regForm = new X86RegForm(this);
        gen = new X86Generator(this);
    }

    public String generate(List<BytecodeStatement> lines) {
        core.avx = avx;
        return gen.generate(lines);
    }
}
