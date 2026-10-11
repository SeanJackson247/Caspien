// FASTA benchmark (Node / Bun, Uint8Array buffer). Same algorithm and output as fasta.c.
const ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
const CHARS = "ACGTBDHKMNRSVWYACGT";
const THR = [37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968];
const HDR = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"];
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 40000000;
const cnt = [Math.floor(n * 2 / 10), Math.floor(n * 3 / 10), 0];
cnt[2] = n - cnt[0] - cnt[1];
const off = [0, 0, 15];
let last = 42;
const buf = new Uint8Array(n + Math.floor(n / 60) + 1024);
const alu = Buffer.from(ALU), chars = Buffer.from(CHARS);
let pos = 0, ai = 0, a = 0, c = 0, g = 0, t = 0, other = 0;
for (let s = 0; s < 3; s++) {
  const hd = Buffer.from(HDR[s]);
  buf.set(hd, pos);
  pos += hd.length;
  let col = 0;
  const cs = cnt[s], o = off[s];
  for (let i = 0; i < cs; i++) {
    let ch;
    if (s === 0) {
      ch = alu[ai];
      if (++ai === 287) ai = 0;
    } else {
      last = (last * 3877 + 29573) % 139968;
      let j = o;
      while (last >= THR[j]) j++;
      ch = chars[j];
    }
    buf[pos++] = ch;
    if (ch === 65) a++; else if (ch === 67) c++; else if (ch === 71) g++; else if (ch === 84) t++; else other++;
    if (++col === 60) { buf[pos++] = 10; col = 0; }
  }
  if (col > 0) buf[pos++] = 10;
}
let h = 7;
for (let i = 0; i < pos; i++) h = (Math.imul(h, 31) + buf[i]) >>> 0;
console.log(`${pos} ${h} ${a} ${c} ${g} ${t} ${other}`);
