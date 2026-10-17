#!/bin/bash
# Run from the project root:  bash docs/c_interop/build_and_run.sh
# The compiler stops after code generation (--asm) and gcc links the result with the C file.
set -e
cd "$(dirname "$0")/../.."
mkdir -p output
java Compiler --asm -i docs/c_interop/interop.caspien output/interop.s
gcc output/interop.s docs/c_interop/helper.c -o output/interop -no-pie -pthread -lm
./output/interop
