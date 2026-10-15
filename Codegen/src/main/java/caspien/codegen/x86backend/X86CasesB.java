package caspien.codegen.x86backend;

import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;

/** Mnemonic groups 4 and 5 of the stack-form instruction set (see X86Generator for how the parts fit together). */
final class X86CasesB {
    private final X86Backend hub;
    private final X86Core core;

    X86CasesB(X86Backend hub) {
        this.hub = hub;
        this.core = hub.core;
    }

    boolean emitCases4(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("DOT")) {
            // See README.md, "DOT and DOT_LHS".
            int size = (int) Long.parseLong(line.get(1).text);
            if (pendingBlockSize <= 0) {
                boolean fieldIsSmallArrayV = line.size() > 2 && line.get(2).text.equals("ra");
                hub.emit.popReg("rbx"); // field offset in bytes
                hub.emit.popReg("rax"); // the whole value
                hub.emit.raw("    pushq %rcx");   // %rcx is the shift count and may hold an already-popped call argument
                hub.emit.raw("    movq %rbx, %rcx");
                hub.emit.raw("    shlq $3, %rcx");
                hub.emit.raw("    shrq %cl, %rax");
                hub.emit.raw("    popq %rcx");
                if (size < 8) {
                    if (X86Core.isOddSize(size)) {
                        int sh = 64 - size * 8;
                        hub.emit.raw("    shlq $" + sh + ", %rax");
                        hub.emit.raw("    shrq $" + sh + ", %rax");
                    } else {
                        String zext;
                        if (size == 4) {
                            zext = "    movl %eax, %eax";
                        } else if (size == 2) {
                            zext = "    movzwl %ax, %eax";
                        } else {
                            zext = "    movzbl %al, %eax";
                        }
                        hub.emit.raw(zext);
                    }
                }
                hub.emit.pushReg("rax");
                if (fieldIsSmallArrayV) {
                    core.lastValueBlockSize = -size;
                }
                return true;
            }
            long totalWords = (pendingBlockSize + 7) / 8;
            hub.emit.popReg("rbx"); // field offset (in bytes, from the value's own front)
            if (size <= 8) {
                hub.emit.raw("    movq %rbx, %rax");
                hub.emit.raw("    andq $-8, %rax");
                hub.emit.raw("    negq %rax");
                hub.emit.raw("    addq $" + ((totalWords - 1) * 8) + ", %rax");
                hub.emit.raw("    andq $7, %rbx");
                hub.emit.raw("    addq %rbx, %rax");
                hub.emit.raw("    addq %rsp, %rax");

            } else {
                hub.emit.raw("    movq %rbx, %r14");
                hub.emit.raw("    andq $7, %r14");
                hub.emit.raw("    shrq $3, %rbx");
                hub.emit.raw("    imulq $8, %rbx, %rbx");
                hub.emit.raw("    movq $" + (totalWords * 8) + ", %rax");
                hub.emit.raw("    subq $8, %rax");
                hub.emit.raw("    subq %rbx, %rax");
                hub.emit.raw("    addq %r14, %rax");
                hub.emit.raw("    addq %rsp, %rax");
            }
            boolean fieldIsSmallArray = line.size() > 2 && line.get(2).text.equals("ra");
            if (size <= 8 && fieldIsSmallArray) {
                hub.emit.raw("    movq %rcx, %r14");
                hub.emit.raw("    movq %rbx, %rcx");
                hub.emit.raw("    shlq $3, %rcx");
                hub.emit.raw("    subq %rbx, %rax");
                hub.emit.raw("    leaq -8(%rax), %r11");
                hub.emit.raw("    cmpq $" + (8 - size) + ", %rbx");
                hub.emit.raw("    cmovbeq %rax, %r11");   // no straddle: read the same word again, never the word below the block
                hub.emit.raw("    movq (%r11), %r11");
                hub.emit.raw("    movq (%rax), %rax");
                hub.emit.raw("    shrdq %cl, %r11, %rax");
                hub.emit.raw("    movq %r14, %rcx");
                hub.emit.raw("    addq $" + (totalWords * 8) + ", %rsp");

                hub.emit.pushReg("rax");
                core.lastValueBlockSize = -size;
            } else if (size <= 8) {
                hub.emit.loadSizedFromAddr("rax", "rax", size);
                hub.emit.raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                hub.emit.pushReg("rax");
            } else {
                // See README.md, "DOT and DOT_LHS" (multi-word nested field).
                hub.emit.extractWordsFromReversedBlock(totalWords, size);
                core.lastValueBlockSize = size; // for a chained DOT right after this
            }
            return true;
        } else if (mnemonic.equals("LOOKUP_ARRAY_LHS")) {
            int elemSize = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx"); // index
            hub.emit.popReg("rax"); // base address
            hub.emit.raw("    imulq $" + elemSize + ", %rbx, %rbx");
            hub.emit.raw("    addq %rbx, %rax");

            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("LOOKUP_ARRAY")) {
            int elemSize = (int) Long.parseLong(line.get(1).text);
            // "ra": the element read is itself a fixed array narrower than 8 bytes, held by value -- tag it for the next LOOKUP_ARRAY (as PUSH does)
            boolean resultIsSmallArray = false;
            for (int q = 2; q < line.size(); q++) {
                resultIsSmallArray |= line.get(q).text.equals("ra");
            }
            if (pendingBlockSize == 0 && line.size() > 2 && line.get(2).text.equals("t8")) {
                // The lowering says the value is a whole 8-byte fixed array (not a pointer): its bytes are one natural-order word, exactly the shape
                // of a smaller array pushed by value.
                pendingBlockSize = -8;
            }
            if (pendingBlockSize < 0) {
                hub.emit.popReg("rbx"); // index
                hub.emit.popReg("rax"); // the whole small array's own packed value
                hub.emit.raw("    imulq $" + (elemSize * 8) + ", %rbx, %rbx"); // index*elemSize, in bits
                hub.emit.raw("    pushq %rcx");
                hub.emit.raw("    movq %rbx, %rcx");
                hub.emit.raw("    shrq %cl, %rax");
                hub.emit.raw("    popq %rcx");

                if (X86Core.isOddSize(elemSize)) {
                    // 3, 5, 6, 7 bytes (a row of a `u8[3][2]`): keep exactly those bytes. (The 1-byte `movzbl` used to be applied here too,
                    // cutting a row down to its first element.)
                    int sh = 64 - elemSize * 8;
                    hub.emit.raw(("    shlq $" + sh + ", %rax"));
                    hub.emit.raw(("    shrq $" + sh + ", %rax"));
                } else if (elemSize < 8) {
                    String zext;
                    if (elemSize == 4) {
                        zext = "    movl %eax, %eax";
                    } else if (elemSize == 2) {
                        zext = "    movzwl %ax, %eax";
                    } else {
                        zext = "    movzbl %al, %eax";
                    }
                    hub.emit.raw(zext);

                }
                hub.emit.pushReg("rax");
                if (resultIsSmallArray) {
                    core.lastValueBlockSize = -elemSize;
                }
                return true;
            }
            if (pendingBlockSize == 0) {
                hub.emit.popReg("rbx"); // index
                hub.emit.popReg("rax"); // base address (a plain pointer value)
                hub.emit.raw("    imulq $" + elemSize + ", %rbx, %rbx");
                hub.emit.raw("    addq %rbx, %rax");

                hub.calls.pushSizedOrBlockFromAddr("rax", elemSize);
                if (resultIsSmallArray) {
                    core.lastValueBlockSize = -elemSize;
                } else if (elemSize > 8) {
                    core.lastValueBlockSize = elemSize; // a struct element read through a pointer base: the DOT that follows needs the block size
                }
                return true;
            }
            long totalWords = (pendingBlockSize + 7) / 8;
            hub.emit.popReg("rbx"); // index
            hub.emit.raw("    imulq $" + elemSize + ", %rbx, %rbx");

            // See README.md, "LOOKUP_ARRAY and LOOKUP_ARRAY_LHS" (reversed-word block).
            hub.emit.raw("    movq %rbx, %r14");
            hub.emit.raw("    andq $7, %r14");
            hub.emit.raw("    shrq $3, %rbx");
            hub.emit.raw("    imulq $8, %rbx, %rbx");
            hub.emit.raw("    movq $" + (totalWords * 8) + ", %rax");
            hub.emit.raw("    subq $8, %rax");
            hub.emit.raw("    subq %rbx, %rax");
            hub.emit.raw("    addq %r14, %rax");
            hub.emit.raw("    addq %rsp, %rax");

            if (X86Core.isOddSize(elemSize)) {
                hub.emit.raw("    movq %rax, %r11");
                hub.emit.raw("    subq %r14, %r11");
                hub.emit.raw("    shlq $3, %r14");
                hub.emit.raw("    movq %rcx, %r13");
                hub.emit.raw("    movq %r14, %rcx");
                hub.emit.raw("    movq (%r11), %rax");
                hub.emit.raw("    movq -8(%r11), %r14");
                hub.emit.raw("    shrdq %cl, %r14, %rax");
                hub.emit.raw("    movq %r13, %rcx");
                hub.emit.raw("    addq $" + (totalWords * 8) + ", %rsp");

                hub.emit.pushReg("rax");
                if (resultIsSmallArray) {
                    core.lastValueBlockSize = -elemSize;
                }
            } else if (elemSize <= 8) {
                hub.emit.loadSizedFromAddr("rax", "rax", elemSize);
                // discard the whole original block now that the one
                // element we need is safely copied into rax.
                hub.emit.raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                hub.emit.pushReg("rax");
                if (resultIsSmallArray) {
                    core.lastValueBlockSize = -elemSize;
                }
            } else {
                hub.emit.extractWordsFromReversedBlock(totalWords, elemSize);
                core.lastValueBlockSize = elemSize; // for a chained DOT right after this
            }
            return true;
        } else if (mnemonic.equals("LOOKUP_DYN") || mnemonic.equals("LOOKUP_DYN_LHS")) {
            int elemSize = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx"); // index
            hub.emit.popReg("rax"); // dynarray pointer
            hub.emit.raw("    imulq $" + elemSize + ", %rbx, %rbx");
            hub.emit.raw("    addq %rbx, %rax");
            hub.emit.raw("    addq $16, %rax");

            if (mnemonic.equals("LOOKUP_DYN_LHS")) {
                hub.emit.pushReg("rax");
            } else {
                hub.calls.pushSizedOrBlockFromAddr("rax", elemSize);
                if (elemSize > 8) {
                    core.lastValueBlockSize = elemSize;
                }
            }
            return true;
        } else if (mnemonic.equals("LEN")) {
            hub.emit.popReg("rax"); // dynarray pointer
            hub.emit.loadSizedFromAddr("rax", "rax", 8); // the header's own len field, offset 0
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("STACK_LOCK") || mnemonic.equals("STRUCT_PADDING")) {
            if (mnemonic.equals("STACK_LOCK")) {
                long n = Long.parseLong(line.get(1).text);
                hub.emit.raw(("    subq $" + n + ", %rsp"));
            }
            return true;
        } else if (mnemonic.equals("NEW")) {
            // See README.md, "NEW".
            long size = Long.parseLong(line.get(1).text);
            if (!core.packedNewLines.contains(core.currentLineIndex) && line.size() > 2) {
                hub.emit.emitNewRepack(size, line);
                return true;
            }
            hub.emit.raw("    movq %rsp, %r12"); // r12: source address -- the construction's own tightly-packed image already sitting on the stack, callee-saved, survives the malloc call below
            hub.regForm.emitMallocCall(size);
            hub.emit.raw("    movq %rax, %r14"); // r14: the fresh buffer, also callee-saved
            // A failed malloc leaves r14 null: skip the copy (it would fault on address 0); the bytecode's own null check throws.
            String newDone = hub.regForm.newInternalLabel("new_done");
            hub.emit.raw("    testq %r14, %r14");
            hub.emit.raw("    jz " + newDone);
            hub.emit.raw("    movq %r14, %rdi");
            hub.emit.raw("    movq %r12, %rsi");
            hub.emit.raw("    movq $" + size + ", %rcx");
            hub.emit.raw("    rep movsb");

            hub.emit.raw(newDone + ":");
            hub.emit.raw(("    addq $" + size + ", %rsp"));
            hub.emit.pushReg("r14");
            return true;
        } else if (mnemonic.equals("CLONE_DYN")) {
            // A safe dynarray pointee: the block is a 16-byte header (length, capacity) plus length*elemSize bytes of
            // elements, so the size is only known at run time. Same null handling as CLONE.
            long elemSize = Long.parseLong(line.get(1).text);
            hub.emit.popReg("r12"); // source block start
            hub.emit.raw("    movq (%r12), %r15");
            hub.emit.raw("    imulq $" + elemSize + ", %r15, %r15");
            hub.emit.raw("    addq $16, %r15");
            hub.emit.raw("    movq %r15, %" + hub.calls.argReg(0));

            hub.calls.emitAlignedCallByName("malloc");
            hub.emit.raw("    movq %rax, %r14");
            String cloneDynDone = hub.regForm.newInternalLabel("clone_dyn_done");
            hub.emit.raw("    testq %r14, %r14");
            hub.emit.raw("    jz " + cloneDynDone);
            hub.emit.raw("    movq %r14, %rdi");
            hub.emit.raw("    movq %r12, %rsi");
            hub.emit.raw("    movq %r15, %rcx");
            hub.emit.raw("    rep movsb");
            hub.emit.raw("    movq (%r14), %rax"); // the copy holds exactly length elements: capacity = length
            hub.emit.raw("    movq %rax, 8(%r14)");

            hub.emit.raw(cloneDynDone + ":");
            hub.emit.pushReg("r14");
            return true;
        } else {
            return false;
        }
    }

    boolean emitCases5(String mnemonic, BytecodeStatement line, String first, boolean wasCmp, long pendingBlockSize) {
        if (mnemonic.equals("CLONE")) {
            long size = Long.parseLong(line.get(1).text);
            hub.emit.popReg("r12"); // source address (r12: callee-saved, survives the call below)
            hub.regForm.emitMallocCall(size);
            hub.emit.raw("    movq %rax, %r14"); // r14: new pointer, also callee-saved
            // A failed malloc leaves r14 null: skip the copy (it would fault on address 0) and push the null,
            // which the bytecode's own null check right after CLONE turns into a throw.
            String cloneDone = hub.regForm.newInternalLabel("clone_done");
            hub.emit.raw("    testq %r14, %r14");
            hub.emit.raw("    jz " + cloneDone);
            hub.emit.raw("    movq %r14, %rdi");
            hub.emit.raw("    movq %r12, %rsi");
            hub.emit.raw("    movq $" + size + ", %rcx");
            hub.emit.raw("    rep movsb");

            hub.emit.raw(cloneDone + ":");
            hub.emit.pushReg("r14");
            return true;
        } else if (mnemonic.equals("MEMCOPY")) {
            long constSize = hub.calls.memcopyConstantSize();
            hub.emit.popReg("rsi"); // src
            hub.emit.popReg("rcx"); // size
            hub.emit.popReg("rdi"); // dest
            if (constSize >= 0) {
                // A small compile-time size (a char, an integer, a key being hashed) is copied with plain moves: `rep movsb` costs a
                // few dozen cycles of start-up however short the copy is. rax is free here (every sequence of this backend uses it as scratch).
                long pos = 0;
                while (pos < constSize) {
                    long left = constSize - pos;
                    int w;
                    if (left >= 8) {
                        w = 8;
                    } else if (left >= 4) {
                        w = 4;
                    } else if (left >= 2) {
                        w = 2;
                    } else {
                        w = 1;
                    }
                    hub.emit.raw("    mov" + hub.emit.movSuffix(w) + " " + pos + "(%rsi), %" + hub.emit.sizedReg("rax", w));
                    hub.emit.raw("    mov" + hub.emit.movSuffix(w) + " %" + hub.emit.sizedReg("rax", w) + ", " + pos + "(%rdi)");
                    pos += w;
                }
                return true;
            }
            hub.emit.raw("    rep movsb");
            return true;
        } else if (mnemonic.equals("DEREF")) {
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rbx"); // pointer
            if (size > 8) {
                core.lastValueBlockSize = size;
            } else if (size < 8) {
                core.lastValueBlockSize = -size;
            } else {
                core.lastValueBlockSize = 0;
            }
            if (size <= 8) {
                hub.emit.loadSizedFromAddr("rax", "rbx", size);
                hub.emit.pushReg("rax");
            } else {
                long words = (size + 7) / 8;
                for (long i = 0; i < words; i++) {
                    hub.emit.loadSizedFromAddr("rax", "rbx", 8);
                    if (i < words - 1) {
                        hub.emit.raw("    addq $8, %rbx");
                    }
                    hub.emit.pushReg("rax");
                }
            }
            return true;
        } else if (mnemonic.equals("ADDR_OF")) {
            // See README.md, "ADDR_OF".
            String kind = line.get(1).text;
            String operand = line.get(2).text;
            long discardBytes;
            if (pendingBlockSize > 8) {
                discardBytes = ((pendingBlockSize + 7) / 8) * 8;
            } else {
                discardBytes = 8;
            }
            hub.emit.raw(("    addq $" + discardBytes + ", %rsp")); // discard the dead pushed value
            if ((kind.equals("RAW") || kind.equals("AUTO")) && operand.startsWith("$")) {
                hub.emit.leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                hub.emit.pushReg("rax");
            } else {
                hub.emit.comment("TODO(codegen): '" + String.join(" ", X86Core.textsOf(line)) + "' not yet implemented -- pushing 0 as a placeholder");
                hub.emit.movImmToReg("rax", 0);
                hub.emit.pushReg("rax");
            }
            return true;
        } else if (mnemonic.equals("NEW_DYN")) {
            long count = Long.parseLong(line.get(2).text);
            // An element wider than one word (an inline struct) is pushed as es/8 words, lowest offset first, so the
            // element's highest word is on top; it is stored back at its natural offsets with the stride es.
            long totalPushed = Long.parseLong(line.get(1).text);
            long elemBytes;
            if (count > 0 && totalPushed / count > 8) {
                elemBytes = totalPushed / count;
            } else {
                elemBytes = 8;
            }
            if (elemBytes % 8 != 0) {
                throw new IllegalStateException("NEW_DYN: element size " + elemBytes + " is not a whole number of words");
            }
            long elemWords = elemBytes / 8;
            long dataBytes = count * elemBytes;
            hub.regForm.emitMallocCall(16 + dataBytes);
            hub.emit.raw("    movq %rax, %r12");
            // A failed malloc leaves r12 null: write nothing into it, drop the element words and push the null so the
            // bytecode's own null check can throw.
            String newDynNull = hub.regForm.newInternalLabel("newdyn_null");
            String newDynDone = hub.regForm.newInternalLabel("newdyn_done");
            hub.emit.raw("    testq %r12, %r12");
            hub.emit.raw("    jz " + newDynNull);
            hub.regForm.storeSizedToAddr_withOffsetImm("r12", 0, count, 8);
            hub.regForm.storeSizedToAddr_withOffsetImm("r12", 8, count, 8);
            for (long i = count - 1; i >= 0; i--) {
                for (long w = elemWords - 1; w >= 0; w--) {
                    hub.emit.popReg("rax");
                    long off = 16 + i * elemBytes + w * 8;
                    hub.emit.raw("    movq %rax, " + off + "(%r12)");

                }
            }
            hub.emit.raw("    jmp " + newDynDone);
            hub.emit.raw(newDynNull + ":");
            if (count > 0) {
                hub.emit.raw(("    addq $" + count * elemWords * 8 + ", %rsp"));
            }
            hub.emit.raw(newDynDone + ":");
            hub.emit.pushReg("r12");
            return true;
        } else if (mnemonic.equals("NEW_UDYN")) {
            long totalBytes = Long.parseLong(line.get(1).text);
            long count = totalBytes / 8;
            hub.regForm.emitMallocCall(Math.max(totalBytes, 1));
            hub.emit.raw("    movq %rax, %r12");
            // Failed malloc: r12 stays null (see NEW_DYN); nothing is written.
            String newUdynNull = hub.regForm.newInternalLabel("newudyn_null");
            String newUdynDone = hub.regForm.newInternalLabel("newudyn_done");
            hub.emit.raw("    testq %r12, %r12");
            hub.emit.raw("    jz " + newUdynNull);
            for (long i = count - 1; i >= 0; i--) {
                hub.emit.popReg("rax");
                long off = i * 8;
                hub.emit.raw("    movq %rax, " + off + "(%r12)");

            }
            hub.regForm.emitGtCall("gt_register", "r12");
            hub.emit.raw("    jmp " + newUdynDone);
            hub.emit.raw(newUdynNull + ":");
            if (count > 0) {
                hub.emit.raw(("    addq $" + count * 8 + ", %rsp"));
            }
            hub.emit.raw(newUdynDone + ":");
            hub.emit.pushReg("r12");
            return true;
        } else if (mnemonic.equals("NEW_FROM_USTRING") || mnemonic.equals("NEW_FROM_STRING")) {
            final boolean ustr = mnemonic.equals("NEW_FROM_USTRING");
            hub.emit.popReg("r12"); // address of the string literal
            hub.emit.raw("    movq %r12, %" + hub.calls.argReg(0));

            hub.calls.emitAlignedCallByName("strlen");
            // rax now holds the string length.
            hub.emit.raw("    movq %rax, %r13"); // r13: length, callee-saved
            int hdrBytes;
            if (ustr) {
                hdrBytes = 1;
            } else {
                hdrBytes = 16;
            }
            hub.emit.raw("    addq $" + hdrBytes + ", %rax");

            hub.emit.raw("    movq %rax, %" + hub.calls.argReg(0));

            hub.calls.emitAlignedCallByName("malloc");
            hub.emit.raw("    movq %rax, %r14"); // r14: new block
            if (ustr) {
                String ustrDone = hub.regForm.newInternalLabel("newustr_done");
                hub.emit.raw("    testq %r14, %r14");
                hub.emit.raw("    jz " + ustrDone);
                hub.emit.raw("    movq %r14, %rdi");
                hub.emit.raw("    movq %r12, %rsi");
                hub.emit.raw("    leaq 1(%r13), %rcx");
                hub.emit.raw("    rep movsb");

                hub.regForm.emitGtCall("gt_register", "r14");
                hub.emit.raw(ustrDone + ":");
                hub.emit.pushReg("r14");
                return true;
            }
            hub.emit.raw("    movq %r13, (%r14)");
            hub.emit.raw("    movq %r13, 8(%r14)");
            hub.emit.raw("    leaq 16(%r14), %rdi");
            hub.emit.raw("    movq %r12, %rsi");
            hub.emit.raw("    movq %r13, %rcx");
            hub.emit.raw("    rep movsb");

            hub.emit.pushReg("r14");
            return true;
        } else if (mnemonic.equals("RESIZE") || mnemonic.equals("URESIZE")) {
            // See README.md, "RESIZE and URESIZE".
            int elemSize = (int) Long.parseLong(line.get(1).text);
            boolean hasFill = mnemonic.equals("RESIZE");
            final int fillWords = (elemSize + 7) / 8;
            final boolean wideFill = hasFill && elemSize > 8;
            if (wideFill) {
                hub.emit.raw("    movq " + (fillWords * 8) + "(%rsp), %r12");
                hub.emit.raw("    movq " + (fillWords * 8 + 8) + "(%rsp), %rax");

            } else {
                if (hasFill) {
                    hub.emit.popReg("r14"); // fill value
                }
                hub.emit.popReg("r12"); // newCount
                hub.emit.popReg("rax"); // old pointer (scratch; moved into the call's arg reg below)
            }
            hub.emit.pushReg("rax");
            core.resizeExtraStack = 8;
            if (hasFill) {
                hub.emit.loadSizedFromAddr("rbx", "rax", 8); // rbx = oldLen
            }
            String resizeGrow = hub.regForm.newInternalLabel("resize_grow");
            String resizeHdr = hub.regForm.newInternalLabel("resize_hdr");
            String resizeShrink = hub.regForm.newInternalLabel("resize_shrink");
            String resizeCall = hub.regForm.newInternalLabel("resize_call");
            if (hasFill) {
                hub.emit.raw("    movq 8(%rax), %rcx"); // rcx = old capacity
                hub.emit.raw("    cmpq %rcx, %r12");
                hub.emit.raw("    ja " + resizeGrow);
                hub.emit.raw("    leaq 0(,%r12,4), %rdx");
                hub.emit.raw("    cmpq %rcx, %rdx");
                hub.emit.raw("    jb " + resizeShrink); // newCount < capacity / 4: give the memory back
                hub.emit.raw("    movq %rax, %r15"); // fits: same block, capacity unchanged (rcx)
                hub.emit.raw("    jmp " + resizeHdr);
                hub.emit.raw(resizeShrink + ":");
                hub.emit.raw("    movq %r12, %r15"); // shrink to exactly newCount
                hub.emit.raw("    jmp " + resizeCall);
                hub.emit.raw(resizeGrow + ":");
                hub.emit.raw("    leaq (%rcx,%rcx), %rdx");
                hub.emit.raw("    cmpq %rdx, %r12");
                hub.emit.raw("    cmovaq %r12, %rdx"); // rdx = max(newCount, 2 * capacity)
                hub.emit.raw("    movq %rdx, %r15"); // r15 = new capacity
                hub.emit.raw(resizeCall + ":");
            }
            hub.emit.raw("    movq %rax, %" + hub.calls.argReg(0)); // arg1 = old (block-start) pointer
            String fillReg;
            if (hasFill) {
                fillReg = "r15";
            } else {
                fillReg = "r12";
            }
            hub.emit.raw("    movq %" + fillReg + ", %" + hub.calls.argReg(1));
            hub.emit.raw("    imulq $" + elemSize + ", %" + hub.calls.argReg(1) + ", %" + hub.calls.argReg(1));
            if (hasFill) {
                hub.emit.raw("    addq $16, %" + hub.calls.argReg(1));
            } else {
                String nz = hub.regForm.newInternalLabel("uresize_nz");
                hub.emit.raw("    testq %" + hub.calls.argReg(1) + ", %" + hub.calls.argReg(1));
                hub.emit.raw("    jnz " + nz);
                hub.emit.raw("    movq $1, %" + hub.calls.argReg(1));
                hub.emit.raw(nz + ":");
            }

            hub.calls.emitAlignedCallByName("realloc");
            if (hasFill) {
                hub.emit.raw("    movq %r15, %rcx"); // rcx = the new capacity (saved in r15 across the call)
            }
            hub.emit.raw("    movq %rax, %r15"); // r15: new block-start pointer
            // A failed realloc leaves r15 null (the old block stays valid): write nothing, skip the fill and the
            // data-start offset, and push the null so the bytecode's own check can throw.
            String resizeEnd = hub.regForm.newInternalLabel("resize_end");
            hub.emit.raw("    testq %r15, %r15");
            hub.emit.raw("    jz " + resizeEnd);
            if (hasFill) {
                hub.emit.raw(resizeHdr + ":");
                hub.regForm.storeSizedToAddr_reg("r15", 0, "r12", 8); // length = newCount
                hub.regForm.storeSizedToAddr_reg("r15", 8, "rcx", 8); // capacity
            }
            if (!hasFill) {
                hub.emit.raw(resizeEnd + ":");
                hub.regForm.emitResizeGtUpdate();
            }
            if (hasFill) {
                // for (i = oldLen; i < newCount; i++) data[i] = fill;
                String loop = hub.regForm.newInternalLabel("resize_fill");
                String end = resizeEnd;
                if (!wideFill && (elemSize == 1 || elemSize == 2 || elemSize == 4 || elemSize == 8)) {
                    // A big fill of zero bytes (any zero element, or any 1-byte element) is one `rep stosb`; a small one, or any other
                    // value, runs the pointer loop below (rdi/rcx/rdx are free here: the realloc call above already clobbered them).
                    String generic = hub.regForm.newInternalLabel("resize_fill_loop");
                    hub.emit.raw("    cmpq %r12, %rbx");
                    hub.emit.raw("    jge " + end);
                    hub.emit.raw("    movq %r12, %rdx");
                    hub.emit.raw("    subq %rbx, %rdx");
                    if (elemSize > 1) {
                        hub.emit.raw("    imulq $" + elemSize + ", %rdx, %rdx");
                    }
                    boolean useRep = !hub.emit.isWinAbi(); // rdi is callee-saved on win64: only the pointer loop there
                    if (useRep) {
                    hub.emit.raw("    cmpq $64, %rdx");
                    hub.emit.raw("    jb " + generic);
                    if (elemSize > 1) {
                        hub.emit.raw("    test" + hub.emit.movSuffix(elemSize) + " %" + hub.emit.sizedReg("r14", elemSize) + ", %" + hub.emit.sizedReg("r14", elemSize));
                        hub.emit.raw("    jnz " + generic);
                        hub.emit.raw("    xorl %eax, %eax");
                    } else {
                        hub.emit.raw("    movq %r14, %rax");
                    }
                    hub.emit.raw("    movq %rbx, %rdi");
                    if (elemSize > 1) {
                        hub.emit.raw("    imulq $" + elemSize + ", %rdi, %rdi");
                    }
                    hub.emit.raw("    leaq 16(%r15,%rdi), %rdi");
                    hub.emit.raw("    movq %rdx, %rcx");
                    hub.emit.raw("    rep stosb");
                    hub.emit.raw("    movq %r12, %rbx");
                    hub.emit.raw("    jmp " + end);
                    }
                    hub.emit.raw(generic + ":");
                    hub.emit.raw("    movq %rbx, %rax");
                    if (elemSize > 1) {
                        hub.emit.raw("    imulq $" + elemSize + ", %rax, %rax");
                    }
                    hub.emit.raw("    leaq 16(%r15,%rax), %rax");
                    hub.emit.raw(loop + ":");
                    hub.emit.storeSizedToAddr("r14", "rax", elemSize);
                    hub.emit.raw("    addq $" + elemSize + ", %rax");
                    hub.emit.raw("    incq %rbx");
                    hub.emit.raw("    cmpq %r12, %rbx");
                    hub.emit.raw("    jl " + loop);
                    hub.emit.raw(end + ":");
                } else {
                hub.emit.raw(loop + ":");
                hub.emit.raw("    cmpq %r12, %rbx");
                hub.emit.raw("    jge " + end);
                hub.emit.raw("    movq %rbx, %rax");
                hub.emit.raw("    imulq $" + elemSize + ", %rax, %rax");
                hub.emit.raw("    leaq 16(%r15,%rax), %rax");
                if (wideFill) {
                    hub.regForm.emitResizeWideFillCopy(elemSize, fillWords);
                } else {
                    hub.emit.storeSizedToAddr("r14", "rax", elemSize);
                }
                hub.emit.raw("    incq %rbx");

                hub.emit.raw(("    jmp " + loop));
                hub.emit.raw(end + ":");
                }
                hub.regForm.emitResizeGtUpdate();
                if (wideFill) {
                    long drop = fillWords * 8L + 16;
                    hub.emit.raw(("    addq $" + drop + ", %rsp"));
                }
            }
            hub.emit.pushReg("r15");
            return true;
        } else if (mnemonic.equals("ATOMIC_PUSH")) {
            int size = (int) Long.parseLong(line.get(1).text);
            String name = line.get(2).text;
            hub.emit.leaGlobalToReg("rbx", name);
            hub.emit.loadSizedFromAddr("rax", "rbx", size);
            hub.emit.pushReg("rax");
            return true;
        } else if (mnemonic.equals("ATOMIC_SWAP")) {
            int size = (int) Long.parseLong(line.get(1).text);
            hub.emit.popReg("rax"); // new value
            hub.emit.popReg("rbx"); // address
            hub.emit.raw("    xchg" + hub.emit.movSuffix(size) + " %" + hub.emit.sizedReg("rax", size) + ", (%rbx)");

            hub.emit.pushReg("rax");
            return true;
        } else {
            return false;
        }
    }
}
