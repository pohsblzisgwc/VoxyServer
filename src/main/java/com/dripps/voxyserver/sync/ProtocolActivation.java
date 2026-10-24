package com.dripps.voxyserver.sync;

/** Protocol 0 pauses streaming; a matching advertisement resumes via a fresh handshake. */
public final class ProtocolActivation {
    private volatile boolean compatible;
    private boolean handshakePending;

    public boolean compatible() {
        return compatible;
    }

    public void handshakeSent() {
        handshakePending = true;
    }

    public void reset() {
        compatible = false;
        handshakePending = false;
    }

    /** Returns true only when an unsolicited compatible advertisement requires a handshake. */
    public boolean accept(int advertised, int expected) {
        boolean request = advertised == expected && !compatible && !handshakePending;
        compatible = advertised == expected;
        handshakePending = false;
        return request;
    }
}
