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
const ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
const CHARS = "ACGTBDHKMNRSVWYACGT";
const THR = [19]u32{ 37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968 };
const HDR = [3][]const u8{ ">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n" };
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 40000000));
    var cnt = [3]usize{ n * 2 / 10, n * 3 / 10, 0 };
    cnt[2] = n - cnt[0] - cnt[1];
    const off = [3]usize{ 0, 0, 15 };
    const buf = try alloc.alloc(u8, n + n / 60 + 1024);
    defer alloc.free(buf);
    var last: u32 = 42;
    var pos: usize = 0;
    var a: usize = 0;
    var c: usize = 0;
    var g: usize = 0;
    var t: usize = 0;
    var other: usize = 0;
    var ai: usize = 0;
    for (0..3) |s| {
        @memcpy(buf[pos .. pos + HDR[s].len], HDR[s]);
        pos += HDR[s].len;
        var col: usize = 0;
        for (0..cnt[s]) |_| {
            var ch: u8 = undefined;
            if (s == 0) {
                ch = ALU[ai];
                ai += 1;
                if (ai == 287) ai = 0;
            } else {
                last = (last * 3877 + 29573) % 139968;
                var j = off[s];
                while (last >= THR[j]) j += 1;
                ch = CHARS[j];
            }
            buf[pos] = ch;
            pos += 1;
            switch (ch) {
                'A' => a += 1,
                'C' => c += 1,
                'G' => g += 1,
                'T' => t += 1,
                else => other += 1,
            }
            col += 1;
            if (col == 60) {
                buf[pos] = '\n';
                pos += 1;
                col = 0;
            }
        }
        if (col > 0) {
            buf[pos] = '\n';
            pos += 1;
        }
    }
    var h: u32 = 7;
    for (buf[0..pos]) |b| h = h *% 31 +% b;
    out(init, "{d} {d} {d} {d} {d} {d} {d}\n", .{ pos, h, a, c, g, t, other });
}
