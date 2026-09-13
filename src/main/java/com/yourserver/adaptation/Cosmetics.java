package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Косметика на голове игрока.
 *
 * Косметика — это НЕ предмет в слоте шлема и не броня: у головы игрока держится
 * отдельная сущность {@link ItemDisplay} с трансформацией HEAD, поэтому она
 * не мешает надеть настоящий шлем, не видна в слотах брони и не выпадает
 * при смерти. Видна всем, кроме самого владельца (ему она только загораживала
 * бы обзор), поэтому владелец видит её только в меню профиля.
 *
 * Модель берётся из ресурспака: {@code file: kosmetika1} → предмет
 * {@code f8resurs:kosmetika1} на базе обычного листа бумаги (сам лист не видно —
 * рисуется только модель косметики).
 *
 * Запись о надетой косметике живёт в {@code worn}, даже когда самой сущности
 * сейчас нет (игрок умер или невидим): иначе после возрождения косметика
 * не вернулась бы. Устройство то же, что у {@link ProfileTags}.
 */
final class Cosmetics {
    /** База для косметики: её модель целиком задаёт ресурспак. */
    private static final Material BASE_ITEM = Material.PAPER;
    /** Насколько выше ног держится косметика, если высоту глаз спросить не удалось. */
    private static final double FALLBACK_HEIGHT = 1.62;

    private static final class Entry {
        ItemDisplay display;
        String file;
    }

    private final JavaPlugin plugin;
    /** Косметика игрока (или null, если ничего не надето). */
    private final Function<Player, CosmeticCatalog.Cosmetic> equipped;
    private final Map<UUID, Entry> worn = new HashMap<>();
    private final BukkitTask task;
    private boolean disabled;

    Cosmetics(JavaPlugin plugin, Function<Player, CosmeticCatalog.Cosmetic> equipped) {
        this.plugin = plugin;
        this.equipped = equipped;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * Предмет косметики: обычный лист бумаги, у которого модель заменена на
     * {@code f8resurs:<file>} из ресурспака.
     */
    static ItemStack item(CosmeticCatalog.Cosmetic cosmetic) {
        ItemStack item = new ItemStack(BASE_ITEM);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(cosmetic.name(), NamedTextColor.GOLD));
        meta.setItemModel(new NamespacedKey("f8resurs", cosmetic.file()));
        item.setItemMeta(meta);
        return item;
    }

    /** Надеть/снять косметику; null снимает её совсем. */
    void apply(Player player, CosmeticCatalog.Cosmetic cosmetic) {
        if (disabled || player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();
        if (cosmetic == null) {
            remove(id);
            return;
        }
        try {
            Entry entry = worn.get(id);
            if (entry == null) {
                entry = new Entry();
                worn.put(id, entry);
            }
            if (entry.display == null || !entry.display.isValid()
                    || !entry.display.getWorld().equals(player.getWorld())
                    || !cosmetic.file().equals(entry.file)) {
                removeDisplay(entry);
                entry.display = spawn(player, cosmetic);
                entry.file = cosmetic.file();
            }
            moveToHead(player, entry.display);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось надеть косметику на " + player.getName(), ex);
            remove(id);
        }
    }

    void quit(Player player) { remove(player.getUniqueId()); }

    void disable() {
        disabled = true;
        task.cancel();
        for (UUID id : new ArrayList<>(worn.keySet())) remove(id);
    }

    private CosmeticCatalog.Cosmetic resolve(Player player) {
        try {
            return equipped.apply(player);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void tick() {
        if (worn.isEmpty()) return;
        for (Map.Entry<UUID, Entry> each : new ArrayList<>(worn.entrySet())) {
            UUID id = each.getKey();
            Entry head = each.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                remove(id);
                continue;
            }
            try {
                if (head.display == null || !head.display.isValid()) {
                    // Сущности нет: создаём для живого видимого игрока, иначе просто ждём.
                    removeDisplay(head);
                    if (!player.isDead() && !player.isInvisible()) apply(player, resolve(player));
                    continue;
                }
                if (!player.getWorld().equals(head.display.getWorld())) {
                    // Переход между мирами: переносим косметику в новый мир.
                    apply(player, resolve(player));
                    continue;
                }
                if (player.isDead() || player.isInvisible()) {
                    // Мёртвому и невидимому косметика не показывается, запись остаётся.
                    removeDisplay(head);
                    continue;
                }
                moveToHead(player, head.display);
            } catch (RuntimeException ex) {
                remove(id);
            }
        }
    }

    private ItemDisplay spawn(Player player, CosmeticCatalog.Cosmetic cosmetic) {
        Location at = headSpot(player);
        ItemDisplay display = player.getWorld().spawn(at, ItemDisplay.class, entity -> {
            entity.setItemStack(item(cosmetic));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setViewRange(64f);
            entity.setShadowRadius(0.4f);
            entity.setShadowStrength(0.4f);
            entity.setTeleportDuration(2);
            entity.setInterpolationDuration(0);
        });
        // Владелец свою косметику не видит: в первом лице она висела бы прямо перед камерой.
        try {
            player.hideEntity(plugin, display);
        } catch (RuntimeException ignored) { }
        return display;
    }

    /** Точка у головы игрока: поворот берём с игрока, наклон не трогаем. */
    private Location headSpot(Player player) {
        Location at = player.getLocation();
        double height;
        try {
            height = player.getEyeHeight();
        } catch (RuntimeException ex) {
            height = FALLBACK_HEIGHT;
        }
        if (!Double.isFinite(height) || height <= 0.1) height = FALLBACK_HEIGHT;
        at.setY(at.getY() + height);
        at.setPitch(0);
        return at;
    }

    private void moveToHead(Player player, ItemDisplay display) {
        Location at = headSpot(player);
        if (display.getLocation().distanceSquared(at) > 0.000001
                || Math.abs(display.getLocation().getYaw() - at.getYaw()) > 0.5f) {
            display.teleport(at);
        }
    }

    private static void removeDisplay(Entry entry) {
        if (entry.display != null && entry.display.isValid()) entry.display.remove();
        entry.display = null;
        entry.file = null;
    }

    private void remove(UUID id) {
        Entry entry = worn.remove(id);
        if (entry != null) removeDisplay(entry);
    }
}
