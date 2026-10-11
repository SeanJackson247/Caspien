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
var x: u32 = 12345;
fn next() u32 {
    x = x *% 1664525 +% 1013904223;
    return x;
}
fn quicksort(a: []i32) void {
    const n: isize = @intCast(a.len);
    var stack: [128]isize = undefined;
    var sp: usize = 0;
    stack[sp] = 0;
    stack[sp + 1] = n - 1;
    sp += 2;
    while (sp > 0) {
        sp -= 2;
        var lo = stack[sp];
        var hi = stack[sp + 1];
        while (lo < hi) {
            const pivot = a[@intCast(@divTrunc(lo + hi, 2))];
            var i = lo;
            var j = hi;
            while (i <= j) {
                while (a[@intCast(i)] < pivot) i += 1;
                while (a[@intCast(j)] > pivot) j -= 1;
                if (i <= j) {
                    const t = a[@intCast(i)];
                    a[@intCast(i)] = a[@intCast(j)];
                    a[@intCast(j)] = t;
                    i += 1;
                    j -= 1;
                }
            }
            if (j - lo < hi - i) {
                stack[sp] = i;
                stack[sp + 1] = hi;
                sp += 2;
                hi = j;
            } else {
                stack[sp] = lo;
                stack[sp + 1] = j;
                sp += 2;
                lo = i;
            }
        }
    }
}
fn mergesort(a: []i32, tmp: []i32) void {
    const n = a.len;
    var src = a;
    var dst = tmp;
    var w: usize = 1;
    while (w < n) : (w *= 2) {
        var lo: usize = 0;
        while (lo < n) : (lo += 2 * w) {
            const mid = @min(lo + w, n);
            const hi = @min(lo + 2 * w, n);
            var i = lo;
            var j = mid;
            var k = lo;
            while (i < mid and j < hi) {
                if (src[i] <= src[j]) {
                    dst[k] = src[i];
                    i += 1;
                } else {
                    dst[k] = src[j];
                    j += 1;
                }
                k += 1;
            }
            while (i < mid) : ({
                i += 1;
                k += 1;
            }) dst[k] = src[i];
            while (j < hi) : ({
                j += 1;
                k += 1;
            }) dst[k] = src[j];
        }
        const t = src;
        src = dst;
        dst = t;
    }
    if (src.ptr != a.ptr) @memcpy(a, src);
}
fn siftdown(a: []i32, root_in: usize, n: usize) void {
    var root = root_in;
    while (true) {
        var c = 2 * root + 1;
        if (c >= n) break;
        if (c + 1 < n and a[c + 1] > a[c]) c += 1;
        if (a[root] >= a[c]) break;
        const t = a[root];
        a[root] = a[c];
        a[c] = t;
        root = c;
    }
}
fn heapsort(a: []i32) void {
    const n = a.len;
    var i: usize = n / 2;
    while (i > 0) {
        i -= 1;
        siftdown(a, i, n);
    }
    var e: usize = n;
    while (e > 1) {
        e -= 1;
        const t = a[0];
        a[0] = a[e];
        a[e] = t;
        siftdown(a, 0, e);
    }
}
fn bsearchHas(a: []const i32, key: i32) bool {
    var lo: usize = 0;
    var hi: usize = a.len;
    while (lo < hi) {
        const m = (lo + hi) / 2;
        if (a[m] < key) lo = m + 1 else hi = m;
    }
    return lo < a.len and a[lo] == key;
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 2000000));
    const orig = try alloc.alloc(i32, n);
    const a = try alloc.dupe(i32, orig);
    const b = try alloc.dupe(i32, orig);
    const d = try alloc.dupe(i32, orig);
    const tmp = try alloc.alloc(i32, n);
    defer {
        alloc.free(orig);
        alloc.free(a);
        alloc.free(b);
        alloc.free(d);
        alloc.free(tmp);
    }
    for (orig) |*v| v.* = @intCast(next() >> 1);
    @memcpy(a, orig);
    @memcpy(b, orig);
    @memcpy(d, orig);
    quicksort(a);
    mergesort(b, tmp);
    heapsort(d);
    var h: u32 = 0;
    var ok = true;
    for (0..n) |i| {
        h = h *% 31 +% @as(u32, @bitCast(a[i]));
        if (a[i] != b[i] or a[i] != d[i]) ok = false;
        if (i > 0 and a[i - 1] > a[i]) ok = false;
    }
    var bs: usize = 0;
    for (0..n) |k| {
        var key: i32 = @intCast(next() >> 1);
        if (k % 2 == 0) key = orig[@as(u32, @bitCast(key)) % n];
        if (bsearchHas(a, key)) bs += 1;
    }
    var ls: usize = 0;
    for (0..20) |k| {
        var key: i32 = @intCast(next() >> 1);
        if (k % 2 == 0) key = orig[@as(u32, @bitCast(key)) % n];
        for (orig) |v| {
            if (v == key) {
                ls += 1;
                break;
            }
        }
    }
    out(init, "{d} {d} {d} {s}\n", .{ h, bs, ls, if (ok) "OK" else "BAD" });
}
