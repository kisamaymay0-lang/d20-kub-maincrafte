package com.yourserver.adaptation;

import java.util.Set;
import org.bukkit.Location;
import net.kyori.adventure.text.format.NamedTextColor;

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
    /**
     * Шанс зимнего улова: base (база) + perLuck за каждый уровень «Удачи моря»
     * (I–III). Значения приходят из config.yml (fishing.winter-tool-*).
     */
    static double catchChance(int luck, double base, double perLuck) {
        return Math.clamp(base, 0.0, 1.0) + Math.clamp(perLuck, 0.0, 1.0) * Math.clamp(luck, 0, 3);
    }
    static boolean canGrab(boolean newlySneaking, boolean airborne, boolean tool, boolean frozen, boolean alreadyClimbing) {
        return newlySneaking && airborne && tool && !frozen && !alreadyClimbing;
    }
    /** Зацеп удержанным Shift: без нового нажатия, когда игрок уже падает рядом со стеной. */
    static boolean canAutoGrab(boolean sneaking, boolean airborne, boolean tool, boolean frozen, boolean alreadyClimbing, boolean falling) {
        return sneaking && airborne && tool && !frozen && !alreadyClimbing && falling;
    }
    static boolean jumpPressed(boolean previous, boolean current) { return current && !previous; }
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
    static boolean descending(double previousY, double currentY, long ticksSinceJump) {
        return ticksSinceJump >= 4 && currentY < previousY - 0.001;
    }
}
