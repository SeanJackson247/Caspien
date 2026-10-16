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
const Node = struct { value: u32, dist: i32, e: [4]*Node };
var x: u32 = 12345;
fn next() u32 {
    x = x *% 1664525 +% 1013904223;
    return x;
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 2000000));
    const nodes = try alloc.alloc(*Node, n);
    defer alloc.free(nodes);
    for (nodes) |*p| {
        const nd = try alloc.create(Node);
        nd.value = next() & 0xFFFFFF;
        nd.dist = -1;
        p.* = nd;
    }
    defer for (nodes) |p| alloc.destroy(p);
    nodes[n * 7 / 10].value = 0xFFFFFFFF;
    for (0..n) |i| {
        nodes[i].e[0] = nodes[(i + 1) % n];
        for (1..4) |k| nodes[i].e[k] = nodes[next() % n];
    }
    const q = try alloc.alloc(*Node, n);
    defer alloc.free(q);
    var head: usize = 0;
    var tail: usize = 0;
    q[tail] = nodes[0];
    tail += 1;
    nodes[0].dist = 0;
    var maxdepth: i32 = 0;
    var needledist: i32 = -1;
    var sum: u32 = 0;
    while (head < tail) {
        const u = q[head];
        head += 1;
        sum +%= u.value;
        if (u.value == 0xFFFFFFFF) needledist = u.dist;
        if (u.dist > maxdepth) maxdepth = u.dist;
        for (u.e) |v| {
            if (v.dist < 0) {
                v.dist = u.dist + 1;
                q[tail] = v;
                tail += 1;
            }
        }
    }
    out(init, "{d} {d} {d} {d}\n", .{ tail, maxdepth, needledist, sum });
}
