package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Одна частная TextDisplay-карточка на смотрящего, никаких actionbar/боссбаров. */
final class ProfileCards {
    private static final class Card {
        UUID target;
        TextDisplay display;
        final SkyFade fade = new SkyFade();
        long revision = -1;
        double lastVisibility = -1;
        Location anchor;
    }

    private final JavaPlugin plugin;
    private final Function<Player, ProfileData> profiles;
    private final Set<UUID> sneaking = new HashSet<>();
    private final Map<UUID, Card> cards = new HashMap<>();
    private final BukkitTask task;
    private final double range;
    private int frames;
    private boolean errorReported;

    ProfileCards(JavaPlugin plugin, Function<Player, ProfileData> profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
        double setting = plugin.getConfig().getDouble("profiles.quick-card-distance", 8.0);
        range = Double.isFinite(setting) ? Math.clamp(setting, 2.0, 16.0) : 8.0;
        for (Player player : Bukkit.getOnlinePlayers()) if (player.isSneaking()) sneaking.add(player.getUniqueId());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    void sneaking(UUID viewer, boolean value) {
        if (value) sneaking.add(viewer); else sneaking.remove(viewer);
    }

    static boolean canInspect(Player viewer, Player target) {
        return !viewer.getUniqueId().equals(target.getUniqueId()) && target.isOnline() && !target.isDead()
                && viewer.getWorld().equals(target.getWorld()) && viewer.canSee(target)
                && Bukkit.getPlayer(target.getUniqueId()) == target
                && !target.isInvisible() && !target.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private Player target(Player viewer) {
        Location eye = viewer.getEyeLocation();
        RayTraceResult hit = viewer.getWorld().rayTrace(eye, eye.getDirection(),
                range, FluidCollisionMode.NEVER, true, 0.15,
                entity -> entity instanceof Player player && canInspect(viewer, player));
        return hit != null && hit.getHitEntity() instanceof Player player ? player : null;
    }

    private void tick() {
        if (sneaking.isEmpty() && cards.isEmpty()) return;
        boolean scan = (frames++ & 1) == 0; // Луч только 5 раз/сек и только для зажатого Shift.
        if (scan) {
            var iterator = sneaking.iterator();
            while (iterator.hasNext()) {
                UUID id = iterator.next();
                Player viewer = Bukkit.getPlayer(id);
                if (viewer == null || !viewer.isSneaking()) { iterator.remove(); continue; }
                Card card = cards.get(id);
                Player target = viewer.isDead() || viewer.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                        ? null : target(viewer);
                if (target == null) {
                    if (card != null) card.target = null;
                    continue;
                }
                if (card == null) {
                    card = new Card();
                    cards.put(id, card);
                }
                if (!target.getUniqueId().equals(card.target)) card.revision = -1;
                card.target = target.getUniqueId();
            }
        }
        var iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player viewer = Bukkit.getPlayer(entry.getKey());
            Card card = entry.getValue();
            Player target = card.target == null ? null : Bukkit.getPlayer(card.target);
            if (viewer == null || !viewer.isOnline() || (card.anchor != null && !viewer.getWorld().equals(card.anchor.getWorld()))) {
                remove(card); iterator.remove(); continue;
            }
            boolean visible = sneaking.contains(entry.getKey()) && viewer.isSneaking() && !viewer.isDead()
                    && target != null && canInspect(viewer, target)
                    && viewer.getLocation().distanceSquared(target.getLocation()) <= (range + 1) * (range + 1)
                    && viewer.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING;
            try {
                if (visible) {
                    ProfileData data = profiles.apply(target);
                    Location location = anchor(viewer, target);
                    if (card.display == null || !card.display.isValid()) {
                        card.display = viewer.getWorld().spawn(location, TextDisplay.class, display -> {
                            display.setVisibleByDefault(false);
                            display.setPersistent(false);
                            display.setBillboard(Display.Billboard.CENTER);
                            display.setShadowRadius(0f);
                            display.setShadowStrength(0f);
                            display.setBrightness(new Display.Brightness(15, 15));
                            display.setDefaultBackground(false);
                            display.setBackgroundColor(Color.fromARGB(0, 22, 22, 26));
                            display.setTextOpacity((byte) 0);
                            display.setShadowed(true);
                            display.setSeeThrough(false);
                            display.setLineWidth(210);
                            display.setAlignment(TextDisplay.TextAlignment.LEFT);
                            display.setTeleportDuration(3);
                            display.setInterpolationDuration(2);
                            display.text(content(data));
                        });
                        card.fade.reset();
                        card.lastVisibility = -1;
                        card.anchor = location;
                        card.revision = data.revision();
                        viewer.showEntity(plugin, card.display);
                    }
                    if (card.revision != data.revision()) {
                        card.display.text(content(data));
                        card.revision = data.revision();
                    }
                    if (card.anchor.distanceSquared(location) > 0.0025) {
                        card.display.teleport(location);
                        card.anchor = location;
                    }
                }
                double value = card.fade.advance(visible, 2, 8);
                if (!visible && card.fade.hidden()) { remove(card); iterator.remove(); continue; }
                if (card.display != null && card.display.isValid() && Double.compare(value, card.lastVisibility) != 0) {
                    int opacity = Math.clamp((int) Math.round(value * 255), 0, 255);
                    card.lastVisibility = value;
                    card.display.setTextOpacity((byte) opacity);
                    card.display.setBackgroundColor(Color.fromARGB((int) (value * 210), 22, 22, 26));
                    card.display.setInterpolationDelay(0);
                    card.display.setTransformation(new Transformation(new Vector3f((float) ((1 - value) * 0.2), 0, 0),
                            new Quaternionf(), new Vector3f((float) (0.45 * (0.85 + value * 0.15))), new Quaternionf()));
                }
            } catch (RuntimeException ex) {
                remove(card);
                iterator.remove();
                if (!errorReported) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось показать карточку профиля", ex);
                    errorReported = true;
                }
            }
        }
    }

    private static Location anchor(Player viewer, Player target) {
        Vector facing = viewer.getEyeLocation().getDirection();
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        if (right.lengthSquared() < 0.001) right = new Vector(1, 0, 0); else right.normalize();
        Location location = target.getEyeLocation().add(right.multiply(1.25)).add(0, 0.35, 0);
        location.setYaw(0); location.setPitch(0);
        return location;
    }

    private static Component content(ProfileData data) {
        Component result = ProfileItems.text(data.name(), NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.newline()).append(Component.newline());
        for (String line : ProfileText.wrap(data.displayedDescription(), 34)) {
            result = result.append(ProfileItems.text(line, NamedTextColor.WHITE)).append(Component.newline());
        }
        return result.append(Component.newline())
                .append(ProfileItems.text("+ " + data.likes(), NamedTextColor.GREEN))
                .append(ProfileItems.text("   •   ", NamedTextColor.DARK_GRAY))
                .append(ProfileItems.text("− " + data.dislikes(), NamedTextColor.RED))
                .append(Component.newline()).append(ProfileItems.text("Shift + ПКМ — открыть профиль", NamedTextColor.GRAY));
    }

    private static void remove(Card card) {
        if (card.display != null) card.display.remove();
    }

    void quit(UUID player) {
        sneaking.remove(player);
        Card own = cards.remove(player);
        if (own != null) remove(own);
        for (Card card : cards.values()) if (player.equals(card.target)) card.target = null;
    }

    void disable() {
        task.cancel();
        cards.values().forEach(ProfileCards::remove);
        cards.clear();
        sneaking.clear();
    }
}
