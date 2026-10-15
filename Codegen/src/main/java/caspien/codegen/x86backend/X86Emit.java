package caspien.codegen.x86backend;

import caspien.codegen.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Emission helpers: output buffering, stack tracking, construction runs, sized loads/stores, block copies, shifts (see X86Generator for how the parts fit together). */
final class X86Emit {
    private final X86Backend hub;
    private final X86Core core;

    X86Emit(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    /** True when the bytecode line `offset` lines after the current one is a bare "ATOMIC_SWAP" -- used to spot the "PUSH addressOperand / PUSH newValue / ATOMIC_SWAP" shape genuinely seen in this pipeline's own ghost-table lock (see PUSH's global-name fallback). */
    boolean lineAtOffsetIsAtomicSwap(int offset) {
        int idx = core.currentLineIndex + offset;
        if (core.allLines == null || idx < 0 || idx >= core.allLines.size()) {
            return false;
        }
        BytecodeStatement l = core.allLines.get(idx);
        return !l.isEmpty() && l.get(0).text.equals("ATOMIC_SWAP");
    }

    void noteArgRegLoaded(String tag) {
        if (!core.loadedArgRegsStack.isEmpty() && !core.loadedArgRegsStack.peek().contains(tag)) {
            core.loadedArgRegsStack.peek().add(tag);
        }
    }

    /** Current sink for `raw()` -- the innermost open call's own buffer, or `out` directly when no "CC_START"/"CC_END" bracket is currently open. */
    StringBuilder currentSink() {
        if (core.callBufferStack.isEmpty()) {
            return core.out;
        }
        return core.callBufferStack.peek();
    }

    /** Consumes (reads and clears) `pendingVarargsXmmCount`, defaulting to 0 (correct for a wholly non-variadic call, or an already-conservative "no floats" guess if this is somehow reached for a variadic call whose count was never set -- should not happen, but 0 is what this backend always assumed before this field existed at all). */
    int consumeVarargsXmmCount() {
        int count;
        if (core.pendingVarargsXmmCount < 0) {
            count = 0;
        } else {
            count = core.pendingVarargsXmmCount;
        }
        core.pendingVarargsXmmCount = -1;
        return count;
    }

    int findRunStart(int endExclusive, long targetSize) {
        // A nested NEW opens a run of its own; the enclosing (total, target) wait on this stack.
        long[] saved = new long[16];
        int depth = 0;
        long total = 0;
        long target = targetSize;
        int idx = endExclusive;
        while (true) {
            if (total < target && idx > 0) {
                BytecodeStatement prev = core.allLines.get(idx - 1);
                if (prev.isEmpty()) {
                    return -1;
                }
                String m = prev.get(0).text;
                if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH") || m.equals("STACK_LOCK")) && prev.size() >= 2) {
                    total += Long.parseLong(prev.get(1).text);
                    idx--;
                } else if (m.equals("NEW") && prev.size() >= 2) {
                    if (depth + 2 > saved.length) {
                        saved = java.util.Arrays.copyOf(saved, saved.length * 2);
                    }
                    saved[depth++] = total;
                    saved[depth++] = target;
                    target = Long.parseLong(prev.get(1).text);
                    total = 0;
                    idx--;
                } else {
                    return -1;
                }
                continue;
            }
            if (total != target) {
                return -1;
            }
            if (depth == 0) {
                return idx;
            }
            // nested run confirmed: the enclosing run resumes before it; the nested NEW's pointer is one 8-byte word
            target = saved[--depth];
            total = saved[--depth] + 8;
        }
    }

    void populateDirectMembers(int start, int endExclusive, long runTotal) {
        X86RunEntries entries = collectRunEntries(start, endExclusive);
        long before = 0;
        long after = runTotal;
        for (int i = 0; i < entries.sizes.size(); i++) {
            long sz = entries.sizes.get(i);
            after -= sz;
            int lineIdx = entries.storeLines.get(i);
            if (lineIdx >= 0) {
                core.pushStoreOffsetByLine.put(lineIdx, before - after);
            }
            before += sz;
        }
    }

    void storeConstructionOrPlainPush(String regName, int size) {
        Long constructionOffset = core.pushStoreOffsetByLine.get(core.currentLineIndex);
        if (constructionOffset == null) {
            pushReg(regName);
            return;
        }
        raw(("    subq $" + size + ", %rsp"));
        if (constructionOffset == 0) {
            storeSizedToAddr(regName, "rsp", size);
        } else if (X86Core.isOddSize(size)) {
            storeOddSize(regName, "rsp", constructionOffset, size);
        } else {
            raw("    mov" + movSuffix(size) + " %" + sizedReg(regName, size) + ", " + constructionOffset
                    + "(%rsp)");
        }
    }

    X86RunEntries collectRunEntries(int start, int endExclusive) {
        X86RunEntries entries = new X86RunEntries();
        int idx = start;
        while (idx < endExclusive) {
            BytecodeStatement l = core.allLines.get(idx);
            String m = l.get(0).text;
            if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH")) && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(idx);
                idx++;
            } else if (m.equals("STACK_LOCK") && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(-1);
                idx++;
            } else if (m.equals("NEW") && l.size() >= 2) {
                long declaredNestedSize = Long.parseLong(l.get(1).text);
                long sum = 0;
                int popCount = 0;
                for (int j = entries.sizes.size() - 1; j >= 0; j--) {
                    sum += entries.sizes.get(j);
                    popCount++;
                    if (sum >= declaredNestedSize) {
                        break;
                    }
                }
                if (sum == declaredNestedSize) {
                    for (int k = 0; k < popCount; k++) {
                        entries.sizes.remove(entries.sizes.size() - 1);
                        entries.storeLines.remove(entries.storeLines.size() - 1);
                    }
                }
                entries.sizes.add(8L);
                entries.storeLines.add(-1);
                idx++;
            } else {
                break; // shouldn't happen -- the caller already validated this exact range
            }
        }
        return entries;
    }

    void emitNewRepack(long size, BytecodeStatement line) {
        // Chunk sizes on the stack, in push order (first field first).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();   // 0 for a padding gap
        List<Long> elemSize = new ArrayList<>();    // > 0 for "a<elemBytes>x<n>": a fixed array written element by element, n whole words
        List<Long> elemCount = new ArrayList<>();
        for (int i = 2; i < line.size(); i++) {
            String tok = line.get(i).text;
            if (tok.charAt(0) == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (tok.charAt(0) == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw("    movq %rsp, %r12"); // r12: the reversed field words on the stack
        hub.regForm.emitMallocCall(size);
        raw("    movq %rax, %r14"); // r14: the fresh buffer
        // A failed malloc leaves r14 null: skip every copy into it; the null is pushed and the bytecode's own check throws.
        String newRepackDone = hub.regForm.newInternalLabel("new_done");
        raw("    testq %r14, %r14");
        raw("    jz " + newRepackDone);
        long dst = 0;      // running true offset in the buffer
        long consumed = 0; // bytes of chunks pushed so far (from the first field)
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed; // this chunk's lowest address, relative to r12
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i); // padding gap: bytes in the buffer stay whatever malloc left
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    copyBytesR12ToR14(chunkBase + (cnt - 1 - k) * 8, dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff;
                if (words > 1) {
                    srcOff = chunkBase + (words - 1 - k) * 8;
                } else {
                    srcOff = chunkBase;
                }
                copyBytesR12ToR14(srcOff, dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(newRepackDone + ":");
        raw(("    addq $" + total + ", %rsp"));
        pushReg("r14");
    }

    void emitAssignRepack(long size, BytecodeStatement line) {
        // One entry per layout token: chunk = bytes it occupies on the stack, fieldSize = real bytes (0 for a padding gap),
        // elemSize/count > 0 for "a<elemBytes>x<n>" (a fixed array written element by element: n whole words, one per element).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();
        List<Long> elemSize = new ArrayList<>();
        List<Long> elemCount = new ArrayList<>();
        for (int i = 4; i < line.size(); i++) {
            String tok = line.get(i).text;
            char kind = tok.charAt(0);
            if (kind == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (kind == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw("    movq %rsp, %r10");
        raw(("    movq " + total + "(%rsp), %r11"));
        long dst = 0;
        long consumed = 0;
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed;
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i);
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    // element k was pushed k-th, so the last element sits nearest %rsp
                    copyBytes("r10", chunkBase + (cnt - 1 - k) * 8, "r11", dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff;
                if (words > 1) {
                    srcOff = chunkBase + (words - 1 - k) * 8;
                } else {
                    srcOff = chunkBase;
                }
                copyBytes("r10", srcOff, "r11", dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(("    addq $" + (total + 8) + ", %rsp"));
    }

    /** Copies `n` (1..8) bytes from srcOff(srcReg) to dstOff(dstReg) through %rax, in the fewest naturally-sized moves. */
    void copyBytes(String srcReg, long srcOff, String dstReg, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = widthFor(left);
            raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%" + srcReg + "), %" + sizedReg("rax", w));
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%" + dstReg + ")");
            
            done += w;
        }
    }

    /** Copies `n` (1..8) bytes from srcOff(%r12) to dstOff(%r14) through %rax, in the fewest naturally-sized moves. */
    void copyBytesR12ToR14(long srcOff, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = widthFor(left);
            raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%r12), %" + sizedReg("rax", w));
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%r14)");
            
            done += w;
        }
    }

    // See README.md, "Construction run detection".
    void precomputeConstructionOffsets() {
        core.pushStoreOffsetByLine.clear();
        core.constructionScopedAssign.clear();
        core.packedNewLines.clear();
        for (int i = 0; i < core.allLines.size(); i++) {
            BytecodeStatement l = core.allLines.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            boolean isNew = m.equals("NEW") && l.size() >= 2;
            boolean isBlockAssign = (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() >= 4
                    && l.get(1).text.equals(l.get(2).text) && l.get(2).text.equals(l.get(3).text);
            if (!isNew && !isBlockAssign) {
                continue;
            }
            long declaredSize = Long.parseLong(l.get(1).text);
            int runStart = findRunStart(i, declaredSize);
            if (runStart < 0) {
                continue;
            }
            if (isBlockAssign) {
                X86RunEntries entries = collectRunEntries(runStart, i);
                if (entries.sizes.size() <= 1) {
                    continue; // a single PUSH feeding this ASSIGN: an ordinary scalar assign or a whole-block copy, not a construction
                }
            }
            populateDirectMembers(runStart, i, declaredSize);
            if (isBlockAssign) {
                core.constructionScopedAssign.add(i);
            } else {
                core.packedNewLines.add(i);
            }
        }
    }

    /** True for the Windows target (windows_gnu: GNU as + mingw-w64): gates win64 ABI choices (argument registers, 32-byte shadow space, no SysV varargs %al convention). The assembly syntax is AT&T on every target. */
    boolean isWinAbi() {
        return core.target == CodegenTarget.WINDOWS_GNU_X64;
    }

    // ---- Pass 1: declarations -------------------------------------------------

    void collectDeclarations(List<BytecodeStatement> lines) {
        HashSet<String> plainGlobalNames = new HashSet<>();
        for (BytecodeStatement line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if ((mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) && line.size() >= 3) {
                String name = line.get(1).text;
                if (!name.contains(".")) {
                    plainGlobalNames.add(name);
                }
            }
            if (mnemonic.equals("FUNC_START") && line.size() >= 2) {
                core.declaredFunctionNames.add(line.get(1).text);
            }
        }
        // Pass 2: real declarations, aliasing dotted fields of a known
        // parent block instead of giving them their own storage.
        LinkedHashMap<String, Long> parentRunningOffset = new LinkedHashMap<>();
        for (BytecodeStatement line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if (mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) {
                String name = line.get(1).text;
                long size = Long.parseLong(line.get(2).text);
                int dot = name.lastIndexOf('.');
                String direct;
                if (dot >= 0) {
                    direct = name.substring(0, dot);
                } else {
                    direct = null;
                }
                if (direct != null && (plainGlobalNames.contains(direct) || core.globalAliasOffset.containsKey(direct)) && !core.globalAliasOffset.containsKey(name)) {
                    boolean nested = core.globalAliasOffset.containsKey(direct);
                    String parent;
                    long baseOffset;
                    if (nested) {
                        parent = core.globalAliasParent.get(direct);
                        baseOffset = core.globalAliasOffset.get(direct);
                    } else {
                        parent = direct;
                        baseOffset = 0L;
                    }
                    long offset = baseOffset + parentRunningOffset.getOrDefault(direct, 0L);
                    core.globalAliasOffset.put(name, offset);
                    core.globalAliasParent.put(name, parent);
                    parentRunningOffset.put(direct, parentRunningOffset.getOrDefault(direct, 0L) + size);
                    if (line.size() >= 4) {
                        List<long[]> inits = core.aliasInits.get(parent);
                        if (inits == null) {
                            inits = new ArrayList<>();
                            core.aliasInits.put(parent, inits);
                        }
                        inits.add(new long[]{offset, size, initBits(line.get(3).text, size)});
                    }
                    continue;
                }
                if (line.size() >= 4) {
                    long init = initBits(line.get(3).text, size);
                    core.globalsSizeInit.put(name, new long[]{size, init});
                    core.globalsHasInit.put(name, true);
                } else if (!core.globalsSizeInit.containsKey(name)) {
                    core.globalsSizeInit.put(name, new long[]{size, 0});
                    core.globalsHasInit.put(name, false);
                }
            } else if (mnemonic.equals("STRING") && line.size() >= 3) {
                String id = line.get(1).text;
                String text = line.get(2).text;
                // Strip the surrounding quotes the parser keeps on a
                // STRING literal token -- real .asciz wants the raw text.
                if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                }
                core.stringLiterals.put(id, text);
            }
        }
    }

    /** A GLOBAL initialiser's bit pattern for a slot of `size` bytes: null/integer as before, a float literal as its IEEE-754 bits (4 bytes: single, otherwise double) -- previously any float initialiser was silently zero. */
    long initBits(String text, long size) {
        if (!text.equals("null") && !X86Core.isInteger(text) && X86Core.isFloatLiteral(text)) {
            if (size <= 4) {
                return Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(text)));
            }
            return Double.doubleToRawLongBits(Double.parseDouble(text));
        }
        return parseInitValue(text);
    }

    long parseInitValue(String text) {
        if (text.equals("null")) {
            return 0;
        }
        if (X86Core.isInteger(text)) {
            return Long.parseLong(text);
        }
        return 0;
    }

    void emitDataSections() {
        if (!core.globalsSizeInit.isEmpty()) {
            raw(".data");
            for (String key : core.globalsSizeInit.keySet()) {
                long[] value = core.globalsSizeInit.get(key);
                String label = mangleGlobalName(key);
                long size = value[0];
                long init = value[1];
                boolean hasInit = core.globalsHasInit.getOrDefault(key, false);
                raw((".globl " + label));
                raw(label + ":");
                List<long[]> parts = core.aliasInits.get(key);
                if (parts != null) {
                    // A whole-block global whose elements carry initial
                    // values: lay the bytes out little-endian.
                    byte[] image = new byte[(int) size];
                    for (long[] part : parts) {
                        for (int b = 0; b < part[1] && part[0] + b < size; b++) {
                            image[(int) (part[0] + b)] = (byte) (part[2] >>> (8 * b));
                        }
                    }
                    StringBuilder bytes = new StringBuilder();
                    for (int b = 0; b < image.length; b++) {
                        if (b != 0) {
                            bytes.append(", ");
                        }
                        bytes.append(image[b] & 0xFF);
                    }
                    raw(("    .byte ") + bytes);
                } else {
                    long storeInit;
                    if (hasInit) {
                        storeInit = init;
                    } else {
                        storeInit = 0;
                    }
                    emitStorageDirective(size, storeInit);
                }
            }
        }
        if (!core.stringLiterals.isEmpty()) {
            raw(".section .rodata");
            for (String key : core.stringLiterals.keySet()) {
                String value = core.stringLiterals.get(key);
                raw(key + ":");
                raw(("    .asciz \"" + value + "\""));
            }
        }
    }

    /** Emits `size` bytes of storage, in units of quad/long/word/byte, initialized to `init` (repeated/truncated as needed -- good enough for the scalar globals this pass actually sees; anything wider than 8 bytes is zero-filled regardless of `init`, since no observed GLOBAL line initializes a multi-word aggregate). */
    void emitStorageDirective(long size, long init) {
        if (size == 8) {
            raw(("    .quad " + init));
        } else if (size == 4) {
            raw(("    .long " + init));
        } else if (size == 2) {
            raw(("    .word " + init));
        } else if (size == 1) {
            raw(("    .byte " + init));
        } else {
            raw(("    .zero " + size));
        }
    }

    /** "ghost_table.lockState" -> "ghost_table_lockState" -- '.' isn't valid in an assembly label; every GLOBAL declaration and every reference to it (PUSH/ADDR/ATOMIC_* of a bare name) goes through this same mangling, so the two always agree. */
    String mangleGlobalName(String name) {
        return name.replace('.', '_');
    }

    void emitHeader() {
        if (core.target == CodegenTarget.WINDOWS_GNU_X64) {
            core.out.append("# Generated by caspien-codegen (target: windows_gnu x86-64, mingw-w64/GNU as)\n");
            core.out.append("# GAS AT&T syntax, win64 ABI -- assemble/link with x86_64-w64-mingw32-gcc.\n");
        } else {
            core.out.append("# Generated by caspien-codegen (target: linux x86-64)\n");
            core.out.append("# GAS AT&T syntax -- assemblable/runnable directly with as/ld/gcc.\n");
        }
    }

    void emitFooter() {
    }

    void comment(String text) {
        core.out.append("# ").append(text).append('\n');
    }

    void raw(String text) {
        currentSink().append(text).append('\n');
        trackStackDelta(text);
        if (core.strideKeySink != null) {
            strideKeyAfter(text);
        }
    }

    void strideKeyAfter(String text) {
        String t = text.trim();
        int sp = t.indexOf(' ');
        String op;
        if (sp < 0) {
            op = t;
        } else {
            op = t.substring(0, sp);
        }
        if (t.indexOf('\n') >= 0 || t.endsWith(":") || !X86Core.STRIDE_KEEP_OPS.contains(op)) {
            core.strideKeySink = null;
            return;
        }
        // destination = the last operand outside parentheses
        int depth = 0, cut = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) cut = i;
        }
        String destRaw;
        if (cut < 0) {
            int from;
            if (sp < 0) {
                from = t.length();
            } else {
                from = sp;
            }
            destRaw = t.substring(from);
        } else {
            destRaw = t.substring(cut + 1);
        }
        String dest = destRaw.trim();
        if (!dest.startsWith("%")) {
            return;                       // a memory destination (a store) or an immediate-only form: no register changes
        }
        if (dest.startsWith("%xmm")) {
            return;
        }
        if (X86Core.regAliasOf(dest.substring(1), X86Core.RF_SCRATCH) || X86Core.regAliasOf(dest.substring(1), core.strideKeyIdx)) {
            core.strideKeySink = null;
        }
    }

    void trackStackDelta(String rawText) {
        if (core.argTrackSuspendDepth > 0 || core.argTrackDeltaStack.isEmpty()) {
            return;
        }
        String t = rawText.trim();
        long delta = 0;
        if (t.startsWith("pushq %") || (t.startsWith("push ") && !t.startsWith("pushq"))) {
            delta = 8;
        } else if (t.startsWith("popq %") || (t.startsWith("pop ") && !t.startsWith("popq"))) {
            delta = -8;
        } else if (t.startsWith("subq $") && t.endsWith(", %rsp")) {
            delta = parseRspDelta(t, "subq $", ", %rsp");
        } else if (t.startsWith("addq $") && t.endsWith(", %rsp")) {
            delta = -parseRspDelta(t, "addq $", ", %rsp");
        } else if (t.startsWith("sub rsp, ")) {
            delta = Long.parseLong(t.substring("sub rsp, ".length()).trim());
        } else if (t.startsWith("add rsp, ")) {
            delta = -Long.parseLong(t.substring("add rsp, ".length()).trim());
        }
        if (delta != 0) {
            core.argTrackDeltaStack.push(core.argTrackDeltaStack.pop() + delta);
        }
    }

    long parseRspDelta(String t, String prefix, String suffix) {
        return Long.parseLong(t.substring(prefix.length(), t.length() - suffix.length()).trim());
    }

    /** push the 64-bit value in the given register onto the real machine stack. */
    void pushReg(String reg) {
        raw(("    pushq %" + reg));
    }

    /** pop the top of the real machine stack into the given register. */
    void popReg(String reg) {
        raw(("    popq %" + reg));
    }

    void movMemToReg(String reg, long offset) {
        raw("    movq " + offset + "(%rbp), %" + reg);
        
    }

    void movRegToMem(String reg, long offset) {
        raw("    movq %" + reg + ", " + offset + "(%rbp)");
        
    }

    void leaMemToReg(String reg, long offset) {
        raw("    leaq " + offset + "(%rbp), %" + reg);
        
    }

    void leaGlobalToReg(String reg, String globalName) {
        if (core.globalAliasOffset.containsKey(globalName)) {
            String parentLabel = mangleGlobalName(core.globalAliasParent.get(globalName));
            long offset = core.globalAliasOffset.get(globalName);
            raw("    leaq " + parentLabel + plusOffset(offset) + "(%rip), %" + reg);
            
            return;
        }
        String label = mangleGlobalName(globalName);
        raw("    leaq " + label + "(%rip), %" + reg);
        
    }

    /** The RIP-relative memory operand of a global (an alias into a parent block adds its fixed offset): `label+off(%rip)`. */
    String globalRipOperand(String globalName) {
        // "sym+N" (GlobalConstAddrPass: a global array element at a constant index) is the global plus N bytes
        long extra = 0;
        int plus = globalName.indexOf('+');
        if (plus > 0) {
            extra = Long.parseLong(globalName.substring(plus + 1));
            globalName = globalName.substring(0, plus);
        }
        if (core.globalAliasOffset.containsKey(globalName)) {
            String parentLabel = mangleGlobalName(core.globalAliasParent.get(globalName));
            long offset = core.globalAliasOffset.get(globalName) + extra;
            return parentLabel + plusOffset(offset) + "(%rip)";
        }
        return mangleGlobalName(globalName) + plusOffset(extra) + "(%rip)";
    }

    void movImmToReg(String reg, long imm) {
        // 0 .. 0xFFFFFFFF: `movl $imm, %r32` (5-6 bytes, zero-extends, flags untouched) instead of a 10-byte movabs
        if (imm >= 0 && imm <= 0xFFFFFFFFL && sizedReg(reg, 4) != reg) {
            raw("    movl $" + imm + ", %" + sizedReg(reg, 4));
            return;
        }
        raw("    movq $" + imm + ", %" + reg);
    }

    // ---- Size-aware register/memory helpers ------------------------------

    /** al/ax/eax/rax-style sub-register name for a bare 64-bit register name and a byte width. */
    String sizedReg(String reg64, int size) {
        if (reg64.equals("rax")) {
            return pickBySize(size, "al", "ax", "eax", "rax");
        } else if (reg64.equals("rbx")) {
            return pickBySize(size, "bl", "bx", "ebx", "rbx");
        } else if (reg64.equals("rcx")) {
            return pickBySize(size, "cl", "cx", "ecx", "rcx");
        } else if (reg64.equals("rdx")) {
            return pickBySize(size, "dl", "dx", "edx", "rdx");
        } else if (reg64.equals("rsi")) {
            return pickBySize(size, "sil", "si", "esi", "rsi");
        } else if (reg64.equals("rdi")) {
            return pickBySize(size, "dil", "di", "edi", "rdi");
        } else {
            if (reg64.matches("r(8|9|1[0-5])") && size < 8) {
                return reg64 + pickBySize(size, "b", "w", "d", "d");
            }
            return reg64;
        }
    }

    /** Sign-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8). */
    void signExtendReg(String reg, int size) {
        if (size >= 8) {
            return;
        }
        if (size == 4) {
            raw("    movslq %" + sizedReg(reg, 4) + ", %" + reg);
        } else {
            raw("    movs" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    void emitShiftCore(String op, int size, String v) {
        if (op.equals("SAR")) {
            signExtendReg(v, size);
            // count = min(count, 63)
            movImmToReg(X86Core.RF_SCRATCH, 63);
            raw("    cmpq %" + X86Core.RF_SCRATCH + ", %rcx");
            raw("    cmovaq %" + X86Core.RF_SCRATCH + ", %rcx");
            raw("    sarq %cl, %" + v);
            
            zeroExtendReg(v, size);
            return;
        }
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        String shiftOp;
        if (left) {
            shiftOp = "shlq";
        } else {
            shiftOp = "shrq";
        }
        raw("    " + shiftOp + " %cl, %" + v);
        raw("    cmpq $64, %rcx");
        raw("    sbbq %rcx, %rcx");
        raw("    andq %rcx, %" + v);
        
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    void emitShiftBmi2(String op, int size, String v, String cnt) {
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        String shiftOp;
        if (left) {
            shiftOp = "shlxq";
        } else {
            shiftOp = "shrxq";
        }
        raw("    " + shiftOp + " %" + cnt + ", %" + v + ", %" + v);
        raw("    cmpq $64, %" + cnt);
        raw("    sbbq %" + cnt + ", %" + cnt);
        raw("    andq %" + cnt + ", %" + v);
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    /** Zero-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8 or an odd size). */
    void zeroExtendReg(String reg, int size) {
        if (size != 1 && size != 2 && size != 4) {
            return;
        }
        if (size == 4) {
            raw("    movl %" + sizedReg(reg, 4) + ", %" + sizedReg(reg, 4));
        } else {
            raw("    movz" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    /** "+off" for a nonzero offset, otherwise "" */
    private static String plusOffset(long off) {
        if (off != 0) {
            return "+" + off;
        }
        return "";
    }

    /** the decimal offset, or "" for zero (an AT&T displacement of 0 is omitted) */
    private static String offsetText(long off) {
        if (off == 0) {
            return "";
        }
        return String.valueOf(off);
    }

    /** the largest natural move width (8, 4, 2 or 1) that fits in `left` bytes */
    private static int widthFor(long left) {
        if (left >= 8) {
            return 8;
        } else if (left >= 4) {
            return 4;
        } else if (left >= 2) {
            return 2;
        }
        return 1;
    }

    /** one of four names by operand size: 1, 2, 4, anything else */
    private static String pickBySize(int size, String s1, String s2, String s4, String other) {
        if (size == 1) {
            return s1;
        } else if (size == 2) {
            return s2;
        } else if (size == 4) {
            return s4;
        }
        return other;
    }

    String movSuffix(int size) {
        return pickBySize(size, "b", "w", "l", "q");
    }

    /** zero-extending load of `size` bytes at (%rbp+offset) into the full 64-bit `reg64`. */
    void loadSizedFromFrame(String reg64, long offset, int size) {
        int loadSize = X86Core.roundUpToLoadableWidth(size);
        if (loadSize == 8) {
            raw("    movq " + offset + "(%rbp), %" + reg64);
        } else if (loadSize == 4) {
            // movl auto-zero-extends into the full 64-bit register.
            raw("    movl " + offset + "(%rbp), %" + sizedReg(reg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + offset + "(%rbp), %" + reg64);
        }
    }

    /** store the low `size` bytes of `reg64` to (%rbp+offset). */
    void storeSizedToFrame(String reg64, long offset, int size) {
        if (X86Core.isOddSize(size)) {
            storeOddSize(reg64, "rbp", offset, size);
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(reg64, size) + ", " + offset + "(%rbp)");
    }

    /** zero-extending load of `size` bytes at (%addrReg) into `destReg64`. Same over-read-past-a-non-power-of-2-size reasoning as `loadSizedFromFrame` -- see `roundUpToLoadableWidth`'s own doc comment. */
    void loadSizedFromAddr(String destReg64, String addrReg64, int size) {
        int loadSize = X86Core.roundUpToLoadableWidth(size);
        String mem = hub.gen.rfMemOperand(addrReg64);
        if (loadSize == 8) {
            raw("    movq " + mem + ", %" + destReg64);
        } else if (loadSize == 4) {
            raw("    movl " + mem + ", %" + sizedReg(destReg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + mem + ", %" + destReg64);
        }
    }

    void extractWordsFromReversedBlock(long totalWords, int size) {
        long elemWords = (size + 7) / 8;
        raw("    movq %rax, %r11");
        raw("    subq %r14, %r11");
        raw("    shlq $3, %r14");
        raw("    movq %rcx, %r13");
        raw("    movq %r14, %rcx");
        
        for (long j = 0; j < elemWords; j++) {
            long lo = -(j * 8);
            long hi = -(j * 8 + 8);
            long dst = (totalWords - 1 - j) * 8;
            raw("    movq " + lo + "(%r11), %rax");
            raw("    movq " + hi + "(%r11), %r14");
            raw("    shrdq %cl, %r14, %rax");
            raw("    movq %rax, " + dst + "(%rsp)");
            
        }
        raw("    movq %r13, %rcx");
        if (totalWords > elemWords) {
            raw(("    addq $" + ((totalWords - elemWords) * 8) + ", %rsp"));
        }
    }

    void storeOddSize(String srcReg64, String baseReg64, long off, int size) {
        int[] widths;
        if (size == 3) {
            widths = new int[] {2, 1};
        } else if (size == 5) {
            widths = new int[] {4, 1};
        } else if (size == 6) {
            widths = new int[] {4, 2};
        } else {
            widths = new int[] {4, 2, 1};
        }
        long at = off;
        int rot = 0;
        for (int i = 0; i < widths.length; i++) {
            storePiece(srcReg64, baseReg64, at, widths[i]);
            at += widths[i];
            if (i + 1 < widths.length) {
                rotReg(srcReg64, true, widths[i] * 8);
                rot += widths[i] * 8;
            }
        }
        if (rot > 0) {
            rotReg(srcReg64, false, rot);
        }
    }

    void storePiece(String srcReg64, String baseReg64, long off, int width) {
        raw("    mov" + movSuffix(width) + " %" + sizedReg(srcReg64, width) + ", " + offsetText(off) + "(%" + baseReg64 + ")");
        
    }

    void rotReg(String reg64, boolean right, int bits) {
        String op;
        if (right) {
            op = "ror";
        } else {
            op = "rol";
        }
        raw(("    " + op + "q $" + bits + ", %" + reg64));
    }

    /** store the low `size` bytes of `srcReg64` to (%addrReg64). */
    void storeSizedToAddr(String srcReg64, String addrReg64, int size) {
        if (X86Core.isOddSize(size)) {
            storeOddSize(srcReg64, addrReg64, 0, size);
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(srcReg64, size) + ", " + hub.gen.rfMemOperand(addrReg64));
    }

    /** pushes a >8-byte value stored at (%rbp+baseOffset), one whole word at a time, lowest source offset first -- the highest-offset word ends up on top of the real stack. Mirror of `assignBlock`/POP's own block-store below. */
    void pushBlockFromFrame(long baseOffset, long size) {
        long words = (size + 7) / 8;
        for (long i = 0; i < words; i++) {
            loadSizedFromFrame("rax", baseOffset + i * 8, 8);
            pushReg("rax");
        }
    }

    void pushBlockFromFrameConstructionAware(long baseOffset, long size) {
        Long constructionOffset = core.pushStoreOffsetByLine.get(core.currentLineIndex);
        if (constructionOffset == null) {
            pushBlockFromFrame(baseOffset, size);
            return;
        }
        raw("    subq $" + size + ", %rsp");
        
        long pos = 0;
        while (pos < size) {
            long left = size - pos;
            int w = widthFor(left);
            loadSizedFromFrame("rax", baseOffset + pos, w);
            long dst = constructionOffset + pos;
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + dst + "(%rsp)");
            
            pos += w;
        }
    }

    /** the block-store counterpart of `pushBlockFromFrame`/DEREF's own multi-word push: pops a >8-byte value (top of stack = highest-offset word) together with the single-word address pushed just before it, and stores each word to its real position. The address is read non-destructively first (it sits `size` bytes below the current top, past the whole value block) so it can still be located after the value words are popped off. */
    void assignBlock(long size) {
        long words = (size + 7) / 8;
        raw("    movq " + (words * 8) + "(%rsp), %r15");
        
        for (long i = 0; i < words; i++) {
            popReg("rax");
            // Word k of the value sits at byte 8k. The top word popped first is the highest one, k = words-1. (This used `size - 8 - 8i`, which is
            // the same only when size is a whole number of words: a 12- or 20-byte array copy (`let a = mut s.v`) put its words 4 bytes too low.)
            long k = words - 1 - i;
            long destOff = k * 8;
            long n = Math.min(8, size - destOff); // the last word of a size that is not a multiple of 8 is partial: write only its real bytes
            if (n == 8) {
                raw("    movq %rax, " + offsetText(destOff) + "(%r15)");
                
            } else if (X86Core.isOddSize((int) n)) {
                storeOddSize("rax", "r15", destOff, (int) n);
            } else {
                storePiece("rax", "r15", destOff, (int) n);
            }
        }
        raw("    addq $8, %rsp");
    }

    void assignConstructionBlock(long size) {
        if (size > 0 && size % 8 == 0 && size <= 64) {
            // A small whole-word block (a range is 16 bytes, built on every `for` entry) is copied word by word: no rep prefix, no
            // rcx save/restore. Only r15 (destination) and rdi (data) are used; both were already clobbered by the general path below.
            raw("    movq " + size + "(%rsp), %r15");
            for (long o = 0; o < size; o += 8) {
                raw("    movq " + o + "(%rsp), %rdi");
                raw("    movq %rdi, " + o + "(%r15)");
            }
            raw("    addq $" + (size + 8) + ", %rsp");
            
            return;
        }
        raw("    movq " + size + "(%rsp), %r15"); // destination address, sitting just past the value block
        raw("    movq %r15, %rdi");
        raw("    movq %rsp, %rsi");
        raw("    pushq %rcx"); // rcx may still hold a live, not-yet-consumed incoming argument (SysV's ARG3) -- rep movsb needs it as a byte counter, so save/restore around that use rather than clobbering it
        raw("    movq $" + size + ", %rcx");
        raw("    rep movsb");
        raw("    popq %rcx");
        raw("    addq $" + size + ", %rsp");
        raw("    addq $8, %rsp"); // the address word itself, now that it's been read
        
    }
}
