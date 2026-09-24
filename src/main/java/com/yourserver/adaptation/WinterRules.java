package com.yourserver.adaptation;

import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;

/** Чистые правила зимнего улова и зацепа, без доступа к серверу. */
final class WinterRules {
    static final int DURABILITY = 16;
    static final int CLIMB_DAMAGE = 4;
    static final int FISH_LOCK_TICKS = 160;
    static final int SANDWICH_LOCK_TICKS = 80;
    static final int COLD_TICKS = 200;
    static final Set<String> BIOMES = Set.of(
            "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_taiga",
            "minecraft:snowy_beach", "minecraft:frozen_river", "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean", "minecraft:grove", "minecraft:snowy_slopes",
            "minecraft:frozen_peaks", "minecraft:jagged_peaks");

    /* ==== Зацеп — механика мода Gouge (https://modrinth.com/mod/gouge).
       Управление: ПКМ по стене в падении — зацеп, присед — стоп (на твёрдом блоке),
       пробел на приседе — прыжок от стены. Роль hardness играет ванильная прочность
       блока (destroy speed): чем прочнее блок, тем сильнее он тормозит скольжение. ==== */
    static final double GRAB_REACH = 2.5;              // mechanics.reach
    static final double MIN_FALL_CLEARANCE = 1.5;      // mechanics.min_fall_distance (только для ПКМ)
    static final double MAX_DRIFT = 1.5;               // mechanics.max_drift
    static final int HANG_TICKS = 200;                 // pickaxes.diamond_pickaxe.hang_time = 10 с;
                                                       // в config.yml по умолчанию 0 — держит без ограничения
    static final int SLIP_COOLDOWN_TICKS = 100;        // mechanics.slip_cooldown = 5 с (модовое значение);
                                                       // в config.yml стоит 0 — чар Momentum, который срезал бы
                                                       // паузу, здесь нет, и она мешала бы связывать прыжки
    static final int SOFT_FALL_GRACE_TICKS = 5;        // окно после скольжения по мягкому блоку
    static final double AUTO_GRAB_MAX_RISE = 0.0;      // пока взлетаем, присед ещё не цепляется: зацеп по апогею
    static final double WALL_JUMP_FORWARD_BOOST = 0.2; // от стены — скорость ходьбы, а не модовые 1.4
    static final double WALL_JUMP_UPWARD_BOOST = 0.42; // вверх — ровно сила обычного прыжка игрока
    static final double SOFT_FALL_DAMAGE = 0.5;        // mechanics.soft_fall_damage
    static final double SOFT_FALL_DAMAGE_CAP = 6.0;    // mechanics.soft_fall_damage_cap = 3 сердца

    /* Скольжение: скорость входа, торможение и «ползущая» скорость — всё от прочности блока. */
    static final double HORIZONTAL_DAMPING = 0.5;      // mod HORIZONTAL_DAMPING: снос в сторону за тик
    static final double GRAVITY_DRAG = 0.98;           // ванильное вертикальное трение: фактическая скорость = заданная * 0.98
    static final double SLIDE_ENTRY_SPEED = 0.55;      // быстрее этого скольжение не начинается
    static final double SLIDE_FRICTION_BASE = 0.80;    // -20% скорости за тик на мягком блоке
    static final double SLIDE_FRICTION_HARDNESS = 0.02;
    static final double SLIDE_FRICTION_MIN = 0.55;
    static final double SLIDE_FRICTION_MAX = 0.92;
    static final double SLIDE_CREEP_SPEED = 0.55;      // «ползущая» скорость мягкого блока
    static final double SLIDE_CREEP_HARDNESS = 0.08;   // каждый пункт прочности отнимает её у блока
    static final double SLIDE_CREEP_MIN = 0.05;        // обсидиан: почти держит, но не замирает в воздухе
    static final double SLIDE_DAMAGE_MIN_SPEED = 0.35; // «быстрое» скольжение — то, что стирает инструмент
    static final int SLIDE_DAMAGE_PER_BLOCK = 1;       // прочности за блок такого скольжения

    private WinterRules() { }

    /** 2% без «Удачи моря», +1% за уровень (I–III) до максимума 5%. */
    static double catchChance(int luck) { return 0.02 + 0.01 * Math.clamp(luck, 0, 3); }

    /** Секунды конфига в тики с ограничением: значения вне диапазона заменяются границей. */
    static int ticks(double seconds, int min, int max) { return Math.clamp((int) Math.round(seconds * 20), min, max); }

    /** Зацеп по ПКМ: инструмент в руке, игрок в воздухе и падает, под ногами есть запас высоты,
     *  проскальзывание ещё не откатилось и другого зацепа нет. */
    static boolean canGrab(boolean tool, boolean airborne, boolean falling, boolean clearance,
            boolean cooling, boolean gripping, boolean frozen) {
        return tool && airborne && falling && clearance && !cooling && !gripping && !frozen;
    }

    /** Зацеп приседом у стены: высоту под ногами не проверяем — так цепляются сразу
     *  после обычного прыжка с пола, как только игрок перестал взлетать. */
    static boolean canAutoGrab(boolean tool, boolean airborne, boolean notRising,
            boolean cooling, boolean gripping, boolean frozen) {
        return tool && airborne && notRising && !cooling && !gripping && !frozen;
    }

    /** Ещё взлетаем (например, только что прыгнули от стены) — цепляться рано. */
    static boolean notRising(double verticalSpeed) { return verticalSpeed <= AUTO_GRAB_MAX_RISE; }

    /** Торможение за тик: прочный блок гасит скорость сильнее мягкого. */
    static double slideFriction(double hardness, double base, double scale, double min, double max) {
        return Math.clamp(base - hardness * scale, Math.min(min, max), Math.max(min, max));
    }

    /** «Ползущая» скорость блока: ниже неё скольжение не гаснет, иначе игрок завис бы в воздухе. */
    static double slideCreep(double hardness, double base, double scale, double min) {
        return Math.max(base - hardness * scale, min);
    }

    /** Первый тик скольжения: скорость падения, но не выше входа и не ниже ползущей. */
    static double slideEntry(double fallSpeed, double creep, double entrySpeed) {
        return Math.clamp(Math.max(fallSpeed, creep), creep, Math.max(creep, entrySpeed));
    }

    /** Тик скольжения: скорость тянется к ползущей и никогда её не пересекает. */
    static double slideStep(double speed, double creep, double friction) {
        return creep + (speed - creep) * friction;
    }

    /** Быстрое скольжение — то, за которое инструмент стирается. */
    static boolean slideWears(double speed, double minSpeed) { return speed >= minSpeed; }

    /** Ванильное вертикальное трение: чтобы фактическая скорость совпала с задуманной. */
    static double velocityForSpeed(double speed) { return speed / GRAVITY_DRAG; }

    /** Целые единицы из накопленного расхода: прочность списывается только целиком. */
    static int whole(double accumulated) { return accumulated <= 0 ? 0 : (int) Math.floor(accumulated); }

    /** Прочности за накопленные блоки скольжения (для тестов и документации). */
    static int durabilityForSlide(double blocks, int perBlock) { return whole(blocks * perBlock); }

    /** Мягкое скольжение: падение слабее в softFallDamage раз и не больше cap (в очках урона, 2 = сердце). */
    static double softFallDamage(double damage, double multiplier, double cap) {
        double softened = Math.round(Math.max(0, damage) * multiplier);
        return cap > 0 ? Math.min(softened, cap) : softened;
    }

    static NamedTextColor titleColor(String kind) {
        return switch (kind) {
            case "DEPLETED" -> NamedTextColor.GRAY;
            case "SANDWICH" -> NamedTextColor.GOLD;
            default -> NamedTextColor.AQUA;
        };
    }
    static boolean forbiddenEnchant(boolean winterTool, boolean book, boolean enchanted, boolean customRune) {
        return winterTool && (book || enchanted || customRune);
    }
    static int afterUse(int damage, int amount) { return Math.min(DURABILITY, Math.max(0, damage) + Math.max(0, amount)); }
    static boolean broken(int damage) { return damage >= DURABILITY; }
}
