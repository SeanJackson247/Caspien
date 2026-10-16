#!/usr/bin/env python3
"""check_callee_saved.py <file.s> ...  -- structural check of generated AT&T assembly (linux / windows_gnu).
Every function must: save each callee-saved register it touches (rbx, r12-r15; plus rdi/rsi and xmm6-15 on win64) into its frame
right after its prologue, and restore exactly those registers on every exit (`ret`, and the GT_UNWIND `jmp *%rax` path).
Exit status 0 = all functions OK."""
import re, sys
GPR = {"rbx": r"%(?:rbx|ebx|bx|bl|bh)\b", **{f"r{n}": rf"%r{n}[dwb]?\b" for n in range(12, 16)}}
WIN = {"rdi": r"%(?:rdi|edi|di|dil)\b", "rsi": r"%(?:rsi|esi|si|sil)\b", **{f"xmm{n}": rf"%xmm{n}\b" for n in range(6, 16)}}
def check(path):
    win = "windows" in path or "win." in path
    regs = dict(GPR); 
    if win: regs.update(WIN)
    funcs, cur = {}, None
    for ln in open(path):
        m = re.match(r"^([A-Za-z_][\w$.]*):\s*$", ln)
        if m and not m.group(1).startswith(("string_id", ".L")) and ln.startswith(m.group(1)):
            cur = m.group(1); funcs[cur] = []; continue
        if cur is not None: funcs[cur].append(ln.rstrip("\n"))
    bad = 0; n = 0
    for f, lines in funcs.items():
        if not any(l.strip() == "pushq %rbp" for l in lines[:3]): continue
        n += 1
        body = "\n".join(lines)
        saved = set()
        for l in lines[:40]:
            m = re.match(r"\s*(?:movq|movups) %(\w+), -\d+\(%rbp\)", l)
            if m and m.group(1) in regs: saved.add(m.group(1))
        # registers touched outside save/restore lines
        touched = set()
        for l in lines:
            if re.match(r"\s*(?:movq|movups) %\w+, -\d+\(%rbp\)", l) or re.match(r"\s*(?:movq|movups) -\d+\(%rbp\), %\w+", l): 
                continue
            for r, pat in regs.items():
                if re.search(pat, l): touched.add(r)
        if touched - saved:
            print(f"{path}: {f}: touches {sorted(touched-saved)} but does not save them"); bad += 1; continue
        # every exit restores all saved regs
        for i, l in enumerate(lines):
            if l.strip() == "ret" or l.strip() == "jmp *%rax":
                back = lines[max(0, i-12):i]
                for r in saved:
                    if not any(re.match(rf"\s*(?:movq|movups) -\d+\(%rbp\), %{r}$", b) for b in back):
                        print(f"{path}: {f}: exit at line {i} does not restore {r}"); bad += 1
    return n, bad
tot = bad = 0
for p in sys.argv[1:]:
    n, b = check(p); tot += n; bad += b
print(f"{tot} functions checked, {bad} problems"); sys.exit(1 if bad else 0)
