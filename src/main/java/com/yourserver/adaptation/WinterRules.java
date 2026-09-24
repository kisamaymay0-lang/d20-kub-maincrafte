package com.yourserver.adaptation;

import java.util.Set;
import org.bukkit.Location;
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

    /* ==== Зацеп и прыжки со стены — механика мода Gouge (https://modrinth.com/mod/gouge).
       Числа — значения gouge.toml по умолчанию; роль hardness играет ванильная прочность
       блока (destroy speed), поэтому твёрдые блоки гасят падение быстрее мягких. ==== */
    static final double GRAB_REACH = 2.5;              // mechanics.reach
    static final double MIN_FALL_CLEARANCE = 1.5;      // mechanics.min_fall_distance
    static final double MAX_DRIFT = 1.5;               // mechanics.max_drift
    static final int HANG_TICKS = 200;                 // pickaxes.diamond_pickaxe.hang_time = 10 с
    static final int SLIP_COOLDOWN_TICKS = 0;          // mechanics.slip_cooldown; чар Grip и Momentum здесь нет
    static final int SOFT_FALL_GRACE_TICKS = 5;        // окно после мягкого скольжения, в котором падение мягче
    static final int WALL_JUMP_WINDOW_TICKS = 14;      // wall_jump.time_window = 0.7 с
    static final int WALL_JUMP_COOLDOWN_TICKS = 0;     // в моде прыжок тоже запускает slip_cooldown
    static final double WALL_JUMP_FORWARD_BOOST = 1.4; // wall_jump.forward_boost
    static final double WALL_JUMP_UPWARD_BOOST = 1.1;  // wall_jump.upward_boost
    static final double SOFT_FALL_DAMAGE = 0.5;        // mechanics.soft_fall_damage
    static final double SOFT_FALL_DAMAGE_CAP = 6.0;    // mechanics.soft_fall_damage_cap = 3 сердца
    static final double HARD_FRICTION_BASE = 0.90;
    static final double HARD_FRICTION_SCALE = 0.03;
    static final double HARD_MIN_FRICTION = 0.55;
    static final double HARD_MAX_FRICTION = 0.90;
    static final double HORIZONTAL_DAMPING = 0.5;
    static final double HANG_LOCK_SPEED = 0.08;
    static final double SOFT_SLIDE_MAX_SPEED = 0.5;
    static final double SOFT_SLIDE_MIN_SPEED = 0.3;
    static final double SOFT_SLIDE_HARDNESS_SCALE = 0.13;

    private WinterRules() { }

    /** 2% без «Удачи моря», +1% за уровень (I–III) до максимума 5%. */
    static double catchChance(int luck) { return 0.02 + 0.01 * Math.clamp(luck, 0, 3); }

    /** Секунды конфига в тики с ограничением: значения вне диапазона заменяются границей. */
    static int ticks(double seconds, int min, int max) { return Math.clamp((int) Math.round(seconds * 20), min, max); }

    /** Зацеп: инструмент в руке, игрок в воздухе и падает, под ногами есть запас высоты,
     *  проскальзывание ещё не откатилось и другого зацепа нет. */
    static boolean canGrab(boolean tool, boolean airborne, boolean falling, boolean clearance,
            boolean cooling, boolean gripping, boolean frozen) {
        return tool && airborne && falling && clearance && !cooling && !gripping && !frozen;
    }

    /** Двойной присед: второй присед зажат в окне после отпускания (mod wall_jump.time_window). */
    static boolean doubleTap(long releaseTick, long now, int windowTicks) {
        return releaseTick >= 0 && now - releaseTick <= windowTicks;
    }

    /** Твёрдый блок гасит падение тем быстрее, чем он прочнее (mod: 0.90 - hardness * 0.03). */
    static double hardFriction(double hardness) {
        return Math.clamp(HARD_FRICTION_BASE - hardness * HARD_FRICTION_SCALE, HARD_MIN_FRICTION, HARD_MAX_FRICTION);
    }

    /** Мягкий блок: постоянная скорость скольжения вниз (mod: 0.5 - hardness * 0.13). */
    static double softSlideSpeed(double hardness) {
        return Math.clamp(SOFT_SLIDE_MAX_SPEED - hardness * SOFT_SLIDE_HARDNESS_SCALE,
                SOFT_SLIDE_MIN_SPEED, SOFT_SLIDE_MAX_SPEED);
    }

    /** Трение погасило падение: игрок замирает на месте и висит до конца времени захвата. */
    static boolean locksInPlace(double verticalSpeed) { return Math.abs(verticalSpeed) < HANG_LOCK_SPEED; }

    /** Мягкое скольжение: падение слабее в softFallDamage раз и не больше cap (в очках урона, 2 = сердце). */
    static double softFallDamage(double damage, double multiplier, double cap) {
        double softened = Math.round(Math.max(0, damage) * multiplier);
        return cap > 0 ? Math.min(softened, cap) : softened;
    }

    static Location anchoredLook(Location anchor, Location attempt) {
        Location result = anchor.clone();
        result.setYaw(attempt.getYaw()); result.setPitch(attempt.getPitch());
        return result;
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
    static int afterClimb(int damage) { return Math.min(DURABILITY, Math.max(0, damage) + CLIMB_DAMAGE); }
    static boolean broken(int damage) { return damage >= DURABILITY; }
}
