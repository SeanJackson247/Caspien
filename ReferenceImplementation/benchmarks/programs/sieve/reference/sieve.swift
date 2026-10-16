// Sieve of Eratosthenes: count the primes <= N (byte per number).
import Glibc

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 100000000
var f = [UInt8](repeating: 1, count: n + 1)
var i = 2
while i * i <= n {
    if f[i] != 0 {
        var j = i * i
        while j <= n { f[j] = 0; j += i }
    }
    i += 1
}
var count = 0
for k in 2...max(2, n) where k <= n { count += Int(f[k]) }
print(count)
