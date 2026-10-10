package caspien.codegen.x86backend;

import caspien.codegen.BytecodeToken;
import caspien.codegen.CodegenConfig;
import caspien.codegen.CodegenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mnemonic groups 4 and 5 of the stack-form instruction set (see X86Backend for how the parts fit together). */
abstract class X86CasesB extends X86CasesA {
    X86CasesB(CodegenConfig.Target target, boolean bmi2) {
        super(target, bmi2);
    }

    // methods defined in later parts of the chain
    abstract void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size);
    abstract void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size);
    abstract void emitGtCall(String fn, String reg);
    abstract void emitResizeGtUpdate();
    abstract void emitResizeWideFillCopy(int elemSize, int words);

    boolean emitCases4(String mnemonic, List<BytecodeToken> line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "DOT": {
                // Like LOOKUP_ARRAY vs LOOKUP_ARRAY_LHS above: DOT_LHS's
                // base is a plain address, but a *read* DOT's base is
                // the whole containing value pushed by value (confirmed
                // directly by the same evidence: a bare struct's own
                // "PUSH structSize $offset" before PUSH_FIELDNAME+DOT --
                // e.g. reading a field off an array-of-structs element
                // LOOKUP_ARRAY (non-LHS) just extracted, "PUSH 48 $-200 /
                // PUSH idx / LOOKUP_ARRAY 24 / PUSH_FIELDNAME off size /
                // DOT size"). `pendingBlockSize` is that containing
                // value's own total byte size.
                int size = (int) Long.parseLong(line.get(1).text);
                if (pendingBlockSize <= 0) {
                    // The containing value is a struct of at most 8 bytes held BY VALUE in one natural-order word (tagged negative by PUSH / DEREF,
                    // or untagged: an element read through a pointer base is just loaded): the field is a shift and a mask, no address. (This used to fall into the placeholder below, which
                    // silently read 0: `d[i].a0` on an unsafe dynarray of a 3-byte struct with the register-form passes off.)
                    boolean fieldIsSmallArrayV = line.size() > 2 && line.get(2).text.equals("ra");
                    popReg("rbx"); // field offset in bytes
                    popReg("rax"); // the whole value
                    raw("    pushq %rcx");   // %rcx is the shift count and may hold an already-popped call argument
                    raw("    movq %rbx, %rcx");
                    raw("    shlq $3, %rcx");
                    raw("    shrq %cl, %rax");
                    raw("    popq %rcx");
                    if (size < 8) {
                        if (isOddSize(size)) {
                            int sh = 64 - size * 8;
                            raw("    shlq $" + sh + ", %rax");
                            raw("    shrq $" + sh + ", %rax");
                        } else {
                            raw(size == 4 ? "    movl %eax, %eax" : size == 2 ? "    movzwl %ax, %eax" : "    movzbl %al, %eax");
                        }
                    }
                    pushReg("rax");
                    if (fieldIsSmallArrayV) {
                        lastValueBlockSize = -size;
                    }
                    return true;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // field offset (in bytes, from the value's own front)
                if (size <= 8) {
                    // A field inside one word. The block's words are reversed (the front word is the deepest), so the byte at struct offset X lives at
                    // rsp + (totalWords-1-X/8)*8 + X%8. (The formula below is right only for a whole aligned word or a wider field: for a narrower field it
                    // pointed into the wrong half of a word, e.g. `arr[i].a` for a u32 at offset 8 read the byte at offset 12.)
                    raw("    movq %rbx, %rax");
                    raw("    andq $-8, %rax");
                    raw("    negq %rax");
                    raw("    addq $" + ((totalWords - 1) * 8) + ", %rax");
                    raw("    andq $7, %rbx");
                    raw("    addq %rbx, %rax");
                    raw("    addq %rsp, %rax");
                    
                } else {
                    raw("    movq %rbx, %r14");
                    raw("    andq $7, %r14");
                    raw("    shrq $3, %rbx");
                    raw("    imulq $8, %rbx, %rbx");
                    raw("    movq $" + (totalWords * 8) + ", %rax");
                    raw("    subq $8, %rax");
                    raw("    subq %rbx, %rax");
                    raw("    addq %r14, %rax");
                    raw("    addq %rsp, %rax");
                }
                boolean fieldIsSmallArray = line.size() > 2 && line.get(2).text.equals("ra");
                if (size <= 8 && fieldIsSmallArray) {
                    // A fixed array of at most 8 bytes: its bytes may straddle two of the block's (reversed) words, so they are not contiguous in
                    // memory. rax = address of the field's first byte, rbx = its offset within its word. Read the word it starts in and the next
                    // struct word (the next-lower address) -- only when the field really straddles, otherwise the same word again, so nothing below the pushed block is read -- and shift the pair together (shrd); bytes past the field are junk, as for any small array.
                    raw("    movq %rcx, %r14");
                    raw("    movq %rbx, %rcx");
                    raw("    shlq $3, %rcx");
                    raw("    subq %rbx, %rax");
                    raw("    leaq -8(%rax), %r11");
                    raw("    cmpq $" + (8 - size) + ", %rbx");
                    raw("    cmovbeq %rax, %r11");   // no straddle: read the same word again, never the word below the block
                    raw("    movq (%r11), %r11");
                    raw("    movq (%rax), %rax");
                    raw("    shrdq %cl, %r11, %rax");
                    raw("    movq %r14, %rcx");
                    raw("    addq $" + (totalWords * 8) + ", %rsp");
                    
                    pushReg("rax");
                    lastValueBlockSize = -size;
                } else if (size <= 8) {
                    loadSizedFromAddr("rax", "rax", size);
                    raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                } else {
                    // A multi-word nested field (a struct- or array-typed
                    // field read off a base that arrived as a whole pushed
                    // value, e.g. "arr[i].point" -- the array element
                    // itself came from LOOKUP_ARRAY as a still-pending
                    // multi-word block, and this DOT is now reading a
                    // >8-byte field out of it).
                    //
                    // `rax` (just computed above, shared with the size<=8
                    // branch) is *not* the field's front-most word the way
                    // LOOKUP_ARRAY's own analogous `elemSize > 8` fix's
                    // `rax` is (that formula subtracts a fixed one-word `8`;
                    // this one subtracts the field's own full `size`) --
                    // confirmed by working the address algebra through for
                    // a field at byte-offset `off` covering word indices
                    // `off/8 .. off/8+fieldWords-1` within the containing
                    // block: this formula's `rax` lands on word index
                    // `off/8 + fieldWords - 1`, i.e. the field's own
                    // *back*-most word (nearest rsp), the same structural
                    // role plain `%rsp` itself already plays for a whole
                    // pushed block (address_of_wordIndex(totalWords-1) ==
                    // rsp+0, exactly this formula's own shape with
                    // size==totalWords*8, off==0). Since `rax` is already
                    // a same-role "back-word anchor" for the field the way
                    // `rsp` is for the whole block, moving the field's own
                    // words into a fresh, `rsp`-anchored block needs no
                    // reversal at all -- a word at relative offset `k*8`
                    // from `rax` goes to relative offset `k*8` from the new
                    // (post-shrink) `rsp`, k ascending, preserving this
                    // backend's own "back-most word nearest rsp" convention
                    // automatically. (A first attempt at this copied
                    // LOOKUP_ARRAY's own reversed-copy shape verbatim,
                    // reasoning `rax` was a front-word anchor like that
                    // fix's own `rax` -- confirmed wrong via a real
                    // execution test, `arr[i].point` printing `x=8 y=0`
                    // instead of the real field values, before this
                    // same-relative-offset version was worked out and
                    // reverified.)
                    //
                    // rsp is shrunk to the field's own final size FIRST
                    // (mirroring the *reason*, not the shape, of
                    // LOOKUP_ARRAY's own fix: `rax`, captured into `r14`
                    // beforehand, is an absolute address unaffected by
                    // moving rsp afterward, so this ordering is always
                    // safe and guarantees the copy's own destination is
                    // the address range that will actually still be live).
                    extractWordsFromReversedBlock(totalWords, size);
                    lastValueBlockSize = size; // for a chained DOT right after this
                }
                return true;
            }
            
case "LOOKUP_ARRAY_LHS": {
                // The write side always gets its base via a plain
                // "ADDR" (a single address word), confirmed directly
                // against array_lookup_cg_test.caspien's own real
                // low-order output -- ordinary address arithmetic.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // base address
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                raw("    addq %rbx, %rax");
                
                pushReg("rax");
                return true;
            }
            
case "LOOKUP_ARRAY": {
                // The *read* side, by contrast, always gets its base via
                // a plain "PUSH <totalArraySize> $offset" -- the whole
                // array pushed by value (see PUSH's own note above) --
                // never an address, confirmed directly against the same
                // fixture: "PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8"
                // (a real bug this pass shipped with until caught by
                // actually running this exact test -- the old code
                // treated the popped array VALUE as if it were a
                // pointer, corrupting every later use of the real stack).
                // `pendingBlockSize` is the whole array's own total byte
                // count, tracked from that preceding PUSH; extracting
                // element `index` means locating word
                // index*elemSize/8 counting from the *front* of the
                // array (its lowest source offset, which -- per
                // pushBlockFromFrame's own convention -- ended up
                // *deepest* on the real stack, not on top).
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
                    // A small (<8-byte) fixed array pushed by value as a
                    // single, ordinary, natural-byte-order word -- see
                    // PUSH's own "size < 8" branch, which is the only
                    // place this negative encoding is ever produced. No
                    // memory access at all: the whole array's own bytes
                    // are already sitting directly in a register, so
                    // extracting element `index` is a plain shift-and-
                    // mask, never an address computation followed by a
                    // dereference (which is exactly what the fallback
                    // just below does, and exactly what corrupted this
                    // case before this branch existed -- a small array's
                    // own packed *value* is not a pointer).
                    popReg("rbx"); // index
                    popReg("rax"); // the whole small array's own packed value
                    // %rcx is both the shift-count register and an argument register (4th SysV, 1st win64): a finished earlier argument
                    // of the call being assembled may already sit in it (`printf(fmt, a, s[0], s[1], s[2])` popped s[0]/s[1] into their
                    // registers before this lookup runs), so save and restore it around the shift.
                    raw("    imulq $" + (elemSize * 8) + ", %rbx, %rbx"); // index*elemSize, in bits
                    raw("    pushq %rcx");
                    raw("    movq %rbx, %rcx");
                    raw("    shrq %cl, %rax");
                    raw("    popq %rcx");
                    
                    if (isOddSize(elemSize)) {
                        // 3, 5, 6, 7 bytes (a row of a `u8[3][2]`): keep exactly those bytes. (The 1-byte `movzbl` used to be applied here too,
                        // cutting a row down to its first element.)
                        int sh = 64 - elemSize * 8;
                        raw(("    shlq $" + sh + ", %rax"));
                        raw(("    shrq $" + sh + ", %rax"));
                    } else if (elemSize < 8) {
                        // `andq` takes only a sign-extended 32-bit immediate, so a 4-byte
                        // mask (0xFFFFFFFF) was rejected by the assembler; a zero-extending
                        // move does the same job for 1, 2 and 4 bytes.
                        raw(elemSize == 4 ? "    movl %eax, %eax" : elemSize == 2 ? "    movzwl %ax, %eax" : "    movzbl %al, %eax");
                        
                    }
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                    return true;
                }
                if (pendingBlockSize == 0) {
                    // The same LOOKUP_ARRAY(_LHS) family is also used for
                    // an unsafe-dynarray's own direct (unchecked) index,
                    // whose "array" is really just a pointer *value* --
                    // an ordinary single 8-byte PUSH, not a multi-word
                    // block -- confirmed directly (an unsafe-dynarray
                    // fixture pushes "PUSH 8 $ptr / PUSH 8 idx /
                    // LOOKUP_ARRAY size" for both its read *and* write
                    // sides, unlike a true fixed array's own read/write
                    // split above). There, the popped value already *is*
                    // the base address to index from, exactly like the
                    // pre-existing single-word logic this pass shipped
                    // with originally -- so that's the fallback here,
                    // not a guess.
                    popReg("rbx"); // index
                    popReg("rax"); // base address (a plain pointer value)
                    raw("    imulq $" + elemSize + ", %rbx, %rbx");
                    raw("    addq %rbx, %rax");
                    
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    } else if (elemSize > 8) {
                        lastValueBlockSize = elemSize; // a struct element read through a pointer base: the DOT that follows needs the block size
                    }
                    return true;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // index
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                
                // rbx now holds the target element's byte offset from the
                // array's own front (source-offset order). The pushed
                // block's own *words* are reversed (word i-from-front
                // sits at rsp + (totalWords-1-i)*8), but the *bytes
                // within* any one already-placed word are never
                // reordered by the push at all -- so a byte offset has to
                // be split into a word index (which gets the reversed
                // treatment) and a byte-within-word remainder (which
                // doesn't) before it can be turned into a real address:
                // rdx = rbx & 7 (byte-within-word), rbx = rbx >> 3
                // (word index, reusing rbx in place), then address =
                // rsp + (totalWords - 1 - wordIndex)*8 + byteWithinWord.
                //
                // 2026-09: two bugs fixed here together, both found via
                // real execution tests, neither previously caught because
                // every fixture ever checked against this branch before
                // now happened to have `elemSize` be an exact multiple of
                // 8 with `index*elemSize` therefore always landing on a
                // word boundary (byteWithinWord always 0) --
                //
                // 1. This used to subtract a bare `elemSize` here instead
                // of a fixed one-word `8` -- wrong for any element wider
                // than one word (a nested array, a struct: a 2D
                // `u64[3][3]`'s every row read back as row 0's own data,
                // regardless of the real index).
                //
                // 2. Even with that fixed, treating the *whole* byte
                // offset as if it were a whole-word offset is wrong the
                // moment more than one logical element shares a single
                // packed word -- this language's own tight sub-word
                // array/struct-literal construction packing means a
                // `char[10]` (say) really occupies just two real 8-byte
                // words, not ten (see `populateDirectMembers`'s own doc
                // comment) -- so a narrow (<8-byte) element's own real
                // address needs this word/byte split; without it, only
                // elements sharing word 0 (i.e. index 0 itself) ever
                // happened to read back correctly. See
                // caspien-codegen's own CLAUDE.md for the full writeup
                // of all three bugs this branch has now had fixed.
                //
                // The byte-within-word remainder needs a scratch
                // register -- `%rdx` was tried first and was wrong: this
                // whole LOOKUP_ARRAY sequence can sit in the middle of a
                // multi-argument call's own argument list (e.g. each of
                // `printf(fmt, arr[0], arr[1], arr[2])`'s three lookups
                // runs back to back, with each finished argument
                // immediately popped into its own real calling-convention
                // register -- `%rsi`, then `%rdx`, then `%rcx` for SysV --
                // before the *next* argument's own lookup even starts).
                // Using `%rdx` here clobbered exactly that: the second
                // argument's own already-computed, not-yet-called value
                // sitting in `%rdx` was silently overwritten by the
                // *third* argument's own lookup reusing `%rdx` as scratch,
                // before `printf` was ever called -- found via a real
                // execution test, a 2D array's middle column printing `0`
                // instead of its real value, and confirmed via gdb
                // (`%rdx` held the correct, second argument's value right
                // up until the third lookup's own `and $7, %rdx`
                // instruction). `%r14` is used instead -- already this
                // branch's own scratch register for the `elemSize > 8`
                // case just below, never a calling-convention argument
                // register in either ABI, and (unlike `%rdx`) never used
                // to carry a live, not-yet-consumed value across separate
                // LOOKUP_ARRAY invocations anywhere else in this file.
                raw("    movq %rbx, %r14");
                raw("    andq $7, %r14");
                raw("    shrq $3, %rbx");
                raw("    imulq $8, %rbx, %rbx");
                raw("    movq $" + (totalWords * 8) + ", %rax");
                raw("    subq $8, %rax");
                raw("    subq %rbx, %rax");
                raw("    addq %r14, %rax");
                raw("    addq %rsp, %rax");
                
                if (isOddSize(elemSize)) {
                    // A 3, 5, 6 or 7 byte element (a row of a `u16[3][2]`) can start in one source word and end in the next, and the words of
                    // the pushed block are in reverse order (word k+1 sits 8 bytes BELOW word k), so its bytes are not contiguous in memory and a
                    // plain load at `rax` reads the bytes above the block instead of the second word. Same shrd extraction as the multi-word case
                    // below, for one result word: `rax` = address of the element's first byte, `r14` = its offset inside the word (0..7).
                    raw("    movq %rax, %r11");
                    raw("    subq %r14, %r11");
                    raw("    shlq $3, %r14");
                    raw("    movq %rcx, %r13");
                    raw("    movq %r14, %rcx");
                    raw("    movq (%r11), %rax");
                    raw("    movq -8(%r11), %r14");
                    raw("    shrdq %cl, %r14, %rax");
                    raw("    movq %r13, %rcx");
                    raw("    addq $" + (totalWords * 8) + ", %rsp");
                    
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else if (elemSize <= 8) {
                    loadSizedFromAddr("rax", "rax", elemSize);
                    // discard the whole original block now that the one
                    // element we need is safely copied into rax.
                    raw(("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else {
                    // A multi-word element (an array of structs, or a nested array, say). `rax` is the address of the element's first BYTE
                    // inside the pushed block, `r14` = that byte's offset inside its word (0..7).
                    // Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word. So element bytes are NOT
                    // contiguous in memory unless the element starts on a word boundary (a 12-byte `u32[3]` row of a `u32[3][2]` starts at byte
                    // 12): result word j is the 64 bits starting at byte `within` of source word w+j, continued into word w+j+1 (which sits
                    // 8 bytes BELOW it) -- exactly `shrd`. A count of 0 (aligned element) leaves the word unchanged.
                    // Result word j goes to source word j's slot (rsp + (T-1-j)*8): that is where it must end up once rsp is raised by
                    // (T - E)*8 (result word j sits at rsp' + (E-1-j)*8), and writing in ascending j never overwrites a word still to be read
                    // (step j reads words w+j and w+j+1, both >= j+... greater than every slot already written).
                    // The word past the block end (read for the last, possibly partial, result word) lies just below rsp: valid stack, and
                    // only bytes beyond the element come from it.
                    // %rcx is the shift count register and may hold an already-loaded call argument: stash it in r13 (backend scratch).
                    extractWordsFromReversedBlock(totalWords, elemSize);
                    lastValueBlockSize = elemSize; // for a chained DOT right after this
                }
                return true;
            }
            
case "LOOKUP_DYN":
            case "LOOKUP_DYN_LHS": {
                // This backend's own dynarray layout: [len:8][cap:8]
                // [elements...] -- see class doc comment. The element
                // offset is 16 + index*elemSize.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // dynarray pointer
                raw("    imulq $" + elemSize + ", %rbx, %rbx");
                raw("    addq %rbx, %rax");
                raw("    addq $16, %rax");
                
                if (mnemonic.equals("LOOKUP_DYN_LHS")) {
                    pushReg("rax");
                } else {
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    // An element wider than a word (a struct) is now a pushed value block: tell a DOT / LOOKUP that follows (`es[i].to`) how big it
                    // is, exactly as LOOKUP_ARRAY does for an array element. Without this the DOT found no pending block ("DOT with no preceding
                    // pushed value") and pushed 0, so any read of a field of a dynarray-of-struct element was wrong or crashed.
                    if (elemSize > 8) {
                        lastValueBlockSize = elemSize;
                    }
                }
                return true;
            }
            
case "LEN": {
                popReg("rax"); // dynarray pointer
                loadSizedFromAddr("rax", "rax", 8); // the header's own len field, offset 0
                pushReg("rax");
                return true;
            }
            
case "STACK_LOCK":
            case "STRUCT_PADDING": {
                // STACK_LOCK: reserve N raw, valueless bytes of stack
                // space mid-struct-construction so later-pushed members
                // land at their real, padded offsets (see
                // caspien-compiler's own emitInstantiate, "STACK_LOCK
                // reused for padding at struct construction"). Plain
                // 'sub rsp,N' does exactly this. STRUCT_PADDING, by
                // contrast, was never actually observed inside any real
                // function body across every fixture this pass was
                // checked against -- only as inert, stray top-level
                // metadata between two FUNC_END/FUNC_START lines (dead
                // leftovers that should have been fully stripped a stage
                // earlier) -- so it's treated as a pure no-op rather
                // than reserving stack space that would silently
                // misalign this backend's own push/pop accounting if
                // that assumption turns out wrong somewhere this pass
                // wasn't checked.
                if (mnemonic.equals("STACK_LOCK")) {
                    long n = Long.parseLong(line.get(1).text);
                    raw(("    subq $" + n + ", %rsp"));
                }
                return true;
            }
            
case "NEW": {
                // Every field push between the true start of this
                // construction and this exact line is now
                // construction-scoped (see `precomputeConstructionOffsets`/
                // `populateDirectMembers`'s own doc comments): each one reserved
                // and wrote only its own declared width, not a uniform
                // 8-byte word, and each "STACK_LOCK" padding gap did its
                // own exact `subq`. So `%rsp`, right here, already
                // points at the start of a real, byte-exact,
                // tightly-packed image of the value being constructed
                // -- `size` bytes, laid out exactly the way
                // `computeStructLayout` says, with zero further
                // reconciliation needed. NEW's whole job is now
                // genuinely trivial and completely struct-agnostic --
                // no field count, no offsets, nothing type-specific:
                // malloc(size), copy those `size` bytes straight off
                // the stack into the new buffer, deallocate the stack
                // region they came from now that they're safely copied,
                // and push the new pointer.
                //
                // This used to pop one 8-byte hardware word per
                // (size+7)/8, silently under-counting the real number
                // of pushed words the moment any field was narrower
                // than 8 bytes (a `bool` next to a `u64`, say) --
                // confirmed directly against real bytecode/assembly for
                // exactly that shape. Fixed at the true root instead
                // (the pushes themselves, above, weren't byte-exact),
                // so this case no longer needs to know or care how many
                // fields there were: one straight block copy, the exact
                // same `rep movsb` convention `case "CLONE"` already
                // uses for its own whole-buffer copy, just from the
                // stack instead of from another heap pointer.
                long size = Long.parseLong(line.get(1).text);
                if (!packedNewLines.contains(currentLineIndex) && line.size() > 2) {
                    emitNewRepack(size, line);
                    return true;
                }
                raw("    movq %rsp, %r12"); // r12: source address -- the construction's own tightly-packed image already sitting on the stack, callee-saved, survives the malloc call below
                emitMallocCall(size);
                raw("    movq %rax, %r14"); // r14: the fresh buffer, also callee-saved
                // A failed malloc leaves r14 null: skip the copy (it would fault on address 0); the bytecode's own null check throws.
                String newDone = newInternalLabel("new_done");
                raw("    testq %r14, %r14");
                raw("    jz " + newDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq $" + size + ", %rcx");
                raw("    rep movsb");
                
                raw(newDone + ":");
                raw(("    addq $" + size + ", %rsp"));
                pushReg("r14");
                return true;
            }
            
case "CLONE_DYN": {
                // A safe dynarray pointee: the block is a 16-byte header (length, capacity) plus length*elemSize bytes of
                // elements, so the size is only known at run time. Same null handling as CLONE.
                long elemSize = Long.parseLong(line.get(1).text);
                popReg("r12"); // source block start
                raw("    movq (%r12), %r15");
                raw("    imulq $" + elemSize + ", %r15, %r15");
                raw("    addq $16, %r15");
                raw("    movq %r15, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("malloc"));
                raw("    movq %rax, %r14");
                String cloneDynDone = newInternalLabel("clone_dyn_done");
                raw("    testq %r14, %r14");
                raw("    jz " + cloneDynDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq %r15, %rcx");
                raw("    rep movsb");
                raw("    movq (%r14), %rax"); // the copy holds exactly length elements: capacity = length
                raw("    movq %rax, 8(%r14)");
                
                raw(cloneDynDone + ":");
                pushReg("r14");
                return true;
            }
            
            default:
                return false;
        }
    }

    boolean emitCases5(String mnemonic, List<BytecodeToken> line, String first, boolean wasCmp, long pendingBlockSize) {
        switch (mnemonic) {
case "CLONE": {
                long size = Long.parseLong(line.get(1).text);
                popReg("r12"); // source address (r12: callee-saved, survives the call below)
                emitMallocCall(size);
                raw("    movq %rax, %r14"); // r14: new pointer, also callee-saved
                // A failed malloc leaves r14 null: skip the copy (it would fault on address 0) and push the null,
                // which the bytecode's own null check right after CLONE turns into a throw.
                String cloneDone = newInternalLabel("clone_done");
                raw("    testq %r14, %r14");
                raw("    jz " + cloneDone);
                raw("    movq %r14, %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq $" + size + ", %rcx");
                raw("    rep movsb");
                
                raw(cloneDone + ":");
                pushReg("r14");
                return true;
            }
            
case "MEMCOPY": {
                // Pops (top to bottom) src, size, dest -- a plain byte
                // copy, no result pushed (the two "PUSH $addr" lines
                // immediately around every real MEMCOPY site this pass
                // was checked against re-read their own variables
                // separately afterward, confirmed directly).
                long constSize = memcopyConstantSize();
                popReg("rsi"); // src
                popReg("rcx"); // size
                popReg("rdi"); // dest
                if (constSize >= 0) {
                    // A small compile-time size (a char, an integer, a key being hashed) is copied with plain moves: `rep movsb` costs a
                    // few dozen cycles of start-up however short the copy is. rax is free here (every sequence of this backend uses it as scratch).
                    long pos = 0;
                    while (pos < constSize) {
                        long left = constSize - pos;
                        int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
                        raw("    mov" + movSuffix(w) + " " + pos + "(%rsi), %" + sizedReg("rax", w));
                        raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + pos + "(%rdi)");
                        pos += w;
                    }
                    return true;
                }
                raw("    rep movsb");
                return true;
            }
            
case "DEREF": {
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // pointer
                // The value this leaves is a whole aggregate by value when a LOOKUP_ARRAY/DOT follows (an array member read through an
                // owning pointer: "DOT_LHS / DEREF 24 / PUSH idx / LOOKUP_ARRAY 8"), so record its shape the way PUSH does for a value read
                // from the frame: > 8 bytes is a pushed word block, < 8 bytes a single natural-order word (negative tag). Without this
                // LOOKUP_ARRAY took its "bare pointer" fallback and dereferenced the array's own contents as an address.
                lastValueBlockSize = size > 8 ? size : (size < 8 ? -size : 0);
                if (size <= 8) {
                    loadSizedFromAddr("rax", "rbx", size);
                    pushReg("rax");
                } else {
                    // A multi-word (>8 byte) aggregate dereference --
                    // pushes ceil(size/8) whole words, lowest source
                    // address first, so the highest-address word ends up
                    // on top of the real stack; the matching "POP $addr
                    // size" block-store (see POP below) unwinds this in
                    // the mirror order. Only whole-8-byte chunks are
                    // handled -- a non-multiple-of-8 aggregate size was
                    // never observed and isn't specially handled.
                    long words = (size + 7) / 8;
                    for (long i = 0; i < words; i++) {
                        loadSizedFromAddr("rax", "rbx", 8);
                        if (i < words - 1) {
                            raw("    addq $8, %rbx");
                        }
                        pushReg("rax");
                    }
                }
                return true;
            }
            
case "ADDR_OF": {
                // The "ADDR_OF kind $offset" shape (a plain addressable
                // local) was, at first, only ever observed here with
                // kind=RAW -- but AddressLoweringPass's own rewrite
                // (this pass's own doc comment: "opText (AUTO/RAW --
                // REF and a storage-bearing RAW never reach this
                // [rewrite]") already produces the identical "$offset"
                // shape for kind=AUTO too, confirmed directly while
                // testing a real auto-storage pointer end to end (see
                // BytecodeEmitter's "A pointer-typed dot-chain access
                // gets a real runtime dereference" fix): AUTO/RAW are
                // both just "take the address of an already-addressable
                // stack slot," with no runtime difference between them
                // -- the storage keyword only ever changes what the
                // *type checker* allows through it, never what the CPU
                // has to do to compute the address itself, so both kinds
                // get the identical leaMemToReg treatment below. Other
                // operand shapes (a global, a dotted field root, ...)
                // are still flagged rather than guessed at.
                //
                // AddressLoweringPass's own doc comment for this rewrite
                // says the plain "PUSH name type" line immediately
                // preceding this one (emitAddressOf's own
                // `emitExpr(op.left)`, run before ADDR_OF itself) is
                // superseded once the real address is known -- but that
                // pass only rewrites *this* line, it doesn't reach back
                // and delete the one before it, so that now-dead pushed
                // value genuinely survives into real low-order output
                // (confirmed directly by actually assembling and running
                // a real "raw <local>" fixture -- without this discard,
                // the stray value silently shifts everything below it on
                // the real stack, corrupting the very next real ASSIGN).
                // Popping it here, once, before pushing the real address
                // is exactly what the doc comment's own "dropped" already
                // says should happen to it.
                //
                // The dead value being discarded isn't always a single
                // 8-byte word: "auto obj" on a struct-typed `obj` pushes
                // `obj` by its own full declared size first (the ordinary
                // "PUSH size $offset, size>8 -> pushBlockFromFrame"
                // multi-word case, same as any other by-value struct
                // push), and *that's* the value ADDR_OF's own preceding
                // line pushed here -- confirmed directly while testing a
                // real "let p = auto structObj" end to end (a hardcoded
                // single-word discard left one whole stray word behind on
                // the stack for every struct-sized `auto`, corrupting
                // everything below it, the exact same class of bug this
                // case's own doc comment already describes for the
                // no-discard-at-all version). `pendingBlockSize` (already
                // computed above from the immediately preceding PUSH's
                // own `lastValueBlockSize`, the same state DOT/LOOKUP_ARRAY
                // already key off of) is 0 for an ordinary single-word
                // push and the real total byte size for a multi-word one.
                String kind = line.get(1).text;
                String operand = line.get(2).text;
                long discardBytes = pendingBlockSize > 8 ? ((pendingBlockSize + 7) / 8) * 8 : 8;
                raw(("    addq $" + discardBytes + ", %rsp")); // discard the dead pushed value
                if ((kind.equals("RAW") || kind.equals("AUTO")) && operand.startsWith("$")) {
                    leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                    pushReg("rax");
                } else {
                    comment("TODO(codegen): '" + String.join(" ", textsOf(line)) + "' not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                    pushReg("rax");
                }
                return true;
            }
            
case "NEW_DYN": {
                // "NEW_DYN totalPushedBytes count" -- `count` initializer
                // values, each currently assumed to be a plain 8-byte
                // word (this backend's own push/pop model treats every
                // value uniformly as one stack word regardless of its
                // declared type -- a narrower- or wider-than-8-byte
                // dynarray element isn't specially handled, matching
                // this pass's pre-existing "8-byte word" limitation
                // elsewhere). Allocates [len][cap][elements...], with
                // len == cap == count, and pops the `count` initializer
                // values (top of stack = last-pushed = last element)
                // into their slots.
                long count = Long.parseLong(line.get(2).text);
                // An element wider than one word (an inline struct) is pushed as es/8 words, lowest offset first, so the
                // element's highest word is on top; it is stored back at its natural offsets with the stride es.
                long totalPushed = Long.parseLong(line.get(1).text);
                long elemBytes = count > 0 && totalPushed / count > 8 ? totalPushed / count : 8;
                if (elemBytes % 8 != 0) {
                    throw new IllegalStateException("NEW_DYN: element size " + elemBytes + " is not a whole number of words");
                }
                long elemWords = elemBytes / 8;
                long dataBytes = count * elemBytes;
                emitMallocCall(16 + dataBytes);
                raw("    movq %rax, %r12");
                // A failed malloc leaves r12 null: write nothing into it, drop the element words and push the null so the
                // bytecode's own null check can throw.
                String newDynNull = newInternalLabel("newdyn_null");
                String newDynDone = newInternalLabel("newdyn_done");
                raw("    testq %r12, %r12");
                raw("    jz " + newDynNull);
                storeSizedToAddr_withOffsetImm("r12", 0, count, 8);
                storeSizedToAddr_withOffsetImm("r12", 8, count, 8);
                for (long i = count - 1; i >= 0; i--) {
                    for (long w = elemWords - 1; w >= 0; w--) {
                        popReg("rax");
                        long off = 16 + i * elemBytes + w * 8;
                        raw("    movq %rax, " + off + "(%r12)");
                        
                    }
                }
                raw("    jmp " + newDynDone);
                raw(newDynNull + ":");
                if (count > 0) {
                    raw(("    addq $" + count * elemWords * 8 + ", %rsp"));
                }
                raw(newDynDone + ":");
                pushReg("r12");
                return true;
            }
            
case "NEW_UDYN": {
                // "NEW_UDYN totalPushedBytes" -- the unsafe sibling of NEW_DYN; the element count is derived as
                // totalBytes/8 under the same "8-byte elements" working assumption. An unsafe dynarray is HEADERLESS:
                // just the elements, and the pointer it returns (and the variable holds) is exactly what malloc
                // returned, so it is also the address registered with the ghost table and the one free() needs.
                // Direct indexing is plain "base + index*elemSize" (LOOKUP_ARRAY). It stores no length, so there is
                // no len(); never allocates 0 bytes (an empty one gets 1) so a live array is never a NULL/zero-size block.
                long totalBytes = Long.parseLong(line.get(1).text);
                long count = totalBytes / 8;
                emitMallocCall(Math.max(totalBytes, 1));
                raw("    movq %rax, %r12");
                // Failed malloc: r12 stays null (see NEW_DYN); nothing is written.
                String newUdynNull = newInternalLabel("newudyn_null");
                String newUdynDone = newInternalLabel("newudyn_done");
                raw("    testq %r12, %r12");
                raw("    jz " + newUdynNull);
                for (long i = count - 1; i >= 0; i--) {
                    popReg("rax");
                    long off = i * 8;
                    raw("    movq %rax, " + off + "(%r12)");
                    
                }
                emitGtCall("gt_register", "r12");
                raw("    jmp " + newUdynDone);
                raw(newUdynNull + ":");
                if (count > 0) {
                    raw(("    addq $" + count * 8 + ", %rsp"));
                }
                raw(newUdynDone + ":");
                pushReg("r12");
                return true;
            }
            
case "NEW_FROM_USTRING":
            case "NEW_FROM_STRING": {
                // Builds a dynarray(u8-ish) from a string-pool id already
                // on the stack (its address, per the bare-name-PUSH
                // convention above) -- copies strlen(s)+ bytes... this
                // backend doesn't have strlen's result at codegen time,
                // so it calls the real, already-declared extern strlen
                // to get it, then allocates and copies via the same
                // dynarray header layout as NEW_DYN/NEW_UDYN.
                // NEW_FROM_USTRING: the unsafe, headerless form -- malloc'd block = the bytes plus a NUL terminator (so
                // len(arr, '\0') works), pointer = block, registered with the ghost table.
                final boolean ustr = mnemonic.equals("NEW_FROM_USTRING");
                popReg("r12"); // address of the string literal
                // The argument register is an ABI question (win64: rcx,
                // SysV: rdi), not a syntax one -- argReg(0) resolves
                // that; hardcoding "rdi" here (as this used to) is only
                // right for `linux` and silently wrong for
                // `windows_gnu` (AT&T syntax, win64 ABI).
                raw("    movq %r12, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("strlen"));
                // rax now holds the string length.
                raw("    movq %rax, %r13"); // r13: length, callee-saved
                raw("    addq $" + (ustr ? 1 : 16) + ", %rax");
                
                raw("    movq %rax, %" + argReg(0));
                
                emitAlignedCall(() -> emitCallByName("malloc"));
                raw("    movq %rax, %r14"); // r14: new block
                if (ustr) {
                    String ustrDone = newInternalLabel("newustr_done");
                    raw("    testq %r14, %r14");
                    raw("    jz " + ustrDone);
                    raw("    movq %r14, %rdi");
                    raw("    movq %r12, %rsi");
                    raw("    leaq 1(%r13), %rcx");
                    raw("    rep movsb");
                    
                    emitGtCall("gt_register", "r14");
                    raw(ustrDone + ":");
                    pushReg("r14");
                    return true;
                }
                raw("    movq %r13, (%r14)");
                raw("    movq %r13, 8(%r14)");
                raw("    leaq 16(%r14), %rdi");
                raw("    movq %r12, %rsi");
                raw("    movq %r13, %rcx");
                raw("    rep movsb");
                
                pushReg("r14");
                return true;
            }
            
case "RESIZE":
            case "URESIZE": {
                // RESIZE (safe, 3 popped values: fill, newCount, ptr) /
                // URESIZE (unsafe, 2 popped values: newCount, ptr, no
                // fill) -- both confirmed directly against
                // resize_uresize_lowering_test.caspien's own real
                // low-order output and BytecodeEmitter's own doc comment
                // for it. `elemSize` is the mnemonic's own trailing
                // operand. Reallocates to exactly newCount elements (no
                // growth slack, matching NEW_DYN/NEW_UDYN above), and
                // for the safe form, fills any newly-added slots
                // (oldLen..newCount) with the fill value.
                //
                // The safe (RESIZE) pointer is the malloc'd block start with a 16-byte [len][cap] header before the elements
                // (matching NEW_DYN/LOOKUP_DYN). The unsafe (URESIZE) pointer is the malloc'd block itself and the block is
                // just the elements: no header, nothing to read or write, realloc(ptr, newCount*elemSize) rounded up to
                // at least 1 byte (realloc(p, 0) would free the block and return NULL).
                int elemSize = (int) Long.parseLong(line.get(1).text);
                boolean hasFill = mnemonic.equals("RESIZE");
                // A fill value wider than one word (a struct element) is a
                // whole pushed block, nw words deep, ahead of newCount and
                // the old pointer; it stays on the real stack (rsp is
                // restored by emitAlignedCall) until the fill loop has
                // copied it. A fill of <= 8 bytes is one word, popped
                // into r14 and stored at exactly elemSize bytes below.
                final int fillWords = (elemSize + 7) / 8;
                final boolean wideFill = hasFill && elemSize > 8;
                if (wideFill) {
                    raw("    movq " + (fillWords * 8) + "(%rsp), %r12");
                    raw("    movq " + (fillWords * 8 + 8) + "(%rsp), %rax");
                    
                } else {
                    if (hasFill) {
                        popReg("r14"); // fill value
                    }
                    popReg("r12"); // newCount
                    popReg("rax"); // old pointer (scratch; moved into the call's arg reg below)
                }
                // An unsafe dynarray is headerless: its pointer IS the malloc'd block (nothing to subtract, no length to read).
                // The old block start is kept on the stack across the realloc: if the block moves, the ghost table
                // entry for it is dropped (gt_moved: no free, realloc already released it) and the new address registered.
                pushReg("rax");
                resizeExtraStack = 8;
                // oldLen must survive the emitAlignedCall below, which
                // internally saves/restores %rsp through %r13 -- so it
                // cannot be kept in r13 (that was the actual bug behind a
                // very confusing ghost-table-lock hang: r13 silently
                // turned into the saved pre-call %rsp value instead of
                // staying oldLen, and the fill loop below then computed
                // addresses from that garbage). rbx is free once the old
                // pointer's been moved into the call's argument register,
                // and emitAlignedCall never touches it, so oldLen lives
                // there instead across the call.
                if (hasFill) {
                    loadSizedFromAddr("rbx", "rax", 8); // rbx = oldLen
                }
                // The two argument registers are an ABI question (win64:
                // rcx/rdx, SysV: rdi/rsi), independent of syntax --
                // argReg(0)/argReg(1) resolve that; this used to hardcode
                // rdi/rsi in the AT&T branch, which is only right for
                // `linux` and silently wrong for `windows_gnu` (AT&T
                // syntax, win64 ABI).
                // Safe form: the header's capacity word is honoured. A resize that fits the capacity just rewrites the length (no
                // realloc, the block does not move, so the ghost table is untouched); one that does not fit grows to
                // max(newCount, 2 * capacity), so a run of one-element growths costs O(1) amortised reallocs; one that leaves
                // fewer than a quarter of the capacity in use reallocs down to exactly newCount. r15 carries the new
                // capacity across the realloc call (callee-saved); the old block's header is never touched before the call.
                String resizeGrow = newInternalLabel("resize_grow");
                String resizeHdr = newInternalLabel("resize_hdr");
                String resizeShrink = newInternalLabel("resize_shrink");
                String resizeCall = newInternalLabel("resize_call");
                if (hasFill) {
                    raw("    movq 8(%rax), %rcx"); // rcx = old capacity
                    raw("    cmpq %rcx, %r12");
                    raw("    ja " + resizeGrow);
                    raw("    leaq 0(,%r12,4), %rdx");
                    raw("    cmpq %rcx, %rdx");
                    raw("    jb " + resizeShrink); // newCount < capacity / 4: give the memory back
                    raw("    movq %rax, %r15"); // fits: same block, capacity unchanged (rcx)
                    raw("    jmp " + resizeHdr);
                    raw(resizeShrink + ":");
                    raw("    movq %r12, %r15"); // shrink to exactly newCount
                    raw("    jmp " + resizeCall);
                    raw(resizeGrow + ":");
                    raw("    leaq (%rcx,%rcx), %rdx");
                    raw("    cmpq %rdx, %r12");
                    raw("    cmovaq %r12, %rdx"); // rdx = max(newCount, 2 * capacity)
                    raw("    movq %rdx, %r15"); // r15 = new capacity
                    raw(resizeCall + ":");
                }
                raw("    movq %rax, %" + argReg(0)); // arg1 = old (block-start) pointer
                raw("    movq %" + (hasFill ? "r15" : "r12") + ", %" + argReg(1));
                raw("    imulq $" + elemSize + ", %" + argReg(1) + ", %" + argReg(1));
                if (hasFill) {
                    raw("    addq $16, %" + argReg(1));
                } else {
                    String nz = newInternalLabel("uresize_nz");
                    raw("    testq %" + argReg(1) + ", %" + argReg(1));
                    raw("    jnz " + nz);
                    raw("    movq $1, %" + argReg(1));
                    raw(nz + ":");
                }
                
                // realloc(oldBlockPtr, newTotalBytes) -- args already
                // staged into the ABI's own first two argument registers
                // just above (rdi/rsi for SysV, rcx/rdx for win64).
                emitAlignedCall(() -> emitCallByName("realloc"));
                if (hasFill) {
                    raw("    movq %r15, %rcx"); // rcx = the new capacity (saved in r15 across the call)
                }
                raw("    movq %rax, %r15"); // r15: new block-start pointer
                // A failed realloc leaves r15 null (the old block stays valid): write nothing, skip the fill and the
                // data-start offset, and push the null so the bytecode's own check can throw.
                String resizeEnd = newInternalLabel("resize_end");
                raw("    testq %r15, %r15");
                raw("    jz " + resizeEnd);
                if (hasFill) {
                    raw(resizeHdr + ":");
                    storeSizedToAddr_reg("r15", 0, "r12", 8); // length = newCount
                    storeSizedToAddr_reg("r15", 8, "rcx", 8); // capacity
                }
                if (!hasFill) {
                    raw(resizeEnd + ":");
                    emitResizeGtUpdate();
                }
                if (hasFill) {
                    // for (i = oldLen; i < newCount; i++) data[i] = fill;
                    String loop = newInternalLabel("resize_fill");
                    String end = resizeEnd;
                    if (!wideFill && (elemSize == 1 || elemSize == 2 || elemSize == 4 || elemSize == 8)) {
                        // A big fill of zero bytes (any zero element, or any 1-byte element) is one `rep stosb`; a small one, or any other
                        // value, runs the pointer loop below (rdi/rcx/rdx are free here: the realloc call above already clobbered them).
                        String generic = newInternalLabel("resize_fill_loop");
                        raw("    cmpq %r12, %rbx");
                        raw("    jge " + end);
                        raw("    movq %r12, %rdx");
                        raw("    subq %rbx, %rdx");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rdx, %rdx");
                        }
                        boolean useRep = !isWinAbi(); // rdi is callee-saved on win64: only the pointer loop there
                        if (useRep) {
                        raw("    cmpq $64, %rdx");
                        raw("    jb " + generic);
                        if (elemSize > 1) {
                            raw("    test" + movSuffix(elemSize) + " %" + sizedReg("r14", elemSize) + ", %" + sizedReg("r14", elemSize));
                            raw("    jnz " + generic);
                            raw("    xorl %eax, %eax");
                        } else {
                            raw("    movq %r14, %rax");
                        }
                        raw("    movq %rbx, %rdi");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rdi, %rdi");
                        }
                        raw("    leaq 16(%r15,%rdi), %rdi");
                        raw("    movq %rdx, %rcx");
                        raw("    rep stosb");
                        raw("    movq %r12, %rbx");
                        raw("    jmp " + end);
                        }
                        raw(generic + ":");
                        raw("    movq %rbx, %rax");
                        if (elemSize > 1) {
                            raw("    imulq $" + elemSize + ", %rax, %rax");
                        }
                        raw("    leaq 16(%r15,%rax), %rax");
                        raw(loop + ":");
                        storeSizedToAddr("r14", "rax", elemSize);
                        raw("    addq $" + elemSize + ", %rax");
                        raw("    incq %rbx");
                        raw("    cmpq %r12, %rbx");
                        raw("    jl " + loop);
                        raw(end + ":");
                    } else {
                    raw(loop + ":");
                    raw("    cmpq %r12, %rbx");
                    raw("    jge " + end);
                    raw("    movq %rbx, %rax");
                    raw("    imulq $" + elemSize + ", %rax, %rax");
                    raw("    leaq 16(%r15,%rax), %rax");
                    if (wideFill) {
                        emitResizeWideFillCopy(elemSize, fillWords);
                    } else {
                        storeSizedToAddr("r14", "rax", elemSize);
                    }
                    raw("    incq %rbx");
                    
                    raw(("    jmp " + loop));
                    raw(end + ":");
                    }
                    emitResizeGtUpdate();
                    if (wideFill) {
                        long drop = fillWords * 8L + 16;
                        raw(("    addq $" + drop + ", %rsp"));
                    }
                }
                pushReg("r15");
                return true;
            }
            
case "ATOMIC_PUSH": {
                // "ATOMIC_PUSH size globalName" -- an aligned load off a
                // global is already atomic on x86-64; no lock prefix
                // needed for a plain read.
                int size = (int) Long.parseLong(line.get(1).text);
                String name = line.get(2).text;
                leaGlobalToReg("rbx", name);
                loadSizedFromAddr("rax", "rbx", size);
                pushReg("rax");
                return true;
            }
            
case "ATOMIC_SWAP": {
                // Pops (top to bottom) newValue, address -- xchg with a
                // memory operand is *always* atomic on x86, no lock
                // prefix required -- and leaves the previous value in
                // the same register, which every real call site this
                // pass was checked against immediately compares against
                // something (confirmed directly).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rax"); // new value
                popReg("rbx"); // address
                raw("    xchg" + movSuffix(size) + " %" + sizedReg("rax", size) + ", (%rbx)");
                
                pushReg("rax");
                return true;
            }
            
            default:
                return false;
        }
    }
}
