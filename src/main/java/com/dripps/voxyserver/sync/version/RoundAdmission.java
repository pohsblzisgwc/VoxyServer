package com.dripps.voxyserver.sync.version;

/** One round per player/epoch. Busy attempts never replace or retire the active round. */
public final class RoundAdmission {
    public enum Result {
        NEW,
        REPLAY,
        BUSY,
        STALE
    }

    private long active, last;

    public synchronized Result begin(long id) {
        if (id <= 0) throw new IllegalArgumentException("round ID");
        if (active != 0) return active == id ? Result.REPLAY : Result.BUSY;
        if (id <= last) return Result.STALE;
        active = id;
        last = id;
        return Result.NEW;
    }

    public synchronized boolean finish(long id) {
        if (active != id) return false;
        active = 0;
        return true;
    }

    public synchronized void reset() {
        active = 0;
        last = 0;
    }

    public synchronized long active() {
        return active;
    }
}
