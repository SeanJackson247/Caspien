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
const Node = struct { l: ?*Node, r: ?*Node };
fn make(d: i32) *Node {
    const n = alloc.create(Node) catch @panic("oom");
    if (d > 0) {
        n.l = make(d - 1);
        n.r = make(d - 1);
    } else {
        n.l = null;
        n.r = null;
    }
    return n;
}
fn check(n: *const Node) i64 {
    return if (n.l) |l| 1 + check(l) + check(n.r.?) else 1;
}
fn release(n: *Node) void {
    if (n.l) |l| {
        release(l);
        release(n.r.?);
    }
    alloc.destroy(n);
}
pub fn main(init: std.process.Init) !void {
    var maxd: i32 = @intCast(argN(init, 16));
    if (maxd < 6) maxd = 6;
    var buf: [4096]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    const o = &w.interface;
    const t = make(maxd + 1);
    try o.print("{d}", .{check(t)});
    release(t);
    const longlived = make(maxd);
    var d: i32 = 4;
    while (d <= maxd) : (d += 2) {
        const iters: i64 = @as(i64, 1) << @intCast(maxd - d + 4);
        var sum: i64 = 0;
        var i: i64 = 0;
        while (i < iters) : (i += 1) {
            const a = make(d);
            sum += check(a);
            release(a);
        }
        try o.print(" {d}", .{sum});
    }
    try o.print(" {d}\n", .{check(longlived)});
    release(longlived);
    try o.flush();
}
