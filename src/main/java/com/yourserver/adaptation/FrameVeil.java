package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Механики рамок и картин:
 * <ul>
 *   <li><b>Невидимость ножницами:</b> Шифт + ПКМ ножницами по рамке переключает её видимость (FrameVeil).</li>
 *   <li><b>Выпадение обычной рамки:</b> При разрушении невидимой рамки выпадает обычная или светящаяся рамка.</li>
 *   <li><b>Модели картин «Не курить»:</b> Переименование картины в «Не курить 1» меняет её модель предмета
 *       на {@code f8resurs:dont_smoke}, а в «Не курить 2» — на {@code f8resurs:dont_smoke2}. При размещении
 *       в рамку она выглядит как полноценная картина на стене.</li>
 *   <li><b>Отображение названий кастомных предметов в рамке:</b> При наведении прицела на кастомный предмет
 *       плагина в рамке отображается его название (как в action bar, так и во всплывающей подсказке рамки).
 *       На картины с кастомными моделями это правило не распространяется.</li>
 * </ul>
 */
public final class FrameVeil implements Listener {

    private static final int PUFF = 8;

    public static final NamespacedKey DONT_SMOKE_KEY = new NamespacedKey("f8resurs", "dont_smoke");
    public static final NamespacedKey DONT_SMOKE_2_KEY = new NamespacedKey("f8resurs", "dont_smoke2");

    private final Plugin plugin;
    private final Set<UUID> hoveringPlayers = new HashSet<>();

    public FrameVeil() {
        this(null);
    }

    public FrameVeil(Plugin plugin) {
        this.plugin = plugin;
        if (plugin != null) {
            startHoverTask();
        }
    }

    private void startHoverTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkHover, 3L, 3L);
    }

    /**
     * Проверка наведения прицела игроков на рамки с кастомными предметами плагина.
     */
    public void checkHover() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity target = null;
            try {
                RayTraceResult ray = player.getWorld().rayTraceEntities(
                        player.getEyeLocation(),
                        player.getEyeLocation().getDirection(),
                        5.0,
                        0.35,
                        e -> e instanceof ItemFrame && !e.getUniqueId().equals(player.getUniqueId())
                );
                if (ray != null && ray.getHitEntity() instanceof ItemFrame frame) {
                    target = frame;
                }
            } catch (Throwable ignored) {
            }

            if (target instanceof ItemFrame frame) {
                ItemStack item = frame.getItem();
                if (isPluginCustomItem(item)) {
                    Component name = getItemDisplayNameComponent(item);
                    if (name != null) {
                        player.sendActionBar(name);
                        hoveringPlayers.add(player.getUniqueId());
                        continue;
                    }
                }
            }

            if (hoveringPlayers.remove(player.getUniqueId())) {
                player.sendActionBar(Component.empty());
            }
        }
    }

    /**
     * Получить компонент имени предмета для отображения над рамкой и в action bar.
     */
    public static Component getItemDisplayNameComponent(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName();
        }
        return Component.text(Shaker.getItemDisplayName(item));
    }

    /**
     * Определение модели для названия картины:
     * - «Не курить 1» -> f8resurs:dont_smoke
     * - «Не курить 2» -> f8resurs:dont_smoke2
     * При других названиях -> null.
     */
    public static NamespacedKey paintingModelForName(String name) {
        if (name == null) return null;
        String clean = name.trim();
        if ("Не курить 1".equalsIgnoreCase(clean)) {
            return DONT_SMOKE_KEY;
        } else if ("Не курить 2".equalsIgnoreCase(clean)) {
            return DONT_SMOKE_2_KEY;
        }
        return null;
    }

    /**
     * Исключён ли тип предмета из отображения названий в рамке.
     * Картины (включая картины с особой моделью) исключены из этого правила.
     */
    public static boolean isExcludedFromFrameHover(Material type) {
        return type == null || type == Material.AIR || type == Material.PAINTING;
    }

    /**
     * Проверка, является ли предмет кастомным предметом плагина.
     * Картины (включая картины с особой моделью) исключены из этого правила.
     */
    public static boolean isPluginCustomItem(ItemStack item) {
        if (item == null) return false;
        if (isExcludedFromFrameHover(item.getType())) return false;

        if (Shaker.isShaker(item) || Shaker.isDrink(item) || Shaker.isHotWaterBottle(item)) return true;
        if (Cigarette.isCigarette(item)) return true;
        if (AncientJug.isJugItem(item)) return true;

        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            String ns = key.getNamespace();
            if ("adaptation".equalsIgnoreCase(ns) || "f8resurs".equalsIgnoreCase(ns)) {
                return true;
            }
        }

        NamespacedKey itemModel = meta.getItemModel();
        if (itemModel != null && "f8resurs".equalsIgnoreCase(itemModel.getNamespace())) {
            return true;
        }

        return false;
    }

    /**
     * Обновить кастомное имя рамки при нахождении в ней кастомного предмета.
     */
    public static void updateFrameCustomName(ItemFrame frame) {
        if (frame == null) return;
        ItemStack item = frame.getItem();
        if (isPluginCustomItem(item)) {
            Component name = getItemDisplayNameComponent(item);
            frame.customName(name);
            frame.setCustomNameVisible(false);
        } else {
            frame.customName(null);
            frame.setCustomNameVisible(false);
        }
    }

    /**
     * Обновляет модель предмета картины в зависимости от названия:
     * - «Не курить 1» -> f8resurs:dont_smoke
     * - «Не курить 2» -> f8resurs:dont_smoke2
     * При другом названии кастомная модель снимается.
     */
    public static boolean updatePaintingModel(ItemStack item) {
        if (item == null || item.getType() != Material.PAINTING) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        String name = "";
        if (meta.hasDisplayName()) {
            Component dn = meta.displayName();
            if (dn != null) {
                name = PlainTextComponentSerializer.plainText().serialize(dn).trim();
            }
        }

        NamespacedKey targetModel = paintingModelForName(name);
        if (targetModel != null) {
            meta.setItemModel(targetModel);
            item.setItemMeta(meta);
            return true;
        } else {
            NamespacedKey model = meta.getItemModel();
            if (model != null && "f8resurs".equals(model.getNamespace())
                    && ("dont_smoke".equals(model.getKey()) || "dont_smoke2".equals(model.getKey()))) {
                meta.setItemModel(null);
                item.setItemMeta(meta);
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType() != Material.PAINTING) return;

        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        Component dn = meta.displayName();
        String name = dn == null ? "" : PlainTextComponentSerializer.plainText().serialize(dn).trim();

        if ("Не курить 1".equalsIgnoreCase(name)) {
            meta.setItemModel(DONT_SMOKE_KEY);
            result.setItemMeta(meta);
            event.setResult(result);
        } else if ("Не курить 2".equalsIgnoreCase(name)) {
            meta.setItemModel(DONT_SMOKE_2_KEY);
            result.setItemMeta(meta);
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor != null && cursor.getType() == Material.PAINTING) {
            updatePaintingModel(cursor);
        }
        ItemStack current = event.getCurrentItem();
        if (current != null && current.getType() == Material.PAINTING) {
            updatePaintingModel(current);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void shears(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getHand());

        // Переключение видимости рамки ножницами
        if (player.isSneaking() && held != null && held.getType() == Material.SHEARS) {
            event.setCancelled(true);
            frame.setVisible(!frame.isVisible());
            puff(frame);
            return;
        }

        // Обновление модели картины при взаимодействии
        if (held != null && held.getType() == Material.PAINTING) {
            updatePaintingModel(held);
        }

        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> updateFrameCustomName(frame));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        Item dropped = event.getItemDrop();
        ItemStack stack = dropped.getItemStack();
        if (stack.getType() == Material.ITEM_FRAME || stack.getType() == Material.GLOW_ITEM_FRAME) {
            boolean glow = frame instanceof GlowItemFrame;
            dropped.setItemStack(new ItemStack(glow ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME, 1));
        }

        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> updateFrameCustomName(frame));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame && plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> updateFrameCustomName(frame));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hoveringPlayers.remove(event.getPlayer().getUniqueId());
    }

    private static Location dropSpot(ItemFrame frame) {
        Location at = frame.getLocation();
        return at.add(frame.getFacing().getDirection().multiply(0.6));
    }

    private static void puff(ItemFrame frame) {
        frame.getWorld().spawnParticle(
                Particle.CLOUD,
                dropSpot(frame),
                PUFF, 0.18, 0.18, 0.18, 0.02
        );
    }
}
