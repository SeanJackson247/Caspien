package caspien.codegen.x86backend;

import java.util.ArrayList;
import java.util.List;

/** Plain data holder for {@code X86Emit.collectRunEntries}: parallel lists of each direct entry's own size and its store-instruction line index (-1 when it has none of its own -- a `STACK_LOCK` padding gap, or a nested `NEW`'s own opaque pointer). */
final class X86RunEntries {
    final List<Long> sizes = new ArrayList<>();
    final List<Integer> storeLines = new ArrayList<>();
}
