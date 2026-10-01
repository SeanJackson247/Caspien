// fannkuch-redux, single threaded, see fannkuchredux.c.
import Glibc

func fannkuchredux(_ n: Int) -> Int {
    var perm = [Int32](repeating: 0, count: 16)
    var perm1 = [Int32](repeating: 0, count: 16)
    var count = [Int32](repeating: 0, count: 16)
    var maxFlipsCount = 0, permCount = 0, checksum = 0
    var r = n
    for i in 0..<n { perm1[i] = Int32(i) }
    while true {
        while r != 1 { count[r - 1] = Int32(r); r -= 1 }
        for i in 0..<n { perm[i] = perm1[i] }
        var flipsCount = 0
        while true {
            let k = Int(perm[0])
            if k == 0 { break }
            let k2 = (k + 1) >> 1
            for i in 0..<k2 { let t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t }
            flipsCount += 1
        }
        if flipsCount > maxFlipsCount { maxFlipsCount = flipsCount }
        checksum += permCount % 2 == 0 ? flipsCount : -flipsCount
        while true {
            if r == n { print(checksum); return maxFlipsCount }
            let perm0 = perm1[0]
            var i = 0
            while i < r { let j = i + 1; perm1[i] = perm1[j]; i = j }
            perm1[r] = perm0
            count[r] -= 1
            if count[r] > 0 { break }
            r += 1
        }
        permCount += 1
    }
}

let n = CommandLine.arguments.count > 1 ? Int(atoi(CommandLine.arguments[1])) : 7
let flips = fannkuchredux(n)
print("Pfannkuchen(\(n)) = \(flips)")
