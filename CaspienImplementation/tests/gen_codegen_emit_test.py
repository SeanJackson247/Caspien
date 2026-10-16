#!/usr/bin/env python3
"""usage: gen_codegen_emit_test.py NAME > tests/codegen_NAME_test.caspien
One spec table (SPECS[NAME]) drives both sides: a Java reflection harness over the originals (compiled against Codegen/out and run here) and the Caspien test
that calls the converted methods on a fresh backend and compares the emitted text (core.out) with what Java produced. Kinds: R register/text String,
I s64, W small s32 (sizes), B bool, T text token, N s32 (small count). Setups: 0 none, 1 rfIdx (rcx*4+8), 2 rfMemOverride, 3 global alias, 4 windows target."""
import shutil, sys, os, random, subprocess, tempfile, itertools
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POOL = {
    "4": ['DOT 8', 'DOT 4', 'DOT 3', 'DOT 1', 'DOT 2', 'DOT 5', 'DOT 16', 'DOT 24', 'DOT 8 ra', 'DOT 4 ra', 'DOT 3 ra', 'DOT 16 ra', 'DOT 2 ra', 'DOT', 'DOT x',
          'LOOKUP_ARRAY_LHS 4', 'LOOKUP_ARRAY_LHS 8', 'LOOKUP_ARRAY_LHS 1', 'LOOKUP_ARRAY_LHS',
          'LOOKUP_ARRAY 4', 'LOOKUP_ARRAY 8', 'LOOKUP_ARRAY 3', 'LOOKUP_ARRAY 1', 'LOOKUP_ARRAY 16', 'LOOKUP_ARRAY 24', 'LOOKUP_ARRAY 5', 'LOOKUP_ARRAY 4 ra', 'LOOKUP_ARRAY 8 t8', 'LOOKUP_ARRAY 3 ra', 'LOOKUP_ARRAY 2 ra x', 'LOOKUP_ARRAY 16 ra', 'LOOKUP_ARRAY',
          'LOOKUP_DYN 4', 'LOOKUP_DYN 8', 'LOOKUP_DYN 16', 'LOOKUP_DYN 1', 'LOOKUP_DYN_LHS 8', 'LOOKUP_DYN_LHS 24', 'LEN', 'STACK_LOCK 16', 'STACK_LOCK', 'STRUCT_PADDING', 'STRUCT_PADDING 4',
          'NEW 24', 'NEW 24 m8', 'NEW 24 m8 a1x9', 'NEW 8', 'NEW 0', 'NEW', 'CLONE_DYN 8', 'CLONE_DYN 16', 'CLONE_DYN 1', 'CLONE_DYN'],
    "5": ['CLONE 24', 'CLONE 8', 'CLONE 0', 'CLONE', 'MEMCOPY', 'DEREF 1', 'DEREF 2', 'DEREF 3', 'DEREF 4', 'DEREF 8', 'DEREF 12', 'DEREF 16', 'DEREF 24', 'DEREF',
          'ADDR_OF RAW $-16', 'ADDR_OF AUTO $24', 'ADDR_OF RAW glob', 'ADDR_OF BAD $5', 'ADDR_OF', 'ADDR_OF RAW',
          'NEW_DYN 24 3', 'NEW_DYN 48 3', 'NEW_DYN 20 2', 'NEW_DYN 8 0', 'NEW_DYN 0 0', 'NEW_DYN 16 1', 'NEW_DYN 24 1', 'NEW_DYN 8 -1', 'NEW_DYN 16 2', 'NEW_DYN 8',
          'NEW_UDYN 24', 'NEW_UDYN 0', 'NEW_UDYN 8', 'NEW_UDYN 7', 'NEW_UDYN -8', 'NEW_FROM_STRING', 'NEW_FROM_USTRING',
          'RESIZE 1', 'RESIZE 2', 'RESIZE 3', 'RESIZE 4', 'RESIZE 8', 'RESIZE 12', 'RESIZE 16', 'RESIZE 24', 'URESIZE 1', 'URESIZE 8', 'URESIZE 16', 'RESIZE', 'RESIZE 5', 'RESIZE 6',
          'ATOMIC_PUSH 8 glob', 'ATOMIC_PUSH 4 gv', 'ATOMIC_PUSH 1 ga', 'ATOMIC_SWAP 8', 'ATOMIC_SWAP 4', 'ATOMIC_SWAP 1', 'ATOMIC_SWAP 2'],
    "6": ['GT_INIT', 'GT_REGISTER', 'GT_ALIVE_CHECK', 'GT_REF_ID', 'GT_REF_RESOLVE', 'GT_DESTRUCT $-8', 'GT_DESTRUCT $-24', 'GT_DESTRUCT foo', 'GT_DESTRUCT', 'GT_DESTRUCT_ADDR',
          'YIELD', 'SLEEP slp u64', 'SLEEP', 'EXIT', 'EXIT_THREAD', 'TRACE_CAPTURE 16', 'TRACE_CAPTURE 4', 'TRACE_CAPTURE', 'GT_UNWIND', 'GT_UNWIND MSG', 'GT_UNWIND MSG TRACE', 'GT_UNWIND TRACE', 'GT_UNWIND MSG X',
          'THROW', 'RET 8', 'RET 0', 'RET 16', 'RET', 'RET x', 'RET_FLOAT 8', 'RET_FLOAT', 'FOO_BAR'],
    "7": ['VARARGS_XMM_COUNT 3', 'VARARGS_XMM_COUNT 0', 'VARARGS_XMM_COUNT', 'CALL f', 'CALL __drop_T', 'CALL __clone_X', 'CALL', 'INVOKE',
          'POP ARG0 8', 'POP ARG3 8', 'POP ARG7 8', 'POP FARG0 8', 'POP FARG2 4', 'POP FARG9 8', 'POP ARG', 'POP $-8 8', 'POP $-16 4', 'POP $-24 16', 'POP $-24 24', 'POP $-8 3', 'POP $-8 1', 'POP $-8', 'POP x 8', 'POP foo 8', 'POP',
          'PUSH_LABEL L1', 'PUSH_LABEL', 'PUSH_RET_INT', 'PUSH_RET_FLOAT', 'ASM_START', 'ASM_END',
          'FLOAT_CHECK finite 4', 'FLOAT_CHECK nan|finite|infinite 8', 'FLOAT_CHECK infinite|nan 4', 'FLOAT_CHECK nan|x|finite 8', 'FLOAT_CHECK finite||nan 4', 'FLOAT_CHECK nan 4', 'FLOAT_CHECK finite', 'FLOAT_CHECK',
          'CC_START', 'CC_END', 'EXPORT foo', 'GLOBAL x', 'ALLOC_STATIC y', 'STRING s', 'EXTERN e', 'NOP_X'],
    "b": [False],
    "z": [0],
    "2": ['R_BRCM EQ 8 %t0 %t1 @L1', 'R_BRCM LT 8 $-8 %t2 @L2', 'R_BRCM GT 8 #5 %v1 @catch_3', 'R_BRCM EQ 8 %t0',
          'R_PUSH 8 %t0', 'R_PUSH 8 #5', 'R_PUSH 8 $-8', 'R_PUSH 8 %v1', 'R_PUSHA 8 $-16', 'R_PUSHA 8 &glob', 'R_PUSHA 8 %t0',
          'R_SETV 4 %v1 %t0', 'R_SETV 2 %v1 %t3', 'R_SETV 1 %v1 %v2', 'R_SETV 8 %v1 %t0', 'R_SETV 3 %v1 %t0', 'R_SETV 4 %v1 #70000', 'R_SETV 1 %v1 #300', 'R_SETV 2 %v1 #-5', 'R_SETV 8 %v3 #-5',
          'R_LD 8 %t0 $-8', 'R_LD 4 %t0 %t1', 'R_LD 1 %t0 &glob', 'R_ST 4 $-8 #5', 'R_ST 8 %t1 %t0', 'R_ST 2 &glob %t0',
          'R_LDI 4 %t0 &glob %v1 4', 'R_LDI 8 %t0 &glob %v1 8 16', 'R_LDI 1 %t0 %t1 %v1 1', 'R_LDI 4 %t0 $-8 %v1 12 -8', 'R_STI 4 &glob %v1 4 %t0', 'R_STI 8 %t1 %v1 8 #5 24', 'R_STI 2 $-16 %v1 6 %t0',
          'R_LDXI 4 %x0 &glob %v1 4', 'R_LDXI 8 %x1 %t1 %v1 8 16', 'R_STXI 4 &glob %v1 4 %x0', 'R_STXI 8 %t1 %v1 8 %x2 8',
          'R_LDD 8 %t0 %t1 16', 'R_LDD 1 %t0 %t1 0', 'R_STD 4 %t1 8 %t0', 'R_STD 8 %t1 -8 #5', 'R_LDXD 8 %x1 %t1 8', 'R_LDXD 4 %y1 %t1 0', 'R_STXD 4 %t1 8 %x1', 'R_STXD 8 %t1 16 %z2',
          'R_LEA %t0 %t1 %t2 4 8', 'R_LEA %t0 &glob %v1 3', 'R_LEA %t0 $-16 %v1 8', 'R_LEA %t0 %t1 #5 1',
          'R_FBIN ADD 4 %t0 %t1 %t2', 'R_FBIN MUL 8 %t0 #5 %t2', 'R_FCMP LT 8 %t0 %t1 %t2', 'R_FCMP EQ 4 %t0 %t1 %t2', 'R_XVAR %x1 $-16', 'R_XVAR %x2 $-24 8', 'R_XVAR %x0 $-8 4',
          'R_XMOV %x0 %y1', 'R_XMOV %x1 %x1', 'R_XSPILL', 'R_XRELOAD', 'R_XTOG 8 %t0 %x1', 'R_XTOG 4 %t1 %y2', 'R_GTOX 8 %x1 %t0', 'R_GTOX 4 %x1 #1065353216', 'R_GTOX 8 %y1 #4611686018427387904',
          'R_LDX 8 %x1 $-8', 'R_LDX 4 %y0 %t1', 'R_STX 4 $-8 %x2', 'R_STX 8 &glob %y2',
          'R_FBINX ADD 8 %x0 %x1 %x2', 'R_FBINX MUL 4 %x0 %x0 %y1', 'R_FBINX SUB 8 %x1 %x0 %x1', 'R_FSQRT 8 %x0 %x1', 'R_FSQRT 4 %x1 %x1', 'R_FSQRT 8 %y0 %z1',
          'R_POPV 8 %v1', 'R_POPV 2 %t0', 'R_GETRET 4 %v1', 'R_GETRET 1 %t0', 'R_POPX 8 %x1', 'R_POPX 4 %y2', 'R_GETRETF 4 %x1', 'R_GETRETF 8 %y2',
          'R_RMW ADD 8 $-8 #5', 'R_RMW INC 8 $-8', 'R_RMW SUB 8 $-8 %t0',
          'R_ARG 0 8 %t0', 'R_ARG 1 4 #5', 'R_ARG 2 8 $-8', 'R_ARG 3 8 %x1', 'R_ARG 4 4 %y2', 'R_ARG 7 8 %t0', 'R_ARGA 0 $-16', 'R_ARGA 1 &glob', 'R_ARGA 5 $8',
          'R_FARG 0 8 %x1', 'R_FARG 1 4 %t0', 'R_FARG 2 8 #5', 'R_FARG 3 8 $-8', 'R_FARG 9 4 %t0',
          'R_RET %t0', 'R_RET #5', 'R_RET $-8', 'R_RETF %x1', 'R_RETF %t2', 'R_RETF #5', 'R_RETF $-8',
          'ADD_INT', 'SUB_INT', 'MUL_INT', 'SDIV_INT 4', 'SMOD_INT 8', 'SDIV_INT 1', 'SLT_INT 4', 'SGT_INT 8', 'SLT_EQ_INT 2', 'SGT_EQ_INT 1',
          'DIV_INT 8', 'MOD_INT 4', 'DIV_INT', 'MOD_INT 2', 'SHL 4', 'SHR 8', 'SAR 2', 'SHL', 'SAR 8', 'ROTL 4', 'ROTR 8', 'ROTL 1', 'ROTR',
          'BITS_OR 4', 'BITS_AND', 'BITS_XOR 8', 'BITS_OR 1', 'BITS_NOT 2', 'BITS_NOT', 'R_PUSH', 'R_LDD 8', 'R_SETV 4 %v1'],
    "3": ['EQ_INT 8', 'NEQ_INT 4', 'LT_INT 1', 'LT_EQ_INT 2', 'GT_EQ_INT 8', 'GT_INT 4', 'EQ_INT', 'ADD_FLOAT 4', 'SUB_FLOAT 8', 'MUL_FLOAT 4', 'DIV_FLOAT 8',
          'EQ_FLOAT 4', 'NEQ_FLOAT 8', 'LT_FLOAT 4', 'LT_EQ_FLOAT 8', 'GT_EQ_FLOAT 4', 'GT_FLOAT 8', 'AND', 'OR', 'NOT', 'NEG', 'NEG_FLOAT 4', 'NEG_FLOAT 8', 'NEG_FLOAT',
          'INC_INT', 'DEC_INT', 'INC_FLOAT 4', 'INC_FLOAT 8', 'DEC_FLOAT 4', 'DEC_FLOAT 8', 'CMP', 'JMP L1', 'JMP @catch_1', 'JMP',
          'SEXT 1', 'SEXT 2', 'SEXT 4', 'SEXT 4 8', 'SEXT 1 4', 'SEXT 2 2', 'SEXT 1 1', 'SEXT', 'TRUNC 8 4', 'TRUNC 8 1', 'TRUNC 8 2', 'TRUNC 8 8', 'TRUNC 8',
          'ZEXT 1', 'ZEXT 2', 'ZEXT 4', 'ZEXT 8', 'DUP_TOP', 'FCONV 4 8', 'FCONV 8 4', 'FCONV 4 4', 'FCONV 8 8', 'PROMOTE_F32_TO_F64',
          'PUSH_FIELDNAME 8', 'PUSH_FIELDNAME -8', 'PUSH_FIELDNAME', 'DOT_LHS 8', 'DOT_LHS', 'ZEXT 3'],
    "1": ['FUNC_START f1', 'FUNC_START main', 'FUNC_END', 'ALLOC 24', 'ALLOC 0', 'ALLOC 40', 'ALLOC 7', 'ADDR 8 $-16', 'ADDR 8 glob', 'ADDR 8 $24',
          'PUSH 8', 'PUSH 8 $5 extra', 'PUSH 8 $-16', 'PUSH 4 $-8', 'PUSH 3 $-8', 'PUSH 1 $-8', 'PUSH 16 $-24', 'PUSH 24 $-32', 'PUSH 8 %x1', 'PUSH 4 %x2', 'PUSH 8 %x0',
          'PUSH 8 null', 'PUSH 8 5', 'PUSH 4 -5', 'PUSH 8 18446744073709551615', 'PUSH 4 1.5', 'PUSH 8 2.5', 'PUSH 8 12.start', 'PUSH 8 3.end', 'PUSH 8 ARG0', 'PUSH 8 ARG3', 'PUSH 8 ARG7',
          'PUSH 8 FARG1', 'PUSH 8 FARG9', "PUSH 1 'a'", 'PUSH 8 foo', 'PUSH 8 str0', 'PUSH 8 gv', 'PUSH 4 gv', 'PUSH 16 gv', 'PUSH 1 gv', 'PUSH 8 ga', 'PUSH 8 fn1',
          'ASSIGN 8 a b', 'ASSIGN 4', 'ASSIGN 16', 'ATOMIC_ASSIGN 8', 'ASSIGN 24 a', 'ASSIGN 16 m8 a1x8 p3', 'ASSIGN 3',
          'R_MOV 8 %t0 %t1', 'R_MOV 8 %v1 #5', 'R_MOV 8 $-8 %t0', 'R_BIN ADD 8 %t0 %t1 %t2', 'R_BIN SUB 4 %t0 %t1 #5', 'R_BIN SHL 8 %t0 %t1 #3', 'R_BIN EQ 8 %t0 %t1 %v1', 'R_BIN BAD 8 %t0 %t1 %t2',
          'R_DIVC DIV 8 %t0 %t1 #7', 'R_DIVC MOD 4 %t0 %t1 #10', 'R_UN NEG 8 %t0 %t1', 'R_UN NOT 4 %t0 %t1',
          'R_BRF %t0 @L1', 'R_BRF %v1 @catch_2', 'R_BRC EQ 8 %t0 %t1 @L1', 'R_BRC LT F8 %x0 %x1 @L2', 'R_BRC LT F4 %x0 %x1 @L2', 'R_BRC SLT 4 %t0 #5 @L3', 'R_BRC GT_EQ 1 %t0 %t1 @L3',
          'R_CMOV EQ 4 %t0 %t3 %t0 #7', 'R_CMOV EQ 4 %t0 %t3 %t0 %t2', 'R_CMOV NEQ 8 %t0 %t3 %t0 %t2 %t1', 'R_MOV', 'R_CMOV'],
    "A": ["%t0", "%t2", "%v1", "%v7", "%s", "$-16", "$24", "&glob", "&a.b", "%zz"],
    "X": ["xmm0", "xmm9", "xmm15", "%xmm1"],
    "K": ["%t0", "%t3", "%v1", "$-16", "$24", "#5", "#2147483648", "#-3", "#-2147483649", "%s", "5", "abc", "#"],
    "L": ["", "i0", "i1,f2", "f0,i3,f1", "i5", "f9"],
    "J": ["%t0", "%v1", "%t3", "#5", "%s", "x"],
    "O": ["EQ", "NEQ", "LT", "LT_EQ", "GT_EQ", "SLT", "SGT_EQ", "BAD"],
    "Q": [0, 1, 3, 5, 8, 13, 20, -4],
    "Z": [0, 1, 3, 8, 12, 16, 20, 24, 64, 72, -16, -1],
    "Y": [0, 3, 8, 12, 16, 20, 24],
    "D": ["__drop_T", "__clone_value_X", "f", ""],
    "C": [1, 2, 4, 8, 3, 16, 24, 0, -8, 4294967296],
    "V": ["%x0", "%x5", "%y1", "%y9", "%z3", "%z4", "x"],
    "M": ["%x1", "%y2", "%t0", "#0", "#5", "#4294967296", "$-8", "%z1", "#1065353216"],
    "U": ["INC", "DEC", "NEG", "NOT", "BNOT", "BAD"],
    "R": ["rax", "r8", "rcx", "r15", "rbp", "rsi", "xmm0", "r11"],
    "T": ["foo", "a.b", "", "x y", "%rax", "$5", "-8(%rbp)", "16(%rax)"],
    "I": [0, 1, 8, -8, 100, 4096, -4096, 255, 65536, 4294967295, 4294967296, -1, 0x7fffffffffffffff],
    "W": [1, 2, 3, 4, 5, 6, 7, 8],
    "N": [0, 1, 2, 3, 5],
    "G": [0, 1, 2, 3, 5, 7, 10, 16, 25, 100, 641, 1000, 65535, 65537, 4294967295, 4294967296, 6700417, 0x7fffffffffffffff, -1, -3, -16, 12345678901234, 9223372036854775808-0x10000000000000000],
    "r": ['NEW 24', 'NEW 24 m8', 'NEW 24 a1x9', 'NEW 24 p3 m7', 'NEW 24 m8', 'NEW 24 m8 m8', 'NEW 24 m8 a1x9', 'NEW 24 m8 p3 m7', 'NEW 24 m3 p5', 'NEW 24 m3 p5 m8', 'NEW 24 m3 p5 a1x9', 'NEW 24 m3 p5 p3 m7', 'NEW 24 p8 m16', 'NEW 24 p8 m16 m8', 'NEW 24 p8 m16 a1x9', 'NEW 24 p8 m16 p3 m7', 'NEW 24 a4x3', 'NEW 24 a4x3 m8', 'NEW 24 a4x3 a1x9', 'NEW 24 a4x3 p3 m7', 'NEW 24 m8 a2x5 p4 m1', 'NEW 24 m8 a2x5 p4 m1 m8', 'NEW 24 m8 a2x5 p4 m1 a1x9', 'NEW 24 m8 a2x5 p4 m1 p3 m7', 'NEW 24 m5 m5', 'NEW 24 m5 m5 m8', 'NEW 24 m5 m5 a1x9', 'NEW 24 m5 m5 p3 m7', 'NEW 24 a8x2 m12', 'NEW 24 a8x2 m12 m8', 'NEW 24 a8x2 m12 a1x9', 'NEW 24 a8x2 m12 p3 m7', 'NEW 24 m24 p8', 'NEW 24 m24 p8 m8', 'NEW 24 m24 p8 a1x9', 'NEW 24 m24 p8 p3 m7'],
    "u": ['ASSIGN a b c', 'ASSIGN a b c m8', 'ASSIGN a b c a1x9', 'ASSIGN a b c p3 m7', 'ASSIGN a b c m8', 'ASSIGN a b c m8 m8', 'ASSIGN a b c m8 a1x9', 'ASSIGN a b c m8 p3 m7', 'ASSIGN a b c m3 p5', 'ASSIGN a b c m3 p5 m8', 'ASSIGN a b c m3 p5 a1x9', 'ASSIGN a b c m3 p5 p3 m7', 'ASSIGN a b c p8 m16', 'ASSIGN a b c p8 m16 m8', 'ASSIGN a b c p8 m16 a1x9', 'ASSIGN a b c p8 m16 p3 m7', 'ASSIGN a b c a4x3', 'ASSIGN a b c a4x3 m8', 'ASSIGN a b c a4x3 a1x9', 'ASSIGN a b c a4x3 p3 m7', 'ASSIGN a b c m8 a2x5 p4 m1', 'ASSIGN a b c m8 a2x5 p4 m1 m8', 'ASSIGN a b c m8 a2x5 p4 m1 a1x9', 'ASSIGN a b c m8 a2x5 p4 m1 p3 m7', 'ASSIGN a b c m5 m5', 'ASSIGN a b c m5 m5 m8', 'ASSIGN a b c m5 m5 a1x9', 'ASSIGN a b c m5 m5 p3 m7', 'ASSIGN a b c a8x2 m12', 'ASSIGN a b c a8x2 m12 m8', 'ASSIGN a b c a8x2 m12 a1x9', 'ASSIGN a b c a8x2 m12 p3 m7', 'ASSIGN a b c m24 p8', 'ASSIGN a b c m24 p8 m8', 'ASSIGN a b c m24 p8 a1x9', 'ASSIGN a b c m24 p8 p3 m7'],
    "q": ['R_CMOV EQ 4 %t0 %t3 %t0 #7', 'R_CMOV EQ 4 %t0 %t3 %t0 #7 %t0', 'R_CMOV EQ 4 %t0 %t3 %t0 #7 #9', 'R_CMOV EQ 4 %t0 %t3 %t0 %t2', 'R_CMOV EQ 4 %t0 %t3 %t0 %t2 %t0', 'R_CMOV EQ 4 %t0 %t3 %t0 %t2 #9', 'R_CMOV EQ 4 %t0 %t3 %t0 %v1', 'R_CMOV EQ 4 %t0 %t3 %t0 %v1 %t0', 'R_CMOV EQ 4 %t0 %t3 %t0 %v1 #9', 'R_CMOV EQ 4 %t0 %t3 %t3 #7', 'R_CMOV EQ 4 %t0 %t3 %t3 #7 %t0', 'R_CMOV EQ 4 %t0 %t3 %t3 #7 #9', 'R_CMOV EQ 4 %t0 %t3 %t3 %t2', 'R_CMOV EQ 4 %t0 %t3 %t3 %t2 %t0', 'R_CMOV EQ 4 %t0 %t3 %t3 %t2 #9', 'R_CMOV EQ 4 %t0 %t3 %t3 %v1', 'R_CMOV EQ 4 %t0 %t3 %t3 %v1 %t0', 'R_CMOV EQ 4 %t0 %t3 %t3 %v1 #9', 'R_CMOV EQ 4 %t0 $-16 %t0 #7', 'R_CMOV EQ 4 %t0 $-16 %t0 #7 %t0', 'R_CMOV EQ 4 %t0 $-16 %t0 #7 #9', 'R_CMOV EQ 4 %t0 $-16 %t0 %t2', 'R_CMOV EQ 4 %t0 $-16 %t0 %t2 %t0', 'R_CMOV EQ 4 %t0 $-16 %t0 %t2 #9', 'R_CMOV EQ 4 %t0 $-16 %t0 %v1', 'R_CMOV EQ 4 %t0 $-16 %t0 %v1 %t0', 'R_CMOV EQ 4 %t0 $-16 %t0 %v1 #9', 'R_CMOV EQ 4 %t0 $-16 %t3 #7', 'R_CMOV EQ 4 %t0 $-16 %t3 #7 %t0', 'R_CMOV EQ 4 %t0 $-16 %t3 #7 #9', 'R_CMOV EQ 4 %t0 $-16 %t3 %t2', 'R_CMOV EQ 4 %t0 $-16 %t3 %t2 %t0', 'R_CMOV EQ 4 %t0 $-16 %t3 %t2 #9', 'R_CMOV EQ 4 %t0 $-16 %t3 %v1', 'R_CMOV EQ 4 %t0 $-16 %t3 %v1 %t0', 'R_CMOV EQ 4 %t0 $-16 %t3 %v1 #9', 'R_CMOV EQ 4 #5 %t3 %t0 #7', 'R_CMOV EQ 4 #5 %t3 %t0 #7 %t0', 'R_CMOV EQ 4 #5 %t3 %t0 #7 #9', 'R_CMOV EQ 4 #5 %t3 %t0 %t2', 'R_CMOV EQ 4 #5 %t3 %t0 %t2 %t0', 'R_CMOV EQ 4 #5 %t3 %t0 %t2 #9', 'R_CMOV EQ 4 #5 %t3 %t0 %v1', 'R_CMOV EQ 4 #5 %t3 %t0 %v1 %t0', 'R_CMOV EQ 4 #5 %t3 %t0 %v1 #9', 'R_CMOV EQ 4 #5 %t3 %t3 #7', 'R_CMOV EQ 4 #5 %t3 %t3 #7 %t0', 'R_CMOV EQ 4 #5 %t3 %t3 #7 #9', 'R_CMOV EQ 4 #5 %t3 %t3 %t2', 'R_CMOV EQ 4 #5 %t3 %t3 %t2 %t0', 'R_CMOV EQ 4 #5 %t3 %t3 %t2 #9', 'R_CMOV EQ 4 #5 %t3 %t3 %v1', 'R_CMOV EQ 4 #5 %t3 %t3 %v1 %t0', 'R_CMOV EQ 4 #5 %t3 %t3 %v1 #9', 'R_CMOV EQ 4 #5 $-16 %t0 #7', 'R_CMOV EQ 4 #5 $-16 %t0 #7 %t0', 'R_CMOV EQ 4 #5 $-16 %t0 #7 #9', 'R_CMOV EQ 4 #5 $-16 %t0 %t2', 'R_CMOV EQ 4 #5 $-16 %t0 %t2 %t0', 'R_CMOV EQ 4 #5 $-16 %t0 %t2 #9', 'R_CMOV EQ 4 #5 $-16 %t0 %v1', 'R_CMOV EQ 4 #5 $-16 %t0 %v1 %t0', 'R_CMOV EQ 4 #5 $-16 %t0 %v1 #9', 'R_CMOV EQ 4 #5 $-16 %t3 #7', 'R_CMOV EQ 4 #5 $-16 %t3 #7 %t0', 'R_CMOV EQ 4 #5 $-16 %t3 #7 #9', 'R_CMOV EQ 4 #5 $-16 %t3 %t2', 'R_CMOV EQ 4 #5 $-16 %t3 %t2 %t0', 'R_CMOV EQ 4 #5 $-16 %t3 %t2 #9', 'R_CMOV EQ 4 #5 $-16 %t3 %v1', 'R_CMOV EQ 4 #5 $-16 %t3 %v1 %t0', 'R_CMOV EQ 4 #5 $-16 %t3 %v1 #9', 'R_CMOV EQ 8 %t0 %t3 %t0 #7', 'R_CMOV EQ 8 %t0 %t3 %t0 #7 %t0', 'R_CMOV EQ 8 %t0 %t3 %t0 #7 #9', 'R_CMOV EQ 8 %t0 %t3 %t0 %t2', 'R_CMOV EQ 8 %t0 %t3 %t0 %t2 %t0', 'R_CMOV EQ 8 %t0 %t3 %t0 %t2 #9', 'R_CMOV EQ 8 %t0 %t3 %t0 %v1', 'R_CMOV EQ 8 %t0 %t3 %t0 %v1 %t0', 'R_CMOV EQ 8 %t0 %t3 %t0 %v1 #9', 'R_CMOV EQ 8 %t0 %t3 %t3 #7', 'R_CMOV EQ 8 %t0 %t3 %t3 #7 %t0', 'R_CMOV EQ 8 %t0 %t3 %t3 #7 #9', 'R_CMOV EQ 8 %t0 %t3 %t3 %t2', 'R_CMOV EQ 8 %t0 %t3 %t3 %t2 %t0', 'R_CMOV EQ 8 %t0 %t3 %t3 %t2 #9', 'R_CMOV EQ 8 %t0 %t3 %t3 %v1', 'R_CMOV EQ 8 %t0 %t3 %t3 %v1 %t0', 'R_CMOV EQ 8 %t0 %t3 %t3 %v1 #9', 'R_CMOV EQ 8 %t0 $-16 %t0 #7', 'R_CMOV EQ 8 %t0 $-16 %t0 #7 %t0', 'R_CMOV EQ 8 %t0 $-16 %t0 #7 #9', 'R_CMOV EQ 8 %t0 $-16 %t0 %t2', 'R_CMOV EQ 8 %t0 $-16 %t0 %t2 %t0', 'R_CMOV EQ 8 %t0 $-16 %t0 %t2 #9', 'R_CMOV EQ 8 %t0 $-16 %t0 %v1', 'R_CMOV EQ 8 %t0 $-16 %t0 %v1 %t0', 'R_CMOV EQ 8 %t0 $-16 %t0 %v1 #9', 'R_CMOV EQ 8 %t0 $-16 %t3 #7', 'R_CMOV EQ 8 %t0 $-16 %t3 #7 %t0', 'R_CMOV EQ 8 %t0 $-16 %t3 #7 #9', 'R_CMOV EQ 8 %t0 $-16 %t3 %t2', 'R_CMOV EQ 8 %t0 $-16 %t3 %t2 %t0', 'R_CMOV EQ 8 %t0 $-16 %t3 %t2 #9', 'R_CMOV EQ 8 %t0 $-16 %t3 %v1', 'R_CMOV EQ 8 %t0 $-16 %t3 %v1 %t0', 'R_CMOV EQ 8 %t0 $-16 %t3 %v1 #9', 'R_CMOV EQ 8 #5 %t3 %t0 #7', 'R_CMOV EQ 8 #5 %t3 %t0 #7 %t0', 'R_CMOV EQ 8 #5 %t3 %t0 #7 #9', 'R_CMOV EQ 8 #5 %t3 %t0 %t2', 'R_CMOV EQ 8 #5 %t3 %t0 %t2 %t0', 'R_CMOV EQ 8 #5 %t3 %t0 %t2 #9', 'R_CMOV EQ 8 #5 %t3 %t0 %v1', 'R_CMOV EQ 8 #5 %t3 %t0 %v1 %t0', 'R_CMOV EQ 8 #5 %t3 %t0 %v1 #9', 'R_CMOV EQ 8 #5 %t3 %t3 #7', 'R_CMOV EQ 8 #5 %t3 %t3 #7 %t0', 'R_CMOV EQ 8 #5 %t3 %t3 #7 #9', 'R_CMOV EQ 8 #5 %t3 %t3 %t2', 'R_CMOV EQ 8 #5 %t3 %t3 %t2 %t0', 'R_CMOV EQ 8 #5 %t3 %t3 %t2 #9', 'R_CMOV EQ 8 #5 %t3 %t3 %v1', 'R_CMOV EQ 8 #5 %t3 %t3 %v1 %t0', 'R_CMOV EQ 8 #5 %t3 %t3 %v1 #9', 'R_CMOV EQ 8 #5 $-16 %t0 #7', 'R_CMOV EQ 8 #5 $-16 %t0 #7 %t0', 'R_CMOV EQ 8 #5 $-16 %t0 #7 #9', 'R_CMOV EQ 8 #5 $-16 %t0 %t2', 'R_CMOV EQ 8 #5 $-16 %t0 %t2 %t0', 'R_CMOV EQ 8 #5 $-16 %t0 %t2 #9', 'R_CMOV EQ 8 #5 $-16 %t0 %v1', 'R_CMOV EQ 8 #5 $-16 %t0 %v1 %t0', 'R_CMOV EQ 8 #5 $-16 %t0 %v1 #9', 'R_CMOV EQ 8 #5 $-16 %t3 #7', 'R_CMOV EQ 8 #5 $-16 %t3 #7 %t0', 'R_CMOV EQ 8 #5 $-16 %t3 #7 #9', 'R_CMOV EQ 8 #5 $-16 %t3 %t2', 'R_CMOV EQ 8 #5 $-16 %t3 %t2 %t0', 'R_CMOV EQ 8 #5 $-16 %t3 %t2 #9', 'R_CMOV EQ 8 #5 $-16 %t3 %v1', 'R_CMOV EQ 8 #5 $-16 %t3 %v1 %t0', 'R_CMOV EQ 8 #5 $-16 %t3 %v1 #9', 'R_CMOV SLT 4 %t0 %t3 %t0 #7', 'R_CMOV SLT 4 %t0 %t3 %t0 #7 %t0', 'R_CMOV SLT 4 %t0 %t3 %t0 #7 #9', 'R_CMOV SLT 4 %t0 %t3 %t0 %t2', 'R_CMOV SLT 4 %t0 %t3 %t0 %t2 %t0', 'R_CMOV SLT 4 %t0 %t3 %t0 %t2 #9', 'R_CMOV SLT 4 %t0 %t3 %t0 %v1', 'R_CMOV SLT 4 %t0 %t3 %t0 %v1 %t0', 'R_CMOV SLT 4 %t0 %t3 %t0 %v1 #9', 'R_CMOV SLT 4 %t0 %t3 %t3 #7', 'R_CMOV SLT 4 %t0 %t3 %t3 #7 %t0', 'R_CMOV SLT 4 %t0 %t3 %t3 #7 #9', 'R_CMOV SLT 4 %t0 %t3 %t3 %t2', 'R_CMOV SLT 4 %t0 %t3 %t3 %t2 %t0', 'R_CMOV SLT 4 %t0 %t3 %t3 %t2 #9', 'R_CMOV SLT 4 %t0 %t3 %t3 %v1', 'R_CMOV SLT 4 %t0 %t3 %t3 %v1 %t0', 'R_CMOV SLT 4 %t0 %t3 %t3 %v1 #9', 'R_CMOV SLT 4 %t0 $-16 %t0 #7', 'R_CMOV SLT 4 %t0 $-16 %t0 #7 %t0', 'R_CMOV SLT 4 %t0 $-16 %t0 #7 #9', 'R_CMOV SLT 4 %t0 $-16 %t0 %t2', 'R_CMOV SLT 4 %t0 $-16 %t0 %t2 %t0', 'R_CMOV SLT 4 %t0 $-16 %t0 %t2 #9', 'R_CMOV SLT 4 %t0 $-16 %t0 %v1', 'R_CMOV SLT 4 %t0 $-16 %t0 %v1 %t0', 'R_CMOV SLT 4 %t0 $-16 %t0 %v1 #9', 'R_CMOV SLT 4 %t0 $-16 %t3 #7', 'R_CMOV SLT 4 %t0 $-16 %t3 #7 %t0', 'R_CMOV SLT 4 %t0 $-16 %t3 #7 #9', 'R_CMOV SLT 4 %t0 $-16 %t3 %t2', 'R_CMOV SLT 4 %t0 $-16 %t3 %t2 %t0', 'R_CMOV SLT 4 %t0 $-16 %t3 %t2 #9', 'R_CMOV SLT 4 %t0 $-16 %t3 %v1', 'R_CMOV SLT 4 %t0 $-16 %t3 %v1 %t0', 'R_CMOV SLT 4 %t0 $-16 %t3 %v1 #9', 'R_CMOV SLT 4 #5 %t3 %t0 #7', 'R_CMOV SLT 4 #5 %t3 %t0 #7 %t0', 'R_CMOV SLT 4 #5 %t3 %t0 #7 #9', 'R_CMOV SLT 4 #5 %t3 %t0 %t2', 'R_CMOV SLT 4 #5 %t3 %t0 %t2 %t0', 'R_CMOV SLT 4 #5 %t3 %t0 %t2 #9', 'R_CMOV SLT 4 #5 %t3 %t0 %v1', 'R_CMOV SLT 4 #5 %t3 %t0 %v1 %t0', 'R_CMOV SLT 4 #5 %t3 %t0 %v1 #9', 'R_CMOV SLT 4 #5 %t3 %t3 #7', 'R_CMOV SLT 4 #5 %t3 %t3 #7 %t0', 'R_CMOV SLT 4 #5 %t3 %t3 #7 #9', 'R_CMOV SLT 4 #5 %t3 %t3 %t2', 'R_CMOV SLT 4 #5 %t3 %t3 %t2 %t0', 'R_CMOV SLT 4 #5 %t3 %t3 %t2 #9', 'R_CMOV SLT 4 #5 %t3 %t3 %v1', 'R_CMOV SLT 4 #5 %t3 %t3 %v1 %t0', 'R_CMOV SLT 4 #5 %t3 %t3 %v1 #9', 'R_CMOV SLT 4 #5 $-16 %t0 #7', 'R_CMOV SLT 4 #5 $-16 %t0 #7 %t0', 'R_CMOV SLT 4 #5 $-16 %t0 #7 #9', 'R_CMOV SLT 4 #5 $-16 %t0 %t2', 'R_CMOV SLT 4 #5 $-16 %t0 %t2 %t0', 'R_CMOV SLT 4 #5 $-16 %t0 %t2 #9', 'R_CMOV SLT 4 #5 $-16 %t0 %v1', 'R_CMOV SLT 4 #5 $-16 %t0 %v1 %t0', 'R_CMOV SLT 4 #5 $-16 %t0 %v1 #9', 'R_CMOV SLT 4 #5 $-16 %t3 #7', 'R_CMOV SLT 4 #5 $-16 %t3 #7 %t0', 'R_CMOV SLT 4 #5 $-16 %t3 #7 #9', 'R_CMOV SLT 4 #5 $-16 %t3 %t2', 'R_CMOV SLT 4 #5 $-16 %t3 %t2 %t0', 'R_CMOV SLT 4 #5 $-16 %t3 %t2 #9', 'R_CMOV SLT 4 #5 $-16 %t3 %v1', 'R_CMOV SLT 4 #5 $-16 %t3 %v1 %t0', 'R_CMOV SLT 4 #5 $-16 %t3 %v1 #9', 'R_CMOV SLT 8 %t0 %t3 %t0 #7', 'R_CMOV SLT 8 %t0 %t3 %t0 #7 %t0', 'R_CMOV SLT 8 %t0 %t3 %t0 #7 #9', 'R_CMOV SLT 8 %t0 %t3 %t0 %t2', 'R_CMOV SLT 8 %t0 %t3 %t0 %t2 %t0', 'R_CMOV SLT 8 %t0 %t3 %t0 %t2 #9', 'R_CMOV SLT 8 %t0 %t3 %t0 %v1', 'R_CMOV SLT 8 %t0 %t3 %t0 %v1 %t0', 'R_CMOV SLT 8 %t0 %t3 %t0 %v1 #9', 'R_CMOV SLT 8 %t0 %t3 %t3 #7', 'R_CMOV SLT 8 %t0 %t3 %t3 #7 %t0', 'R_CMOV SLT 8 %t0 %t3 %t3 #7 #9', 'R_CMOV SLT 8 %t0 %t3 %t3 %t2', 'R_CMOV SLT 8 %t0 %t3 %t3 %t2 %t0', 'R_CMOV SLT 8 %t0 %t3 %t3 %t2 #9', 'R_CMOV SLT 8 %t0 %t3 %t3 %v1', 'R_CMOV SLT 8 %t0 %t3 %t3 %v1 %t0', 'R_CMOV SLT 8 %t0 %t3 %t3 %v1 #9', 'R_CMOV SLT 8 %t0 $-16 %t0 #7', 'R_CMOV SLT 8 %t0 $-16 %t0 #7 %t0', 'R_CMOV SLT 8 %t0 $-16 %t0 #7 #9', 'R_CMOV SLT 8 %t0 $-16 %t0 %t2', 'R_CMOV SLT 8 %t0 $-16 %t0 %t2 %t0', 'R_CMOV SLT 8 %t0 $-16 %t0 %t2 #9', 'R_CMOV SLT 8 %t0 $-16 %t0 %v1', 'R_CMOV SLT 8 %t0 $-16 %t0 %v1 %t0', 'R_CMOV SLT 8 %t0 $-16 %t0 %v1 #9', 'R_CMOV SLT 8 %t0 $-16 %t3 #7', 'R_CMOV SLT 8 %t0 $-16 %t3 #7 %t0', 'R_CMOV SLT 8 %t0 $-16 %t3 #7 #9', 'R_CMOV SLT 8 %t0 $-16 %t3 %t2', 'R_CMOV SLT 8 %t0 $-16 %t3 %t2 %t0', 'R_CMOV SLT 8 %t0 $-16 %t3 %t2 #9', 'R_CMOV SLT 8 %t0 $-16 %t3 %v1', 'R_CMOV SLT 8 %t0 $-16 %t3 %v1 %t0', 'R_CMOV SLT 8 %t0 $-16 %t3 %v1 #9', 'R_CMOV SLT 8 #5 %t3 %t0 #7', 'R_CMOV SLT 8 #5 %t3 %t0 #7 %t0', 'R_CMOV SLT 8 #5 %t3 %t0 #7 #9', 'R_CMOV SLT 8 #5 %t3 %t0 %t2', 'R_CMOV SLT 8 #5 %t3 %t0 %t2 %t0', 'R_CMOV SLT 8 #5 %t3 %t0 %t2 #9', 'R_CMOV SLT 8 #5 %t3 %t0 %v1', 'R_CMOV SLT 8 #5 %t3 %t0 %v1 %t0', 'R_CMOV SLT 8 #5 %t3 %t0 %v1 #9', 'R_CMOV SLT 8 #5 %t3 %t3 #7', 'R_CMOV SLT 8 #5 %t3 %t3 #7 %t0', 'R_CMOV SLT 8 #5 %t3 %t3 #7 #9', 'R_CMOV SLT 8 #5 %t3 %t3 %t2', 'R_CMOV SLT 8 #5 %t3 %t3 %t2 %t0', 'R_CMOV SLT 8 #5 %t3 %t3 %t2 #9', 'R_CMOV SLT 8 #5 %t3 %t3 %v1', 'R_CMOV SLT 8 #5 %t3 %t3 %v1 %t0', 'R_CMOV SLT 8 #5 %t3 %t3 %v1 #9', 'R_CMOV SLT 8 #5 $-16 %t0 #7', 'R_CMOV SLT 8 #5 $-16 %t0 #7 %t0', 'R_CMOV SLT 8 #5 $-16 %t0 #7 #9', 'R_CMOV SLT 8 #5 $-16 %t0 %t2', 'R_CMOV SLT 8 #5 $-16 %t0 %t2 %t0', 'R_CMOV SLT 8 #5 $-16 %t0 %t2 #9', 'R_CMOV SLT 8 #5 $-16 %t0 %v1', 'R_CMOV SLT 8 #5 $-16 %t0 %v1 %t0', 'R_CMOV SLT 8 #5 $-16 %t0 %v1 #9', 'R_CMOV SLT 8 #5 $-16 %t3 #7', 'R_CMOV SLT 8 #5 $-16 %t3 #7 %t0', 'R_CMOV SLT 8 #5 $-16 %t3 #7 #9', 'R_CMOV SLT 8 #5 $-16 %t3 %t2', 'R_CMOV SLT 8 #5 $-16 %t3 %t2 %t0', 'R_CMOV SLT 8 #5 $-16 %t3 %t2 #9', 'R_CMOV SLT 8 #5 $-16 %t3 %v1', 'R_CMOV SLT 8 #5 $-16 %t3 %v1 %t0', 'R_CMOV SLT 8 #5 $-16 %t3 %v1 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 #7', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 #7 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 #7 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %t2', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %t2 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %t2 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %v1', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %v1 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t0 %v1 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 #7', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 #7 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 #7 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %t2', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %t2 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %t2 #9', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %v1', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %v1 %t0', 'R_CMOV LT_EQ 4 %t0 %t3 %t3 %v1 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 #7', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 #7 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 #7 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %t2', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %t2 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %t2 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %v1', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %v1 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t0 %v1 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 #7', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 #7 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 #7 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %t2', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %t2 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %t2 #9', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %v1', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %v1 %t0', 'R_CMOV LT_EQ 4 %t0 $-16 %t3 %v1 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t0 #7', 'R_CMOV LT_EQ 4 #5 %t3 %t0 #7 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t0 #7 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %t2', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %t2 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %t2 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %v1', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %v1 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t0 %v1 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t3 #7', 'R_CMOV LT_EQ 4 #5 %t3 %t3 #7 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t3 #7 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %t2', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %t2 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %t2 #9', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %v1', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %v1 %t0', 'R_CMOV LT_EQ 4 #5 %t3 %t3 %v1 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t0 #7', 'R_CMOV LT_EQ 4 #5 $-16 %t0 #7 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t0 #7 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %t2', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %t2 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %t2 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %v1', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %v1 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t0 %v1 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t3 #7', 'R_CMOV LT_EQ 4 #5 $-16 %t3 #7 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t3 #7 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %t2', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %t2 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %t2 #9', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %v1', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %v1 %t0', 'R_CMOV LT_EQ 4 #5 $-16 %t3 %v1 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 #7', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 #7 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 #7 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %t2', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %t2 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %t2 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %v1', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %v1 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t0 %v1 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 #7', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 #7 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 #7 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %t2', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %t2 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %t2 #9', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %v1', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %v1 %t0', 'R_CMOV LT_EQ 8 %t0 %t3 %t3 %v1 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 #7', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 #7 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 #7 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %t2', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %t2 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %t2 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %v1', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %v1 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t0 %v1 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 #7', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 #7 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 #7 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %t2', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %t2 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %t2 #9', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %v1', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %v1 %t0', 'R_CMOV LT_EQ 8 %t0 $-16 %t3 %v1 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t0 #7', 'R_CMOV LT_EQ 8 #5 %t3 %t0 #7 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t0 #7 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %t2', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %t2 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %t2 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %v1', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %v1 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t0 %v1 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t3 #7', 'R_CMOV LT_EQ 8 #5 %t3 %t3 #7 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t3 #7 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %t2', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %t2 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %t2 #9', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %v1', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %v1 %t0', 'R_CMOV LT_EQ 8 #5 %t3 %t3 %v1 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t0 #7', 'R_CMOV LT_EQ 8 #5 $-16 %t0 #7 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t0 #7 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %t2', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %t2 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %t2 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %v1', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %v1 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t0 %v1 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t3 #7', 'R_CMOV LT_EQ 8 #5 $-16 %t3 #7 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t3 #7 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %t2', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %t2 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %t2 #9', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %v1', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %v1 %t0', 'R_CMOV LT_EQ 8 #5 $-16 %t3 %v1 #9'],
    "o": ["ADD", "SUB", "MUL", "AND", "OR", "BAND", "BOR", "BXOR", "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "SLT", "SLT_EQ", "SGT", "SGT_EQ", "SHL", "SHR", "SAR", "ROTL", "ROTR", "BAD"],
    "k": ["%t0", "%t3", "%v1", "$-16", "#5", "#3", "#6", "#255", "#65535", "#4294967295", "#4294967297", "#4294967296", "#2147483647", "#-3", "%s"],
    "f": ["ADD", "SUB", "MUL", "DIV", "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "BAD"],
    "F": ["ADD", "SUB", "MUL", "DIV", "BAD"],
    "E": ["%x1", "%x5", "%y2", "%t0", "#5", "#4611686018427387904", "#1073741824", "$-8", "%z1"],
    "H": ["INC", "DEC", "ADD", "SUB", "MUL"],
    "P": ["ROTL", "ROTR", "SHL", "SHR", "SAR", "BAD"],
    "B": [False, True],
}
FIELD = {"X86Emit": "emit", "X86Calls": "calls", "X86Generator": "gen", "X86RegForm": "regForm", "X86CasesA": "casesA", "X86CasesB": "casesB"}
# NAME: (setups, [(class, method, kinds, throws, cap)])
SPECS = {
    "emit28": ([0, 4, 8, 17, 18, 19, 21, 24, 29, 30, 31, 32], [
        ("X86RegForm", "emitCases7", "7BZ", True, 600, "B")]),
    "emit27": ([0, 4, 8, 17, 18, 20, 21], [
        ("X86RegForm", "emitCases6", "6BZ", True, 1500, "B")]),
    "emit26": ([0, 4, 22, 26, 27, 28], [
        ("X86CasesB", "emitCases5", "5BZ", True, 1500, "B")]),
    "emit25": ([0, 4, 25], [
        ("X86CasesB", "emitCases4", "4BZ", True, 2000, "B")]),
    "emit24": ([0, 4], [
        ("X86CasesA", "emitCases3", "3BZ", True, 1500, "B")]),
    "emit23": ([0, 4, 5, 6, 15, 16, 24], [
        ("X86CasesA", "emitCases2", "2bz", True, 400, "B")]),
    "emit22": ([0, 4, 22, 23], [
        ("X86Calls", "emitCases1", "1BZ", True, 1200, "B")]),
    "emit21": ([0, 4], [
        ("X86Emit", "emitNewRepack", "Ir", True, 40), ("X86Emit", "emitAssignRepack", "Iu", True, 40)]),
    "emit20": ([0, 17, 18, 19, 20, 21], [
        ("X86Calls", "resolveStackArgBytesAndCall", "BT", True, 16)]),
    "emit19": ([0, 4], [
        ("X86Generator", "rfCmov", "q", True, 300)]),
    "emit18": ([0, 4, 16], [
        ("X86Generator", "rfFloat", "BfWJEET", True, 900)]),
    "emit17": ([0, 4, 15], [
        ("X86Generator", "rfBin", "oWJkk", True, 2200)]),
    "emit16": ([0, 4, 16], [
        ("X86Generator", "rfFloatX", "FWVEE", True, 700)]),
    "emit15": ([0, 4], [
        ("X86Generator", "rfLea", "JAKCI", True, 900)]),
    "emit14": ([0, 4], [
        ("X86RegForm", "rfDivConst", "BWJKG", True, 700)]),
    "emit13": ([0, 4], [
        ("X86Generator", "rfRmw", "HAK", True, 300)]),
    "emit12": ([0, 4, 15], [
        ("X86RegForm", "rfShift", "PWJJK", True, 400), ("X86Emit", "emitShiftCore", "PWR", True, 60)]),
    "emit11": ([0, 4], [
        ("X86RegForm", "rfRotate", "PWJJK", True, 400)]),
    "emit10": ([0, 4, 13, 14], [
        ("X86Generator", "rfBrc", "OJKT", True, 120, "S"), ("X86Generator", "rfBrcNarrow", "OWJKT", True, 160, "S"),
        ("X86Generator", "rfMulByConst", "AC", True, 80, "B"), ("X86Generator", "rfMulByBigConst", "AJI", True, 100, "B"),
        ("X86Generator", "rfStore", "WAK", True, 150), ("X86Generator", "emitFloatPool", "", True, 1)]),
    "emit9": ([0, 12, 4], [
        ("X86Generator", "rfIndexedBase", "AR", True, 40, "S"), ("X86Generator", "rfSetIndex", "AJC", True, 60),
        ("X86Generator", "rfLoadIndexed", "WJAJCI", True, 90), ("X86Generator", "rfStoreIndexed", "WAJCKI", True, 90),
        ("X86Generator", "rfLoad", "WJK", True, 60), ("X86Generator", "rfLoadXIndexed", "WVAJCI", True, 90), ("X86Generator", "rfStoreXIndexed", "WAJCVI", True, 90),
        ("X86Generator", "rfLoadXmm", "MXW", True, 60), ("X86Generator", "rfUn", "UWJK", True, 80)]),
    "emit8": ([0, 4, 11], [
        ("X86RegForm", "rfMov", "KK", True, 120), ("X86RegForm", "emitResizeWideFillCopy", "YN", True, 30), ("X86RegForm", "emitDropGlueCall", "D", True, 4)]),
    "emit7": ([0, 9, 10, 4], [
        ("X86Emit", "pushBlockFromFrame", "IZ", True, 30), ("X86Emit", "storeConstructionOrPlainPush", "RW", True, 30),
        ("X86Emit", "copyBytes", "RIRIQ", True, 60), ("X86Emit", "copyBytesR12ToR14", "IIQ", True, 30), ("X86Emit", "emitStorageDirective", "ZI", True, 40),
        ("X86Emit", "emitShiftBmi2", "TWRR", True, 40), ("X86Emit", "pushBlockFromFrameConstructionAware", "IZ", True, 30),
        ("X86Emit", "assignBlock", "Z", True, 12), ("X86Emit", "extractWordsFromReversedBlock", "IW", True, 40), ("X86Emit", "assignConstructionBlock", "Z", True, 12)]),
    "emit6": ([0, 1, 2, 4], [
        ("X86Generator", "rfAddrToReg", "RK", True, 30), ("X86Generator", "rfStoreDisp", "WJIK", True, 60), ("X86Generator", "rfLoadDisp", "WJJI", True, 60),
        ("X86Generator", "rfAddImm", "RI", True, 40), ("X86Generator", "rfMovImmKeepFlags", "RI", True, 40),
        ("X86Generator", "rfBrcMem", "OJJT", True, 60), ("X86Generator", "emitFunctionEpilogue", "", True, 1)]),
    "emit5": ([0, 4, 8], [
        ("X86RegForm", "rfOp2", "TTT", True, 20), ("X86RegForm", "rfRegToReg", "RR", True, 20), ("X86RegForm", "storeSizedToAddr_withOffsetImm", "RIIW", True, 30),
        ("X86RegForm", "storeSizedToAddr_reg", "RIRW", True, 30), ("X86RegForm", "emitMallocCall", "I", True, 13), ("X86RegForm", "emitGtCall", "TR", True, 30),
        ("X86RegForm", "emitResizeGtUpdate", "", True, 1), ("X86RegForm", "gtSaveLoadedArgRegs", "", True, 1, "L"),
        ("X86RegForm", "gtRestoreLoadedArgRegs", "L", True, 6), ("X86RegForm", "rfSrc", "K", True, 13, "S")]),
    "emit4": ([0, 1, 2, 4], [
        ("X86Generator", "rfLoadXDisp", "XAIW", True, 40), ("X86Generator", "rfStoreXDisp", "AIXW", True, 40),
        ("X86Generator", "rfLoadX", "XAW", True, 40), ("X86Generator", "rfStoreX", "AXW", True, 40),
        ("X86Generator", "rfMovaps", "XX", True, 16), ("X86Generator", "rfSetcc", "TR", True, 30),
        ("X86Generator", "rfExtend", "RWB", True, 40), ("X86Generator", "rfGprToXmm", "XRW", True, 30), ("X86Generator", "rfXmmToGpr", "XRW", True, 30),
        ("X86Generator", "movRegToRegRbpFromRsp", "", True, 1)]),
    "emit3": ([0, 5, 6, 7], [
        ("X86Generator", "xvSpillAll", "", True, 1), ("X86Generator", "xvReloadAll", "", True, 1),
        ("X86Calls", "movRegToXmm", "RR", True, 20), ("X86Calls", "movXmmToReg", "RR", True, 20), ("X86Calls", "emitStackArgRestore", "I", True, 13),
        ("X86Calls", "emitCallByName", "T", True, 8), ("X86Calls", "emitCallIndirect", "R", True, 8), ("X86Calls", "emitCallTarget", "BT", True, 16),
        ("X86Calls", "emitAlignedCallByName", "T", True, 8), ("X86Calls", "emitAlignedCall", "BT", True, 16),
        ("X86Calls", "reverseStackArgWords", "I", True, 13), ("X86Calls", "pushSizedOrBlockFromAddr", "RW", True, 40)]),
    "emit2": ([0, 1, 2], [
        ("X86Emit", "loadSizedFromFrame", "RIW", True, 60), ("X86Emit", "storeSizedToFrame", "RIW", True, 60),
        ("X86Emit", "loadSizedFromAddr", "RRW", True, 60), ("X86Emit", "storeSizedToAddr", "RRW", True, 60),
        ("X86Emit", "signExtendReg", "RW", True, 60), ("X86Emit", "zeroExtendReg", "RW", True, 60),
        ("X86Emit", "movImmToReg", "RI", True, 80), ("X86Emit", "rotReg", "RBW", True, 40), ("X86Emit", "storeOddSize", "RRIW", True, 80)]),
}
def jlit(k, v):
    if k in "1234567": return 'stmt("%s").get(0).text, stmt("%s"), stmt("%s").get(0).text' % (v, v, v)
    if k in "qru": return 'stmt("%s")' % v
    if k == "L": return 'lst("%s")' % v
    if k in "RTAXKLJODVMUPHFEokfqru": return '"%s"' % v.replace("\\", "\\\\").replace('"', '\\"')
    if k in "IQZCGz": return "%dL" % v
    if k in "WNY": return "%d" % v
    return "true" if v else "false"
def clit(k, v):
    if k in "1234567": return '"%s"' % v
    if k in "RTAXKLJODVMUPHFEokfqru": return '"%s"' % v.replace("\\", "\\\\").replace('"', '\\"')
    if k in "IQZCGz": return ("(%d)" % v) if v < 0 else str(v)
    if k in "WNY": return "%d" % v
    return "true" if v else "false"
def combos(kinds, cap, rnd):
    allc = list(itertools.product(*[POOL[k] for k in kinds]))
    if len(allc) > cap: allc = rnd.sample(allc, cap)
    return allc
def main():
    name = sys.argv[1]
    setups, methods = SPECS[name]
    rnd = random.Random(7)
    cases = []
    for spec in methods:
        (cls, m, kinds, thr, cap) = spec[:5]
        for su in setups:
            for c in combos(kinds, cap, rnd):
                if m == 'reverseStackArgWords' and c[0] > 1 << 20: continue  # a huge count is a heap-exhaustion test of the JVM, not of the code
                cases.append((cls, m, kinds, su, c))
    # --- Java harness
    J = ["package caspien.codegen.x86backend;", "import caspien.codegen.CodegenTarget;", "import java.lang.reflect.Method;", "import caspien.bytecode.*;",
         "public class GenEmit {",
         "  static BytecodeStatement stmt(String t) { BytecodeStatement s = new BytecodeStatement(); for (String x : t.split(\" \")) s.add(new BytecodeToken(x, \"t\", 1, BytecodeTokenKind.CODE)); return s; }",
         "  static String q(String s) { return \"\\\"\" + s.replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\").replace(\"\\n\", \"\\\\n\") + \"\\\"\"; }",
         "  static java.util.List<String> lst(String t) { java.util.List<String> l = new java.util.ArrayList<>(); if (!t.isEmpty()) for (String x : t.split(\",\")) l.add(x); return l; }",
         "  static String tail(X86Backend b) { StringBuilder s = new StringBuilder(); if (b.core.asmSkipUntilIndex != -1) s.append(\";as=\" + b.core.asmSkipUntilIndex); if (!b.core.loadedArgRegsStack.isEmpty()) s.append(\";ls=\" + b.core.loadedArgRegsStack.size() + \":\" + String.join(\",\", b.core.loadedArgRegsStack.peek())); if (!b.core.savedOuterArgRegsStack.isEmpty()) s.append(\";ss=\" + b.core.savedOuterArgRegsStack.size() + \":\" + String.join(\",\", b.core.savedOuterArgRegsStack.peek())); if (b.core.callBufferStack.size() != 0) s.append(\";cn=\" + b.core.callBufferStack.size()); return s.toString(); }",
         "  static String run(int su, Class<?> c, String field, String name, Object... args) {",
         "    X86Backend b = new X86Backend((su == 4 || su == 6 || su == 14 || su == 21) ? CodegenTarget.WINDOWS_GNU_X64 : CodegenTarget.LINUX_X64, su == 15); if (su == 16) b.core.avx = true;",
         "    if (su == 1) { b.core.rfIdxReg = \"rcx\"; b.core.rfIdxScale = 4; b.core.rfIdxDisp = 8; }",
         "    if (su == 2) b.core.rfMemOverride = \"16(%rax)\";",
         "    if (su == 5 || su == 6) { b.core.xvHome.put(0, -16L); b.core.xvHome.put(2, -40L); b.core.xvHome.put(1, 8L); b.core.xvWidth.put(0, 8); b.core.xvWidth.put(2, 4); }",
         "    if (su == 7) b.core.pendingVarargsXmmCount = 3;",
         "    if (su == 12) { b.core.strideKeySink = b.core.out; b.core.strideKeyIdx = \"rax\"; b.core.strideKeyScale = 3; }",
         "    if (su == 11) b.core.resizeExtraStack = 24;",
         "    if (su >= 17 && su <= 21) { if (su != 19) b.core.callBufferStack.push(new StringBuilder(\"BUF\\n\")); b.core.argTrackDeltaStack.push(su == 18 ? 0L : (su == 20 ? 40L : 24L)); if (su == 20) b.core.pendingVarargsXmmCount = 3; }",
         "    if (su == 13 || su == 14) { b.core.floatPool.put(\"4:1065353216\", \".LFC0\"); b.core.floatPool.put(\"8:4611686018427387904\", \".LFC1\"); b.core.floatPool.put(\"8:-1\", \".LFC2\"); b.core.floatPool.put(\"4:0\", \".LFC3\"); }",
         "    if (su == 22) { b.core.stringLiterals.put(\"str0\", \"x\"); b.core.globalsSizeInit.put(\"gv\", new long[]{8}); b.core.declaredFunctionNames.add(\"fn1\"); b.core.globalAliasOffset.put(\"ga\", 8L); b.core.globalAliasParent.put(\"ga\", \"par\"); }",
         "    if (su == 24) b.core.floatResultInR10 = true;",
         "    if (su == 25) b.core.packedNewLines.add(b.core.currentLineIndex);",
         "    if (su >= 26 && su <= 28) { java.util.List<BytecodeStatement> al = new java.util.ArrayList<>(); al.add(stmt(\"PUSH 8 $-8\")); al.add(stmt(\"PUSH 8 \" + (su == 26 ? 7 : su == 27 ? 13 : 64))); al.add(stmt(\"PUSH 8 $-16\")); b.core.allLines = al; b.core.currentLineIndex = 3; }",
         "    if (su == 29 || su == 30) { java.util.List<BytecodeStatement> al = new java.util.ArrayList<>(); al.add(stmt(\"ASM_START\")); al.add(stmt(\"movq $1, %rax\")); al.add(stmt(\"nop x y\")); if (su == 29) al.add(stmt(\"ASM_END\")); al.add(stmt(\"PUSH 8 1\")); b.core.allLines = al; b.core.currentLineIndex = 0; }",
         "    if (su == 31 || su == 32) { b.core.callBufferStack.push(new StringBuilder(\"BUF\\n\")); b.core.argTrackDeltaStack.push(5L); b.core.loadedArgRegsStack.push(new java.util.ArrayList<>(java.util.List.of(\"i0\"))); b.core.savedOuterArgRegsStack.push(new java.util.ArrayList<>(su == 31 ? java.util.List.of(\"f0\", \"i1\", \"f2\") : java.util.List.of(\"i2\", \"i0\"))); }",
         "    if (su == 23) b.core.constructionScopedAssign.add(b.core.currentLineIndex);",
         "    if (su == 9) b.core.pushStoreOffsetByLine.put(b.core.currentLineIndex, 16L);",
         "    if (su == 10) b.core.pushStoreOffsetByLine.put(b.core.currentLineIndex, 0L);",
         "    if (su == 8) b.core.loadedArgRegsStack.push(new java.util.ArrayList<>(java.util.List.of(\"i0\", \"f1\", \"i2\")));",
         "    if (su == 3) { b.core.globalAliasOffset.put(\"g.a\", 24L); b.core.globalAliasParent.put(\"g.a\", \"par.ent\"); }",
         "    try { Object inst = b.getClass().getDeclaredField(field).get(b);",
         "      for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); Object rv = m.invoke(inst, args); return b.core.out.toString() + (rv == null ? \"\" : \"|ret=\" + rv) + \"|d=\" + b.core.argTrackSuspendDepth + \";v=\" + b.core.pendingVarargsXmmCount + \";m=\" + b.core.rfVarMask + \";u=\" + b.core.rfVarUsed + \";ir=\" + b.core.rfIdxReg + \";is=\" + b.core.rfIdxScale + \";id=\" + b.core.rfIdxDisp + \";sh=\" + b.core.strideCseHits + \";sk=\" + b.core.strideKeyScale + \";mo=\" + b.core.rfMemOverride + (b.core.callBufferStack.isEmpty() ? \"\" : \";cb=\" + b.core.callBufferStack.peek().toString().replace(\"\\n\", \"/\")) + (b.core.argTrackDeltaStack.isEmpty() ? \"\" : \";dt=\" + b.core.argTrackDeltaStack.peek()) + (b.core.lastValueBlockSize != 0 ? \";lv=\" + b.core.lastValueBlockSize : \"\") + (b.core.csrFuncStart != -1 ? \";cf=\" + b.core.csrFuncStart : \"\") + (b.core.csrAllocPos != -1 ? \";cp=\" + b.core.csrAllocPos + \":\" + b.core.csrAllocBytes : \"\") + (b.core.csrAllocSum != 0 ? \";cs=\" + b.core.csrAllocSum : \"\") + (b.core.csrAllocLine != null ? \";cl=\" + b.core.csrAllocLine.replace(\"\\n\", \"/\") : \"\") + (b.core.currentFuncName != null ? \";fn=\" + b.core.currentFuncName : \"\") + (b.core.rfClobberSeen ? \";cz\" : \"\") + (b.core.lastWasCmp ? \";wc\" : \"\") + (b.core.pushedWordWithTag ? \";pt\" : \"\") + (b.core.floatResultInR10 ? \";fr\" : \"\") + (b.core.xvHome.isEmpty() ? \"\" : \";xv=\" + b.core.xvHome.size()) + tail(b); }",
         "      return \"NOMETHOD\"; } catch (Exception e) { return \"EX\"; } }",
         ]
    for ci, (cls, m, kinds, su, c) in enumerate(cases):
        if ci % 80 == 0:
            if ci: J.append("  }")
            J.append("  static void p%d() {" % (ci // 80))
        args = ", ".join(jlit(k, v) for k, v in zip(kinds, c))
        label = "%s %d %s" % (m, su, " ".join(str(v) for v in c))
        cargs = "".join(", " + clit(k, v) for k, v in zip(kinds, c))
        J.append('    System.out.println("\\tc_%s(" + q(%s) + ", %d%s, " + q(run(%d, %s.class, "%s", "%s"%s)) + ")");' % (
            m, jlit("R", label), su, cargs.replace("\\", "\\\\").replace('"', '\\"'), su, cls, FIELD[cls], m, (", " + args) if args else ""))
    J.append("  }")
    J.append("  public static void main(String[] a) { " + " ".join("p%d();" % i for i in range((len(cases) + 79) // 80)) + " } }")
    d = tempfile.mkdtemp()
    os.makedirs(d + "/caspien/codegen/x86backend")
    open(d + "/caspien/codegen/x86backend/GenEmit.java", "w").write("\n".join(J))
    out = d + "/cls"; os.makedirs(out)
    cp = ROOT + "/Codegen/out"
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    subprocess.check_call(["javac", "-cp", cp, "-d", out, d + "/caspien/codegen/x86backend/GenEmit.java"], env=env)
    lines = subprocess.check_output(["java", "-cp", cp + ":" + out, "caspien.codegen.x86backend.GenEmit"], env=env).decode().split("\n")
    shutil.rmtree(d, ignore_errors=True)
    lines = [l for l in lines if l.strip()]
    # --- Caspien test
    T = '''// Differential test of converted %s methods against the Java originals (generated by gen_codegen_emit_test.py %s).
import "../stdlib/libc.caspien"
import "../stdlib/gt/*"
import "../Codegen/src/main/java/caspien/codegen/x86backend/X86Backend.caspien"

func check(name: static imut string, ok: imut bool) void{
	unsafe extern{
		if ok{ printf("PASS %%s\\n", name) }
		else{ printf("FAIL %%s\\n", name) }
	}
}

func xvSetup(b: ref imut X86Backend) void{
	?catch(e){ return }
	match Some(b){ match Some(b.core){ ? b.core.xvHome.set(b.core.xvHome, 0, -16) } }
	match Some(b){ match Some(b.core){ ? b.core.xvHome.set(b.core.xvHome, 2, -40) } }
	match Some(b){ match Some(b.core){ ? b.core.xvHome.set(b.core.xvHome, 1, 8) } }
	match Some(b){ match Some(b.core){ ? b.core.xvWidth.set(b.core.xvWidth, 0, 8) } }
	match Some(b){ match Some(b.core){ ? b.core.xvWidth.set(b.core.xvWidth, 2, 4) } }
}

func stackSetup(b: ref mut X86Backend, su: mut s32) void{
	?catch(e){ return }
	if su != 19{
		let bf = mut ? String.from("BUF\\n")
		match Some(b){ match Some(b.core){ ? b.core.callBufferStack.add(b.core.callBufferStack, bf) } }
	}
	let:<mut s64> dv = mut 24
	if su == 18{
		dv = 0
	}
	if su == 20{
		dv = 40
		match Some(b){ match Some(b.core){ b.core.pendingVarargsXmmCount = 3 } }
	}
	match Some(b){ match Some(b.core){ ? b.core.argTrackDeltaStack.pushBack(b.core.argTrackDeltaStack, dv) } }
}

func poolSetup(b: ref imut X86Backend) void{
	?catch(e){ return }
	let k0 = mut ? String.from("4:1065353216")
	let v0 = mut ? String.from(".LFC0")
	let k1 = mut ? String.from("8:4611686018427387904")
	let v1 = mut ? String.from(".LFC1")
	let k2 = mut ? String.from("8:-1")
	let v2 = mut ? String.from(".LFC2")
	let k3 = mut ? String.from("4:0")
	let v3 = mut ? String.from(".LFC3")
	match Some(b){ match Some(b.core){ ? b.core.floatPool.put(b.core.floatPool, k0, v0) } }
	match Some(b){ match Some(b.core){ ? b.core.floatPool.put(b.core.floatPool, k1, v1) } }
	match Some(b){ match Some(b.core){ ? b.core.floatPool.put(b.core.floatPool, k2, v2) } }
	match Some(b){ match Some(b.core){ ? b.core.floatPool.put(b.core.floatPool, k3, v3) } }
}

func nb(su: mut s32, ll: ref mut ObjectList<BytecodeStatement>) owns mut X86Backend{
	?catch(e){ return null }
	if su == 4 || su == 6 || su == 14 || su == 21{
		let w = mut ? new X86Backend(CodegenTarget.WINDOWS_GNU_X64)
		? w.wire(w)
		if su == 21{
			stackSetup(w, su)
		}
		if su == 14{
			poolSetup(w)
		}
		if su == 6{
			xvSetup(w)
		}
		return w
	}
	let b = mut ? new X86Backend(CodegenTarget.LINUX_X64, su == 15)
	? b.wire(b)
	if su == 5{
		xvSetup(b)
	}
	if su >= 17 && su <= 20{
		stackSetup(b, su)
	}
	if su == 16{
		match Some(b){ match Some(b.core){ b.core.avx = true } }
	}
	if su == 9{
		match Some(b){ match Some(b.core){ b.core.pushStoreOffsetByLine.set(b.core.pushStoreOffsetByLine, 0, 16) } }
	}
	if su == 10{
		match Some(b){ match Some(b.core){ b.core.pushStoreOffsetByLine.set(b.core.pushStoreOffsetByLine, 0, 0) } }
	}
	if su == 11{
		match Some(b){ match Some(b.core){ b.core.resizeExtraStack = 24 } }
	}
	if su == 13{
		poolSetup(b)
	}
	if su == 12{
		let kx = mut ? String.from("rax")
		match Some(b){ match Some(b.core){ b.core.strideKeySink = ref b.core.out } }
		match Some(b){ match Some(b.core){ b.core.strideKeyIdx = kx } }
		match Some(b){ match Some(b.core){ b.core.strideKeyScale = 3 } }
	}
	if su == 8{
		let lst = mut ? new ObjectList:<String>()
		mkList(lst, "i0,f1,i2")
		match Some(b){ match Some(b.core){ ? b.core.loadedArgRegsStack.add(b.core.loadedArgRegsStack, lst) } }
	}
	if su == 7{
		match Some(b){ match Some(b.core){ b.core.pendingVarargsXmmCount = 3 } }
	}
	if su == 1{
		let ix = mut ? String.from("rcx")
		match Some(b){ match Some(b.core){
			b.core.rfIdxReg = ix
			b.core.rfIdxScale = 4
			b.core.rfIdxDisp = 8
		} }
	}
	if su == 2{
		let ov = mut ? String.from("16(%%rax)")
		match Some(b){ match Some(b.core){ b.core.rfMemOverride = ov } }
	}
	if su == 22{
		let s0 = mut ? String.from("str0")
		let s1 = mut ? String.from("x")
		let g0 = mut ? String.from("gv")
		let ga = mut ? new DynamicArray:<s64>()
		let f0 = mut ? String.from("fn1")
		let a0 = mut ? String.from("ga")
		let added = mut false
		let a1 = mut ? String.from("ga")
		let a2 = mut ? String.from("par")
		match Some(b){ match Some(b.core){
			? b.core.globalAliasParent.put(b.core.globalAliasParent, a1, a2)
			? b.core.stringLiterals.put(b.core.stringLiterals, s0, s1)
			? b.core.globalsSizeInit.put(b.core.globalsSizeInit, g0, ga)
			added = ? b.core.declaredFunctionNames.add(b.core.declaredFunctionNames, f0)
			? b.core.globalAliasOffset.put(b.core.globalAliasOffset, a0, 8)
		} }
	}
	if su == 24{
		match Some(b){ match Some(b.core){ b.core.floatResultInR10 = true } }
	}
	if su == 25{
		let added25 = mut false
		match Some(b){ match Some(b.core){ added25 = b.core.packedNewLines.add(b.core.packedNewLines, 0) } }
	}
	if su >= 26 && su <= 28{
		let st1 = mut mkStmt("PUSH 8 $-8")
		let st3 = mut mkStmt("PUSH 8 $-16")
		let st2 = mut mkStmt("PUSH 8 7")
		if su == 27{
			st2 = mkStmt("PUSH 8 13")
		}
		if su == 28{
			st2 = mkStmt("PUSH 8 64")
		}
		match Some(ll){ ? ll.add(ll, st1) }
		match Some(ll){ ? ll.add(ll, st2) }
		match Some(ll){ ? ll.add(ll, st3) }
		match Some(b){ match Some(b.core){ b.core.currentLineIndex = 3 } }
	}
	if su == 29 || su == 30{
		let asm1 = mut mkStmt("ASM_START")
		let asm2 = mut mkStmt("movq $1, %%rax")
		let asm3 = mut mkStmt("nop x y")
		let asm4 = mut mkStmt("ASM_END")
		let asm5 = mut mkStmt("PUSH 8 1")
		match Some(ll){ ? ll.add(ll, asm1) }
		match Some(ll){ ? ll.add(ll, asm2) }
		match Some(ll){ ? ll.add(ll, asm3) }
		if su == 29{
			match Some(ll){ ? ll.add(ll, asm4) }
		}
		match Some(ll){ ? ll.add(ll, asm5) }
	}
	if su == 31 || su == 32{
		let bf31 = mut ? String.from("BUF\\n")
		let q31a = mut ? new ObjectList:<String>()
		let q31b = mut ? new ObjectList:<String>()
		mkList(q31a, "i0")
		if su == 31{
			mkList(q31b, "f0,i1,f2")
		} else{
			mkList(q31b, "i2,i0")
		}
		match Some(b){ match Some(b.core){
			? b.core.callBufferStack.add(b.core.callBufferStack, bf31)
			? b.core.argTrackDeltaStack.pushBack(b.core.argTrackDeltaStack, 5)
			? b.core.loadedArgRegsStack.add(b.core.loadedArgRegsStack, q31a)
			? b.core.savedOuterArgRegsStack.add(b.core.savedOuterArgRegsStack, q31b)
		} }
	}
	if su == 23{
		let added23 = mut false
		match Some(b){ match Some(b.core){ added23 = b.core.constructionScopedAssign.add(b.core.constructionScopedAssign, 0) } }
	}
	if su == 3{
		let k1 = mut ? String.from("g.a")
		let k2 = mut ? String.from("g.a")
		let v2 = mut ? String.from("par.ent")
		match Some(b){ match Some(b.core){
			? b.core.globalAliasOffset.put(b.core.globalAliasOffset, k1, 24)
			? b.core.globalAliasParent.put(b.core.globalAliasParent, k2, v2)
		} }
	}
	return b
}

func mkStmt(spec: static imut string) owns mut BytecodeStatement{
	?catch(e){ return null }
	let st = mut ? new BytecodeStatement()
	let t = mut ? new String(spec)
	let:<mut u64> n = mut 0
	match Some(t){ n = t.length(t) }
	let cur = mut ? new String()
	for i in 0..n{
		let c = mut ' '
		match Some(t){ c = t.charAt(t, i) }
		if c == ' '{
			let file = mut ? String.from("t")
			let tok = mut ? new BytecodeToken(cur, file, 1, BytecodeTokenKind.CODE)
			match Some(st){ ? st.items.add(st.items, tok) }
			cur = ? new String()
		} else{
			match Some(cur){ ? cur.appendChar(cur, c) }
		}
	}
	let file2 = mut ? String.from("t")
	let tok2 = mut ? new BytecodeToken(cur, file2, 1, BytecodeTokenKind.CODE)
	match Some(st){ ? st.items.add(st.items, tok2) }
	return st
}

func mkList(l: ref mut ObjectList<String>, spec: static imut string) void{
	?catch(e){ return }
	let t = mut ? new String(spec)
	let:<mut u64> n = mut 0
	match Some(t){ n = t.length(t) }
	if n == 0{ return }
	let cur = mut ? new String()
	for i in 0..n{
		let c = mut ' '
		match Some(t){ c = t.charAt(t, i) }
		if c == ','{
			match Some(l){ ? l.add(l, cur) }
			cur = ? new String()
		} else{
			match Some(cur){ ? cur.appendChar(cur, c) }
		}
	}
	match Some(l){ ? l.add(l, cur) }
}

func outOf(b: ref imut X86Backend) owns mut String{
	?catch(e){ return null }
	let r = mut ? new String()
	match Some(b){ match Some(b.core){ r = ? b.core.out.substring(b.core.out, 0) } }
	return r
}

func joinTop(l: ref imut ObjectList<String>, out: ref mut String) void{
	?catch(e){ return }
	let:<mut u64> n = mut 0
	match Some(l){ n = l.size(l) }
	for i in 0..n{
		if i > 0{
			match Some(out){ ? out.append(out, ",") }
		}
		match Some(l){
			let it = l.get(l, i)
			match Some(it){ match Some(out){ ? out.append(out, it) } }
		}
	}
}

func tailOf(b: ref imut X86Backend) owns mut String{
	?catch(e){ return null }
	let r = mut ? new String()
	let:<mut s64> as1 = mut -1
	let:<mut u64> ln = mut 0
	let:<mut u64> sn = mut 0
	let:<mut u64> cn = mut 0
	match Some(b){ match Some(b.core){
		as1 = wrap:<s64>(b.core.asmSkipUntilIndex)
		ln = b.core.loadedArgRegsStack.size(b.core.loadedArgRegsStack)
		sn = b.core.savedOuterArgRegsStack.size(b.core.savedOuterArgRegsStack)
		cn = b.core.callBufferStack.size(b.core.callBufferStack)
	} }
	if as1 != -1{
		? r.append(r, ";as=")
		? r.appendS64(r, as1)
	}
	if ln != 0{
		? r.append(r, ";ls=")
		? r.appendS64(r, wrap:<s64>(ln))
		? r.append(r, ":")
		match Some(b){ match Some(b.core){
			let top = b.core.loadedArgRegsStack.peek(b.core.loadedArgRegsStack)
			match Some(top){ joinTop(top, r) }
		} }
	}
	if sn != 0{
		? r.append(r, ";ss=")
		? r.appendS64(r, wrap:<s64>(sn))
		? r.append(r, ":")
		match Some(b){ match Some(b.core){
			let top2 = b.core.savedOuterArgRegsStack.peek(b.core.savedOuterArgRegsStack)
			match Some(top2){ joinTop(top2, r) }
		} }
	}
	if cn != 0{
		? r.append(r, ";cn=")
		? r.appendS64(r, wrap:<s64>(cn))
	}
	return r
}

func verdict(label: static imut string, thrown: imut bool, b: ref imut X86Backend, rv: ref imut String, want: static imut string) void{
	?catch(e){
		check(label, false)
		return
	}
	if thrown{
		check(label, String.literalsEqual(want, "EX"))
		return
	}
	let o = mut outOf(b)
	let full = mut ? new String()
	match Some(o){ ? full.append(full, o) }
	match Some(rv){ ? full.append(full, rv) }
	? full.append(full, "|d=")
	let dv = mut 0
	let:<mut s64> d = mut 0
	let:<mut s64> v = mut 0
	let:<mut s64> mk = mut 0
	let used = mut false
	match Some(b){ match Some(b.core){
		d = b.core.argTrackSuspendDepth as s64
		v = b.core.pendingVarargsXmmCount as s64
		mk = b.core.rfVarMask as s64
		used = b.core.rfVarUsed
	} }
	? full.appendS64(full, d)
	? full.append(full, ";v=")
	? full.appendS64(full, v)
	? full.append(full, ";m=")
	? full.appendS64(full, mk)
	if used{
		? full.append(full, ";u=true")
	} else{
		? full.append(full, ";u=false")
	}
	let:<mut s64> ixs = mut 0
	let:<mut s64> idd = mut 0
	let:<mut s64> sh = mut 0
	let:<mut s64> sk = mut 0
	match Some(b){ match Some(b.core){
		ixs = wrap:<s64>(b.core.rfIdxScale)
		idd = wrap:<s64>(b.core.rfIdxDisp)
		sh = wrap:<s64>(b.core.strideCseHits)
		sk = wrap:<s64>(b.core.strideKeyScale)
	} }
	? full.append(full, ";ir=")
	match Some(b){ match Some(b.core){
		match Some(b.core.rfIdxReg){ ? full.append(full, b.core.rfIdxReg) }
		else{ ? full.append(full, "null") }
	} }
	? full.append(full, ";is=")
	? full.appendS64(full, ixs)
	? full.append(full, ";id=")
	? full.appendS64(full, idd)
	? full.append(full, ";sh=")
	? full.appendS64(full, sh)
	? full.append(full, ";sk=")
	? full.appendS64(full, sk)
	? full.append(full, ";mo=")
	match Some(b){ match Some(b.core){
		match Some(b.core.rfMemOverride){ ? full.append(full, b.core.rfMemOverride) }
		else{ ? full.append(full, "null") }
	} }
	let hasCb = mut false
	let cp = mut ? new String()
	let cp0 = mut ? new String()
	match Some(b){ match Some(b.core){
		if b.core.callBufferStack.isEmpty(b.core.callBufferStack) == false{
			hasCb = true
			let top = b.core.callBufferStack.peek(b.core.callBufferStack)
			match Some(top){ cp0 = ? top.substring(top, 0) }
		}
	} }
	let:<mut u64> tl = mut 0
	match Some(cp0){ tl = cp0.length(cp0) }
	for ci in 0..tl{
		let cc = mut ' '
		match Some(cp0){ cc = cp0.charAt(cp0, ci) }
		if cc == '\\n'{
			? cp.appendChar(cp, '/')
		} else{
			? cp.appendChar(cp, cc)
		}
	}
	if hasCb{
		? full.append(full, ";cb=")
		? full.append(full, cp)
	}
	let hasDt = mut false
	let:<mut s64> dtop = mut 0
	match Some(b){ match Some(b.core){
		if len(b.core.argTrackDeltaStack.backing) > 0{
			hasDt = true
			let:<mut u64> li = mut 0
			li = len(b.core.argTrackDeltaStack.backing)
			li = li - 1
			match li in b.core.argTrackDeltaStack.backing{ dtop = b.core.argTrackDeltaStack.get(b.core.argTrackDeltaStack, li) }
		}
	} }
	if hasDt{
		? full.append(full, ";dt=")
		? full.appendS64(full, dtop)
	}
	let:<mut s64> lv = mut 0
	let:<mut s32> cf = mut 0
	let:<mut s32> cpos = mut 0
	let:<mut s64> cbytes = mut 0
	let:<mut s64> csum = mut 0
	let:<mut u64> xvn = mut 0
	let clobber = mut false
	let wcmp = mut false
	let ptag = mut false
	let frr = mut false
	match Some(b){ match Some(b.core){
		lv = b.core.lastValueBlockSize
		cf = b.core.csrFuncStart
		cpos = b.core.csrAllocPos
		cbytes = b.core.csrAllocBytes
		csum = b.core.csrAllocSum
		xvn = b.core.xvHome.size(b.core.xvHome)
		if b.core.rfClobberSeen{ clobber = true }
		if b.core.lastWasCmp{ wcmp = true }
		if b.core.pushedWordWithTag{ ptag = true }
		if b.core.floatResultInR10{ frr = true }
	} }
	if lv != 0{
		? full.append(full, ";lv=")
		? full.appendS64(full, lv)
	}
	if cf != -1{
		? full.append(full, ";cf=")
		? full.appendS64(full, wrap:<s64>(cf))
	}
	if cpos != -1{
		? full.append(full, ";cp=")
		? full.appendS64(full, wrap:<s64>(cpos))
		? full.append(full, ":")
		? full.appendS64(full, cbytes)
	}
	if csum != 0{
		? full.append(full, ";cs=")
		? full.appendS64(full, csum)
	}
	let hasCal = mut false
	let cal = mut ? new String()
	let hasFn = mut false
	let fnm = mut ? new String()
	match Some(b){ match Some(b.core){
		match Some(b.core.csrAllocLine){
			hasCal = true
			cal = ? b.core.csrAllocLine.substring(b.core.csrAllocLine, 0)
		}
	} }
	match Some(b){ match Some(b.core){
		match Some(b.core.currentFuncName){
			hasFn = true
			fnm = ? b.core.currentFuncName.substring(b.core.currentFuncName, 0)
		}
	} }
	if hasCal{
		? full.append(full, ";cl=")
		let:<mut u64> cln = mut 0
		match Some(cal){ cln = cal.length(cal) }
		for cli in 0..cln{
			let ch = mut ' '
			match Some(cal){ ch = cal.charAt(cal, cli) }
			if ch == '\\n'{
				? full.appendChar(full, '/')
			} else{
				? full.appendChar(full, ch)
			}
		}
	}
	if hasFn{
		? full.append(full, ";fn=")
		match Some(fnm){ ? full.append(full, fnm) }
	}
	if clobber{ ? full.append(full, ";cz") }
	if wcmp{ ? full.append(full, ";wc") }
	if ptag{ ? full.append(full, ";pt") }
	if frr{ ? full.append(full, ";fr") }
	if xvn != 0{
		? full.append(full, ";xv=")
		? full.appendS64(full, wrap:<s64>(xvn))
	}
	let tl2 = mut tailOf(b)
	match Some(tl2){ ? full.append(full, tl2) }
	let ok = mut full.equals(full, want)
	check(label, ok)
}
''' % (name, name)
    seen = set()
    for spec in methods:
        (cls, m, kinds, thr, cap) = spec[:5]; ret = spec[5] if len(spec) > 5 else None
        if m in seen: continue
        seen.add(m)
        ps = ""; pre = ""; call = []
        for i, k in enumerate(kinds):
            if k == "L":
                ps += ", a%d: static imut string" % i; pre += "\tlet s%d = mut ? new ObjectList:<String>()\n\tmkList(s%d, a%d)\n" % (i, i, i); call.append("s%d" % i)
            elif k in "qru":
                ps += ", a%d: static imut string" % i; pre += "\tlet s%d = mut mkStmt(a%d)\n" % (i, i); call.append("s%d" % i)
            elif k in "1234567":
                ps += ", a%d: static imut string" % i; pre += "\tlet s%d = mut mkStmt(a%d)\n\tlet m%d = mut ? new String()\n\tmatch Some(s%d){ m%d = ? X86Core.lineTok(s%d, 0) }\n" % (i, i, i, i, i, i); call.append("m%d, s%d, m%d" % (i, i, i))
            elif k in "RTAXKJODVMUPHFEokf":
                ps += ", a%d: static imut string" % i; pre += "\tlet s%d = mut ? new String(a%d)\n" % (i, i); call.append("s%d" % i)
            elif k in "IQZCGz": ps += ", a%d: mut s64" % i; call.append("a%d" % i)
            elif k in "WNY": ps += ", a%d: mut s32" % i; call.append("a%d" % i)
            else: ps += ", a%d: mut bool" % i; call.append("a%d" % i)
        if ret == "S":
            decl = "\tlet rs = mut ? new String()\n"; asg = "rs = "
            post = "\tlet rv = mut ? String.from(\"|ret=\")\n\tmatch Some(rs){ ? rv.append(rv, rs) }\n"
        elif ret == "L":
            decl = "\tlet rl = mut ? new ObjectList:<String>()\n"; asg = "rl = "
            post = """	let rv = mut ? String.from("|ret=[")
	let:<mut u64> rn = mut 0
	match Some(rl){ rn = rl.size(rl) }
	for ri in 0..rn{
		if ri > 0{ ? rv.append(rv, ", ") }
		match Some(rl){
			let it = rl.get(rl, ri)
			match Some(it){ ? rv.append(rv, it) }
		}
	}
	? rv.append(rv, "]")
"""
        elif ret == "B":
            decl = "\tlet rb = mut false\n"; asg = "rb = "
            post = "\tlet rv = mut ? String.from(\"|ret=\")\n\tif rb{ ? rv.append(rv, \"true\") } else{ ? rv.append(rv, \"false\") }\n"
        else:
            decl = ""; asg = ""; post = "\tlet rv = mut ? new String()\n"
        T += '''
func c_%s(label: static imut string, su: mut s32%s, want: static imut string) void{
	?catch(ex){
		check(label, false)
		return
	}
	let thrown = mut false
	let ll = mut ? new ObjectList:<BytecodeStatement>()
	let b = mut nb(su, ll)
	if su >= 26 && su <= 30{
		match Some(b){ match Some(b.core){ b.core.allLines = ref ll } }
	}
%s%s%s	try{
		match Some(b){ match Some(b.%s){
			%stry b.%s.%s(b.%s%s) catch(e){
				thrown = true
				continue
			}
		} }
	}
%s	verdict(label, thrown, b, rv, want)
}
''' % (m, ps, pre, decl, "", FIELD[cls], asg, FIELD[cls], m, FIELD[cls], "".join(", " + c for c in call), post)
    parts = [lines[i:i + 100] for i in range(0, len(lines), 100)]
    for k, ps_ in enumerate(parts):
        T += "\nfunc part%d() void{\n%s\n}\n" % (k, "\n".join(ps_))
    T += "\nfunc main() void{\n" + "".join("\tpart%d()\n" % k for k in range(len(parts))) + "}\n"
    sys.stdout.write(T)
main()
