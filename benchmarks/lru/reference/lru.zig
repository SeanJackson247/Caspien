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
const CAP: u32 = 262144;
const KEYS: u32 = 1048576;
const HOT: u32 = 131072;
const NB: u32 = 524288;
const NONE: u32 = 0xFFFFFFFF;
var rng: u32 = 12345;
fn next() u32 {
    rng = rng *% 1664525 +% 1013904223;
    return rng;
}
var key: []u32 = undefined;
var val: []u32 = undefined;
var prv: []u32 = undefined;
var nxt: []u32 = undefined;
var hn: []u32 = undefined;
var bucket: []u32 = undefined;
var head: u32 = NONE;
var tail: u32 = NONE;
inline fn hsh(k: u32) u32 {
    return @as(u32, @truncate(@as(u64, k) *% 2654435761)) >> 13;
}
fn unlinkNode(i: u32) void {
    if (prv[i] != NONE) nxt[prv[i]] = nxt[i] else head = nxt[i];
    if (nxt[i] != NONE) prv[nxt[i]] = prv[i] else tail = prv[i];
}
fn pushFront(i: u32) void {
    prv[i] = NONE;
    nxt[i] = head;
    if (head != NONE) prv[head] = i else tail = i;
    head = i;
}
fn find(k: u32) u32 {
    var i = bucket[hsh(k)];
    while (i != NONE and key[i] != k) i = hn[i];
    return i;
}
fn hashRemove(i: u32) void {
    const b = hsh(key[i]);
    if (bucket[b] == i) {
        bucket[b] = hn[i];
        return;
    }
    var j = bucket[b];
    while (hn[j] != i) j = hn[j];
    hn[j] = hn[i];
}
pub fn main(init: std.process.Init) !void {
    const n: i64 = argN(init, 20000000);
    key = try alloc.alloc(u32, CAP);
    val = try alloc.alloc(u32, CAP);
    prv = try alloc.alloc(u32, CAP);
    nxt = try alloc.alloc(u32, CAP);
    hn = try alloc.alloc(u32, CAP);
    bucket = try alloc.alloc(u32, NB);
    defer {
        alloc.free(key);
        alloc.free(val);
        alloc.free(prv);
        alloc.free(nxt);
        alloc.free(hn);
        alloc.free(bucket);
    }
    @memset(bucket, NONE);
    var size: u32 = 0;
    var hits: u64 = 0;
    var misses: u64 = 0;
    var sum: u64 = 0;
    var op: i64 = 0;
    while (op < n) : (op += 1) {
        const a = next();
        const y = next();
        const range: u32 = if ((y >> 20) % 4 == 0) KEYS else HOT;
        const k = (a >> 8) % range;
        var i = find(k);
        if ((y >> 24) % 4 != 0) {
            if (i != NONE) {
                hits += 1;
                sum +%= @as(u64, val[i]) + k;
                unlinkNode(i);
                pushFront(i);
            } else misses += 1;
        } else if (i != NONE) {
            val[i] = y;
            unlinkNode(i);
            pushFront(i);
        } else {
            if (size == CAP) {
                i = tail;
                sum +%= key[i];
                unlinkNode(i);
                hashRemove(i);
            } else {
                i = size;
                size += 1;
            }
            key[i] = k;
            val[i] = y;
            const b = hsh(k);
            hn[i] = bucket[b];
            bucket[b] = i;
            pushFront(i);
        }
    }
    var fin: u64 = 0;
    var i = head;
    while (i != NONE) : (i = nxt[i]) fin = (fin *% 31 +% key[i]) % 4294967296;
    out(init, "{d} {d} {d}\n", .{ hits, misses, (sum +% fin) % 4294967296 });
}
