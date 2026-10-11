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
const Rec = struct { id: u64, name_off: u64, name_len: u64, score: u64, tag_off: u64, tag_cnt: u64 };
var rng: u32 = 12345;
fn next() u32 {
    rng = rng *% 1664525 +% 1013904223;
    return rng;
}
fn wnum(t: []u8, p: usize, v_in: u64) usize {
    var d: usize = 1;
    var u = v_in;
    while (u >= 10) {
        u /= 10;
        d += 1;
    }
    var v = v_in;
    var k = d;
    while (k > 0) {
        k -= 1;
        t[p + k] = @intCast('0' + v % 10);
        v /= 10;
    }
    return p + d;
}
fn wlit(t: []u8, p: usize, s: []const u8) usize {
    @memcpy(t[p .. p + s.len], s);
    return p + s.len;
}
pub fn main(init: std.process.Init) !void {
    const n: usize = @intCast(argN(init, 4000000));
    const text = try alloc.alloc(u8, n * 80 + 16);
    defer alloc.free(text);
    var p: usize = 0;
    text[p] = '[';
    p += 1;
    for (0..n) |i| {
        if (i > 0) {
            text[p] = ',';
            p += 1;
        }
        p = wlit(text, p, "{\"id\":");
        p = wnum(text, p, i);
        p = wlit(text, p, ",\"name\":\"");
        const nl = 3 + (next() >> 16) % 8;
        for (0..nl) |_| {
            text[p] = @intCast('a' + (next() >> 16) % 26);
            p += 1;
        }
        p = wlit(text, p, "\",\"score\":");
        const cents: u64 = (next() >> 8) % 1000000;
        p = wnum(text, p, cents / 100);
        text[p] = '.';
        text[p + 1] = @intCast('0' + cents % 100 / 10);
        text[p + 2] = @intCast('0' + cents % 10);
        p += 3;
        p = wlit(text, p, ",\"tags\":[");
        const tc = (next() >> 16) % 5;
        for (0..tc) |k| {
            if (k > 0) {
                text[p] = ',';
                p += 1;
            }
            p = wnum(text, p, (next() >> 16) % 100);
        }
        p = wlit(text, p, "]}");
    }
    text[p] = ']';
    p += 1;
    const tlen = p;
    // ---- parse ----
    const recs = try alloc.alloc(Rec, n + 1);
    defer alloc.free(recs);
    const names = try alloc.alloc(u8, n * 10 + 8);
    defer alloc.free(names);
    const tags = try alloc.alloc(u8, n * 4 + 8);
    defer alloc.free(tags);
    var count: usize = 0;
    var noff: usize = 0;
    var toff: usize = 0;
    var q: usize = 1;
    while (true) {
        const c = text[q];
        if (c == ']') break;
        if (c == ',') {
            q += 1;
            continue;
        }
        q += 1;
        var r = Rec{ .id = 0, .name_off = 0, .name_len = 0, .score = 0, .tag_off = 0, .tag_cnt = 0 };
        while (true) {
            q += 1;
            const k0 = text[q];
            while (text[q] != '"') q += 1;
            q += 2;
            if (k0 == 'i') {
                var v: u64 = 0;
                while (text[q] >= '0' and text[q] <= '9') : (q += 1) v = v * 10 + (text[q] - '0');
                r.id = v;
            } else if (k0 == 'n') {
                q += 1;
                r.name_off = noff;
                while (text[q] != '"') : (q += 1) {
                    names[noff] = text[q];
                    noff += 1;
                }
                r.name_len = noff - r.name_off;
                q += 1;
            } else if (k0 == 's') {
                var v: u64 = 0;
                while (text[q] >= '0' and text[q] <= '9') : (q += 1) v = v * 10 + (text[q] - '0');
                q += 1;
                v = v * 100 + @as(u64, text[q] - '0') * 10 + (text[q + 1] - '0');
                q += 2;
                r.score = v;
            } else {
                q += 1;
                r.tag_off = toff;
                while (text[q] != ']') {
                    if (text[q] == ',') q += 1;
                    var v: u64 = 0;
                    while (text[q] >= '0' and text[q] <= '9') : (q += 1) v = v * 10 + (text[q] - '0');
                    tags[toff] = @truncate(v);
                    toff += 1;
                }
                r.tag_cnt = toff - r.tag_off;
                q += 1;
            }
            if (text[q] == ',') {
                q += 1;
            } else {
                q += 1;
                break;
            }
        }
        recs[count] = r;
        count += 1;
    }
    var h: u32 = 7;
    for (recs[0..count]) |r| {
        h = h *% 31 +% @as(u32, @truncate(r.id));
        h = h *% 31 +% @as(u32, @truncate(r.score));
        h = h *% 31 +% @as(u32, @truncate(r.name_len));
        for (names[r.name_off .. r.name_off + r.name_len]) |c| h = h *% 31 +% c;
        h = h *% 31 +% @as(u32, @truncate(r.tag_cnt));
        for (tags[r.tag_off .. r.tag_off + r.tag_cnt]) |c| h = h *% 31 +% c;
    }
    out(init, "{d} {d} {d}\n", .{ count, tlen, h });
}
