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
    const n: i64 = argN(init, 3000);
    const dn: f64 = @floatFromInt(n);
    var inside: i64 = 0;
    var total: i64 = 0;
    var y: i64 = 0;
    while (y < n) : (y += 1) {
        const ci = 2.0 * @as(f64, @floatFromInt(y)) / dn - 1.0;
        var xx: i64 = 0;
        while (xx < n) : (xx += 1) {
            const cr = 2.0 * @as(f64, @floatFromInt(xx)) / dn - 1.5;
            var zr: f64 = 0;
            var zi: f64 = 0;
            var tr: f64 = 0;
            var ti: f64 = 0;
            var i: i32 = 0;
            while (i < 100 and tr + ti <= 4.0) {
                zi = 2.0 * zr * zi + ci;
                zr = tr - ti + cr;
                tr = zr * zr;
                ti = zi * zi;
                i += 1;
            }
            total += i;
            if (i == 100) inside += 1;
        }
    }
    out(init, "{d} {d}\n", .{ inside, total });
}
