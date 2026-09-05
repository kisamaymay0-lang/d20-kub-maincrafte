package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PowerEdgeTrackerTest {

    private final PowerEdgeTracker tracker = new PowerEdgeTracker();

    @Test
    void handHitsWithoutRedstoneNeverStartPlayback() {
        for (int hit = 0; hit < 100; hit++) {
            assertFalse(tracker.update("block", false));
        }
    }

    @Test
    void constantPowerAndAdditionalHandHitsDoNotRetriggerPlayback() {
        assertTrue(tracker.update("block", true));
        for (int hit = 0; hit < 100; hit++) {
            assertFalse(tracker.update("block", true));
        }
    }

    @Test
    void aNewRedstonePulseStartsPlaybackOnce() {
        assertTrue(tracker.update("block", true));
        assertFalse(tracker.update("block", false));
        assertFalse(tracker.update("block", false));
        assertTrue(tracker.update("block", true));
        assertFalse(tracker.update("block", true));
    }

    @Test
    void blocksAreIndependentAndCleanupForgetsOnlyTheMovedBlock() {
        assertTrue(tracker.update("one", true));
        assertTrue(tracker.update("two", true));
        tracker.remove("one");
        assertTrue(tracker.update("one", true));
        assertFalse(tracker.update("two", true));
        tracker.clear();
        assertTrue(tracker.update("two", true));
    }
}
