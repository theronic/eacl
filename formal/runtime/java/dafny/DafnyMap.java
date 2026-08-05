// Copyright by the contributors to the Dafny Project
// SPDX-License-Identifier: MIT
//
// EACL target-cost refinement. See DafnySet.java in this directory.

package dafny;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import java.util.*;
import java.util.function.BiConsumer;

public class DafnyMap<K, V> {
    private IPersistentMap innerMap;

    public DafnyMap() {
        innerMap = PersistentHashMap.EMPTY;
    }

    public DafnyMap(Map<K, V> values) {
        assert values != null : "Precondition Violation";
        innerMap = PersistentHashMap.EMPTY;
        for (Map.Entry<K, V> entry : values.entrySet()) {
            innerMap = innerMap.assoc(entry.getKey(), entry.getValue());
        }
    }

    private DafnyMap(IPersistentMap persistentMap) {
        innerMap = persistentMap;
    }

    public static <K, V> DafnyMap<K, V> empty() {
        return new DafnyMap<>();
    }

    @SafeVarargs
    public static <K, V> DafnyMap<K, V> fromElements(
            Tuple2<K, V> ... pairs) {
        DafnyMap<K, V> result = new DafnyMap<>();
        for (Tuple2<K, V> pair : pairs) {
            result.innerMap =
                result.innerMap.assoc(pair.dtor__0(), pair.dtor__1());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> TypeDescriptor<DafnyMap<? extends K, ? extends V>>
            _typeDescriptor(
                TypeDescriptor<K> keyType,
                TypeDescriptor<V> valueType) {
        return TypeDescriptor.referenceWithDefault(
                (Class<DafnyMap<? extends K, ? extends V>>)
                    (Class<?>) DafnyMap.class,
                DafnyMap.empty());
    }

    public boolean contains(Object key) {
        return innerMap.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> DafnyMap<K, V> update(
            DafnyMap<? extends K, ? extends V> source,
            K key,
            V value) {
        return new DafnyMap<>(
            ((IPersistentMap) source.innerMap).assoc(key, value));
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        DafnyMap<?, ?> other = (DafnyMap<?, ?>) value;
        return innerMap.equals(other.innerMap);
    }

    @Override
    public int hashCode() {
        return innerMap.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("map[");
        String separator = "";
        for (Map.Entry<K, V> entry : entries()) {
            result.append(separator)
                .append(Helpers.toString(entry.getKey()))
                .append(" := ")
                .append(Helpers.toString(entry.getValue()));
            separator = ", ";
        }
        return result.append("]").toString();
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (Map.Entry<K, V> entry : entries()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    public static <K, V> DafnyMap<? extends K, ? extends V> merge(
            DafnyMap<? extends K, ? extends V> left,
            DafnyMap<? extends K, ? extends V> right) {
        assert left != null : "Precondition Violation";
        assert right != null : "Precondition Violation";
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        DafnyMap<K, V> result =
            new DafnyMap<>((IPersistentMap) right.innerMap);
        left.forEach((key, value) -> {
            if (!result.contains(key)) {
                result.innerMap = result.innerMap.assoc(key, value);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> DafnyMap<? extends K, ? extends V> subtract(
            DafnyMap<? extends K, ? extends V> source,
            DafnySet<? extends K> keys) {
        assert source != null : "Precondition Violation";
        assert keys != null : "Precondition Violation";
        if (source.isEmpty() || keys.isEmpty()) {
            return source;
        }
        IPersistentMap result = source.innerMap;
        for (K key : keys.Elements()) {
            result = result.without(key);
        }
        return new DafnyMap<K, V>(result);
    }

    public int size() {
        return innerMap.count();
    }

    public int cardinalityInt() {
        return size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    @SuppressWarnings("unchecked")
    public V get(Object key) {
        return (V) innerMap.valAt(key);
    }

    public DafnySet<K> keySet() {
        return new DafnySet<>(asJavaMap().keySet());
    }

    public DafnySet<V> valueSet() {
        return new DafnySet<>(asJavaMap().values());
    }

    @SuppressWarnings("unchecked")
    public <KK, VV> DafnySet<? extends Tuple2<KK, VV>> entrySet() {
        ArrayList<Tuple2<K, V>> values = new ArrayList<>();
        for (Map.Entry<K, V> entry : entries()) {
            values.add(
                new Tuple2<K, V>(entry.getKey(), entry.getValue()));
        }
        return (DafnySet<? extends Tuple2<KK, VV>>)
            (Object) new DafnySet<Tuple2<K, V>>(values);
    }

    @SuppressWarnings("unchecked")
    private Map<K, V> asJavaMap() {
        return (Map<K, V>) innerMap;
    }

    private Set<Map.Entry<K, V>> entries() {
        return asJavaMap().entrySet();
    }
}
