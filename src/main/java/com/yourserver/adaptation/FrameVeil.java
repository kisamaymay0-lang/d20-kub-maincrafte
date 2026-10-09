package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Рамка, которую можно переключать в невидимое состояние ножницами.
 *
 * <h2>Что делает</h2>
 *
 * - **Шифт + ПКМ ножницами по рамке** — переключает видимость рамки (видимая/невидимая)
 *   с частицами облачка дымки.
 * - **Без ограничений:** невидимая рамка работает в точности как стандартная рамка
 *   (помещение, извлечение, вращение предмета, поломка — без особых перехватов).
 * - **Выпадение:** при разрушении невидимой рамки всегда выпадает обычная (или светящаяся)
 *   рамка, а не невидимая.
 * - **Без всплывающих названий кастомных предметов:** как и в ванильном майнкрафте,
 *   непереименованные на наковальне предметы не показывают парящее название над рамкой.
 */
final class FrameVeil implements Listener {

    /** Сколько дымки в клубе: немного, только обозначить изменение. */
    private static final int PUFF = 8;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        ItemStack held = event.getHand() == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();

        if (player.isSneaking() && held != null && held.getType() == Material.SHEARS && event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            frame.setVisible(!frame.isVisible());
            puff(frame);
            return;
        }

        // Если в рамку помещается кастомный предмет плагина, очищаем custom_name (displayName)
        // в пользу itemName, чтобы не вызывалось отображение всплывающего названия над рамкой.
        if (frame.getItem().getType().isAir() && held != null && !held.getType().isAir()) {
            sanitizeFrameItem(held);
        }
    }

    /**
     * Очищает displayName в пользу itemName для кастомных предметов плагина,
     * чтобы над рамкой не высвечивалось имя непереименованного предмета.
     */
    static void sanitizeFrameItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        if (isPluginCustomItem(item)) {
            Component dn = meta.displayName();
            if (dn != null) {
                meta.itemName(dn.decoration(TextDecoration.ITALIC, false));
                meta.displayName(null);
                item.setItemMeta(meta);
            }
        }
    }

    /**
     * Проверка, является ли предмет кастомным предметом плагина.
     */
    public static boolean isPluginCustomItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        var pdc = meta.getPersistentDataContainer();
        for (var key : pdc.getKeys()) {
            String ns = key.getNamespace();
            if ("f8-plugin".equalsIgnoreCase(ns) || "adaptation".equalsIgnoreCase(ns) || "f8resurs".equalsIgnoreCase(ns)) {
                return true;
            }
        }
        var model = meta.getItemModel();
        if (model != null && "f8resurs".equalsIgnoreCase(model.getNamespace())) {
            return true;
        }
        return false;
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
    }

    /** Точка частиц: чуть перед рамкой. */
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
