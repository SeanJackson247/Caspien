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

    public BytecodeProgram() {
        items = new ArrayList<>();
    }

    public BytecodeProgram(int capacity) {
        items = new ArrayList<>(capacity);
    }

    public BytecodeProgram(Collection<? extends BytecodeStatement> elements) {
        items = new ArrayList<>(elements);
    }

    public BytecodeProgram(BytecodeProgram other) {
        items = new ArrayList<>(other.items);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public BytecodeStatement get(int i) {
        return items.get(i);
    }

    public BytecodeStatement set(int i, BytecodeStatement e) {
        return items.set(i, e);
    }

    public boolean add(BytecodeStatement e) {
        return items.add(e);
    }

    public void add(int i, BytecodeStatement e) {
        items.add(i, e);
    }

    public boolean addAll(Collection<? extends BytecodeStatement> c) {
        return items.addAll(c);
    }

    public boolean addAll(BytecodeProgram other) {
        return items.addAll(other.items);
    }

    public boolean addAll(int i, BytecodeProgram other) {
        return items.addAll(i, other.items);
    }

    /** Removes [from, to). */
    public void removeRange(int from, int to) {
        items.subList(from, to).clear();
    }

    /** A copy of [from, to) as a new program. */
    public BytecodeProgram slice(int from, int to) {
        return new BytecodeProgram(items.subList(from, to));
    }

    public boolean addAll(int i, Collection<? extends BytecodeStatement> c) {
        return items.addAll(i, c);
    }

    public BytecodeStatement remove(int i) {
        return items.remove(i);
    }

    public boolean remove(Object o) {
        return items.remove(o);
    }

    public boolean removeIf(Predicate<? super BytecodeStatement> p) {
        return items.removeIf(p);
    }

    public void clear() {
        items.clear();
    }

    public int indexOf(Object o) {
        return items.indexOf(o);
    }

    public int lastIndexOf(Object o) {
        return items.lastIndexOf(o);
    }

    public boolean contains(Object o) {
        return items.contains(o);
    }

    public void sort(Comparator<? super BytecodeStatement> c) {
        items.sort(c);
    }

    public Stream<BytecodeStatement> stream() {
        return items.stream();
    }

    @Override
    public Iterator<BytecodeStatement> iterator() {
        return items.iterator();
    }

    /** The contents as a live list view, for code that works on List (writes go through to this object). */
    public List<BytecodeStatement> asList() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BytecodeProgram other && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return items.toString();
    }
}
