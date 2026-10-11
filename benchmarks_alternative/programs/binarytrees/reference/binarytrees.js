// Binary trees (Node / Bun): ordinary heap objects with left/right references.
let maxd = process.argv.length > 2 ? parseInt(process.argv[2]) : 16;
if (maxd < 6) maxd = 6;
function make(d) { return d > 0 ? { l: make(d - 1), r: make(d - 1) } : { l: null, r: null }; }
function check(n) { return n.l === null ? 1 : 1 + check(n.l) + check(n.r); }
const out = [check(make(maxd + 1))];
const longlived = make(maxd);
for (let d = 4; d <= maxd; d += 2) {
  const iters = 2 ** (maxd - d + 4);
  let sum = 0;
  for (let i = 0; i < iters; i++) sum += check(make(d));
  out.push(sum);
}
out.push(check(longlived));
console.log(out.join(" "));
