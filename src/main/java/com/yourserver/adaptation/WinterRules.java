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
    static final int SOFT_FALL_GRACE_TICKS = 5;        // окно после скольжения по мягкому блоку
    static final double AUTO_GRAB_MAX_RISE = 0.0;      // пока взлетаем, присед ещё не цепляется: зацеп по апогею
    static final double WALL_JUMP_FORWARD_BOOST = 0.2; // от стены — скорость ходьбы, а не модовые 1.4
    static final double WALL_JUMP_UPWARD_BOOST = 0.55; // вверх — больше обычного прыжка (0.42): чтобы
                                                       // заново цепляться выше и карабкаться вверх
    static final double SOFT_FALL_DAMAGE = 0.5;        // mechanics.soft_fall_damage
    static final double SOFT_FALL_DAMAGE_CAP = 6.0;    // mechanics.soft_fall_damage_cap = 3 сердца

    /* Скольжение: скорость входа даёт падение, дальше трение блока отнимает её каждый тик,
       причём тем сильнее, чем сильнее игрок замедлился — торможение идёт плавными фазами:
       сначала почти не мешает, к концу держит крепко. Трение подобрано так, чтобы скорость
       таяла постепенно: с падения в 20+ блоков минимальная скорость приходит только к
       16-му блоку скольжения. Твёрдый блок держит крепче мягкого, поэтому скольжение гаснет
       почти в ноль; у мягкого «ползущая» скорость высокая, и сползание продолжается.
       Присед не останавливает совсем, а переводит на тихий шаг — hold-floor-speed. */
    static final double SLIDE_ENTRY_SPEED = 1.5;             // предел скорости входа: её даёт высота падения
    static final double SLIDE_HARD_FRICTION = 0.025;         // трение твёрдого блока в начале скольжения
    static final double SLIDE_HARD_FRICTION_RAMP = 0.03;     // и надбавка к нему у «ползущей» скорости
    static final double SLIDE_HARD_FRICTION_HARDNESS = 0.02; // чем прочнее блок, тем сильнее трение
    static final double SLIDE_HARD_FRICTION_MAX = 0.35;      // предел: обсидиан гасит почти сразу
    static final double SLIDE_HARD_FLOOR_SPEED = 0.12;       // минимальная скорость скольжения (2.4 блока в секунду)
    static final double SLIDE_HOLD_FLOOR_SPEED = 0.05;       // на приседе инструмент сползает тише: 1 блок в секунду
    static final double SLIDE_SOFT_FRICTION = 0.04;          // мягкий блок тормозит слабее
    static final double SLIDE_SOFT_FRICTION_RAMP = 0.025;
    static final double SLIDE_SOFT_FLOOR_SPEED = 0.30;       // и не держит: сползание продолжается
    static final int SLIDE_COOLDOWN_TICKS = 80;              // откат изморози после скольжения — 4 секунды

    /* Ванильная механика: скорость падения растёт как (v + 0.08) * 0.98, а заданная скорость
       доходит до клиента умноженной на 0.98 — отсюда множитель в velocityForSpeed. */
    static final double HORIZONTAL_DAMPING = 0.5;            // mod HORIZONTAL_DAMPING: снос в сторону за тик
    static final double GRAVITY_DRAG = 0.98;
    static final double SLIDE_DAMAGE_MIN_SPEED = 0.8;        // «быстрое» скольжение — то, что стирает инструмент
    static final int SLIDE_DAMAGE_PER_BLOCK = 1;             // прочности за блок такого скольжения

    private WinterRules() { }

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

    /** Скорость входа в скольжение: её даёт падение — чем выше падал, тем быстрее начнёшь. */
    static double slideEntry(double fallSpeed, double floorSpeed, double maxSpeed) {
        return Math.clamp(Math.max(fallSpeed, floorSpeed), floorSpeed, Math.max(floorSpeed, maxSpeed));
    }

    /** Трение твёрдого блока в начале скольжения: чем прочнее блок, тем крепче он держит. */
    static double slideFrictionBase(double hardness, double base, double scale, double max) {
        return Math.min(base + hardness * scale, Math.max(base, max));
    }

    /** Трение за тик: чем сильнее игрок замедлился, тем крепче держит блок — торможение идёт
     *  фазами, от почти незаметного в начале до крепкого у «ползущей» скорости. */
    static double slideFriction(double speed, double entrySpeed, double floorSpeed, double base, double ramp) {
        double span = Math.max(entrySpeed - floorSpeed, 1e-6);
        double progress = Math.clamp((entrySpeed - speed) / span, 0.0, 1.0);
        return base + ramp * progress;
    }

    /** Тик скольжения: трение отнимает скорость, но ниже «ползущей» блок её не отдаёт. */
    static double slideStep(double speed, double floorSpeed, double friction) {
        return Math.max(floorSpeed, speed - friction);
    }

    /** «Ползущая» скорость блока: у мягкого она высокая — инструмент мягкое не держит. */
    static double slideFloor(boolean soft, double hardFloorSpeed, double softFloorSpeed) {
        return soft ? softFloorSpeed : hardFloorSpeed;
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
