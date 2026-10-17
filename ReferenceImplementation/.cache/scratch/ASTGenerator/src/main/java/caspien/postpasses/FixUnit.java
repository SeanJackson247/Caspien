package caspien.postpasses;

import java.util.Set;

/** One run of direct children that end up in the same block (or, with no tags, outside every block). */
final class FixUnit {
    int from, to;
    Set<String> tags;
}
