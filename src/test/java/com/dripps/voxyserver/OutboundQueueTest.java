package com.dripps.voxyserver;

import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.server.PlayerLodTracker;
import com.dripps.voxyserver.server.throttle.*;
import net.minecraft.resources.Identifier;
import java.util.*;

public final class OutboundQueueTest {
    private static final Identifier DIM=Identifier.parse("minecraft:overworld");
    private static PlayerOutboundQueue.QueuedPacket packet(long id,int bytes,boolean high) {
        return new PlayerOutboundQueue.QueuedPacket(new PreSerializedLodPayload(1,id,1,new byte[bytes]),DIM,1,id,high,0);
    }
    private static void bound(PlayerOutboundQueue q,int count,long bytes) {
        require(q.getQueuedPacketCount()<=count && q.getQueuedBytes()>=0 && q.getQueuedBytes()<=bytes,"queue cap/accounting violated");
    }
    public static void runAllTests() {
        var q=new PlayerOutboundQueue(4,1024); var evicted=new ArrayList<PlayerOutboundQueue.QueuedPacket>();
        require(q.enqueue(packet(1,700,true),evicted),"first high rejected");
        require(!q.enqueue(packet(2,400,true),evicted),"high-only byte overflow"); bound(q,4,1024);
        require(!q.enqueue(packet(3,1025,true),evicted),"oversized high admitted");
        require(!q.enqueue(packet(4,1025,false),evicted),"oversized normal admitted");
        require(q.getQueuedBytes()==700 && evicted.isEmpty(),"oversized admission destroyed queue");
        q.clearAndDrain(); bound(q,4,1024); require(q.getQueuedBytes()==0,"clear leaked bytes");
        var oldest=packet(5,100,false); var newest=packet(6,100,false);
        q.enqueue(oldest,evicted); q.enqueue(newest,evicted); q.enqueue(packet(7,600,true),evicted);
        require(q.enqueue(packet(8,300,true),evicted),"mixed admission failed");
        require(evicted.size()==1 && evicted.getFirst()==newest,"eviction did not protect oldest normal");
        require(!q.enqueue(packet(9,200,true),evicted),"normal eviction bypassed final byte cap"); bound(q,4,1024);
        q.clearDimension(DIM); require(q.getQueuedBytes()==0 && q.getQueuedPacketCount()==0,"dimension cleanup leaked");
        var countQ=new PlayerOutboundQueue(2,1024);
        countQ.enqueue(packet(1,1,true),evicted); countQ.enqueue(packet(2,1,true),evicted);
        require(!countQ.enqueue(packet(3,1,true),evicted),"high packet-count overflow");
        evicted.clear();
        require(countQ.enqueue(packet(4,1,false),evicted),"first normal admission starved behind full high queue");
        require(evicted.size()==1 && evicted.getFirst().batchId()==2,"normal progress must evict newest high packet");
        var fairness=new PlayerOutboundQueue(32,1024); var normal=packet(40,1,false);
        fairness.enqueue(normal,evicted); for(int i=0;i<20;i++) fairness.enqueue(packet(50+i,1,true),evicted);
        for(int i=0;i<8;i++) require(fairness.pollNext().highPriority(),"dirty priority lost");
        require(fairness.peekNext()==normal && fairness.pollNext()==normal,"normal packet starved");
        fairness.clear(); require(fairness.getQueuedBytes()==0,"clear bytes nonzero");
        resourceRecovery();
        concurrentAccounting();
        pressure();
        System.out.println("PASS OutboundQueueTest: strict byte/count bounds, priority fairness, eviction recovery, 64-player constrained-bandwidth stress");
    }
    private static void resourceRecovery() {
        var t=new PlayerLodTracker(); t.beginSession(DIM,false,60);
        var q=new PlayerOutboundQueue(2,1000);
        var evicted=new ArrayList<PlayerOutboundQueue.QueuedPacket>();
        for(int i=1;i<=3;i++) {
            var b=t.reserveBatch(DIM,t.streamEpoch(),new long[]{i},new long[]{i+10},400,0);
            var p=new PlayerOutboundQueue.QueuedPacket(new PreSerializedLodPayload(b.streamEpoch(),b.batchId(),1,new byte[400]),
                    DIM,b.streamEpoch(),b.batchId(),i==3,0);
            require(q.enqueue(p,evicted),"recovery setup rejected");
        }
        require(evicted.size()==1 && evicted.getFirst().batchId()==2,"wrong eviction for recovery");
        var failed=evicted.getFirst();
        require(t.completeBatch(failed.streamEpoch(),failed.batchId(),false,0),"eviction did not fail batch");
        require(!t.hasOutstanding(2) && t.isBackedOff(99) && !t.isBackedOff(100),"failed batch recovery/backoff incorrect");
        require(t.pollDeferredDirtySection()==2,"failed section was not retained for retry");
        for(var p:q.clearAndDrain()) t.completeBatch(p.streamEpoch(),p.batchId(),false,0);
        var retry=t.reserveBatch(DIM,t.streamEpoch(),new long[]{2},new long[]{12},400,100);
        require(retry!=null && t.completeBatch(retry.streamEpoch(),retry.batchId(),true,101),"retry could not ACK");
        require(t.hasSent(2,12) && t.syncStats().outstandingBytes()==0 && t.remainingSectionCredit()==512,"retry leaked resources");
    }

    private static void concurrentAccounting() {
        var q=new PlayerOutboundQueue(16,4096);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<4;worker++) {
                final int role=worker;
                futures.add(executor.submit(()->{
                    for(int i=0;i<10_000;i++) {
                        if(role<2) q.enqueue(packet(i,128+(i&255),(i&1)==0),null);
                        else if(role==2) q.pollNext();
                        else if((i&31)==0) q.clearDimension(DIM); else q.peekNext();
                        bound(q,16,4096);
                    }
                }));
            }
            for(var future:futures) future.get(20,java.util.concurrent.TimeUnit.SECONDS);
            q.clearAndDrain(); require(q.getQueuedBytes()==0,"concurrent clear leaked bytes");
        } catch(Exception e) { throw new AssertionError("concurrent queue failed",e); }
        finally { executor.shutdownNow(); }
    }

    private static void pressure() {
        final int players=64,ticks=400,limit=24*1024;
        var queues=new PlayerOutboundQueue[players]; var trackers=new PlayerLodTracker[players];
        var buckets=new BandwidthThrottler.TokenBucket[players]; long failed=0, sent=0; long started=System.nanoTime();
        byte[] data=new byte[4096];
        for(int i=0;i<players;i++) {
            queues[i]=new PlayerOutboundQueue(8,limit); trackers[i]=new PlayerLodTracker();
            trackers[i].beginSession(DIM,false,60); buckets[i]=new BandwidthThrottler.TokenBucket(20_000);
            buckets[i].tryAcquire(buckets[i].getAvailableTokens()); buckets[i].refill(0);
        }
        for(int tick=1;tick<=ticks;tick++) for(int i=0;i<players;i++) {
            var t=trackers[i]; var q=queues[i]; buckets[i].refill(tick);
            for(int n=0;n<3;n++) {
                long key=((long)tick*players+i)*3+n;
                var delivery=t.reserveBatch(DIM,t.streamEpoch(),new long[]{key},new long[]{key+1},data.length,tick);
                if(delivery==null) continue;
                var p=new PlayerOutboundQueue.QueuedPacket(new PreSerializedLodPayload(delivery.streamEpoch(),delivery.batchId(),1,data),
                        DIM,delivery.streamEpoch(),delivery.batchId(),n!=0,System.nanoTime());
                var evicted=new ArrayList<PlayerOutboundQueue.QueuedPacket>();
                boolean accepted=q.enqueue(p,evicted);
                for(var e:evicted) { require(t.completeBatch(e.streamEpoch(),e.batchId(),false,tick),"eviction failed to release batch"); failed++; }
                if(!accepted) { require(t.completeBatch(delivery.streamEpoch(),delivery.batchId(),false,tick),"rejection leaked batch"); failed++; }
                bound(q,8,limit);
            }
            var next=q.peekNext();
            if(next!=null && buckets[i].tryAcquire(next.byteSize())) {
                var p=q.pollNext(); require(t.completeBatch(p.streamEpoch(),p.batchId(),true,tick),"ACK lost"); sent++;
            }
            bound(q,8,limit);
        }
        for(int i=0;i<players;i++) {
            for(var p:queues[i].clearAndDrain()) trackers[i].completeBatch(p.streamEpoch(),p.batchId(),false,ticks);
            require(trackers[i].syncStats().outstandingBytes()==0 && trackers[i].remainingSectionCredit()==512,"stress outstanding resources leaked");
            bound(queues[i],8,limit); require(trackers[i].syncStats().retries()>0,"stress did not exercise retries");
        }
        require(sent>0 && failed>0,"load did not exercise traffic and failures");
        System.out.println("  stress players="+players+" ticks="+ticks+" attempts="+(players*ticks*3)+" sent="+sent+" failed="+failed+
                " queueByteLimit="+limit+" elapsedMillis="+((System.nanoTime()-started)/1_000_000));
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
