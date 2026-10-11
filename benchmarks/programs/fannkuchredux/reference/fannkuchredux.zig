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
fn fannkuchredux(n: usize, o: *std.Io.Writer) !i32 {
    var perm: [16]i32 = undefined;
    var perm1: [16]i32 = undefined;
    var count: [16]i32 = undefined;
    var maxFlips: i32 = 0;
    var permCount: i32 = 0;
    var checksum: i32 = 0;
    var r: usize = n;
    for (0..n) |i| perm1[i] = @intCast(i);
    while (true) {
        while (r != 1) {
            count[r - 1] = @intCast(r);
            r -= 1;
        }
        for (0..n) |i| perm[i] = perm1[i];
        var flips: i32 = 0;
        while (perm[0] != 0) {
            const k: usize = @intCast(perm[0]);
            const k2 = (k + 1) >> 1;
            for (0..k2) |i| {
                const t = perm[i];
                perm[i] = perm[k - i];
                perm[k - i] = t;
            }
            flips += 1;
        }
        if (flips > maxFlips) maxFlips = flips;
        checksum += if (@mod(permCount, 2) == 0) flips else -flips;
        while (true) {
            if (r == n) {
                try o.print("{d}\n", .{checksum});
                return maxFlips;
            }
            const perm0 = perm1[0];
            var i: usize = 0;
            while (i < r) {
                const j = i + 1;
                perm1[i] = perm1[j];
                i = j;
            }
            perm1[r] = perm0;
            count[r] -= 1;
            if (count[r] > 0) break;
            r += 1;
        }
        permCount += 1;
    }
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 7));
    var buf: [256]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    const o = &w.interface;
    const m = try fannkuchredux(n, o);
    try o.print("Pfannkuchen({d}) = {d}\n", .{ n, m });
    try o.flush();
}
