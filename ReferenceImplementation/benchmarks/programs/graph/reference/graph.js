// Heap graph benchmark (Node / Bun).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 2000000;
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x; }
class Node { constructor(v) { this.value = v; this.dist = -1; this.e0 = null; this.e1 = null; this.e2 = null; this.e3 = null; } }
const nodes = new Array(n);
for (let i = 0; i < n; i++) nodes[i] = new Node(next() & 0xFFFFFF);
nodes[Math.floor(n * 7 / 10)].value = 0xFFFFFFFF;
for (let i = 0; i < n; i++) {
  const u = nodes[i];
  u.e0 = nodes[(i + 1) % n];
  u.e1 = nodes[next() % n];
  u.e2 = nodes[next() % n];
  u.e3 = nodes[next() % n];
}
const q = new Array(n);
let head = 0, tail = 0;
q[tail++] = nodes[0]; nodes[0].dist = 0;
let maxdepth = 0, needledist = -1, sum = 0;
while (head < tail) {
  const u = q[head++];
  sum = (sum + u.value) >>> 0;
  if (u.value === 0xFFFFFFFF) needledist = u.dist;
  if (u.dist > maxdepth) maxdepth = u.dist;
  let v = u.e0; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
  v = u.e1; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
  v = u.e2; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
  v = u.e3; if (v.dist < 0) { v.dist = u.dist + 1; q[tail++] = v; }
}
console.log(`${tail} ${maxdepth} ${needledist} ${sum}`);
