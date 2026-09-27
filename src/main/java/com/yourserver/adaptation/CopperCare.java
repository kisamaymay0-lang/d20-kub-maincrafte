package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Шифт + ПКМ бутылкой с водой по меди — медь стареет на одну ступень, играют
 * частицы снятия воска, а вода уходит (остаётся пустая бутылка).
 *
 * <h2>Где это работает</h2>
 *
 * <ul>
 *   <li><b>На любом медном блоке.</b> Обычный, потемневший, состаренный,
 *       окисленный — всех форм: блок, огранённый блок, лестница, плита, решётка,
 *       лампа, дверь, люк, светошахта. Ступень меняется по приставке имени:
 *       {@code copper_block} → {@code exposed_copper_block} →
 *       {@code weathered_copper_block} → {@code oxidized_copper_block}, а все
 *       состояния блока (поворот лестницы, форма плиты, открытость двери,
 *       водность решётки) сохраняются: подменяется только имя материала в
 *       строке {@code BlockData}, а не весь блок.</li>
 *   <li><b>На вощёной меди.</b> Вода смывает воск: {@code waxed_copper_block}
 *       становится {@code copper_block}, ступень та же. Дальше такая медь
 *       стареет как обычная — воск именно для того и нужен, чтобы медь не
 *       старела, и снятый воск возвращает её в общий круг.</li>
 *   <li><b>На медном предмете, который можно поставить.</b> Шифт + ПКМ водой
 *       в воздух, когда в другой руке лежит медный предмет (блок, дверь, люк,
 *       решётку…): стареет сам предмет, и его можно поставить уже
 *       окисленным.</li>
 * </ul>
 *
 * <h2>Чего здесь нет</h2>
 *
 * - **Дальше окисленной.** Полностью окисленная медь — последняя ступень, воде
 *   её старить нечем.
 * - **Кастомные блоки CraftEngine.** Если на месте стоит блок плагина (медный
 *   нотный блок), блок не трогаем: подмена материала сломала бы привязку
 *   модели, а «окислять» нечего — это свой блок.
 * - **Обратный ход.** Ваниль сама снимает окисление только разбором блока
 *   (молния и шлифовка), поэтому вода только старит.
 * - **Креатив.** Блок стареет, но вода не тратится — как и любое использование
 *   предметов в креативе.
 */
final class CopperCare implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void oxidize(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        // Событие приходит на каждую руку: работает только та, в которой бутылка.
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        // Бутылки в обеих руках — считаем клик один раз, по главной.
        if (hand == EquipmentSlot.OFF_HAND
                && WaterBottle.isWaterBottle(event.getPlayer().getInventory().getItemInMainHand())) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        // Предмет меди в другой руке: стареет предмет, а не блок.
        if (action == Action.RIGHT_CLICK_AIR) {
            EquipmentSlot copper = copperItemHand(player, hand);
            if (copper != null && age(player, copper, hand)) event.setCancelled(true);
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || isCustomBlock(block)) return;

        // Сначала пробуем следующую ступень, а вощёной меди вода смывает воск.
        String data = CopperWeathering.nextBlockData(block.getBlockData().getAsString());
        if (data == null) data = CopperWeathering.unwaxBlockData(block.getBlockData().getAsString());
        boolean applied = false;
        if (data != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(data), false);
                applied = true;
            } catch (IllegalArgumentException ignored) {
                // Состояние новой ступени не совпало — подменим только материал.
            }
        }
        if (!applied) {
            Material stepped = CopperWeathering.next(block.getType());
            if (stepped == null) stepped = CopperWeathering.unwax(block.getType());
            if (stepped == null) return; // Полностью окисленная медь: старить нечего.
            block.setType(stepped, false);
        }

        // Частицы снятия воска: именно они в ванили играют на меди.
        block.getWorld().spawnParticle(
                Particle.WAX_OFF,
                block.getLocation().add(0.5, 0.5, 0.5),
                18, 0.35, 0.35, 0.35, 0.0
        );

        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, hand);
        }
        event.setCancelled(true);
    }

    /** В какой руке медный предмет, который можно поставить, или null. */
    private static EquipmentSlot copperItemHand(Player player, EquipmentSlot clicked) {
        EquipmentSlot other = clicked == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack item = handItem(player, other);
        if (item == null || item.getType().isAir()) return null;
        return CopperWeathering.next(item.getType()) != null ? other : null;
    }

    private static ItemStack handItem(Player player, EquipmentSlot slot) {
        return slot == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    /**
     * Состарить медный предмет на одну ступень и потратить бутылку.
     *
     * @return получилось ли: у полностью окисленного предмета стареть некуда.
     */
    private static boolean age(Player player, EquipmentSlot itemHand, EquipmentSlot bottleHand) {
        ItemStack item = handItem(player, itemHand);
        if (item == null) return false;
        Material stepped = CopperWeathering.next(item.getType());
        if (stepped == null) return false;
        item.setType(stepped);
        // Предмет возвращаем в руку: setType меняет копию, а не сам слот.
        if (itemHand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(item);
        else player.getInventory().setItemInMainHand(item);
        player.getWorld().spawnParticle(
                Particle.WAX_OFF,
                player.getLocation().add(0.0, 1.0, 0.0),
                14, 0.3, 0.3, 0.3, 0.0
        );
        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, bottleHand);
        }
        return true;
    }

    /** На месте стоит кастомный блок CraftEngine (например, медный нотный блок). */
    private static boolean isCustomBlock(Block block) {
        return CraftEngineSupport.available() && CraftEngineSupport.idAt(block) != null;
    }
}
