// Copyright by the contributors to the Dafny Project
// SPDX-License-Identifier: MIT
//
// EACL target-cost refinement:
// The upstream Dafny Java runtime copies a java.util.HashSet for every
// immutable set union. EACL's generated indexed traversal grows sets one
// element at a time, making the otherwise linear traversal quadratic.
// This drop-in implementation preserves the DafnySet API and extensional
// semantics while using Clojure's persistent hash set. Clojure is already a
// mandatory EACL runtime dependency.

package dafny;

import clojure.lang.IPersistentSet;
import clojure.lang.PersistentHashSet;
import java.util.*;

public class DafnySet<T> {
    private IPersistentSet innerSet;

    public DafnySet() {
        innerSet = PersistentHashSet.EMPTY;
    }

    public DafnySet(Set<T> s) {
        this((Collection<T>) s);
    }

    public DafnySet(Collection<T> c) {
        assert c != null : "Precondition Violation";
        innerSet = PersistentHashSet.EMPTY;
        for (T value : c) {
            innerSet = (IPersistentSet) innerSet.cons(value);
        }
    }

    public DafnySet(DafnySet<T> other) {
        assert other != null : "Precondition Violation";
        innerSet = other.innerSet;
    }

    public DafnySet(List<T> l) {
        this((Collection<T>) l);
    }

    private DafnySet(IPersistentSet persistentSet, boolean trusted) {
        innerSet = persistentSet;
    }

    @SafeVarargs
    public static <T> DafnySet<T> of(T ... elements) {
        DafnySet<T> result = new DafnySet<>();
        for (T element : elements) {
            result.add(element);
        }
        return result;
    }

    private static final DafnySet<Object> EMPTY =
        new DafnySet<>(PersistentHashSet.EMPTY, true);

    @SuppressWarnings("unchecked")
    public static <T> DafnySet<T> empty() {
        return (DafnySet<T>) EMPTY;
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeDescriptor<DafnySet<? extends T>> _typeDescriptor(
            TypeDescriptor<T> elementType) {
        return TypeDescriptor.referenceWithDefault(
                (Class<DafnySet<? extends T>>) (Class<?>) DafnySet.class,
                DafnySet.empty());
    }

    public boolean isSubsetOf(DafnySet other) {
        assert other != null : "Precondition Violation";
        return other.containsAll(this);
    }

    public boolean isProperSubsetOf(DafnySet other) {
        assert other != null : "Precondition Violation";
        return isSubsetOf(other) && size() < other.size();
    }

    public boolean contains(Object value) {
        assert value != null : "Precondition Violation";
        return innerSet.contains(value);
    }

    public <U> boolean disjoint(DafnySet<? extends U> other) {
        assert other != null : "Precondition Violation";
        DafnySet<?> smaller = size() <= other.size() ? this : other;
        DafnySet<?> larger = smaller == this ? other : this;
        for (Object value : smaller.Elements()) {
            if (larger.contains(value)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static <T> DafnySet<T> union(
            DafnySet<? extends T> left,
            DafnySet<? extends T> right) {
        assert left != null : "Precondition Violation";
        assert right != null : "Precondition Violation";
        if (left.isEmpty()) {
            return (DafnySet<T>) right;
        }
        if (right.isEmpty()) {
            return (DafnySet<T>) left;
        }
        DafnySet<? extends T> larger =
            left.size() >= right.size() ? left : right;
        DafnySet<? extends T> smaller = larger == left ? right : left;
        DafnySet<T> result =
            new DafnySet<>((IPersistentSet) larger.innerSet, true);
        for (T value : smaller.Elements()) {
            result.add(value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <T> DafnySet<T> difference(
            DafnySet<? extends T> left,
            DafnySet<? extends T> right) {
        assert left != null : "Precondition Violation";
        assert right != null : "Precondition Violation";
        if (left.isEmpty() || right.isEmpty()) {
            return (DafnySet<T>) left;
        }
        DafnySet<T> result =
            new DafnySet<>((IPersistentSet) left.innerSet, true);
        for (T value : right.Elements()) {
            result.remove(value);
        }
        return result;
    }

    public static <T> DafnySet<T> intersection(
            DafnySet<? extends T> left,
            DafnySet<? extends T> right) {
        assert left != null : "Precondition Violation";
        assert right != null : "Precondition Violation";
        DafnySet<? extends T> smaller =
            left.size() <= right.size() ? left : right;
        DafnySet<? extends T> larger = smaller == left ? right : left;
        DafnySet<T> result = new DafnySet<>();
        for (T value : smaller.Elements()) {
            if (larger.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public boolean containsAll(DafnySet other) {
        assert other != null : "Precondition Violation";
        for (Object value : other.Elements()) {
            if (!contains(value)) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        return innerSet.count();
    }

    public int cardinalityInt() {
        return size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    // The compiler uses mutating construction only on a fresh wrapper.
    // The underlying persistent value is never mutated.
    public boolean add(T value) {
        assert value != null : "Precondition Violation";
        boolean changed = !innerSet.contains(value);
        innerSet = (IPersistentSet) innerSet.cons(value);
        return changed;
    }

    public boolean remove(T value) {
        assert value != null : "Precondition Violation";
        boolean changed = innerSet.contains(value);
        innerSet = innerSet.disjoin(value);
        return changed;
    }

    public boolean removeAll(DafnySet<T> other) {
        assert other != null : "Precondition Violation";
        boolean changed = false;
        for (T value : other.Elements()) {
            changed |= remove(value);
        }
        return changed;
    }

    public boolean addAll(DafnySet<T> other) {
        assert other != null : "Precondition Violation";
        boolean changed = false;
        for (T value : other.Elements()) {
            changed |= add(value);
        }
        return changed;
    }

    public Collection<DafnySet<T>> AllSubsets() {
        List<T> elements = new ArrayList<>(Elements());
        int count = elements.size();
        HashSet<DafnySet<T>> result = new HashSet<>();
        for (int mask = 0; mask < (1 << count); mask++) {
            DafnySet<T> subset = new DafnySet<>();
            for (int index = 0; index < count; index++) {
                if ((mask & (1 << index)) != 0) {
                    subset.add(elements.get(index));
                }
            }
            result.add(subset);
        }
        return result;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        DafnySet<?> other = (DafnySet<?>) value;
        return size() == other.size() && containsAll(other);
    }

    @Override
    public int hashCode() {
        return innerSet.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("{");
        String separator = "";
        for (T element : Elements()) {
            result.append(separator).append(Helpers.toString(element));
            separator = ", ";
        }
        return result.append("}").toString();
    }

    public DafnyMultiset<T> asDafnyMultiset() {
        return new DafnyMultiset<>(Elements());
    }

    @SuppressWarnings("unchecked")
    public Set<T> Elements() {
        return (Set<T>) innerSet;
    }
}
