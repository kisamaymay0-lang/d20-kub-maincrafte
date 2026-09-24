package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WinterRulesTest {
    /** Трение твёрдого блока за тик при настройках по умолчанию. */
    private static double hardFriction(double hardness) {
        return WinterRules.slideFriction(hardness, WinterRules.SLIDE_HARD_FRICTION,
                WinterRules.SLIDE_HARD_FRICTION_HARDNESS, WinterRules.SLIDE_HARD_FRICTION_MAX);
    }

    /** Ползущая скорость блока при настройках по умолчанию. */
    private static double floor(boolean soft) {
        return WinterRules.slideFloor(soft, WinterRules.SLIDE_HARD_FLOOR_SPEED, WinterRules.SLIDE_SOFT_FLOOR_SPEED);
    }

    /** Сколько тиков и блоков скольжение тормозит до ползущей скорости. */
    private static double[] deceleration(double entrySpeed, boolean soft, double hardness) {
        double floor = floor(soft), friction = soft ? WinterRules.SLIDE_SOFT_FRICTION : hardFriction(hardness);
        double speed = WinterRules.slideEntry(entrySpeed, floor, WinterRules.SLIDE_ENTRY_SPEED);
        double ticks = 0, distance = 0;
        while (speed > floor + 1e-9 && ticks < 600) {
            speed = WinterRules.slideStep(speed, floor, friction);
            distance += speed;
            ticks++;
        }
        return new double[]{ticks, distance};
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
    void harderBlocksPutUpMoreFriction() {
        assertEquals(0.11, hardFriction(0.5), 1e-9);
        assertEquals(0.13, hardFriction(1.5), 1e-9);   // камень
        assertEquals(0.16, hardFriction(3.0), 1e-9);   // железная руда
        assertEquals(0.35, hardFriction(50), 1e-9);    // обсидиан — предел
        assertEquals(0.35, hardFriction(1000), 1e-9);
        assertTrue(hardFriction(50) > hardFriction(1.5));
        assertTrue(WinterRules.SLIDE_SOFT_FRICTION < hardFriction(0.5)); // мягкий тормозит слабее
    }

    @Test
    void slideStartsFromTheFallAndDeceleratesEveryTick() {
        // Скорость входа — та, с которой игрок подлетел к стене, но не выше предела.
        assertEquals(0.72, WinterRules.slideEntry(0.72, 0.05, 1.5), 1e-9);
        assertEquals(1.5, WinterRules.slideEntry(2.4, 0.05, 1.5), 1e-9);
        assertEquals(0.05, WinterRules.slideEntry(0, 0.05, 1.5), 1e-9);   // твёрдый блок: не ниже ползущей
        assertEquals(0.30, WinterRules.slideEntry(0, 0.30, 1.5), 1e-9);  // мягкий: ползущая выше

        // Трение отнимает скорость каждый тик, но ниже ползущей блок её не отдаёт.
        assertEquals(0.87, WinterRules.slideStep(1.0, 0.05, 0.13), 1e-9);
        assertEquals(0.05, WinterRules.slideStep(0.10, 0.05, 0.13), 1e-9);

        double speed = WinterRules.slideEntry(1.5, floor(false), 1.5);
        double previous = speed;
        for (int tick = 1; tick <= 100; tick++) {
            speed = WinterRules.slideStep(speed, floor(false), hardFriction(1.5));
            assertTrue(speed <= previous, "скорость не растёт: «быстро, медленнее, ещё медленнее»");
            assertTrue(speed >= floor(false) - 1e-9, "скольжение не замирает в воздухе");
            previous = speed;
        }
        assertEquals(0.05, speed, 1e-9);
    }

    @Test
    void higherFallDeceleratesLonger() {
        // Чем выше падал, тем дольше тормозит: и по времени, и по пути.
        double[] shortFall = deceleration(0.52, false, 1.5);   // падение ~2 блока
        double[] midFall = deceleration(1.02, false, 1.5);     // ~8 блоков
        double[] bigFall = deceleration(1.50, false, 1.5);     // ~20+ блоков
        assertTrue(shortFall[0] < midFall[0], "дольше по тикам");
        assertTrue(midFall[0] < bigFall[0], "дольше по тикам");
        assertTrue(shortFall[1] < midFall[1] && midFall[1] < bigFall[1], "дольше по пути");
        assertEquals(4, shortFall[0], 1e-9);  // 0.52 -> 0.05 шагами по 0.13
        assertEquals(8, midFall[0], 1e-9);
        assertEquals(12, bigFall[0], 1e-9);
        assertEquals(0.83, shortFall[1], 1e-2);
        assertEquals(7.97, bigFall[1], 1e-2); // падение с 20 блоков: около 8 блоков скольжения
        assertTrue(bigFall[0] > 2 * shortFall[0], "торможение дольше более чем вдвое");
    }

    @Test
    void softBlocksBrakeSofterAndKeepSliding() {
        double[] sand = deceleration(1.50, true, 0.5);
        double[] stone = deceleration(1.50, false, 1.5);
        assertTrue(sand[0] > stone[0], "мягкий блок тормозит дольше");
        assertTrue(sand[1] > stone[1], "и по пути тоже");
        assertEquals(0.30, floor(true), 1e-9);
        assertEquals(0.05, floor(false), 1e-9);
        assertEquals(14, sand[0], 1e-9);
        assertEquals(11.61, sand[1], 1e-2);
        // Мягкий блок не держит: на ползущей скорости сползание продолжается, и оно быстрое.
        assertTrue(floor(true) > 6 * floor(false));
    }

    @Test
    void obsidianStopsTheSlideAlmostImmediately() {
        double[] obsidian = deceleration(1.50, false, 50);
        assertEquals(5, obsidian[0], 1e-9);          // против 12 тиков у камня
        assertEquals(2.55, obsidian[1], 1e-2);       // и меньше трёх блоков пути
        assertTrue(hardFriction(50) > hardFriction(1.5));
    }

    @Test
    void slidingSpeedCompensatesVanillaVerticalDrag() {
        // Заданная скорость доходит до клиента умноженной на 0.98, поэтому задаём её с запасом:
        // 0.5 блока за тик должны получаться именно 0.5.
        assertEquals(0.5, WinterRules.velocityForSpeed(0.5) * WinterRules.GRAVITY_DRAG, 1e-9);
        assertTrue(WinterRules.velocityForSpeed(0.5) > 0.5);
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
