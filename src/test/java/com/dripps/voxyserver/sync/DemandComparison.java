package com.dripps.voxyserver.sync;

/** Only server-present leaves are replacements. Unknown client hashes always need delivery. */
public final class DemandComparison {
    private DemandComparison() {}

    public static long needed(long coverage, long[] server, long[] client, boolean reuse) {
        if (server.length != 64 || client.length != 64)
            throw new IllegalArgumentException("expected 64 hashes");
        long needed = 0;
        for (int i = 0; i < 64; i++)
            if ((coverage & (1L << i)) != 0 && (!reuse || client[i] == 0 || client[i] != server[i]))
                needed |= 1L << i;
        return needed;
    }
}
