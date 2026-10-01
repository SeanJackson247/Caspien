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
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 100000000));
    const f = try alloc.alloc(u8, n + 1);
    defer alloc.free(f);
    @memset(f, 1);
    var i: usize = 2;
    while (i * i <= n) : (i += 1) {
        if (f[i] != 0) {
            var j = i * i;
            while (j <= n) : (j += i) f[j] = 0;
        }
    }
    var count: usize = 0;
    i = 2;
    while (i <= n) : (i += 1) count += f[i];
    out(init, "{d}\n", .{count});
}
