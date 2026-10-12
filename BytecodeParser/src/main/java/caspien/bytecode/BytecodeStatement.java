package caspien.bytecode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * One line of HOB: an opcode (or label) followed by its operands, as a list
 * of tokens. A pass that rewrites a line builds a new BytecodeStatement.
 */
public final class BytecodeStatement implements Iterable<BytecodeToken> {
    private final ArrayList<BytecodeToken> items;

    public BytecodeStatement() {
        items = new ArrayList<>();
    }

    public BytecodeStatement(int capacity) {
        items = new ArrayList<>(capacity);
    }

    public BytecodeStatement(Collection<? extends BytecodeToken> elements) {
        items = new ArrayList<>(elements);
    }

    public BytecodeStatement(BytecodeStatement other) {
        items = new ArrayList<>(other.items);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public BytecodeToken get(int i) {
        return items.get(i);
    }

    public BytecodeToken set(int i, BytecodeToken e) {
        return items.set(i, e);
    }

    public boolean add(BytecodeToken e) {
        return items.add(e);
    }

    public void add(int i, BytecodeToken e) {
        items.add(i, e);
    }

    public boolean addAll(Collection<? extends BytecodeToken> c) {
        return items.addAll(c);
    }

    public boolean addAll(BytecodeStatement other) {
        return items.addAll(other.items);
    }

    public boolean addAll(int i, Collection<? extends BytecodeToken> c) {
        return items.addAll(i, c);
    }

    public BytecodeToken remove(int i) {
        return items.remove(i);
    }

    public boolean remove(Object o) {
        return items.remove(o);
    }

    public boolean removeIf(Predicate<? super BytecodeToken> p) {
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

    public void sort(Comparator<? super BytecodeToken> c) {
        items.sort(c);
    }

    public Stream<BytecodeToken> stream() {
        return items.stream();
    }

    @Override
    public Iterator<BytecodeToken> iterator() {
        return items.iterator();
    }

    /** A copy of [from, to). */
    public List<BytecodeToken> slice(int from, int to) {
        return new ArrayList<>(items.subList(from, to));
    }

    /** The contents as a live list view, for code that works on List (writes go through to this object). */
    public List<BytecodeToken> asList() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BytecodeStatement other && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return items.toString();
    }

    /** First token's text (mnemonic or label), or "" for an empty statement. */
    public String opcode() {
        return isEmpty() ? "" : get(0).text;
    }

    /** Text of token i, or "" when the statement is shorter. */
    public String text(int i) {
        return i < size() ? get(i).text : "";
    }

    /** Line of the HOB text this statement was read from (0 for an empty statement). */
    public int sourceLine() {
        return isEmpty() ? 0 : get(0).line;
    }

    /**
     * An instruction a pass generates, written as text ("ALLOC 16"): split by the same scanner as program text.
     * For building new lines inside a pass, never for reading a program (that is BytecodeParser, once, up front).
     */
    public static BytecodeStatement fromText(String text, String origin) {
        return BytecodeParser.parseLine(text, origin, 1);
    }

    /** Several generated instructions, one statement per string. */
    public static java.util.List<BytecodeStatement> fromTexts(java.util.List<String> texts, String origin) {
        java.util.List<BytecodeStatement> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            out.add(BytecodeParser.parseLine(texts.get(i), origin, i + 1));
        }
        return out;
    }
}
