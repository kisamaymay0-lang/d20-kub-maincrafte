package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Шифт + ПКМ бутылкой с водой по медному блоку — блок меняется на одну ступень,
 * играет частицы снятия воска, а вода уходит (остаётся пустая бутылка).
 *
 * <h2>Что именно происходит</h2>
 *
 * Полить можно <b>любой</b> медный блок — обычный, потускневший, обветренный,
 * вощёный, в виде блока, среза, ступени, плиты, решётки, лампы, двери, сундука
 * (в том числе новых медных форм). Ступень меняется по приставке имени:
 * {@code copper_block} → {@code exposed_copper} →
 * {@code weathered_copper} → {@code oxidized_copper}, а вощёный
 * блок сперва теряет воск ({@code waxed_weathered_copper} →
 * {@code weathered_copper}) и только следующим поливом стареет. Все состояния
 * блока (поворот лестницы, форма плиты, открытость двери) сохраняются:
 * подменяется только имя материала в строке {@code BlockData}, а не весь блок.
 *
 * <h2>Чего здесь нет</h2>
 *
 * - **Обратного хода.** Воск и шлифовка остаются ванильными: вода только
 *   старит и снимает воск.
 * - **Пустого полива.** На последней ступени ({@code oxidized_*}) вода не
 *   тратится и блок не меняется: старить больше нечего.
 * - **Креатив.** Блок меняется, но вода не тратится — как и любое
 *   использование предметов в креативе.
 * - **Медного нотного блока.** Если на месте стоит кастомный блок плагина
 *   ({@code f8resurs:copper_note_block}), блок не трогаем: подмена материала
 *   сломала бы привязку модели и выброшенные ноты, а «окислять» там нечего —
 *   это свой блок.
 */
final class CopperCare implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void oxidize(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.isSneaking()) return;
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        if (isNoteBlock(block)) return;

        Material stepped = CopperWeathering.next(block.getType());
        if (stepped == null) return;

        // Copper chest и другие медные хранилища: смена материала не должна
        // выбрасывать или стирать лежащие внутри предметы. Для двойного сундука
        // берём именно локальный инвентарь одной половины, не общие 54 слота.
        ItemStack[] contents = null;
        BlockState oldState = block.getState();
        if (oldState instanceof Container container) {
            contents = localInventory(container).getContents();
        }

        String data = CopperWeathering.nextBlockData(block.getBlockData().getAsString());
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

        // Частицы снятия воска: именно они в ванили играют и на меди, и на воске.
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

    private static Inventory localInventory(Container container) {
        return container instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
    }

    /** На месте стоит медный нотный блок плагина (кастомный блок CraftEngine). */
    private static boolean isNoteBlock(Block block) {
        // isCopper сам отвечает «нет», когда CraftEngine не стоит: id блока будет null.
        return CraftEngineCopper.isCopper(block);
    }
}
