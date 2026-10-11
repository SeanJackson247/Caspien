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
const PI = 3.141592653589793;
const SOLAR_MASS = 4.0 * PI * PI;
const DPY = 365.24;
const Planet = struct { x: f64, y: f64, z: f64, vx: f64, vy: f64, vz: f64, mass: f64 };
var bodies = [5]Planet{
    .{ .x = 0, .y = 0, .z = 0, .vx = 0, .vy = 0, .vz = 0, .mass = SOLAR_MASS },
    .{ .x = 4.84143144246472090e+00, .y = -1.16032004402742839e+00, .z = -1.03622044471123109e-01, .vx = 1.66007664274403694e-03 * DPY, .vy = 7.69901118419740425e-03 * DPY, .vz = -6.90460016972063023e-05 * DPY, .mass = 9.54791938424326609e-04 * SOLAR_MASS },
    .{ .x = 8.34336671824457987e+00, .y = 4.12479856412430479e+00, .z = -4.03523417114321381e-01, .vx = -2.76742510726862411e-03 * DPY, .vy = 4.99852801234917238e-03 * DPY, .vz = 2.30417297573763929e-05 * DPY, .mass = 2.85885980666130812e-04 * SOLAR_MASS },
    .{ .x = 1.28943695621391310e+01, .y = -1.51111514016986312e+01, .z = -2.23307578892655734e-01, .vx = 2.96460137564761618e-03 * DPY, .vy = 2.37847173959480950e-03 * DPY, .vz = -2.96589568540237556e-05 * DPY, .mass = 4.36624404335156298e-05 * SOLAR_MASS },
    .{ .x = 1.53796971148509165e+01, .y = -2.59193146099879641e+01, .z = 1.79258772950371181e-01, .vx = 2.68067772490389322e-03 * DPY, .vy = 1.62824170038242295e-03 * DPY, .vz = -9.51592254519715870e-05 * DPY, .mass = 5.15138902046611451e-05 * SOLAR_MASS },
};
fn advance(dt: f64) void {
    for (0..5) |i| {
        for (i + 1..5) |j| {
            const dx = bodies[i].x - bodies[j].x;
            const dy = bodies[i].y - bodies[j].y;
            const dz = bodies[i].z - bodies[j].z;
            const d2 = dx * dx + dy * dy + dz * dz;
            const mag = dt / (d2 * @sqrt(d2));
            bodies[i].vx -= dx * bodies[j].mass * mag;
            bodies[i].vy -= dy * bodies[j].mass * mag;
            bodies[i].vz -= dz * bodies[j].mass * mag;
            bodies[j].vx += dx * bodies[i].mass * mag;
            bodies[j].vy += dy * bodies[i].mass * mag;
            bodies[j].vz += dz * bodies[i].mass * mag;
        }
    }
    for (&bodies) |*b| {
        b.x += dt * b.vx;
        b.y += dt * b.vy;
        b.z += dt * b.vz;
    }
}
fn energy() f64 {
    var e: f64 = 0;
    for (0..5) |i| {
        e += 0.5 * bodies[i].mass * (bodies[i].vx * bodies[i].vx + bodies[i].vy * bodies[i].vy + bodies[i].vz * bodies[i].vz);
        for (i + 1..5) |j| {
            const dx = bodies[i].x - bodies[j].x;
            const dy = bodies[i].y - bodies[j].y;
            const dz = bodies[i].z - bodies[j].z;
            e -= bodies[i].mass * bodies[j].mass / @sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    return e;
}
pub fn main(init: std.process.Init) !void {
    const n = argN(init, 1000);
    var px: f64 = 0;
    var py: f64 = 0;
    var pz: f64 = 0;
    for (bodies) |b| {
        px += b.vx * b.mass;
        py += b.vy * b.mass;
        pz += b.vz * b.mass;
    }
    bodies[0].vx = -px / SOLAR_MASS;
    bodies[0].vy = -py / SOLAR_MASS;
    bodies[0].vz = -pz / SOLAR_MASS;
    var buf: [256]u8 = undefined;
    var w = std.Io.File.stdout().writer(init.io, &buf);
    const o = &w.interface;
    try o.print("{d:.9}\n", .{energy()});
    var i: i64 = 0;
    while (i < n) : (i += 1) advance(0.01);
    try o.print("{d:.9}\n", .{energy()});
    try o.flush();
}
