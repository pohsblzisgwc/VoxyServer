package com.dripps.voxyserver.sync;

import java.util.*;

/**
 * Bounded round-robin mailbox: one client's initial view cannot monopolize the maintenance worker.
 */
public final class FairWorkQueue<K, V> {
    private final int capacity, perKey;
    private final Map<K, ArrayDeque<V>> queues = new HashMap<>();
    private final ArrayDeque<K> turns = new ArrayDeque<>();
    private int size;

    public FairWorkQueue(int capacity, int perKey) {
        if (capacity < 1 || perKey < 1 || perKey > capacity)
            throw new IllegalArgumentException("invalid mailbox limits");
        this.capacity = capacity;
        this.perKey = perKey;
    }

    public synchronized boolean offer(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        var q = queues.get(key);
        if (size >= capacity || (q != null && q.size() >= perKey)) return false;
        if (q == null) {
            q = new ArrayDeque<>();
            queues.put(key, q);
            turns.addLast(key);
        }
        q.addLast(value);
        size++;
        return true;
    }

    public synchronized V poll() {
        K key = turns.pollFirst();
        if (key == null) return null;
        var q = queues.get(key);
        V next = q.removeFirst();
        size--;
        if (q.isEmpty()) queues.remove(key);
        else turns.addLast(key);
        return next;
    }

    public synchronized void remove(K key) {
        var q = queues.remove(key);
        if (q == null) return;
        size -= q.size();
        turns.remove(key);
    }

    public synchronized int size() {
        return size;
    }
}
