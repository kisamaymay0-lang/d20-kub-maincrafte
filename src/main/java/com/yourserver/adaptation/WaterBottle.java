package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <h2>Почему воду ещё и нельзя выпить</h2>
 *
 * Шифт + ПКМ по блоку — жест полива, а не питья, но ваниль всё равно
 * запускает питьё: блок действия не съел, вот предмет и пошёл в рот. Отмена
 * {@link org.bukkit.event.player.PlayerInteractEvent} этому не мешает, поэтому
 * запоминаем игрока в {@link WaterBottleUse} и отменяем уже
 * {@link org.bukkit.event.player.PlayerItemConsumeEvent}. Отметка живёт
 * {@value #NO_DRINK_MILLIS} мс — этого хватает на анимацию питья (~1.6 с), а
 * любой следующий клик бутылкой без шифта её снимает: выпить воду по-прежнему
 * можно, достаточно не жать шифт.
 */
final class WaterBottle {

    /** Сколько действует запрет пить: от клика до конца анимации − полторы секунды. */
    static final long NO_DRINK_MILLIS = 3_000L;

    /** Кого нельзя поить: игрок → время, до которого отметка действительна. */
    private static final Map<UUID, Long> noDrink = new ConcurrentHashMap<>();

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

    // ===== ЗАПРЕТ ПИТЬ =====

    /** Запретить игроку выпивать воду этим кликом: жест был «полить блок». */
    static void preventDrinking(Player player) {
        if (player == null) return;
        if (noDrink.size() > 64) prune(System.currentTimeMillis());
        noDrink.put(player.getUniqueId(), System.currentTimeMillis() + NO_DRINK_MILLIS);
    }

    /** Разрешить пить: клик был обычным, без шифта. */
    static void allowDrinking(Player player) {
        if (player != null) noDrink.remove(player.getUniqueId());
    }

    /**
     * Можно ли выпивать воду прямо сейчас. Отметка снимается сама: дважды
     * запрет не срабатывает, а просроченная отметка — всё равно что её нет.
     */
    static boolean drinkingPrevented(Player player) {
        if (player == null) return false;
        Long until = noDrink.remove(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    /** Выбросить просроченные отметки: карта не должна расти бесконечно. */
    private static void prune(long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = noDrink.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) it.remove();
        }
    }

}
