package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WinterRulesTest {
    @ParameterizedTest
    @CsvSource({"0,0.08", "1,0.10", "2,0.12", "3,0.14", "-1,0.08", "100,0.14"})
    void fishingOddsMatchLuckOfTheSea(int luck, double expected) {
        assertEquals(expected, WinterRules.catchChance(luck), 1e-9);
    }

    @Test
    void onlySnowyAndFrozenBiomesAreIncluded() {
        assertEquals(11, WinterRules.BIOMES.size());
        assertTrue(WinterRules.BIOMES.contains("minecraft:ice_spikes"));
        assertTrue(WinterRules.BIOMES.contains("minecraft:frozen_ocean"));
        assertTrue(WinterRules.BIOMES.contains("minecraft:grove"));
        assertTrue(WinterRules.BIOMES.contains("minecraft:jagged_peaks"));
        assertFalse(WinterRules.BIOMES.contains("minecraft:cold_ocean"));
        assertFalse(WinterRules.BIOMES.contains("minecraft:deep_cold_ocean"));
        assertFalse(WinterRules.BIOMES.contains("minecraft:taiga"));
        assertFalse(WinterRules.BIOMES.contains("minecraft:stony_peaks"));
    }

    @Test
    void fourClimbingJumpsUseAllSixteenDurability() {
        int damage = 0;
        for (int jump = 1; jump <= 4; jump++) {
            damage = WinterRules.afterClimb(damage);
            assertEquals(jump * 4, damage);
            assertEquals(jump == 4, WinterRules.broken(damage));
        }
        assertEquals(16, WinterRules.afterClimb(15));
        assertEquals(16, WinterRules.afterClimb(16));
    }

    @Test
    void initialGripRequiresThePressEdgeAndAirbornePlayer() {
        assertTrue(WinterRules.canGrab(true, true, true, false, false));
        assertFalse(WinterRules.canGrab(false, true, true, false, false));
        assertFalse(WinterRules.canGrab(true, false, true, false, false));
        assertFalse(WinterRules.canGrab(true, true, false, false, false));
        assertFalse(WinterRules.canGrab(true, true, true, true, false));
        assertFalse(WinterRules.canGrab(true, true, true, false, true));
    }

    @Test
    void holdingJumpDoesNotRepeatedlySpendDurability() {
        assertTrue(WinterRules.jumpPressed(false, true));
        assertFalse(WinterRules.jumpPressed(true, true));
        assertFalse(WinterRules.jumpPressed(true, false));
        assertFalse(WinterRules.jumpPressed(false, false));
    }

    @Test
    void automaticGripWaitsForDescentAfterTheJumpImpulse() {
        assertFalse(WinterRules.descending(10, 10.1, 5));
        assertFalse(WinterRules.descending(10, 10, 5));
        assertFalse(WinterRules.descending(10, 9.9, 1));
        assertTrue(WinterRules.descending(10, 9.9, 5));
    }

    @Test
    void foodAndColdTimersAreIndependentAndHaveTheRequestedDurations() {
        assertEquals(8 * 20, WinterRules.FISH_LOCK_TICKS);
        assertEquals(4 * 20, WinterRules.SANDWICH_LOCK_TICKS);
        assertEquals(10 * 20, WinterRules.COLD_TICKS);
        assertTrue(WinterRules.CLIMB_VELOCITY > 0.42);
    }
}
