// spectral-norm, single threaded. Same algorithm as spectralnorm-gcc-1 of the Computer Language Benchmarks Game.
function evalA(i, j) { return 1.0 / (((i + j) * (i + j + 1) >> 1) + i + 1); }
function aTimesU(n, u, au) { for (let i = 0; i < n; i++) { let s = 0; for (let j = 0; j < n; j++) s += evalA(i, j) * u[j]; au[i] = s; } }
function atTimesU(n, u, au) { for (let i = 0; i < n; i++) { let s = 0; for (let j = 0; j < n; j++) s += evalA(j, i) * u[j]; au[i] = s; } }
function ataTimesU(n, u, atau) { const v = new Float64Array(n); aTimesU(n, u, v); atTimesU(n, v, atau); }
const n = process.argv.length > 2 ? parseInt(process.argv[2], 10) : 100;
const u = new Float64Array(n).fill(1), v = new Float64Array(n);
for (let i = 0; i < 10; i++) { ataTimesU(n, u, v); ataTimesU(n, v, u); }
let vBv = 0, vv = 0;
for (let i = 0; i < n; i++) { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
console.log(vBv === 0 ? "nan" : Math.sqrt(vBv / vv).toFixed(9));
