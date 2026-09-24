package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WinterRulesTest {
    @ParameterizedTest
    @CsvSource({"0,0.02", "1,0.03", "2,0.04", "3,0.05", "-1,0.02", "100,0.05"})
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
    void fourWallJumpsUseAllSixteenDurability() {
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
    void gripNeedsToolAirborneFallingPlayerWithClearance() {
        assertTrue(WinterRules.canGrab(true, true, true, true, false, false, false));
        assertFalse(WinterRules.canGrab(false, true, true, true, false, false, false)); // инструмент не в руке
        assertFalse(WinterRules.canGrab(true, false, true, true, false, false, false)); // уже на земле
        assertFalse(WinterRules.canGrab(true, true, false, true, false, false, false)); // не падаем
        assertFalse(WinterRules.canGrab(true, true, true, false, false, false, false)); // под ногами блок
        assertFalse(WinterRules.canGrab(true, true, true, true, true, false, false));   // идёт откат
        assertFalse(WinterRules.canGrab(true, true, true, true, false, true, false));   // уже в зацепе
        assertFalse(WinterRules.canGrab(true, true, true, true, false, false, true));   // заморожен рыбой
    }

    @Test
    void wallJumpNeedsSecondCrouchInsideTheWindow() {
        assertTrue(WinterRules.doubleTap(0, 14, WinterRules.WALL_JUMP_WINDOW_TICKS));
        assertTrue(WinterRules.doubleTap(10, 20, WinterRules.WALL_JUMP_WINDOW_TICKS));
        assertFalse(WinterRules.doubleTap(0, 15, WinterRules.WALL_JUMP_WINDOW_TICKS));
        assertFalse(WinterRules.doubleTap(0, 100, WinterRules.WALL_JUMP_WINDOW_TICKS));
        assertFalse(WinterRules.doubleTap(-1, 0, WinterRules.WALL_JUMP_WINDOW_TICKS)); // присед ещё не отпускали
    }

    @Test
    void harderBlocksBleedFallingSpeedFaster() {
        assertEquals(0.90, WinterRules.hardFriction(0), 1e-9);
        assertEquals(0.855, WinterRules.hardFriction(1.5), 1e-9);   // камень
        assertEquals(0.55, WinterRules.hardFriction(50), 1e-9);     // обсидиан — предел
        assertEquals(0.55, WinterRules.hardFriction(1000), 1e-9);
    }

    @Test
    void softBlocksSlideAtConstantSpeedWithoutLocks() {
        assertEquals(0.5, WinterRules.softSlideSpeed(0), 1e-9);
        assertEquals(0.435, WinterRules.softSlideSpeed(0.5), 1e-9); // земля, песок
        assertEquals(0.3, WinterRules.softSlideSpeed(10), 1e-9);    // предел
        assertTrue(WinterRules.softSlideSpeed(1) < WinterRules.softSlideSpeed(0));
    }

    @Test
    void theToolLocksInPlaceOnlyAfterTheFallIsNearlyStopped() {
        assertTrue(WinterRules.locksInPlace(0));
        assertTrue(WinterRules.locksInPlace(-0.079));
        assertFalse(WinterRules.locksInPlace(-0.08));
        assertFalse(WinterRules.locksInPlace(-0.5));
        assertFalse(WinterRules.locksInPlace(0.2));
    }

    @Test
    void softSlideHalvesFallDamageAndCapsItAtThreeHearts() {
        assertEquals(6.0, WinterRules.softFallDamage(30, 0.5, 6), 1e-9); // предел — 3 сердца
        assertEquals(5.0, WinterRules.softFallDamage(10, 0.5, 6), 1e-9);
        assertEquals(2.0, WinterRules.softFallDamage(3, 0.5, 6), 1e-9);  // округление как в моде
        assertEquals(0.0, WinterRules.softFallDamage(0, 0.5, 6), 1e-9);
        assertEquals(5.0, WinterRules.softFallDamage(10, 0.5, 0), 1e-9);  // предел выключен, множитель остался
        assertEquals(6.0, WinterRules.softFallDamage(10, 1.0, 6), 1e-9);  // предел работает при любом множителе
        assertEquals(10.0, WinterRules.softFallDamage(10, 1.0, 0), 1e-9); // ни множителя, ни предела
    }

    @Test
    void configSecondsBecomeTicksAndAreClamped() {
        assertEquals(14, WinterRules.ticks(0.7, 1, 100));
        assertEquals(200, WinterRules.ticks(10, 0, 3600));
        assertEquals(0, WinterRules.ticks(-5, 0, 600));
        assertEquals(600, WinterRules.ticks(99999, 0, 600));
        assertEquals(1, WinterRules.ticks(0, 1, 100));
    }

    @Test
    void gripCorrectionPreservesTheLatestCameraAngles() {
        org.bukkit.Location anchor = new org.bukkit.Location(null, 10, 20, 30, 0, 0);
        org.bukkit.Location attempt = new org.bukkit.Location(null, 10.2, 19.9, 30.1, 125, -45);
        var corrected = WinterRules.anchoredLook(anchor, attempt);
        assertEquals(10, corrected.getX());
        assertEquals(20, corrected.getY());
        assertEquals(30, corrected.getZ());
        assertEquals(125, corrected.getYaw());
        assertEquals(-45, corrected.getPitch());
        assertEquals(0, anchor.getYaw());
        assertEquals(10.2, attempt.getX());
    }

    @Test
    void winterColorsMatchIceDepletedFishAndExistingSandwiches() {
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.AQUA, WinterRules.titleColor("TOOL"));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.AQUA, WinterRules.titleColor("RAW"));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.AQUA, WinterRules.titleColor("ROE"));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.GRAY, WinterRules.titleColor("DEPLETED"));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.GOLD, WinterRules.titleColor("SANDWICH"));
    }

    @Test
    void enchantmentIsBlockedButOrdinaryRepairAndRenameRemainAllowed() {
        assertTrue(WinterRules.forbiddenEnchant(true, true, false, false));
        assertTrue(WinterRules.forbiddenEnchant(true, false, true, false));
        assertTrue(WinterRules.forbiddenEnchant(true, false, false, true));
        assertFalse(WinterRules.forbiddenEnchant(true, false, false, false));
        assertFalse(WinterRules.forbiddenEnchant(false, true, true, true));
    }

}
