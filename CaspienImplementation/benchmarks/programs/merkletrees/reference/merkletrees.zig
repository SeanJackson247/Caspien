const std = @import("std");
const alloc = std.heap.smp_allocator;
fn argN(init: std.process.Init, def: i64) i64 {
    var it = std.process.Args.Iterator.init(init.minimal.args);
    _ = it.next();
    return if (it.next()) |s| (std.fmt.parseInt(i64, s, 10) catch def) else def;
}
fn out(init: std.process.Init, comptime fmt: []const u8, args: anytype) void {
    var buf: [1024]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    w.interface.print(fmt, args) catch {};
    w.interface.flush() catch {};
}
var rng: u32 = 12345;
fn next() u32 {
    rng = rng *% 1664525 +% 1013904223;
    return rng;
}
const K = [64]u32{
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};
const H0 = [8]u32{ 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19 };
inline fn ror(v: u32, comptime n: u5) u32 {
    return std.math.rotr(u32, v, n);
}
fn compress(st: *[8]u32, b: *const [16]u32) void {
    var w: [64]u32 = undefined;
    for (0..16) |i| w[i] = b[i];
    for (16..64) |i| {
        const s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] +% s0 +% w[i - 7] +% s1;
    }
    var a = st[0];
    var bb = st[1];
    var c = st[2];
    var d = st[3];
    var e = st[4];
    var f = st[5];
    var g = st[6];
    var h = st[7];
    for (0..64) |i| {
        const S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
        const ch = (e & f) ^ (~e & g);
        const t1 = h +% S1 +% ch +% K[i] +% w[i];
        const S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
        const mj = (a & bb) ^ (a & c) ^ (bb & c);
        const t2 = S0 +% mj;
        h = g;
        g = f;
        f = e;
        e = d +% t1;
        d = c;
        c = bb;
        bb = a;
        a = t1 +% t2;
    }
    st[0] +%= a;
    st[1] +%= bb;
    st[2] +%= c;
    st[3] +%= d;
    st[4] +%= e;
    st[5] +%= f;
    st[6] +%= g;
    st[7] +%= h;
}
inline fn bswap32(v: u32) u32 {
    return @byteSwap(v);
}
fn leaf(out_: *[8]u32, dlo: u32, dhi: u32, i: u64) void {
    const b = [16]u32{ bswap32(dlo), bswap32(dhi), bswap32(@truncate(i)), bswap32(@truncate(i >> 32)), 0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 128 };
    out_.* = H0;
    compress(out_, &b);
}
fn node(out_: *[8]u32, l: *const [8]u32, r: *const [8]u32) void {
    var b: [16]u32 = undefined;
    var st: [8]u32 = undefined;
    for (0..8) |k| {
        b[k] = l[k];
        b[8 + k] = r[k];
        st[k] = H0[k];
    }
    compress(&st, &b);
    const p = [16]u32{ 0x80000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 512 };
    compress(&st, &p);
    out_.* = st;
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 140000));
    const data = try alloc.alloc(u32, n);
    defer alloc.free(data);
    const t = try alloc.alloc([8]u32, 2 * n + 64);
    defer alloc.free(t);
    for (0..n) |i| {
        data[i] = next();
        leaf(&t[i], data[i], 0, i);
    }
    var off: usize = 0;
    var size: usize = n;
    while (size > 1) {
        const noff = off + size;
        const ns = (size + 1) / 2;
        for (0..ns) |j| {
            const l = &t[off + 2 * j];
            const r = if (2 * j + 1 < size) &t[off + 2 * j + 1] else l;
            node(&t[noff + j], l, r);
        }
        off = noff;
        size = ns;
    }
    const root = t[off];
    var verified: u64 = 0;
    var rejected: u64 = 0;
    var sibs: [64][8]u32 = undefined;
    var cur: [8]u32 = undefined;
    for (0..n / 2) |p| {
        const idx: usize = next() % n;
        var o: usize = 0;
        var s: usize = n;
        var pos: usize = idx;
        var depth: usize = 0;
        while (s > 1) {
            var sb = if (pos % 2 == 0) pos + 1 else pos - 1;
            if (sb >= s) sb = pos;
            sibs[depth] = t[o + sb];
            depth += 1;
            o += s;
            s = (s + 1) / 2;
            pos /= 2;
        }
        var dlo = data[idx];
        var dhi: u32 = 0;
        if (p % 4 == 3) {
            dlo +%= 1;
            dhi = @intFromBool(dlo == 0);
        }
        leaf(&cur, dlo, dhi, idx);
        pos = idx;
        for (0..depth) |d| {
            if (pos % 2 == 0) node(&cur, &cur, &sibs[d]) else node(&cur, &sibs[d], &cur);
            pos /= 2;
        }
        if (std.mem.eql(u32, &cur, &root)) verified += 1 else rejected += 1;
    }
    var buf: [256]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    const o = &w.interface;
    for (root) |v| try o.print("{x:0>8}", .{v});
    try o.print(" {d} {d}\n", .{ verified, rejected });
    try o.flush();
}
