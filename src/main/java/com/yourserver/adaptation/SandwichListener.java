package com.yourserver.adaptation;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.meta.ItemMeta;

/** Автоматические медали за поедание бутербродов с икрой (красная, чёрная, ледяная). */
final class SandwichListener implements Listener {
    private final ProfileManager profileManager;

    SandwichListener(ProfileManager profileManager) {
        this.profileManager = profileManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null) return;
        NamespacedKey model = meta.getItemModel();
        if (model == null || !model.getNamespace().equals("f8resurs")) return;
        String kind = switch (model.getKey()) {
            case "caviar_sandwich_red" -> "red";
            case "caviar_sandwich_black" -> "black";
            case "ice_caviar_sandwich" -> "ice";
            default -> null;
        };
        if (kind != null) profileManager.sandwichEaten(event.getPlayer(), kind);
    }
}
