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
fn hash(b: []const u8) u32 {
    var h: u32 = 7;
    for (b) |c| h = h *% 31 +% c;
    return h;
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 4000000));
    const text = try alloc.alloc(u8, n);
    defer alloc.free(text);
    for (text) |*c| {
        const r = (next() >> 16) % 27;
        c.* = if (r == 26) ' ' else @as(u8, @intCast('a' + r));
    }
    var words: usize = 0;
    var longest: usize = 0;
    var cur: usize = 0;
    for (text) |c| {
        if (c == ' ') {
            if (cur > 0) {
                words += 1;
                if (cur > longest) longest = cur;
                cur = 0;
            }
        } else cur += 1;
    }
    if (cur > 0) {
        words += 1;
        if (cur > longest) longest = cur;
    }
    var abc: usize = 0;
    if (n >= 3) {
        for (0..n - 2) |i| {
            if (text[i] == 'a' and text[i + 1] == 'b' and text[i + 2] == 'c') abc += 1;
        }
    }
    const rev = try alloc.alloc(u8, n);
    defer alloc.free(rev);
    for (0..n) |i| rev[i] = text[n - 1 - i];
    const hrev = hash(rev);
    var es: usize = 0;
    for (text) |c| {
        if (c == 'e') es += 1;
    }
    const m = n + es;
    const rep = try alloc.alloc(u8, m);
    defer alloc.free(rep);
    var k: usize = 0;
    for (text) |c| {
        if (c == 'e') {
            rep[k] = '3';
            rep[k + 1] = '3';
            k += 2;
        } else {
            rep[k] = c;
            k += 1;
        }
    }
    const hrep = hash(rep);
    const up = try alloc.alloc(u8, n);
    defer alloc.free(up);
    for (0..n) |i| up[i] = if (text[i] == ' ') ' ' else text[i] - 32;
    const hup = hash(up);
    out(init, "{d} {d} {d} {d} {d} {d} {d}\n", .{ words, longest, abc, hrev, m, hrep, hup });
}
