package com.yourserver.adaptation;

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
