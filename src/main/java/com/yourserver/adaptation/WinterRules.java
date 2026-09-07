package com.yourserver.adaptation;

import java.util.Set;

/** Чистые правила зимнего улова, без доступа к серверу. */
final class WinterRules {
    static final int DURABILITY = 16;
    static final int CLIMB_DAMAGE = 4;
    static final int FISH_LOCK_TICKS = 160;
    static final int SANDWICH_LOCK_TICKS = 80;
    static final int COLD_TICKS = 200;
    static final double CLIMB_VELOCITY = 0.62;
    static final Set<String> BIOMES = Set.of(
            "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_taiga",
            "minecraft:snowy_beach", "minecraft:frozen_river", "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean", "minecraft:grove", "minecraft:snowy_slopes",
            "minecraft:frozen_peaks", "minecraft:jagged_peaks");

    private WinterRules() { }
    static double catchChance(int luck) { return 0.08 + 0.02 * Math.clamp(luck, 0, 3); }
    static boolean canGrab(boolean newlySneaking, boolean airborne, boolean tool, boolean frozen, boolean alreadyClimbing) {
        return newlySneaking && airborne && tool && !frozen && !alreadyClimbing;
    }
    static boolean jumpPressed(boolean previous, boolean current) { return current && !previous; }
    static int afterClimb(int damage) { return Math.min(DURABILITY, Math.max(0, damage) + CLIMB_DAMAGE); }
    static boolean broken(int damage) { return damage >= DURABILITY; }
    static boolean descending(double previousY, double currentY, long ticksSinceJump) {
        return ticksSinceJump >= 4 && currentY < previousY - 0.001;
    }
}
