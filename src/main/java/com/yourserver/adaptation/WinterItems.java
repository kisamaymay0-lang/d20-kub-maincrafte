package com.yourserver.adaptation;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/** Предметы используют настоящие инструмент/еду и независимый от названия маркер. */
final class WinterItems {
    enum Kind {
        TOOL("icy_rime", "Ледяная изморозь", Material.DIAMOND_PICKAXE),
        RAW("rime", "Изморозь", Material.COD),
        DEPLETED("depleted_rime", "Опустошённая изморозь", Material.COD),
        ROE("ice_caviar", "Ледяная икра", Material.LIGHT_BLUE_DYE),
        SANDWICH("ice_caviar_sandwich", "Бутерброд с ледяной икрой", Material.BREAD);
        final String id, title;
        final Material material;
        Kind(String id, String title, Material material) { this.id = id; this.title = title; this.material = material; }
    }
    private final NamespacedKey kindKey;
    private final NamespacedKey toolKey;

    WinterItems(JavaPlugin plugin) {
        kindKey = new NamespacedKey(plugin, "winter_item");
        toolKey = new NamespacedKey(plugin, "winter_tool_id");
    }

    ItemStack create(Kind kind) {
        ItemStack item = new ItemStack(kind.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(kind.title, kind == Kind.TOOL ? NamedTextColor.AQUA : NamedTextColor.WHITE));
        String description = switch (kind) {
            case TOOL -> "Особый предмет рыбалки в зимних биомах.";
            case RAW, DEPLETED -> "Рыба с неведомых земель.";
            case ROE -> "Добывается из Изморози";
            case SANDWICH -> "Сытный, как золотая морковка";
        };
        meta.lore(List.of(ProfileItems.text(description, NamedTextColor.GRAY)));
        meta.setItemModel(new NamespacedKey("f8resurs", kind.id));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind.name());
        if (kind == Kind.TOOL) {
            Damageable damage = (Damageable) meta;
            damage.setMaxDamage(WinterRules.DURABILITY); damage.setDamage(0);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        } else if (kind == Kind.RAW || kind == Kind.DEPLETED || kind == Kind.SANDWICH) {
            var food = meta.getFood();
            food.setNutrition(kind == Kind.SANDWICH ? 6 : 2);
            food.setSaturation(kind == Kind.SANDWICH ? 14.4f : 0.4f);
            food.setCanAlwaysEat(false); meta.setFood(food);
        }
        item.setItemMeta(meta);
        return item;
    }

    Kind kind(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String value = item.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return Kind.valueOf(value); } catch (IllegalArgumentException ex) { return null; }
    }

    String toolId(ItemStack item) {
        return item == null ? null : item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    boolean holdsTool(Player player) { return kind(player.getInventory().getItemInMainHand()) == Kind.TOOL; }

    /** Ровно 4 прочности за усиленный прыжок, без случайного уменьшения от «Прочности». */
    boolean useClimb(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (kind(held) != Kind.TOOL || !(held.getItemMeta() instanceof Damageable meta)) return false;
        int damage = WinterRules.afterClimb(meta.getDamage());
        if (WinterRules.broken(damage)) {
            player.getInventory().setItemInMainHand(create(Kind.RAW));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
            return false;
        }
        meta.setDamage(damage); held.setItemMeta(meta);
        return true;
    }
}
