package com.dripps.voxyserver.sync;

/**
 * A native cache placeholder is not stored terrain. Implemented on WorldSection by a required
 * mixin.
 */
public interface SectionPresence {
    boolean voxyserver$isPresent();

    void voxyserver$setPresent(boolean present);
}
