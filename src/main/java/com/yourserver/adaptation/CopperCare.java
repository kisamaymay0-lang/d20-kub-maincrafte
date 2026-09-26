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

/**
 * Шифт + ПКМ бутылкой с водой по медному блоку — блок окисляется на одну
 * ступень, играет частицы снятия воска, а вода уходит (остаётся пустая бутылка).
 *
 * <h2>Что именно происходит</h2>
 *
 * Ступень меняется по приставке имени: {@code copper_block} → {@code
 * exposed_copper_block} → {@code weathered_copper_block} → {@code
 * oxidized_copper_block}. Все состояния блока (поворот лестницы, форма плиты,
 * открытость двери) сохраняются: подменяется только имя материала в строке
 * {@code BlockData}, а не весь блок.
 *
 * <h2>Чего здесь нет</h2>
 *
 * - **Воск.** Навощённая медь ({@code waxed_*}) не окисляется — воск именно
 *   для этого и нужен.
 * - **Кастомные блоки CraftEngine.** Если на месте стоит блок плагина (медный
 *   нотный блок), блок не трогаем: подмена материала сломала бы привязку
 *   модели, а «окислять» нечего — это свой блок.
 * - **Обратный ход.** Воск и шлифовка остаются ванильными: вода только старит.
 * - **Креатив.** Блок окисляется, но вода не тратится — как и любое
 *   использование предметов в креативе.
 */
final class CopperCare implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void oxidize(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.isSneaking()) return;
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        if (isCustomBlock(block)) return;

        Material stepped = CopperWeathering.next(block.getType());
        if (stepped == null) return;

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

        // Частицы снятия воска: именно они в ванили играют на меди.
        block.getWorld().spawnParticle(
                Particle.WAX_OFF,
                block.getLocation().add(0.5, 0.5, 0.5),
                18, 0.35, 0.35, 0.35, 0.0
        );

        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, EquipmentSlot.HAND);
        }
        event.setCancelled(true);
    }

    /** На месте стоит кастомный блок CraftEngine (например, медный нотный блок). */
    private static boolean isCustomBlock(Block block) {
        return CraftEngineSupport.available() && CraftEngineSupport.idAt(block) != null;
    }
}
