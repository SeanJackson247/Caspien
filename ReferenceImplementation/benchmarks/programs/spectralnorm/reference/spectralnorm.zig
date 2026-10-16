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
fn evalA(i: usize, j: usize) f64 {
    return 1.0 / @as(f64, @floatFromInt((i + j) * (i + j + 1) / 2 + i + 1));
}
fn aTimesU(n: usize, u: []const f64, au: []f64) void {
    for (0..n) |i| {
        au[i] = 0;
        for (0..n) |j| au[i] += evalA(i, j) * u[j];
    }
}
fn atTimesU(n: usize, u: []const f64, au: []f64) void {
    for (0..n) |i| {
        au[i] = 0;
        for (0..n) |j| au[i] += evalA(j, i) * u[j];
    }
}
fn ataTimesU(n: usize, u: []const f64, ataU: []f64) void {
    const v = alloc.alloc(f64, n) catch @panic("oom");
    defer alloc.free(v);
    aTimesU(n, u, v);
    atTimesU(n, v, ataU);
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 100));
    const u = try alloc.alloc(f64, n);
    const v = try alloc.alloc(f64, n);
    defer alloc.free(u);
    defer alloc.free(v);
    @memset(u, 1);
    for (0..10) |_| {
        ataTimesU(n, u, v);
        ataTimesU(n, v, u);
    }
    var vBv: f64 = 0;
    var vv: f64 = 0;
    for (0..n) |i| {
        vBv += u[i] * v[i];
        vv += v[i] * v[i];
    }
    out(init, "{d:.9}\n", .{@sqrt(vBv / vv)});
}
