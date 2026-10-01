import java.util.LinkedHashMap;
import java.util.Map;
// LRU cache benchmark (Java, garbage collected): the natural idiom, an access-ordered LinkedHashMap (eldest = least recently used).
public class Lru {
    static final int CAP = 262144, KEYS = 1048576, HOT = 131072;
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20000000;
        LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>(CAP * 2, 0.75f, true);
        long hits = 0, misses = 0;
        int sum = 0;
        for (int op = 0; op < n; op++) {
            int a = next(), y = next();
            int range = ((y >>> 20) % 4 == 0) ? KEYS : HOT;
            int k = (a >>> 8) % range;
            if ((y >>> 24) % 4 != 0) {
                Integer v = m.get(k);   // access order: get moves the entry to the most-recent end
                if (v != null) { hits++; sum += v + k; } else misses++;
            } else if (m.containsKey(k)) {
                m.put(k, y);            // put of an existing key also counts as an access
            } else {
                if (m.size() == CAP) {
                    Map.Entry<Integer, Integer> eldest = m.entrySet().iterator().next();
                    sum += eldest.getKey();
                    m.remove(eldest.getKey());
                }
                m.put(k, y);
            }
        }
        long fin = 0;
        // iteration order is least -> most recent; the checksum walks most -> least recent
        int sz = m.size();
        int[] keys = new int[sz];
        int j = sz;
        for (int kk : m.keySet()) keys[--j] = kk;
        for (int i = 0; i < sz; i++) fin = (fin * 31 + keys[i]) % 4294967296L;
        System.out.println(hits + " " + misses + " " + (((sum & 0xFFFFFFFFL) + fin) % 4294967296L));
    }
}
