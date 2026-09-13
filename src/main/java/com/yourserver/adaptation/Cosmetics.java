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
 * Косметика видна всем, включая самого владельца, и ни при каком способе показа
 * не занимает серверный слот шлема: настоящий шлем надевается, в инвентаре и в
 * слотах брони предмет не появляется и при смерти не выпадает.
 *
 * Способов показа два:
 * <ul>
 *   <li>стоит ProtocolLib — косметика уходит клиенту пакетом экипировки и
 *       надевается настоящим предметом в слот HEAD ({@link CosmeticEquipment});
 *       посадка и плавность при этом ровно как у обычной брони;</li>
 *   <li>ProtocolLib нет — у головы держится сущность {@link ItemDisplay}
 *       с трансформацией HEAD.</li>
 * </ul>
 * Оба берут из модели раздел {@code display.head}, то есть посадка — та, что
 * настроена в Blockbench.
 *
 * Трансформация HEAD берёт из модели раздел {@code display.head} — то есть
 * косметика сидит ровно так, как она выглядит надетой на голову в Blockbench.
 * Якорь — уровень глаз игрока, поэтому в присяди и в воде она остаётся на голове;
 * если посадку надо поправить, крутите {@code translation} в {@code display.head}
 * самой модели.
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
    /** Не null, пока работает рассылка пакетом; null — косметика идёт сущностью. */
    private CosmeticEquipment equipment;
    private final BukkitTask task;
    private boolean disabled;

    Cosmetics(JavaPlugin plugin, Function<Player, CosmeticCatalog.Cosmetic> equipped) {
        this.plugin = plugin;
        this.equipped = equipped;
        this.equipment = CosmeticEquipment.create(plugin, this::onEquipmentBroken);
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
        if (equipment != null && !equipment.isBroken()) {
            // Слот шлема на клиенте: сущность у головы не нужна вовсе.
            remove(id);
            if (cosmetic == null) equipment.clear(player);
            else equipment.wear(player, item(cosmetic));
            return;
        }
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
            // Запись оставляем: tick() увидит, что сущности нет, и попробует снова.
            // Иначе случайный сбой (чанк не загружен, мир меняется) снял бы косметику совсем.
            Entry failed = worn.get(id);
            if (failed != null) removeDisplay(failed);
        }
    }

    /**
     * Рассылка пакетом встала на ходу. Способ показа переключаем на сущность и
     * сразу переодеваем всех, кто уже носил косметику: иначе пакет не уходит,
     * сущности тоже нет, и игрок остаётся с пустой головой.
     */
    private void onEquipmentBroken() {
        equipment = null;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            apply(player, resolve(player));
        }
    }

    /**
     * Что сейчас происходит с косметикой игрока — для /profile cosmetic status.
     * Отвечает на два вопроса, из-за которых косметику обычно и не видно:
     * каким способом она показывается и не мешает ли ей настоящий шлем.
     */
    String status(Player player) {
        StringBuilder out = new StringBuilder();
        out.append("показ — ").append(equipment == null
                ? "сущность у головы (ProtocolLib не стоит или рассылка встала)"
                : "предметом в слот шлема (ProtocolLib)");
        if (equipment == null) {
            Entry entry = worn.get(player.getUniqueId());
            if (entry == null || entry.display == null || !entry.display.isValid()) {
                out.append("; сущности у головы нет");
            } else {
                Location at = entry.display.getLocation();
                out.append(String.format(java.util.Locale.ROOT,
                        "; сущность есть в %s %d %d %d, модель f8resurs:%s",
                        at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), entry.file));
            }
        }
        out.append("; шлем надет: ")
                .append(player.getInventory().getHelmet() != null
                        ? "да — косметика не показывается, пока он не снят"
                        : "нет");
        return out.toString();
    }

    void quit(Player player) {
        remove(player.getUniqueId());
        if (equipment != null) equipment.clear(player);
    }

    void disable() {
        disabled = true;
        task.cancel();
        for (UUID id : new ArrayList<>(worn.keySet())) remove(id);
        if (equipment != null) equipment.disable();
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
            // Тени нет: сущность висит у головы, и её тень плавала бы в воздухе.
            entity.setShadowRadius(0f);
            entity.setShadowStrength(0f);
            // Ноль — иначе клиент догоняет голову пару тиков, и косметика
            // «летает» за игроком вместо того, чтобы сидеть на голове.
            entity.setTeleportDuration(0);
            entity.setInterpolationDuration(0);
        });
        return display;
    }

    /**
     * Точка у головы игрока. Уровень глаз — это центр головы (1.62 против 1.65
     * у куба головы), поэтому модель с трансформацией HEAD ложится ровно так,
     * как она надета в слоте шлема. Наклон берём с игрока: шлем поворачивается
     * вместе с головой.
     */
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
        return at;
    }

    private void moveToHead(Player player, ItemDisplay display) {
        Location at = headSpot(player);
        Location now = display.getLocation();
        // Наклон проверяем тоже: без этого косметика не кивала бы вместе с головой.
        if (now.distanceSquared(at) > 0.000001
                || Math.abs(now.getYaw() - at.getYaw()) > 0.5f
                || Math.abs(now.getPitch() - at.getPitch()) > 0.5f) {
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
