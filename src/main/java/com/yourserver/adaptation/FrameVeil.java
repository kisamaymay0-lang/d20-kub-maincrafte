package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Рамка, которую можно спрятать ножницами.
 *
 * <h2>Что делает</h2>
 *
 * - **Шифт + ПКМ ножницами по рамке** — рамка становится невидимой (или снова
 *   видимой, если уже спрятана). Предмет внутри не пропадает: он остаётся в
 *   рамке, просто рамку не видно.
 * - **Клик по спрятанной рамке** (любой: ЛКМ или ПКМ) — предмет вылетает из
 *   неё наружу, и в том месте проходит немного дымки: видно, что рамка
 *   изменилась, даже когда её самой не видно.
 * - **Пустая спрятанная рамка** ломается как обычно: клик по ней не мешает
 *   снять рамку с блока.
 *
 * <h2>Почему так</h2>
 *
 * Невидимая рамка в ванили — это и есть «спрятать предмет»: исчезает и рамка,
 * и то, что в ней лежало. Здесь предмет остаётся внутри, пока игрок сам не
 * достанет его кликом, — то есть рамку можно убрать из вида и вернуть содержимое
 * без поломки и без потери. Рамка после этого остаётся невидимой: прятали её
 * именно затм.
 *
 * <h2>Чего здесь нет</h2>
 *
 * Ножницы не изнашиваются: действие похоже на переключатель, а не на работу
 * инструментом.
 */
final class FrameVeil implements Listener {

    /** Сколько дымки в клубе: немного, только обозначить изменение. */
    private static final int PUFF = 8;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void shears(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() != Material.SHEARS) return;

        event.setCancelled(true);
        frame.setVisible(!frame.isVisible());
        puff(frame);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void takeOut(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        if (frame.isVisible()) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean withShears = held != null && held.getType() == Material.SHEARS;
        if (player.isSneaking() && withShears) return; // это прятанье, его обрабатывает shears()

        event.setCancelled(true);
        popOut(frame);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void punchOut(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (frame.isVisible()) return;
        if (isAir(frame.getItem())) return; // пустую рамку ломать можно как обычно

        event.setCancelled(true);
        popOut(frame);
    }

    /** Выбросить предмет из рамки: он летит наружу, рамка остаётся спрятанной. */
    private static void popOut(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (isAir(item)) return;
        frame.setItem(null, false);
        frame.getWorld().dropItem(dropSpot(frame), item);
        puff(frame);
    }

    /** Точка выброса: чуть перед рамкой, чтобы предмет не оказался в блоке. */
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

    private static boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
