# Assembly kept in a file: `ASM "18_asm_helper.s"` copies it into the output as written.
.text
.globl asm_plus100
asm_plus100:
	lea 100(%rdi), %rax
	ret
