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
    private long totalQueuedBytes = 0L;
    private long droppedNormalPackets = 0L;
    private long totalTransmittedBytes = 0L;
    private long totalTransmittedPackets = 0L;

    /**
     * Enqueues a packet. If normal scan packets must be evicted to make room, they are returned in evictedOut.
     *
     * @param packet the packet to enqueue
     * @param evictedOut optional list to collect evicted packets so the tracker can fail their batches
     * @return true if successfully queued, false if rejected due to capacity limits
     */
    public synchronized boolean enqueue(QueuedPacket packet, List<QueuedPacket> evictedOut) {
        if (packet == null) return false;
        int size = packet.byteSize();

        if (packet.highPriority()) {
            // Evict lowest priority scan packets to make room for high priority updates
            while ((highPriorityQueue.size() + normalQueue.size() >= MAX_QUEUED_PACKETS
                    || totalQueuedBytes + size > MAX_QUEUED_BYTES) && !normalQueue.isEmpty()) {
                QueuedPacket evicted = normalQueue.pollFirst();
                if (evicted != null) {
                    totalQueuedBytes -= evicted.byteSize();
                    droppedNormalPackets++;
                    if (evictedOut != null) evictedOut.add(evicted);
                }
            }
            if (highPriorityQueue.size() >= MAX_QUEUED_PACKETS) {
                QueuedPacket evicted = highPriorityQueue.pollFirst();
                if (evicted != null) {
                    totalQueuedBytes -= evicted.byteSize();
                    droppedNormalPackets++;
                    if (evictedOut != null) evictedOut.add(evicted);
                }
            }
            highPriorityQueue.addLast(packet);
            totalQueuedBytes += size;
            return true;
        } else {
            // Normal scan packet: reject if queue is saturated
            if (highPriorityQueue.size() + normalQueue.size() >= MAX_QUEUED_PACKETS
                    || totalQueuedBytes + size > MAX_QUEUED_BYTES) {
                droppedNormalPackets++;
                return false;
            }
            normalQueue.addLast(packet);
            totalQueuedBytes += size;
            return true;
        }
    }

    public synchronized QueuedPacket peekNext() {
        if (!highPriorityQueue.isEmpty()) {
            return highPriorityQueue.peekFirst();
        }
        return normalQueue.peekFirst();
    }

    public synchronized QueuedPacket pollNext() {
        QueuedPacket pkt = null;
        if (!highPriorityQueue.isEmpty()) {
            pkt = highPriorityQueue.pollFirst();
        } else if (!normalQueue.isEmpty()) {
            pkt = normalQueue.pollFirst();
        }
        if (pkt != null) {
            totalQueuedBytes -= pkt.byteSize();
            totalTransmittedBytes += pkt.byteSize();
            totalTransmittedPackets++;
        }
        return pkt;
    }

    public synchronized List<QueuedPacket> clearAndDrain() {
        List<QueuedPacket> drained = new ArrayList<>(highPriorityQueue.size() + normalQueue.size());
        drained.addAll(highPriorityQueue);
        drained.addAll(normalQueue);
        highPriorityQueue.clear();
        normalQueue.clear();
        totalQueuedBytes = 0L;
        return drained;
    }

    public synchronized void clear() {
        highPriorityQueue.clear();
        normalQueue.clear();
        totalQueuedBytes = 0L;
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
        return droppedNormalPackets;
    }

    public synchronized long getTotalTransmittedBytes() {
        return totalTransmittedBytes;
    }

    public synchronized long getTotalTransmittedPackets() {
        return totalTransmittedPackets;
    }
}
