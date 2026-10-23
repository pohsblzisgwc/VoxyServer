package com.dripps.voxyserver.util;

import java.security.SecureRandom;

/** Opaque epochs are never restored from a backup or compared by age. */
public final class SessionEpoch {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SessionEpoch() {}
    public static long next() {
        return RANDOM.nextLong(1L, Long.MAX_VALUE);
    }
}
