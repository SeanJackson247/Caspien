// JSON serialise + parse benchmark (Node / Bun). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
// Records are parallel typed arrays (Float64Array holds every field exactly: all values are far below 2^53).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 4000000;
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x; }
const text = new Uint8Array(n * 80 + 16);
function wnum(p, v) {
  let d = 1; for (let u = v; u >= 10; u = Math.floor(u / 10)) d++;
  for (let k = d - 1; k >= 0; k--) { text[p + k] = 48 + (v % 10); v = Math.floor(v / 10); }
  return p + d;
}
function wlit(p, s) { for (let i = 0; i < s.length; i++) text[p++] = s.charCodeAt(i); return p; }
let p = 0;
text[p++] = 91;
for (let i = 0; i < n; i++) {
  if (i > 0) text[p++] = 44;
  p = wlit(p, '{"id":'); p = wnum(p, i);
  p = wlit(p, ',"name":"');
  const nl = 3 + (next() >>> 16) % 8;
  for (let k = 0; k < nl; k++) text[p++] = 97 + (next() >>> 16) % 26;
  p = wlit(p, '","score":');
  const cents = (next() >>> 8) % 1000000;
  p = wnum(p, Math.floor(cents / 100)); text[p++] = 46; text[p++] = 48 + Math.floor((cents % 100) / 10); text[p++] = 48 + cents % 10;
  p = wlit(p, ',"tags":[');
  const tc = (next() >>> 16) % 5;
  for (let k = 0; k < tc; k++) { if (k > 0) text[p++] = 44; p = wnum(p, (next() >>> 16) % 100); }
  p = wlit(p, "]}");
}
text[p++] = 93;
const tlen = p;
const rid = new Float64Array(n + 1), rnoff = new Float64Array(n + 1), rnlen = new Float64Array(n + 1);
const rscore = new Float64Array(n + 1), rtoff = new Float64Array(n + 1), rtcnt = new Float64Array(n + 1);
const names = new Uint8Array(n * 10 + 8), tags = new Uint8Array(n * 4 + 8);
let count = 0, noff = 0, toff = 0, q = 1;
for (;;) {
  const c = text[q];
  if (c === 93) break;
  if (c === 44) { q++; continue; }
  q++;
  let id = 0, nameOff = 0, nameLen = 0, score = 0, tagOff = 0, tagCnt = 0;
  for (;;) {
    q++;
    const k0 = text[q];
    while (text[q] !== 34) q++;
    q += 2;
    if (k0 === 105) {
      let v = 0; while (text[q] >= 48 && text[q] <= 57) { v = v * 10 + (text[q] - 48); q++; }
      id = v;
    } else if (k0 === 110) {
      q++;
      nameOff = noff;
      while (text[q] !== 34) { names[noff++] = text[q]; q++; }
      nameLen = noff - nameOff;
      q++;
    } else if (k0 === 115) {
      let v = 0; while (text[q] >= 48 && text[q] <= 57) { v = v * 10 + (text[q] - 48); q++; }
      q++;
      v = v * 100 + (text[q] - 48) * 10 + (text[q + 1] - 48); q += 2;
      score = v;
    } else {
      q++;
      tagOff = toff;
      while (text[q] !== 93) {
        if (text[q] === 44) q++;
        let v = 0; while (text[q] >= 48 && text[q] <= 57) { v = v * 10 + (text[q] - 48); q++; }
        tags[toff++] = v;
      }
      tagCnt = toff - tagOff;
      q++;
    }
    if (text[q] === 44) q++; else { q++; break; }
  }
  rid[count] = id; rnoff[count] = nameOff; rnlen[count] = nameLen; rscore[count] = score; rtoff[count] = tagOff; rtcnt[count] = tagCnt;
  count++;
}
let h = 7;
for (let i = 0; i < count; i++) {
  h = (Math.imul(h, 31) + (rid[i] >>> 0)) >>> 0;
  h = (Math.imul(h, 31) + (rscore[i] >>> 0)) >>> 0;
  h = (Math.imul(h, 31) + (rnlen[i] >>> 0)) >>> 0;
  for (let k = 0; k < rnlen[i]; k++) h = (Math.imul(h, 31) + names[rnoff[i] + k]) >>> 0;
  h = (Math.imul(h, 31) + (rtcnt[i] >>> 0)) >>> 0;
  for (let k = 0; k < rtcnt[i]; k++) h = (Math.imul(h, 31) + tags[rtoff[i] + k]) >>> 0;
}
console.log(`${count} ${tlen} ${h}`);
