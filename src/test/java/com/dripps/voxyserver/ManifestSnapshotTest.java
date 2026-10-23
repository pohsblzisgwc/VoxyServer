package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.server.PlayerLodTracker;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.resources.Identifier;

/** Real tracker transactions, including overlapping delivery ownership and late ACKs. */
public final class ManifestSnapshotTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");
    private static final long IN = key(0), OUT = key(10);
    private static long key(int x) { return WorldEngine.getWorldSectionId(0, x, 0, 0); }
    private static PlayerLodTracker tracker() {
        var t = new PlayerLodTracker(); t.beginSession(DIM, false, 60); return t;
    }
    private static void known(PlayerLodTracker t, long key, long hash) {
        var b = t.reserveBatch(DIM, t.streamEpoch(), new long[]{key}, new long[]{hash}, 100, 0);
        require(b != null && t.completeBatch(b.streamEpoch(), b.batchId(), true, 1), "seed ACK failed");
    }
    private static PlayerLodTracker.StreamSession start(PlayerLodTracker t, long tick) {
        var session = t.refreshManifest(tick + 60, new ManifestScope(0, 0, 1), 0);
        return session;
    }
    private static int page(PlayerLodTracker t, PlayerLodTracker.StreamSession s, long id,
                            int status, long[] keys, long[] hashes, long tick) {
        return t.acceptManifestPage(DIM, s.streamEpoch(), s.manifestRequestId(), 0, keys, hashes, status, id, tick, true);
    }
    public static void runAllTests() {
        var t = tracker(); known(t, IN, 11); known(t, OUT, 99);
        var s = start(t, 100);
        require(page(t,s,0,LODManifestPayload.PART,new long[]{IN},new long[]{22},101)==0,"PART failed");
        require(t.hasSent(IN,11) && !t.hasSent(IN,22), "PART prematurely replaced H1");
        require(page(t,s,0,LODManifestPayload.PART,new long[]{IN},new long[]{22},102)==0,"duplicate page failed");
        require(page(t,s,1,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{22},103)==0,"duplicate key failed");
        require(t.hasSent(IN,22) && !t.hasSent(IN,11),"H1 did not become H2");
        require(t.hasSent(OUT,99),"outside range lost");
        var deleted = start(t, 200);
        require(page(t,deleted,0,LODManifestPayload.COMPLETE,new long[0],new long[0],201)==0,"empty complete failed");
        require(!t.hasKnownSection(IN) && t.hasSent(OUT,99),"absence did not remove only scoped confirmation");
        require(page(t,s,2,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{11},202)==LODManifestAckPayload.ABORT,
                "old request accepted");

        var late = tracker();
        var old = late.reserveBatch(DIM,late.streamEpoch(),new long[]{IN,OUT},new long[]{11,99},100,0);
        var snapshot = start(late, 100);
        require(page(late,snapshot,0,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{22},101)==0,"commit failed");
        require(!late.isBatchSendable(old.streamEpoch(),old.batchId()),"old queued batch can still be sent");
        var fresh = late.reserveBatch(DIM,late.streamEpoch(),new long[]{IN},new long[]{22},100,102);
        require(fresh != null,"superseded owner blocked recovery");
        require(late.completeBatch(fresh.streamEpoch(),fresh.batchId(),true,103),"fresh ACK failed");
        require(late.completeBatch(old.streamEpoch(),old.batchId(),true,104),"old ACK did not release resources");
        require(late.hasSent(IN,22) && !late.hasSent(IN,11) && late.hasSent(OUT,99),"old mixed ACK overwrote snapshot or lost outside ACK");
        require(late.syncStats().outstandingBytes()==0 && late.remainingSectionCredit()==512,"ACK resources leaked");
        require(page(late,snapshot,0,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{22},105)==0,"complete retry rejected");
        require(late.hasSent(IN,22),"complete replay changed newer state");

        var ordered = tracker(); var seq = start(ordered,100);
        require(page(ordered,seq,1,LODManifestPayload.COMPLETE,new long[0],new long[0],101)==LODManifestAckPayload.RETRY,
                "gapped complete committed");
        require(page(ordered,seq,0,LODManifestPayload.PART,new long[]{IN},new long[]{22},102)==0,"first page failed");
        require(page(ordered,seq,1,LODManifestPayload.COMPLETE,new long[0],new long[0],103)==0,"reordered recovery failed");
        var conflict = start(ordered,200);
        require(page(ordered,conflict,0,LODManifestPayload.PART,new long[]{IN},new long[]{33},201)==0,"staging failed");
        require(page(ordered,conflict,1,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{44},202)==LODManifestAckPayload.ABORT,
                "contradictory duplicate accepted");
        require(!ordered.hasKnownSection(IN) && !ordered.isManifestGated(DIM,203),"failure retained uncertain scoped claim or gate");

        for (int terminal : new int[]{LODManifestPayload.FAILED,LODManifestPayload.CANCELED}) {
            var recover = tracker(); known(recover, IN, 11); known(recover, OUT, 99); var request=start(recover,100);
            page(recover,request,0,terminal,new long[0],new long[0],101);
            require(!recover.isManifestGated(DIM,102) && recover.prepareScanWork(0,0,1,0,1,102,1200),"terminal state blocked scan");
            require(!recover.hasKnownSection(IN) && recover.hasSent(OUT,99),"failure invalidation escaped scope");
        }
        var timeout = tracker(); known(timeout,IN,11); var stale = start(timeout,100);
        long expiry=100+PlayerLodTracker.MANIFEST_IDLE_TICKS;
        timeout.expireManifest(expiry);
        require(timeout.syncStats().manifestTimeouts()==1 && !timeout.isManifestGated(DIM,expiry+1),"timeout did not expire transaction");
        require(page(timeout,stale,0,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{22},expiry+1)==LODManifestAckPayload.ABORT,
                "timed out transaction resurrected");
        require(timeout.prepareScanWork(0,0,1,0,1,expiry+1,1200),"timeout recovery failed");
        var bounds=tracker(); var bounded=start(bounds,100);
        require(bounds.containsManifestKey(IN) && !bounds.containsManifestKey(OUT),"scope changed with player position");
        require(page(bounds,bounded,0,LODManifestPayload.COMPLETE,new long[]{OUT},new long[]{9},101)==LODManifestAckPayload.ABORT,
                "outside-scope page accepted");
        var lifetime=tracker(); var lifetimeSession=start(lifetime,100); long id=0;
        for(long tick=101;tick<12_100;tick+=300) {
            require(page(lifetime,lifetimeSession,id++,LODManifestPayload.PART,new long[0],new long[0],tick)==0,
                    "valid progress rejected before lifetime cap");
        }
        lifetime.expireManifest(12_100);
        require(lifetime.syncStats().manifestTimeouts()==1,"progress kept snapshot alive forever");
        // Outside dimension confirmations must survive a dimension-scoped refresh too.
        var dimensionScoped=tracker();
        long other=IN | (1L << 60);
        known(dimensionScoped,other,77); var dimSession=start(dimensionScoped,100);
        page(dimensionScoped,dimSession,0,LODManifestPayload.COMPLETE,new long[0],new long[0],101);
        require(dimensionScoped.hasSent(other,77),"another dimension lost its confirmation");
        concurrency();
        sharedPagePressure();
        System.out.println("PASS ManifestSnapshotTest: transactional H1/H2, absence, bounds, duplicate/order, late ACK and timeout recovery");
    }
    private static void sharedPagePressure() {
        final int players=256;
        var trackers=new PlayerLodTracker[players];
        var sessions=new PlayerLodTracker.StreamSession[players];
        var pending=new java.util.ArrayDeque<Integer>();
        var pages=new int[players];
        for(int i=0;i<players;i++) { trackers[i]=tracker(); sessions[i]=start(trackers[i],100); pending.addLast(i); }
        long tick=100;
        while(!pending.isEmpty()) {
            tick+=5; // Same global page pacing as the streaming service.
            for(var tracker:trackers) tracker.expireManifest(tick);
            int i=pending.removeFirst(); int id=pages[i]++;
            int status=id==0?LODManifestPayload.PART:LODManifestPayload.COMPLETE;
            require(page(trackers[i],sessions[i],id,status,id==0?new long[]{IN}:new long[0],
                    id==0?new long[]{77}:new long[0],tick)==0,"legitimate globally paced page expired under load");
            if(id==0) pending.addLast(i);
        }
        for(var tracker:trackers) require(tracker.syncStats().manifestsCompleted()==1
                && tracker.syncStats().manifestTimeouts()==0 && tracker.hasSent(IN,77),"shared inventory load failed");
        System.out.println("  manifest stress players="+players+" pages="+(players*2)+" elapsedTicks="+(tick-100));
    }

    private static void concurrency() {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<100;i++) {
                var t=tracker(); var old=t.reserveBatch(DIM,t.streamEpoch(),new long[]{IN},new long[]{11},100,0);
                var s=start(t,100); var barrier=new java.util.concurrent.CyclicBarrier(2);
                var a=executor.submit(()->{ await(barrier); t.completeBatch(old.streamEpoch(),old.batchId(),true,102); });
                var b=executor.submit(()->{ await(barrier); page(t,s,0,LODManifestPayload.COMPLETE,new long[]{IN},new long[]{22},102); });
                a.get(10,java.util.concurrent.TimeUnit.SECONDS); b.get(10,java.util.concurrent.TimeUnit.SECONDS);
                require(!t.hasSent(IN,11) && t.syncStats().outstandingBytes()==0,"concurrent old ACK escaped snapshot fence");
                var retry=t.reserveBatch(DIM,t.streamEpoch(),new long[]{IN},new long[]{22},100,103);
                require(retry!=null && t.completeBatch(retry.streamEpoch(),retry.batchId(),true,104)
                        && t.hasSent(IN,22),"concurrent recovery did not ACK H2");
            }
        } catch(Exception e) { throw new AssertionError("manifest concurrency failed",e); }
        finally { executor.shutdownNow(); }
    }
    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
