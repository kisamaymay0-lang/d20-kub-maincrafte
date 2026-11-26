package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Крафт блока света и понижение его уровня рукой.
 *
 * <h2>Крафт</h2>
 *
 * Факел в центре, вокруг — четыре белые стеклянные панели (крестом):
 *
 * <pre>
 *   . P .        P — белая панель
 *   P T P        T — факел
 *   . P .
 * </pre>
 *
 * Выходит один блок света уровня 15 — самый яркий.
 *
 * <h2>Понижение</h2>
 *
 * Шифт + ПКМ блоком света по поставленному блоку света: уровень падает на 1
 * (15 → 14 → … → 1 → 0), а с нуля круг начинается заново — 0 → 15. Частиц
 * изменения нет: уровень виден сам по себе, по яркости. Предмет в руке не
 * тратится — это настройка, а не расходник.
 *
 * <h2>Почему уровень, а не другой блок</h2>
 *
 * У блока света ровно одно состояние — {@code level} 0…15
 * ({@link Levelled}), отдельного «тусклого света» в ванили нет.
 */
final class LightBlocks implements Listener {

    /** Самый тусклый блок света: света не даёт, но блок остаётся. */
    static final int MIN_LEVEL = 0;

    /** Самый яркий блок света — и начало круга после нуля. */
    static final int MAX_LEVEL = 15;

    private final JavaPlugin plugin;

    LightBlocks(JavaPlugin plugin) {
        this.plugin = plugin;
        registerRecipe();
    }

    /**
     * Следующий уровень: на 1 ниже, а с {@link #MIN_LEVEL} — снова
     * {@link #MAX_LEVEL}. Чужой уровень (из будущего или сломанный) приводится
     * к краю диапазона.
     */
    static int nextLevel(int level) {
        if (level > MAX_LEVEL) return MAX_LEVEL - 1;
        if (level <= MIN_LEVEL) return MAX_LEVEL;
        return level - 1;
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "light_block");
        ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(Material.LIGHT));
        recipe.shape(" P ", "PTP", " P ");
        recipe.setIngredient('P', Material.WHITE_STAINED_GLASS_PANE);
        recipe.setIngredient('T', Material.TORCH);
        Bukkit.removeRecipe(key);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void dim(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || block.getType() != Material.LIGHT) return;
        if (!player.isSneaking()) return;

        ItemStack held = event.getItem();
        if (held == null || held.getType() != Material.LIGHT) return;
        if (!(block.getBlockData() instanceof Levelled levelled)) return;

        levelled.setLevel(nextLevel(levelled.getLevel()));
        block.setBlockData(levelled);
        event.setCancelled(true);
    }
}
