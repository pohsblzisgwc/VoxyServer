package com.dripps.voxyserver.util;

import java.util.LinkedHashMap;

/** Small shared worker cache; dirty callbacks may invalidate it from other threads. */
public final class BoundedLruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedLruCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("invalid cache capacity");
        this.capacity = capacity;
    }
    public synchronized V get(K key) { return entries.get(key); }
    public synchronized void put(K key, V value) {
        entries.put(key, value);
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }
    public synchronized V compute(K key, java.util.function.BiFunction<K, V, V> update) {
        V value = update.apply(key, entries.get(key));
        if (value == null) entries.remove(key); else put(key, value);
        return value;
    }
    public synchronized void remove(K key) { entries.remove(key); }
    public synchronized void clear() { entries.clear(); }
    public synchronized int size() { return entries.size(); }
}
