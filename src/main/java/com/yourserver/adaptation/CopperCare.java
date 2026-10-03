package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
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
 *       лампа, дверь, люк, сундук, светошахта. Ступень меняется по приставке
 *       имени: {@code copper_block} → {@code exposed_copper} →
 *       {@code weathered_copper} → {@code oxidized_copper} (у блока целиком
 *       приставки {@code _block} в следующих ступенях нет), а все состояния
 *       блока (поворот лестницы, форма плиты, открытость двери, водность
 *       решётки) сохраняются: подменяется только имя материала в строке
 *       {@code BlockData}, а не весь блок. У медного сундука лежащие внутри
 *       предметы возвращаются на место — подмена материала их не трогает.</li>
 *   <li><b>На вощёной меди.</b> Вода смывает воск и в том же поливе старит
 *       медь на одну ступень: {@code waxed_copper_block} становится
 *       {@code exposed_copper}, {@code waxed_weathered_cut_copper} —
 *       {@code weathered_cut_copper}. Вощёная медь «догоняет» обычную за один
 *       клик: снятие воска и ступень — одно действие.</li>
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        // Событие приходит на каждую руку: работает только та, в которой бутылка.
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        // Бутылки в обеих руках — считаем клик один раз, по главной.
        if (hand == EquipmentSlot.OFF_HAND
                && WaterBottle.isWaterBottle(event.getPlayer().getInventory().getItemInMainHand())) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        Block block = event.getClickedBlock();
        if (block == null || isCustomBlock(block)) return;

        // Один полив: ступень окисления +1, у вощёной меди вода ещё и снимает воск.
        String data = CopperWeathering.nextBlockData(block.getBlockData().getAsString());
        Material stepped = CopperWeathering.next(block.getType());
        if (data == null && stepped == null) return; // Полностью окисленная медь: старить нечего.

        // Медный сундук и другие медные хранилища: подмена материала не должна
        // выбрасывать или стирать лежащие внутри предметы. У двойного сундука
        // берём локальный инвентарь одной половины, а не общие 54 слота.
        ItemStack[] contents = null;
        if (block.getState() instanceof Container container) {
            contents = localInventory(container).getContents();
        }

        boolean applied = false;
        if (data != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(data), false);
                applied = true;
            } catch (IllegalArgumentException ignored) {
                // Состояние новой ступени не совпало — подменим только материал.
            }
        }
        if (!applied) block.setType(stepped, false);

        if (contents != null && block.getState() instanceof Container container) {
            Inventory storage = localInventory(container);
            if (storage.getSize() == contents.length) storage.setContents(contents);
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

    /** Инвентарь одной половины сундука: у двойного сундука getInventory даёт все 54 слотов. */
    private static Inventory localInventory(Container container) {
        return container instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
    }

    /** На месте стоит кастомный блок CraftEngine (например, медный нотный блок). */
    private static boolean isCustomBlock(Block block) {
        return CraftEngineSupport.available() && CraftEngineSupport.idAt(block) != null;
    }
}
