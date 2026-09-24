package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WinterRulesTest {
    /** «Ползущая» скорость блока при настройках по умолчанию. */
    private static double creep(double hardness) {
        return WinterRules.slideCreep(hardness, WinterRules.SLIDE_CREEP_SPEED,
                WinterRules.SLIDE_CREEP_HARDNESS, WinterRules.SLIDE_CREEP_MIN);
    }

    /** Торможение блока за тик при настройках по умолчанию. */
    private static double friction(double hardness) {
        return WinterRules.slideFriction(hardness, WinterRules.SLIDE_FRICTION_BASE,
                WinterRules.SLIDE_FRICTION_HARDNESS, WinterRules.SLIDE_FRICTION_MIN, WinterRules.SLIDE_FRICTION_MAX);
    }

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
            damage = WinterRules.afterUse(damage, WinterRules.CLIMB_DAMAGE);
            assertEquals(jump * 4, damage);
            assertEquals(jump == 4, WinterRules.broken(damage));
        }
        assertEquals(16, WinterRules.afterUse(15, 4));   // предел — вся прочность
        assertEquals(16, WinterRules.afterUse(16, 4));
        assertEquals(13, WinterRules.afterUse(12, 1));   // блок скольжения — 1 прочности
        assertEquals(12, WinterRules.afterUse(12, 0));   // выключенный расход
        assertEquals(12, WinterRules.afterUse(12, -5));
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
    void crouchGrabsAWallInTheAirButOnlyOnceWeStopRising() {
        assertTrue(WinterRules.canAutoGrab(true, true, true, false, false, false));
        assertFalse(WinterRules.canAutoGrab(false, true, true, false, false, false)); // инструмент не в руке
        assertFalse(WinterRules.canAutoGrab(true, false, true, false, false, false)); // стоим на земле
        assertFalse(WinterRules.canAutoGrab(true, true, false, false, false, false)); // ещё взлетаем
        assertFalse(WinterRules.canAutoGrab(true, true, true, true, false, false));   // идёт откат
        assertFalse(WinterRules.canAutoGrab(true, true, true, false, true, false));   // уже в зацепе
        assertFalse(WinterRules.canAutoGrab(true, true, true, false, false, true));   // заморожен рыбой
        assertTrue(WinterRules.notRising(0));
        assertTrue(WinterRules.notRising(-0.4));
        assertFalse(WinterRules.notRising(0.42)); // только что прыгнули от стены
    }

    @Test
    void wallJumpIsAsWeakAsAVanillaJump() {
        // Просили не модовые 1.1/1.4, а силу обычного прыжка: 0.42 вверх и лёгкий толчок.
        assertEquals(0.42, WinterRules.WALL_JUMP_UPWARD_BOOST, 1e-9);
        assertTrue(WinterRules.WALL_JUMP_FORWARD_BOOST <= 0.3);
    }

    @Test
    void harderBlocksSlideSlowerAndHoldBetter() {
        assertEquals(0.51, creep(0.5), 1e-9);   // земля, песок
        assertEquals(0.43, creep(1.5), 1e-9);   // камень
        assertEquals(0.31, creep(3.0), 1e-9);   // железная руда
        assertEquals(0.05, creep(50), 1e-9);    // обсидиан — предел
        assertTrue(creep(0.5) > creep(1.5));
        assertTrue(creep(1.5) > creep(50));
        assertEquals(0.79, friction(0.5), 1e-9);
        assertEquals(0.77, friction(1.5), 1e-9);
        assertEquals(0.55, friction(50), 1e-9); // предел торможения
        assertEquals(0.55, friction(1000), 1e-9);
        assertTrue(friction(50) < friction(0.5));
    }

    @Test
    void slideStartsFromTheFallAndSlowsDownWithoutStopping() {
        // Первый тик: что было при падении, но не выше входа и не ниже ползущей скорости.
        assertEquals(0.55, WinterRules.slideEntry(2.0, 0.43, 0.55), 1e-9);
        assertEquals(0.55, WinterRules.slideEntry(0.55, 0.43, 0.55), 1e-9);
        assertEquals(0.43, WinterRules.slideEntry(0.1, 0.43, 0.55), 1e-9);
        assertEquals(0.31, WinterRules.slideEntry(0, 0.31, 0.55), 1e-9);

        double speed = WinterRules.slideEntry(1.0, creep(1.5), 0.55);
        double previous = speed;
        for (int tick = 1; tick <= 200; tick++) {
            speed = WinterRules.slideStep(speed, creep(1.5), friction(1.5));
            assertTrue(speed <= previous, "скорость не растёт: «быстро, медленнее, ещё медленнее»");
            assertTrue(speed >= creep(1.5) - 1e-9, "скольжение не замирает в воздухе");
            previous = speed;
        }
        assertEquals(0.43, speed, 1e-3); // в итоге выходит на ползущую скорость блока
    }

    @Test
    void sandSlidesFartherThanStoneInTheSameTime() {
        double sand = 0.55, stone = 0.55;
        for (int tick = 1; tick <= 20; tick++) {
            sand = WinterRules.slideStep(sand, creep(0.5), friction(0.5));
            stone = WinterRules.slideStep(stone, creep(1.5), friction(1.5));
            assertTrue(sand > stone, "мягкий блок скользит быстрее твёрдого");
        }
    }

    @Test
    void fastSlideWearsTheToolPerBlockAndNeverInCreative() {
        assertTrue(WinterRules.slideWears(0.5, 0.35));
        assertTrue(WinterRules.slideWears(0.35, 0.35));
        assertFalse(WinterRules.slideWears(0.3, 0.35));  // медленное скольжение прочность не тратит
        assertFalse(WinterRules.slideWears(0.0, 0.35));  // вис прочности не тратит

        assertEquals(3, WinterRules.durabilityForSlide(3.7, 1)); // целые блоки
        assertEquals(0, WinterRules.durabilityForSlide(0.9, 1));
        assertEquals(4, WinterRules.durabilityForSlide(2.0, 2)); // настройка «за блок»
        assertEquals(3, WinterRules.whole(3.9));
        assertEquals(0, WinterRules.whole(0.5));
        assertEquals(0, WinterRules.whole(-2));

        // 16 блоков быстрого скольжения стирают изморозь до конца — как 4 прыжка.
        assertEquals(WinterRules.DURABILITY, WinterRules.durabilityForSlide(16, 1));
    }

    @Test
    void slidingSpeedCompensatesVanillaVerticalDrag() {
        // Ванильное трение умножает скорость на 0.98, поэтому задаём её с запасом:
        // 0.5 блока за тик должны получаться именно 0.5.
        assertEquals(0.5, WinterRules.velocityForSpeed(0.5) * WinterRules.GRAVITY_DRAG, 1e-9);
        assertTrue(WinterRules.velocityForSpeed(0.5) > 0.5);
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
        assertEquals(0, WinterRules.ticks(0, 0, 3600));   // вис без ограничения
        assertEquals(600, WinterRules.ticks(99999, 0, 600));
        assertEquals(1, WinterRules.ticks(0, 1, 100));
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
