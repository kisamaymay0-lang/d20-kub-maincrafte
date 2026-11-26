package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Бутылка с водой: как её узнать и как потратить.
 *
 * <h2>Как ваниль хранит воду</h2>
 *
 * Вода — это то же зелье ({@code Material.POTION}) с типом
 * {@link PotionType#WATER}: отдельного материала под воду нет. Поэтому
 * проверка идёт по мета-данным, а не только по типу предмета.
 *
 * <h2>Как тратится</h2>
 *
 * Вода уходит, а в руке остаётся пустая бутылка — как после любого зелья.
 * Если бутылок в стопке несколько, одна списывается из стопки, а пустая
 * уходит в инвентарь (или падает рядом, когда инвентарь полон).
 */
final class WaterBottle {

    private WaterBottle() { }

    /** Это бутылка с водой, а не зелье и не пустая бутылка. */
    static boolean isWaterBottle(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        if (type == null) return !meta.hasCustomEffects();
        return type == PotionType.WATER;
    }

    /**
     * Потратить одну бутылку из указанной руки: на её место встаёт пустая.
     * Возвращает {@code false}, если в руке была не вода.
     */
    static boolean consume(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isWaterBottle(item)) return false;
        ItemStack empty = new ItemStack(Material.GLASS_BOTTLE);
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(item);
            else player.getInventory().setItemInMainHand(item);
            var left = player.getInventory().addItem(empty);
            for (ItemStack dropped : left.values()) {
                player.getWorld().dropItem(player.getLocation(), dropped);
            }
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(empty);
        } else {
            player.getInventory().setItemInMainHand(empty);
        }
        return true;
    }
}
