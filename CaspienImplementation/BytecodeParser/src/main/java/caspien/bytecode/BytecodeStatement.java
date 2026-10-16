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

    public BytecodeStatement() { }

    public BytecodeStatement(int capacity) { }

    public BytecodeStatement(Collection<? extends BytecodeToken> elements) { }

    public BytecodeStatement(BytecodeStatement other) { }

    public int size() { return 0; }

    public boolean isEmpty() { return false; }

    public BytecodeToken get(int i) { return null; }

    public BytecodeToken set(int i, BytecodeToken e) { return null; }

    public boolean add(BytecodeToken e) { return false; }

    public void add(int i, BytecodeToken e) { }

    public boolean addAll(Collection<? extends BytecodeToken> c) { return false; }

    public boolean addAll(BytecodeStatement other) { return false; }

    public boolean addAll(int i, Collection<? extends BytecodeToken> c) { return false; }

    public BytecodeToken remove(int i) { return null; }

    public boolean remove(Object o) { return false; }

    public boolean removeIf(Predicate<? super BytecodeToken> p) { return false; }

    public void clear() { }

    public int indexOf(Object o) { return 0; }

    public int lastIndexOf(Object o) { return 0; }

    public boolean contains(Object o) { return false; }

    public void sort(Comparator<? super BytecodeToken> c) { }

    public Stream<BytecodeToken> stream() { return null; }

    @Override
    public Iterator<BytecodeToken> iterator() { return null; }

    /** A copy of [from, to). */
    public List<BytecodeToken> slice(int from, int to) { return null; }

    /** The contents as a live list view, for code that works on List (writes go through to this object). */
    public List<BytecodeToken> asList() { return null; }

    @Override
    public boolean equals(Object o) { return false; }

    @Override
    public int hashCode() { return 0; }

    @Override
    public String toString() { return null; }

    /** First token's text (mnemonic or label), or "" for an empty statement. */
    public String opcode() { return null; }

    /** Text of token i, or "" when the statement is shorter. */
    public String text(int i) { return null; }

    /** Line of the HOB text this statement was read from (0 for an empty statement). */
    public int sourceLine() { return 0; }

    /**
     * An instruction a pass generates, written as text ("ALLOC 16"): split by the same scanner as program text.
     * For building new lines inside a pass, never for reading a program (that is BytecodeParser, once, up front).
     */
    public static BytecodeStatement fromText(String text, String origin) { return null; }

    /** Several generated instructions, one statement per string. */
    public static java.util.List<BytecodeStatement> fromTexts(java.util.List<String> texts, String origin) { return null; }
}
