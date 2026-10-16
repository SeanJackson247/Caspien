package caspien.bytecode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** A whole HOB program: its statements in order. This is the only thing the optimizer passes read and write. */
public final class BytecodeProgram implements Iterable<BytecodeStatement> {
    private final ArrayList<BytecodeStatement> items;

    public BytecodeProgram() { }

    public BytecodeProgram(int capacity) { }

    public BytecodeProgram(Collection<? extends BytecodeStatement> elements) { }

    public BytecodeProgram(BytecodeProgram other) { }

    public int size() { return 0; }

    public boolean isEmpty() { return false; }

    public BytecodeStatement get(int i) { return null; }

    public BytecodeStatement set(int i, BytecodeStatement e) { return null; }

    public boolean add(BytecodeStatement e) { return false; }

    public void add(int i, BytecodeStatement e) { }

    public boolean addAll(Collection<? extends BytecodeStatement> c) { return false; }

    public boolean addAll(BytecodeProgram other) { return false; }

    public boolean addAll(int i, BytecodeProgram other) { return false; }

    /** Removes [from, to). */
    public void removeRange(int from, int to) { }

    /** A copy of [from, to) as a new program. */
    public BytecodeProgram slice(int from, int to) { return null; }

    public boolean addAll(int i, Collection<? extends BytecodeStatement> c) { return false; }

    public BytecodeStatement remove(int i) { return null; }

    public boolean remove(Object o) { return false; }

    public boolean removeIf(Predicate<? super BytecodeStatement> p) { return false; }

    public void clear() { }

    public int indexOf(Object o) { return 0; }

    public int lastIndexOf(Object o) { return 0; }

    public boolean contains(Object o) { return false; }

    public void sort(Comparator<? super BytecodeStatement> c) { }

    public Stream<BytecodeStatement> stream() { return null; }

    @Override
    public Iterator<BytecodeStatement> iterator() { return null; }

    /** The contents as a live list view, for code that works on List (writes go through to this object). */
    public List<BytecodeStatement> asList() { return null; }

    @Override
    public boolean equals(Object o) { return false; }

    @Override
    public int hashCode() { return 0; }

    @Override
    public String toString() { return null; }
}
