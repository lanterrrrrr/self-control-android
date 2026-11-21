package com.focusguard.selfcontrol;

import com.focusguard.selfcontrol.SupervisionStatus.State;

public final class SupervisionStatusTest {
    public static void main(String[] args) {
        check(State.ACTIVE, false, true, true, true, true, true);
        check(State.CONNECTING, false, true, false, true, true, true);
        check(State.ACCESSIBILITY_DISABLED, false, false, true, true, true, true);
        check(State.USAGE_PERMISSION_REQUIRED, false, true, true, false, true, true);
        check(State.USAGE_PERMISSION_REQUIRED, false, true, false, false, true, true);
        check(State.CLOCK_ERROR, false, true, true, true, true, false);
        check(State.STORAGE_ERROR, false, true, true, true, false, false);
        check(State.PAUSED, true, true, true, true, true, true);
        check(State.PAUSED, true, false, false, false, true, false);
        check(State.STORAGE_ERROR, true, false, false, false, false, false);
        System.out.println("10 supervision status scenarios passed");
    }

    private static void check(State expected, boolean paused, boolean accessibilityEnabled,
                              boolean connected, boolean usageAllowed, boolean storageReady, boolean clockValid) {
        State actual = new SupervisionStatus(paused, accessibilityEnabled, connected,
                usageAllowed, storageReady, clockValid).state();
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
