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
const Slot = struct { key: u64, cnt: u32 };
const KS = [6]u6{ 1, 2, 3, 4, 6, 12 };
const QK = [11]u6{ 1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12 };
const QV = [11]u64{ 0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487 };
fn lookup(tab: []const Slot, bits: u6, v: u64) u64 {
    const kk = v + 1;
    const cap_mask = tab.len - 1;
    var h: usize = @intCast((kk *% 0x9E3779B97F4A7C15) >> @intCast(64 - @as(u7, bits)));
    while (true) {
        if (tab[h].key == 0) return 0;
        if (tab[h].key == kk) return tab[h].cnt;
        h = (h + 1) & cap_mask;
    }
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 30000000));
    const seq = try alloc.alloc(u8, n + 1);
    defer alloc.free(seq);
    var last: u32 = 42;
    for (0..n) |i| {
        last = (last * 3877 + 29573) % 139968;
        seq[i] = if (last < 42404) 0 else if (last < 70117) 1 else if (last < 97767) 2 else 3;
    }
    var first12: u64 = 0;
    for (0..@min(12, n)) |i| first12 = (first12 << 2) | seq[i];
    var distinct: [6]u64 = undefined;
    var counts: [12]u64 = undefined;
    var nq: usize = 0;
    for (KS, 0..) |k, ki| {
        var maxd: usize = @as(usize, 1) << (2 * k);
        const win: usize = if (n >= k) n - k + 1 else 0;
        if (win < maxd) maxd = win;
        if (maxd > 139968) maxd = 139968;
        var bits: u6 = 1;
        while ((@as(usize, 1) << bits) < 2 * maxd) bits += 1;
        const cap: usize = @as(usize, 1) << bits;
        const tab = try alloc.alloc(Slot, cap);
        defer alloc.free(tab);
        @memset(tab, Slot{ .key = 0, .cnt = 0 });
        const mask: u64 = (@as(u64, 1) << (2 * k)) - 1;
        var key: u64 = 0;
        var nd: u64 = 0;
        const shift: u6 = @intCast(64 - @as(u7, bits));
        for (0..n) |i| {
            key = ((key << 2) | seq[i]) & mask;
            if (i + 1 >= k) {
                var h: usize = @intCast(((key + 1) *% 0x9E3779B97F4A7C15) >> shift);
                while (true) {
                    if (tab[h].key == 0) {
                        tab[h].key = key + 1;
                        tab[h].cnt = 1;
                        nd += 1;
                        break;
                    }
                    if (tab[h].key == key + 1) {
                        tab[h].cnt += 1;
                        break;
                    }
                    h = (h + 1) & (cap - 1);
                }
            }
        }
        distinct[ki] = nd;
        for (0..11) |q| {
            if (QK[q] != k) continue;
            counts[nq] = lookup(tab, bits, QV[q]);
            nq += 1;
        }
        if (k == 12) {
            counts[nq] = lookup(tab, bits, first12);
            nq += 1;
        }
    }
    var buf: [1024]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    const o = &w.interface;
    for (distinct) |d| try o.print("{d} ", .{d});
    for (counts, 0..) |c, i| {
        if (i > 0) try o.writeAll(" ");
        try o.print("{d}", .{c});
    }
    try o.writeAll("\n");
    try o.flush();
}
