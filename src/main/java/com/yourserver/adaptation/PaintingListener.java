package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Модели картин при переименовании:
 * - «Не курить 1» -> f8resurs:dont_smoke
 * - «Не курить 2» -> f8resurs:dont_smoke2
 */
public final class PaintingListener implements Listener {

    public static final NamespacedKey DONT_SMOKE_KEY = new NamespacedKey("f8resurs", "dont_smoke");
    public static final NamespacedKey DONT_SMOKE_2_KEY = new NamespacedKey("f8resurs", "dont_smoke2");

    /**
     * Определение модели для названия картины.
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
     * Обновляет модель предмета картины.
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
}
