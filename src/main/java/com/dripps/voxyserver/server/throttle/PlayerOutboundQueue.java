package com.dripps.voxyserver.server.throttle;

import com.dripps.voxyserver.network.PreSerializedLodPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded, dual-priority outbound queue for per-player LOD packets.
 * Ensures dirty section updates take precedence over background scan batches,
 * and bounds maximum queued packets and bytes to prevent memory exhaustion.
 */
public class PlayerOutboundQueue {
    public static final int MAX_QUEUED_PACKETS = 128;
    public static final long MAX_QUEUED_BYTES = 8 * 1024 * 1024L; // 8 MB per player

    public record QueuedPacket(
            PreSerializedLodPayload payload,
            Identifier dimension,
            long streamEpoch,
            long batchId,
            boolean highPriority,
            long queuedAtNanos
    ) {
        public int byteSize() {
            return payload != null && payload.data() != null ? payload.data().length : 0;
        }
    }

    private final ArrayDeque<QueuedPacket> highPriorityQueue = new ArrayDeque<>();
    private final ArrayDeque<QueuedPacket> normalQueue = new ArrayDeque<>();
    private final int packetLimit;
    private final long byteLimit;
    public PlayerOutboundQueue() { this(MAX_QUEUED_PACKETS, MAX_QUEUED_BYTES); }
    public PlayerOutboundQueue(int packetLimit, long byteLimit) {
        if (packetLimit < 1 || packetLimit > MAX_QUEUED_PACKETS || byteLimit < 1 || byteLimit > MAX_QUEUED_BYTES)
            throw new IllegalArgumentException("invalid outbound queue limits");
        this.packetLimit = packetLimit; this.byteLimit = byteLimit;
    }
    private long totalQueuedBytes = 0L;
    private long droppedPackets = 0L;
    private long evictedPackets, rejectedPackets, peakBytes;
    private int highBurst;
    private long totalTransmittedBytes = 0L;
    private long totalTransmittedPackets = 0L;

    /**
     * Enqueues within both hard caps. Every eviction is returned for batch failure/recovery.
     *
     * @param packet the packet to enqueue
     * @param evictedOut optional list to collect evicted packets so the tracker can fail their batches
     * @return true if successfully queued, false if rejected due to capacity limits
     */
    public synchronized boolean enqueue(QueuedPacket packet, List<QueuedPacket> evictedOut) {
        if (packet == null) return false;
        int size = packet.byteSize();

        if (size <= 0 || size > byteLimit) { rejectedPackets++; droppedPackets++; return false; }
        if (packet.highPriority()) {
            // Retain the oldest normal packet so a sustained update stream cannot evict it forever.
            while (wouldOverflow(size) && normalQueue.size() > 1) evict(normalQueue.pollLast(), evictedOut);
        } else if (normalQueue.isEmpty()) {
            // Reserve progress for one background packet even under continuous dirty traffic.
            while (wouldOverflow(size) && !highPriorityQueue.isEmpty()) evict(highPriorityQueue.pollLast(), evictedOut);
        }
        if (wouldOverflow(size)) { rejectedPackets++; droppedPackets++; return false; }
        (packet.highPriority() ? highPriorityQueue : normalQueue).addLast(packet);
        totalQueuedBytes += size;
        peakBytes = Math.max(peakBytes, totalQueuedBytes);
        return true;
    }

    private boolean wouldOverflow(int size) {
        return highPriorityQueue.size() + normalQueue.size() >= packetLimit
                || totalQueuedBytes + size > byteLimit;
    }
    private void evict(QueuedPacket packet, List<QueuedPacket> evictedOut) {
        totalQueuedBytes -= packet.byteSize();
        droppedPackets++; evictedPackets++;
        if (evictedOut != null) evictedOut.add(packet);
    }

    public synchronized QueuedPacket peekNext() {
        return !normalQueue.isEmpty() && (highPriorityQueue.isEmpty() || highBurst >= 8)
                ? normalQueue.peekFirst() : highPriorityQueue.peekFirst();
    }

    public synchronized QueuedPacket pollNext() {
        QueuedPacket packet = peekNext();
        if (packet == null) return null;
        if (packet == highPriorityQueue.peekFirst()) { highPriorityQueue.removeFirst(); highBurst = Math.min(8, highBurst + 1); }
        else { normalQueue.removeFirst(); highBurst = 0; }
        totalQueuedBytes -= packet.byteSize();
        totalTransmittedBytes += packet.byteSize();
        totalTransmittedPackets++;
        return packet;
    }

    public record Stats(int packets, long bytes, long peakBytes, long evicted, long rejected) {}
    public synchronized Stats stats() {
        return new Stats(getQueuedPacketCount(), totalQueuedBytes, peakBytes, evictedPackets, rejectedPackets);
    }

    public synchronized List<QueuedPacket> clearAndDrain() {
        List<QueuedPacket> drained = new ArrayList<>(highPriorityQueue.size() + normalQueue.size());
        drained.addAll(highPriorityQueue);
        drained.addAll(normalQueue);
        highPriorityQueue.clear();
        normalQueue.clear();
        totalQueuedBytes = 0L;
        highBurst = 0;
        return drained;
    }

    public synchronized void clear() {
        highPriorityQueue.clear();
        normalQueue.clear();
        totalQueuedBytes = 0L;
        highBurst = 0;
    }

    public synchronized List<QueuedPacket> clearDimension(Identifier dim) {
        List<QueuedPacket> removed = new ArrayList<>();
        if (dim == null) return removed;
        removeDimensionFromQueue(highPriorityQueue, dim, removed);
        removeDimensionFromQueue(normalQueue, dim, removed);
        return removed;
    }

    private void removeDimensionFromQueue(ArrayDeque<QueuedPacket> deque, Identifier dim, List<QueuedPacket> removed) {
        Iterator<QueuedPacket> it = deque.iterator();
        while (it.hasNext()) {
            QueuedPacket pkt = it.next();
            if (dim.equals(pkt.dimension())) {
                it.remove();
                totalQueuedBytes -= pkt.byteSize();
                removed.add(pkt);
            }
        }
    }

    public synchronized int getQueuedPacketCount() {
        return highPriorityQueue.size() + normalQueue.size();
    }

    public synchronized long getQueuedBytes() {
        return totalQueuedBytes;
    }

    public synchronized long getDroppedCount() {
        return droppedPackets;
    }

    public synchronized long getTotalTransmittedBytes() {
        return totalTransmittedBytes;
    }

    public synchronized long getTotalTransmittedPackets() {
        return totalTransmittedPackets;
    }
}
