using System;
using System.Collections.Generic;

// LRU cache benchmark (C#, garbage collected): the natural idiom, Dictionary<K, LinkedListNode<...>> + LinkedList (front = most recent).
public static class Lru
{
    const int CAP = 262144;
    const uint KEYS = 1048576, HOT = 131072;
    static uint x = 12345;
    static uint Next() { x = x * 1664525u + 1013904223u; return x; }

    sealed class Entry { public uint key, val; }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 20000000;
        var map = new Dictionary<uint, LinkedListNode<Entry>>(CAP * 2);
        var list = new LinkedList<Entry>();
        ulong hits = 0, misses = 0;
        uint sum = 0;
        for (int op = 0; op < n; op++)
        {
            uint a = Next(), y = Next();
            uint range = ((y >> 20) % 4 == 0) ? KEYS : HOT;
            uint k = (a >> 8) % range;
            map.TryGetValue(k, out LinkedListNode<Entry> node);
            if ((y >> 24) % 4 != 0)
            {
                if (node != null)
                {
                    hits++; sum += node.Value.val + k;
                    list.Remove(node); list.AddFirst(node);
                }
                else misses++;
            }
            else if (node != null)
            {
                node.Value.val = y;
                list.Remove(node); list.AddFirst(node);
            }
            else
            {
                if (map.Count == CAP)
                {
                    LinkedListNode<Entry> last = list.Last;
                    sum += last.Value.key;
                    list.RemoveLast();
                    map.Remove(last.Value.key);
                }
                var e = new Entry { key = k, val = y };
                map[k] = list.AddFirst(e);
            }
        }
        ulong fin = 0;
        for (var nd = list.First; nd != null; nd = nd.Next) fin = (fin * 31 + nd.Value.key) % 4294967296UL;
        Console.Out.Write(hits + " " + misses + " " + (((ulong)sum + fin) % 4294967296UL) + "\n");
    }
}
