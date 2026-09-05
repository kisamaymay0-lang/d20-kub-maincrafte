package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DiscTracksTest {

    @ParameterizedTest
    @CsvSource({"178,3560", "71,1420", "175,3500", "134,2680", "1.25,25", "0.001,1", "1.025,21"})
    void songDurationComesFromRegistrySecondsAndRoundsUp(float seconds, long expectedTicks) {
        assertEquals(expectedTicks, DiscTracks.durationTicks(seconds));
    }

    @Test
    void malformedDurationsCannotCreateALoopWithZeroOrInvalidLength() {
        assertEquals(0, DiscTracks.durationTicks(0));
        assertEquals(0, DiscTracks.durationTicks(-1));
        assertEquals(0, DiscTracks.durationTicks(Float.NaN));
        assertEquals(0, DiscTracks.durationTicks(Float.POSITIVE_INFINITY));
        assertEquals(0, DiscTracks.durationTicks(Float.NEGATIVE_INFINITY));
    }
}
