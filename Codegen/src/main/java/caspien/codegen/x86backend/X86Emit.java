package caspien.codegen.x86backend;

import caspien.codegen.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return core.callBufferStack.isEmpty() ? core.out : core.callBufferStack.peek();
    }

    /** Consumes (reads and clears) `pendingVarargsXmmCount`, defaulting to 0 (correct for a wholly non-variadic call, or an already-conservative "no floats" guess if this is somehow reached for a variadic call whose count was never set -- should not happen, but 0 is what this backend always assumed before this field existed at all). */
    int consumeVarargsXmmCount() {
        int count = core.pendingVarargsXmmCount < 0 ? 0 : core.pendingVarargsXmmCount;
        core.pendingVarargsXmmCount = -1;
        return count;
    }

    /**
     * Returns the start index of the run of field productions ending
     * exactly at `endExclusive` (i.e. lines endExclusive-1,
     * endExclusive-2, ... going backward) whose sizes sum to EXACTLY
     * `targetSize` -- or -1 if no such run exists. A "production" is an
     * ordinary "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" line (contributing its
     * own declared size/gap) or a complete nested "NEW <size>"
     * (contributing exactly 8 bytes -- the pointer it leaves behind --
     * once its *own* run is confirmed, with an explicit stack, to sum to that same
     * declared size).
     *
     * `targetSize` is load-bearing, not a mere sanity check: a nested
     * construction's own trailing field pushes are textually
     * indistinguishable from the outer run's own earlier field pushes
     * (both are just "PUSH size operand" lines, with no marker of its
     * own separating one run from the next) -- the *only* thing that
     * tells this scan where the nested run actually started is that its
     * own declared size (the nested "NEW"'s own operand) is reached
     * exactly. A scan with no target, greedily consuming every
     * syntactically-valid production it finds, cannot stop there: it
     * would keep walking straight past the nested run's own true start
     * and into the outer run's remaining fields, silently miscounting
     * both. So this stops the instant the running total reaches
     * `targetSize` (success) or would exceed it (failure) -- never
     * "as much as looks valid."
     */
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

    /**
     * Populates `pushStoreOffsetByLine` for every *direct* (non-nested)
     * "PUSH"/"ATOMIC_PUSH" member of the run [start, endExclusive) --
     * each one's own value is `trueOffset - remainingBytes`, the signed
     * byte offset from `%rsp` *after that specific push's own `subq`*
     * to store its value at (see the class-level note above `case
     * "PUSH"`'s own doc comment for why plain "(%rsp)" -- offset 0 --
     * is wrong the moment a construction has more than one field: each
     * field's own `subq` grows the stack *downward*, so by itself that
     * lays fields out in the *reverse* of their real struct order;
     * `trueOffset - remainingBytes` corrects for exactly that). A
     * nested "NEW" found along the way is skipped as a single opaque
     * unit (it never itself needs a store-offset entry -- its own
     * `pushReg` inside `case "NEW"` is a plain, ordinary, always-8-byte
     * push, ineligible for this treatment entirely) -- its own direct
     * members are populated separately, when *that* "NEW" is processed
     * on its own by `precomputeConstructionOffsets`.
     */
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

    /**
     * After a value's own bits are already sitting in `regName` (always
     * "rax" today, but kept general for clarity), either stores it at its
     * own precomputed, tightly-packed construction offset (see
     * `populateDirectMembers`'s own doc comment) when this exact PUSH line
     * is a direct member of a struct/array/range/primitive construction,
     * or, when it isn't, does a plain, ordinary full-word `pushq`
     * (`pushReg`), the identical fallback every PUSH already had before
     * construction-packing existed.
     *
     * Shared between every PUSH operand shape (`case "PUSH"`'s "$offset"
     * branch and its own generic literal/ARG/global fallback) so newly-
     * added operand shapes automatically participate in construction-
     * packing rather than needing this decision duplicated -- **the exact
     * fix this doc comment itself documents**: the "$offset" branch used
     * to call `pushReg` unconditionally, silently NEVER checking
     * `pushStoreOffsetByLine` at all, so any construction with a variable-
     * sourced field (e.g. "0..index", a range literal whose end is a
     * parameter's own value, not a literal) got its variable-sourced entry
     * pushed as an ordinary, unrelated stack push -- corrupting the whole
     * tightly-packed image the terminating ASSIGN/`assignConstructionBlock`/
     * `NEW` expects, in a way that only manifests once that entry is
     * actually consumed (silently, no crash at the construction site
     * itself). Found via a real segfault inside the ghost table's own
     * `gtSlotAt`/`gtWriteSlot` machinery (`stdlib/gt/ghost_table.caspien`'s
     * own "for i in 0..index" -- a range literal with one immediate ("0")
     * and one frame-loaded ("index") entry).
     */
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

    /**
     * The shared forward-scan used both to *count* a run's own direct
     * members (`precomputeConstructionOffsets`'s own way of telling a
     * genuine multi-field construction apart from an ordinary
     * single-value `ASSIGN` -- ordinary scalar assigns and whole-block
     * copies alike are always exactly *one* `PUSH`, whatever their
     * declared size, while any real struct/array literal is always
     * *more than one* -- so the entry *count*, not the byte size, is
     * what actually distinguishes them) and to lay out their real
     * store offsets (`populateDirectMembers`). Walking forward, a
     * nested construction's own field pushes are indistinguishable
     * from this (outer) run's own direct field pushes right up until
     * its terminating "NEW" line is actually reached -- so they get
     * recorded provisionally, exactly like any other direct member,
     * and are only recognized and collapsed retroactively, once that
     * "NEW" line is seen: pop entries off the back of what's been
     * recorded so far until their sizes sum to exactly this "NEW"'s
     * own declared size (an already-collapsed deeper-nested "NEW" pops
     * as a single 8-byte entry, so this handles arbitrary nesting
     * depth correctly), then replace them all with one opaque 8-byte
     * entry (the nested "NEW"'s own resulting pointer, pushed by its
     * own plain, ordinary, always-8-byte `pushReg` -- never eligible
     * for a store offset of its own).
     */
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

    /**
     * "NEW size m8 m8 p4 m1 ..." where the run of field pushes could NOT be
     * packed (see `packedNewLines`). Every field production was pushed the
     * ordinary way: a scalar as one full 8-byte word, a wider field as a
     * word-reversed block (`pushBlockFromFrame`'s convention, highest-offset
     * word nearest %rsp), and each padding gap as an exact `subq` -- so the
     * stack region, read from %rsp upward, is the word-for-word REVERSE of
     * the "padded image" (fields in declared order, each rounded up to whole
     * words, gaps exact). This rebuilds the real, tightly-packed struct in a
     * fresh heap buffer straight from that description: for each layout
     * entry, copy its bytes from where that chunk sits on the stack to the
     * entry's true offset in the buffer. Unlike the packed path this needs
     * to know the layout, which LowerOrderGenerator appends to the line.
     */
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
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
                copyBytesR12ToR14(srcOff, dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(newRepackDone + ":");
        raw(("    addq $" + total + ", %rsp"));
        pushReg("r14");
    }

    /**
     * "ASSIGN size size size m8 m4 p4 a4x3 ..." for a stack struct literal whose field pushes could not be packed (see
     * `emitNewRepack`, which does the same for a heap `new`): every scalar field was pushed as one whole 8-byte word, a wider
     * field as a word-reversed block, and each padding gap as an exact `subq`, so the stack region from %rsp upward is the
     * reverse of the padded image, with the destination address in the word just above it. Copies each field's bytes to its
     * true offset from that address, then drops the field words and the address. Uses only the scratch registers %r10 (source
     * base), %r11 (destination) and %rax.
     */
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
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
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
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
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
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%r12), %" + sizedReg("rax", w));
            raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%r14)");
            
            done += w;
        }
    }

    /**
     * Runs once per `generate()` call: finds every construction
     * *terminator* in the program -- a "NEW <size>" line (a heap
     * construction), or an "ASSIGN"/"ATOMIC_ASSIGN size size size" line
     * whose three size operands agree and exceed 8 (a plain,
     * stack-resident struct/array/primitive literal's own top-level
     * store -- never a scalar ASSIGN, which this bytecode's own
     * convention always carries as three *equal*, but not necessarily
     * `>8`, sizes too, so the `>8` check is what actually tells the two
     * apart) -- and, for each whose own immediately-preceding run of
     * pushes resolves to that exact declared size (`findRunStart`),
     * records every direct member's own store offset into
     * `pushStoreOffsetByLine`, exactly as it already did for "NEW"
     * alone.
     *
     * This single generalization is the whole fix for a real bug: the
     * "each push in a construction run reserves only its own declared
     * width" treatment only ever fired ahead of a "NEW" line before --
     * a plain, non-heap struct/array literal fell back to the old,
     * blind, always-8-byte-word path instead (see
     * `constructionScopedAssign`'s own doc comment for the real
     * fixtures this broke). Nothing about *how* a run is detected
     * changed at all -- `findRunStart`/`collectRunEntries` are the
     * identical, unmodified methods "NEW" alone used before; only
     * *which lines are allowed to trigger that same detection* grew a
     * second case.
     *
     * An "ASSIGN"/"ATOMIC_ASSIGN size size size" line's three equal
     * size operands alone do NOT tell a genuine construction (a
     * struct/array literal's own top-level store) apart from an
     * ordinary scalar assign or a whole-block copy (`let b = a`) --
     * this bytecode's own convention emits all three the exact same
     * way, and a copy's declared size can just as easily be large (a
     * big struct) as a literal's can be small (a two-element `char`
     * array, total 2 bytes). A size threshold was tried first and was
     * wrong on both ends: it misses small literals like a `char[4]`
     * (declared size 4, well under any plausible threshold) and it
     * would just as readily misfire on a large whole-struct copy given
     * a low enough one. What actually distinguishes them is the
     * *number of direct pushes* feeding the ASSIGN, not their combined
     * size: a scalar assign and a whole-block copy alike are always
     * fed by exactly *one* PUSH (a single value, or a single `$offset`
     * reference to the whole source block); a real literal construction
     * -- however small -- is always fed by *more than one*, one per
     * member. So the run is found the same way regardless (`findRunStart`
     * doesn't care what the caller intends to do with it), and only
     * *afterward*, once `collectRunEntries` has actually counted the
     * direct entries, is a single-entry run recognized as "not a
     * construction" and left on the old, already-correct blind path.
     */
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
        // Pass 1: every plain (non-dotted) GLOBAL/ALLOC_STATIC name, so
        // pass 2 below can tell a dotted "parent.field" declaration
        // apart from one whose "parent" isn't actually a separately
        // declared whole-block global (in which case it keeps its own
        // independent storage, the pre-existing behavior).
        java.util.Set<String> plainGlobalNames = new java.util.HashSet<>();
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
        Map<String, Long> parentRunningOffset = new LinkedHashMap<>();
        for (BytecodeStatement line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if (mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) {
                // "GLOBAL name size" (bss, zero-init) or
                // "GLOBAL name size initVal" (data, non-zero init) --
                // "ALLOC_STATIC name size initVal" is the identical
                // shape for a function-local 'static', just declared at
                // its first-use point inside a function body instead of
                // at top level.
                String name = line.get(1).text;
                long size = Long.parseLong(line.get(2).text);
                int dot = name.lastIndexOf('.');
                String direct = dot >= 0 ? name.substring(0, dot) : null;
                // A nested member ("b.0.x" of a static array of structs) has an already-aliased direct parent ("b.0"): it lives in the ROOT
                // global at (parent's offset + running offset inside the parent). Before, only a plain parent was aliased, so the members of
                // every struct element kept their own stray storage and the array itself was emitted zeroed.
                if (direct != null && (plainGlobalNames.contains(direct) || core.globalAliasOffset.containsKey(direct)) && !core.globalAliasOffset.containsKey(name)) {
                    boolean nested = core.globalAliasOffset.containsKey(direct);
                    String parent = nested ? core.globalAliasParent.get(direct) : direct;
                    long offset = (nested ? core.globalAliasOffset.get(direct) : 0L) + parentRunningOffset.getOrDefault(direct, 0L);
                    core.globalAliasOffset.put(name, offset);
                    core.globalAliasParent.put(name, parent);
                    parentRunningOffset.put(direct, parentRunningOffset.getOrDefault(direct, 0L) + size);
                    if (line.size() >= 4) {
                        // The element's own initial value (a static array
                        // literal's elements arrive as "GLOBAL a.0 4 1.5" ...)
                        // -- kept so the parent's storage can be emitted with
                        // them; before, they were dropped and every static
                        // array started zeroed.
                        core.aliasInits.computeIfAbsent(parent, k -> new ArrayList<>())
                                .add(new long[]{offset, size, initBits(line.get(3).text, size)});
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
            return size <= 4
                    ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(text)))
                    : Double.doubleToRawLongBits(Double.parseDouble(text));
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
        // A float initializer, or anything else this first version
        // doesn't resolve to a plain integer -- zero-initialize rather
        // than guess at a bit pattern.
        return 0;
    }

    void emitDataSections() {
        if (!core.globalsSizeInit.isEmpty()) {
            raw(".data");
            for (Map.Entry<String, long[]> e : core.globalsSizeInit.entrySet()) {
                String label = mangleGlobalName(e.getKey());
                long size = e.getValue()[0];
                long init = e.getValue()[1];
                boolean hasInit = core.globalsHasInit.getOrDefault(e.getKey(), false);
                raw((".globl " + label));
                raw(label + ":");
                List<long[]> parts = core.aliasInits.get(e.getKey());
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
                        bytes.append(b == 0 ? "" : ", ").append(image[b] & 0xFF);
                    }
                    raw(("    .byte ") + bytes);
                } else {
                    emitStorageDirective(size, hasInit ? init : 0);
                }
            }
        }
        if (!core.stringLiterals.isEmpty()) {
            raw(".section .rodata");
            for (Map.Entry<String, String> e : core.stringLiterals.entrySet()) {
                raw(e.getKey() + ":");
                raw(("    .asciz \"" + e.getValue() + "\""));
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

    /**
     * The single low-level text-emission sink every other emission helper
     * in this file ultimately funnels through -- which makes it the one
     * place that can both (a) redirect output into whichever call's own
     * buffer is currently open (`currentSink()`, `callBufferStack`) and
     * (b) track the real running stack-pointer delta a call's own
     * argument-marshalling sequence leaves behind (`argTrackDelta`) by
     * recognizing the handful of instruction shapes that actually move
     * `%rsp`/`rsp`, directly off the real text being emitted -- see
     * `argTrackDelta`'s own doc comment for why this has to measure the
     * real emitted instructions rather than infer intent from the
     * bytecode. Only ever silently *not* attributed when
     * `argTrackSuspendDepth > 0` (inside an `emitAlignedCall`-wrapped
     * nested call, whose own net effect is always zero by construction)
     * or when no "CC_START" bracket is currently open at all (ordinary
     * code outside any call's own argument marshalling never needs this).
     */
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
        String op = sp < 0 ? t : t.substring(0, sp);
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
        String dest = (cut < 0 ? t.substring(sp < 0 ? t.length() : sp) : t.substring(cut + 1)).trim();
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

    /**
     * Recognizes the exact instruction shapes this backend ever emits
     * that move the real stack pointer by a literal, fixed amount --
     * `pushq %reg`/`push reg` (+8), `popq %reg`/`pop reg` (-8), `subq
     * $N, %rsp`/`sub rsp, N` (+N), `addq $N, %rsp`/`add rsp, N` (-N) --
     * and folds each into `argTrackDelta`. Deliberately does *not*
     * recognize an absolute assignment to rsp (`movq ..., %rsp`/`mov
     * rsp, ...`, the shape `emitAlignedCall`'s own restore and this same
     * fix's own "CC_START"/"CALL" reservation-and-restore both use) --
     * those aren't a *relative* move by a byte count this tracker could
     * even meaningfully add or subtract, and every real caller of one
     * already guarantees, by its own construction, that the net effect
     * across it is exactly zero.
     */
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
            // An alias into a parent block's own storage (see
            // globalAliasOffset's doc comment) -- load the parent's
            // address and add the field's fixed offset, rather than
            // referencing a separate (nonexistent) label of its own.
            String parentLabel = mangleGlobalName(core.globalAliasParent.get(globalName));
            long offset = core.globalAliasOffset.get(globalName);
            raw("    leaq " + parentLabel + (offset != 0 ? "+" + offset : "") + "(%rip), %" + reg);
            
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
            return parentLabel + (offset != 0 ? "+" + offset : "") + "(%rip)";
        }
        return mangleGlobalName(globalName) + (extra != 0 ? "+" + extra : "") + "(%rip)";
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
        switch (reg64) {
            case "rax": return size == 1 ? "al" : size == 2 ? "ax" : size == 4 ? "eax" : "rax";
            case "rbx": return size == 1 ? "bl" : size == 2 ? "bx" : size == 4 ? "ebx" : "rbx";
            case "rcx": return size == 1 ? "cl" : size == 2 ? "cx" : size == 4 ? "ecx" : "rcx";
            case "rdx": return size == 1 ? "dl" : size == 2 ? "dx" : size == 4 ? "edx" : "rdx";
            case "rsi": return size == 1 ? "sil" : size == 2 ? "si" : size == 4 ? "esi" : "rsi";
            case "rdi": return size == 1 ? "dil" : size == 2 ? "di" : size == 4 ? "edi" : "rdi";
            default:
                if (reg64.matches("r(8|9|1[0-5])") && size < 8) {
                    return reg64 + (size == 1 ? "b" : size == 2 ? "w" : "d");
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

    /**
     * The shared core of every variable-count shift (stack form SHL/SHR/SAR, register-form rfShift). On entry %rcx holds the shift
     * count already zero-extended from the operand width (the caller saved %rcx and loaded it); `v` is a 64-bit register holding
     * the value (stale bits above `size` bytes allowed). On exit `v` holds the result, zero-extended from `size` bytes, and %rcx
     * (and %r15 for SAR) are clobbered. The rule (see the SHL case): count >= 64 gives 0 (SHL/SHR) / sign fill (SAR); a count
     * in [size*8, 63] needs no special case, because SHL's extra bits fall off the truncation, SHR's zero-extended operand has
     * no bits left, and SAR's sign-extended operand already is all sign bits.
     */
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
        raw("    " + (left ? "shlq" : "shrq") + " %cl, %" + v);
        raw("    cmpq $64, %rcx");
        raw("    sbbq %rcx, %rcx");
        raw("    andq %rcx, %" + v);
        
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    /**
     * BMI2 form of a variable SHL/SHR (same meaning as emitShiftCore: a count >= 64 gives 0): `shlx/shrx` take the count from any register (no
     * %rcx save/restore), then the same compare-and-mask removes a count >= 64. `cnt` holds the zero-extended count and is overwritten (the mask).
     */
    void emitShiftBmi2(String op, int size, String v, String cnt) {
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        raw("    " + (left ? "shlxq" : "shrxq") + " %" + cnt + ", %" + v + ", %" + v);
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

    String movSuffix(int size) {
        return size == 1 ? "b" : size == 2 ? "w" : size == 4 ? "l" : "q";
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

    /**
     * Cuts a field / element of `size` (> 8) bytes out of the pushed value block of `totalWords` words and leaves it as a block of its own on the
     * stack (rsp raised past the words that are no longer needed). On entry `rax` = address of the field's first byte inside the block and `r14` =
     * that byte's offset inside its word (0..7). Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word, so the
     * field's bytes are NOT contiguous in memory unless it starts on a word boundary: result word j is the 64 bits starting at byte `within` of source
     * word w+j, continued into word w+j+1 (8 bytes BELOW it), which is exactly `shrd` (count 0 for an aligned field leaves the word unchanged).
     * Result word j goes to the slot of source word j (rsp + (T-1-j)*8): once rsp is raised by (T - E)*8 that is where it must be (rsp' + (E-1-j)*8),
     * and writing in ascending j never overwrites a word still to be read. The word past the block end (read for the last, possibly partial, result
     * word) lies just below rsp: valid stack, and only bytes beyond the field come from it.
     * %rcx is the shift count register and may hold an already-loaded call argument: it is stashed in r13 (backend scratch).
     * (Used by LOOKUP_ARRAY for an element wider than a word, and by DOT for a field wider than a word: a field that is not a whole number of
     * words used to be cut out with a shrink by `total*8 - size` bytes, which is only right when the field's offset and size are whole words.)
     */
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

    /**
     * Stores exactly the low `size` bytes (3, 5, 6 or 7) of `srcReg64` at `off(base)`, as a 4/2/1-byte sequence. A single 8-byte store here (what
     * `movSuffix`/`sizedReg` used to fall back to) writes 1 to 5 bytes too many: past the end of a packed construction image that is the
     * pointer sitting just above it (a `u16[3][2]` built from two row variables lost the low 2 bytes of its destination address), in a frame
     * it is the neighbouring variable. The source register is rotated between the pieces and rotated back, so it is unchanged afterwards.
     */
    void storeOddSize(String srcReg64, String baseReg64, long off, int size) {
        int[] widths = size == 3 ? new int[] {2, 1} : size == 5 ? new int[] {4, 1} : size == 6 ? new int[] {4, 2} : new int[] {4, 2, 1};
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
        raw("    mov" + movSuffix(width) + " %" + sizedReg(srcReg64, width) + ", " + (off == 0 ? "" : String.valueOf(off)) + "(%" + baseReg64 + ")");
        
    }

    void rotReg(String reg64, boolean right, int bits) {
        String op = right ? "ror" : "rol";
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

    /**
     * The construction-aware sibling of `pushBlockFromFrame`, used only
     * when this exact "PUSH size $offset" line is itself a direct member
     * of a construction run `precomputeConstructionOffsets` already
     * resolved (see that "PUSH" case's own call site for the real bug
     * this fixes). Unlike `pushBlockFromFrame` -- which reverses word
     * order, matching DEREF's own "highest-offset word on top" pushed-
     * value convention -- this reserves exactly this entry's own
     * declared width and copies its bytes straight across, front to
     * back, preserving the source's own real, natural, ascending-offset
     * order, landing them at the exact packed position
     * (`constructionOffset`, possibly negative -- see
     * `storeConstructionOrPlainPush`'s own doc comment for why that's
     * both expected and safe) the run's own layout already computed for
     * it. `%rcx` is saved/restored around the copy for the identical
     * reason `assignConstructionBlock` already does -- this can run as
     * one entry inside a still-in-progress call's own argument
     * marshalling, where `%rcx` may already be carrying a live,
     * not-yet-consumed incoming parameter.
     */
    void pushBlockFromFrameConstructionAware(long baseOffset, long size) {
        Long constructionOffset = core.pushStoreOffsetByLine.get(core.currentLineIndex);
        if (constructionOffset == null) {
            pushBlockFromFrame(baseOffset, size);
            return;
        }
        // Copy through rax in 8/4/2/1-byte pieces. This used to be `rep movsb` with rdi/rsi/rcx and a `push rcx` around it: the push writes 8 bytes
        // just below rsp, which is where the fields of this construction stored EARLIER already sit (each field is stored at its final place,
        // below the current rsp, before rsp reaches it), so an earlier narrow field (`tag: u8` before an array member) was overwritten; and rdi/rsi
        // are argument registers that an enclosing call may already have loaded.
        raw("    subq $" + size + ", %rsp");
        
        long pos = 0;
        while (pos < size) {
            long left = size - pos;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
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
                raw("    movq %rax, " + (destOff == 0 ? "" : String.valueOf(destOff)) + "(%r15)");
                
            } else if (X86Core.isOddSize((int) n)) {
                storeOddSize("rax", "r15", destOff, (int) n);
            } else {
                storePiece("rax", "r15", destOff, (int) n);
            }
        }
        // The value block is now fully popped; the address word pushed
        // ahead of it is still sitting on top -- drop it for real now
        // that it's been read.
        raw("    addq $8, %rsp");
    }

    /**
     * The construction-scoped counterpart of `assignBlock`, used only
     * when `constructionScopedAssign` confirms this exact "ASSIGN" line
     * is a plain, stack-resident struct/array/primitive literal's own
     * top-level store (see that field's own doc comment for the bug
     * this fixes). Stack, top to bottom: a real, byte-exact,
     * `size`-byte tightly-packed image of the value (every push in the
     * run reserved only its own declared width), then the single-word
     * destination address pushed ahead of it. Mirrors `case "NEW"`'s own
     * "genuinely trivial and completely struct-agnostic" copy exactly --
     * one straight `rep movsb` block copy, no field count, no offsets,
     * nothing type-specific -- except the destination is this already-
     * pushed address rather than a fresh `malloc` buffer, so there's no
     * allocation call here at all.
     */
    // 2026-09: fixed a real, silent register-clobber bug here -- see
    // this project's own CLAUDE.md, "assignConstructionBlock clobbered
    // %rcx" -- pushq/popq %rcx now bracket the rep movsb counter setup
    // so a still-unread incoming argument register (SysV ARG3/win64
    // ARG3, both %rcx) survives this method's own use of it.
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
