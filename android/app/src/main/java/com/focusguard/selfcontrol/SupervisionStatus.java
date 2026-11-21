package com.focusguard.selfcontrol;

record SupervisionStatus(boolean paused, boolean accessibilityEnabled, boolean connected,
                         boolean usageAllowed, boolean storageReady, boolean clockValid) {
    enum State {
        STORAGE_ERROR,
        PAUSED,
        CLOCK_ERROR,
        ACCESSIBILITY_DISABLED,
        USAGE_PERMISSION_REQUIRED,
        CONNECTING,
        ACTIVE
    }

    State state() {
        if (!storageReady) return State.STORAGE_ERROR;
        if (paused) return State.PAUSED;
        if (!clockValid) return State.CLOCK_ERROR;
        if (!accessibilityEnabled) return State.ACCESSIBILITY_DISABLED;
        if (!usageAllowed) return State.USAGE_PERMISSION_REQUIRED;
        return connected ? State.ACTIVE : State.CONNECTING;
    }
}
