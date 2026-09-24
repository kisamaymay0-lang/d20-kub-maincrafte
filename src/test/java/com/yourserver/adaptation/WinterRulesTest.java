package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WinterRulesTest {
    /** Трение блока за тик при такой скорости скольжения (настройки по умолчанию). */
    private static double friction(double speed, boolean soft, double hardness) {
        double base = soft ? WinterRules.SLIDE_SOFT_FRICTION
                : WinterRules.slideFrictionBase(hardness, WinterRules.SLIDE_HARD_FRICTION,
                        WinterRules.SLIDE_HARD_FRICTION_HARDNESS, WinterRules.SLIDE_HARD_FRICTION_MAX);
        double ramp = soft ? WinterRules.SLIDE_SOFT_FRICTION_RAMP : WinterRules.SLIDE_HARD_FRICTION_RAMP;
        return WinterRules.slideFriction(speed, WinterRules.SLIDE_ENTRY_SPEED, floor(soft), base, ramp);
    }

    /** Трение твёрдого блока в начале скольжения. */
    private static double baseFriction(double hardness) {
        return WinterRules.slideFrictionBase(hardness, WinterRules.SLIDE_HARD_FRICTION,
                WinterRules.SLIDE_HARD_FRICTION_HARDNESS, WinterRules.SLIDE_HARD_FRICTION_MAX);
    }

    /** Ползущая скорость блока при настройках по умолчанию. */
    private static double floor(boolean soft) {
        return WinterRules.slideFloor(soft, WinterRules.SLIDE_HARD_FLOOR_SPEED, WinterRules.SLIDE_SOFT_FLOOR_SPEED);
    }

    /** Сколько тиков и блоков скольжение тормозит до ползущей скорости. */
    private static double[] deceleration(double entrySpeed, boolean soft, double hardness) {
        double floor = floor(soft);
        double speed = WinterRules.slideEntry(entrySpeed, floor, WinterRules.SLIDE_ENTRY_SPEED);
        double ticks = 0, distance = 0;
        while (speed > floor + 1e-9 && ticks < 600) {
            speed = WinterRules.slideStep(speed, floor, friction(speed, soft, hardness));
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
    void wallJumpGoesHigherThanAVanillaJump() {
        // Прыжок от стены выше обычного (0.42), чтобы заново цепляться выше и карабкаться,
        // но всё ещё далеко не модовые 1.1 — тяжёлого выброса нет.
        assertEquals(0.55, WinterRules.WALL_JUMP_UPWARD_BOOST, 1e-9);
        assertTrue(WinterRules.WALL_JUMP_UPWARD_BOOST > 0.42, "выше обычного прыжка");
        assertTrue(WinterRules.WALL_JUMP_UPWARD_BOOST < 0.7, "и всё ещё умеренный");
        assertTrue(WinterRules.WALL_JUMP_FORWARD_BOOST <= 0.3);
    }

    @Test
    void harderBlocksPutUpMoreFriction() {
        assertEquals(0.07, baseFriction(0.5), 1e-9);   // земля, песок
        assertEquals(0.09, baseFriction(1.5), 1e-9);   // камень
        assertEquals(0.12, baseFriction(3.0), 1e-9);   // железная руда
        assertEquals(0.35, baseFriction(50), 1e-9);    // обсидиан — предел
        assertEquals(0.35, baseFriction(1000), 1e-9);
        assertTrue(baseFriction(50) > baseFriction(1.5));
        assertTrue(WinterRules.SLIDE_SOFT_FRICTION < baseFriction(0.5)); // мягкий тормозит слабее
    }

    @Test
    void slideStartsFromTheFallAndDeceleratesEveryTick() {
        // Скорость входа — та, с которой игрок подлетел к стене, но не выше предела.
        assertEquals(0.72, WinterRules.slideEntry(0.72, 0.12, 1.5), 1e-9);
        assertEquals(1.5, WinterRules.slideEntry(2.4, 0.12, 1.5), 1e-9);
        assertEquals(0.12, WinterRules.slideEntry(0, 0.12, 1.5), 1e-9);   // твёрдый блок: не ниже ползущей
        assertEquals(0.30, WinterRules.slideEntry(0, 0.30, 1.5), 1e-9);  // мягкий: ползущая выше

        // Трение отнимает скорость каждый тик, но ниже ползущей блок её не отдаёт.
        assertEquals(0.91, WinterRules.slideStep(1.0, 0.12, 0.09), 1e-9);
        assertEquals(0.12, WinterRules.slideStep(0.14, 0.12, 0.19), 1e-9);

        double speed = WinterRules.slideEntry(1.5, floor(false), 1.5);
        double previous = speed;
        for (int tick = 1; tick <= 100; tick++) {
            speed = WinterRules.slideStep(speed, floor(false), friction(speed, false, 1.5));
            assertTrue(speed <= previous, "скорость не растёт: «быстро, медленнее, ещё медленнее»");
            assertTrue(speed >= floor(false) - 1e-9, "скольжение не замирает в воздухе");
            previous = speed;
        }
        assertEquals(0.12, speed, 1e-9);
    }

    @Test
    void frictionGrowsAsTheSlideSlowsDown() {
        // «Фазы» торможения: в начале скольжения блок почти не мешает, к концу держит крепко.
        double atEntry = friction(1.5, false, 1.5);
        double middle = friction(0.8, false, 1.5);
        double nearFloor = friction(0.13, false, 1.5);
        assertEquals(0.09, atEntry, 1e-9);
        assertTrue(atEntry < middle && middle < nearFloor, "трение растёт по мере замедления");
        assertEquals(0.19, nearFloor, 1e-2);                         // base + ramp у ползущей скорости
        assertEquals(atEntry + WinterRules.SLIDE_HARD_FRICTION_RAMP, friction(floor(false), false, 1.5), 1e-9);

        // И растёт оно плавно: за скольжение проходит больше десятка разных значений.
        java.util.Set<String> steps = new java.util.HashSet<>();
        double speed = 1.5;
        while (speed > floor(false) + 1e-9) {
            double current = friction(speed, false, 1.5);
            steps.add(String.format("%.4f", current));
            speed = WinterRules.slideStep(speed, floor(false), current);
        }
        assertTrue(steps.size() >= 10, "фаз торможения не меньше десяти, а не три-четыре");
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
        assertEquals(3, shortFall[0], 1e-9);
        assertEquals(7, midFall[0], 1e-9);
        assertEquals(11, bigFall[0], 1e-9);
        assertEquals(0.67, shortFall[1], 1e-2);
        assertEquals(8.92, bigFall[1], 1e-2); // падение с 20 блоков: около 9 блоков скольжения
        assertTrue(bigFall[0] > 3 * shortFall[0], "торможение дольше более чем втрое");
    }

    @Test
    void softBlocksBrakeSofterAndKeepSliding() {
        double[] sand = deceleration(1.50, true, 0.5);
        double[] stone = deceleration(1.50, false, 1.5);
        assertTrue(sand[0] > stone[0], "мягкий блок тормозит дольше");
        assertTrue(sand[1] > stone[1], "и по пути тоже");
        assertEquals(15, sand[0], 1e-9);
        assertEquals(13.71, sand[1], 1e-2);
        // Мягкий блок не держит: ползущая скорость втрое больше, сползание продолжается.
        assertEquals(0.30, floor(true), 1e-9);
        assertEquals(0.12, floor(false), 1e-9);
        assertTrue(floor(true) > 2 * floor(false));
    }

    @Test
    void obsidianStopsTheSlideAlmostImmediately() {
        double[] obsidian = deceleration(1.50, false, 50);
        assertEquals(4, obsidian[0], 1e-9);          // против 11 тиков у камня
        assertEquals(2.42, obsidian[1], 1e-2);       // и меньше трёх блоков пути
        assertTrue(baseFriction(50) > baseFriction(1.5));
    }

    @Test
    void crouchKeepsCreepingInsteadOfStopping() {
        // Присед больше не останавливает: игрок сползает на прежней минимальной скорости.
        assertEquals(0.05, WinterRules.SLIDE_HOLD_FLOOR_SPEED, 1e-9);
        assertTrue(WinterRules.SLIDE_HOLD_FLOOR_SPEED < WinterRules.SLIDE_HARD_FLOOR_SPEED);
        // Сползание медленнее «быстрого» скольжения, поэтому прочность на нём не тратится.
        assertTrue(WinterRules.SLIDE_HOLD_FLOOR_SPEED < WinterRules.SLIDE_DAMAGE_MIN_SPEED);
    }

    @Test
    void slidingPutsTheRimeOnCooldownForFourSeconds() {
        assertEquals(80, WinterRules.SLIDE_COOLDOWN_TICKS);
        assertEquals(80, WinterRules.ticks(4.0, 0, 600));
        assertEquals(0, WinterRules.ticks(0, 0, 600));      // откат можно выключить
        assertTrue(WinterRules.SLIDE_COOLDOWN_TICKS > 40, "откат заметный, но не модовые пять секунд");
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
