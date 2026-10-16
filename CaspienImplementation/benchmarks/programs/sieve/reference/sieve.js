// Sieve of Eratosthenes (Node / Bun).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 100000000;
const f = new Uint8Array(n + 1).fill(1);
for (let i = 2; i * i <= n; i++)
  if (f[i])
    for (let j = i * i; j <= n; j += i) f[j] = 0;
let count = 0;
for (let i = 2; i <= n; i++) count += f[i];
console.log(count);
