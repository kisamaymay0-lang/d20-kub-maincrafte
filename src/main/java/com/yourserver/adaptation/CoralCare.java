package com.yourserver.adaptation;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Шифт + ПКМ бутылкой с водой по кораллу — коралл остаётся «цветным» (живым)
 * до следующего ломания, даже вне воды.
 *
 * <h2>Как ваниль сушит кораллы</h2>
 *
 * Живой коралл вне воды умирает случайным тиком: блок меняется на свой
 * {@code dead_*} вариант. Bukkit об этом сообщает событием
 * {@link BlockFadeEvent} (в его списке прямо значится «coral fading to dead
 * coral due to lack of water»), поэтому «оставить цветным» — это отменить
 * увядание у нужных координат, а не переписывать блок.
 *
 * <h2>Где хранится список</h2>
 *
 * В {@code plugins/f8-plugin/corals.yml}: список координат
 * {@code мир|x|y|z}. Запись уходит на диск тем же батчевым писателем
 * ({@link BatchedYamlFile}), что и кувшины, — тик сохранения один на плагин.
 * Сломанный блок из списка убирается: «навсегда» до следующего ломания, а не
 * навсегда на этом месте.
 *
 * <h2>Чего здесь нет</h2>
 *
 * Мёртвые кораллы ({@code dead_*}) полить нельзя — они уже мертвы, оживить их
 * ваниль не умеет. В креативе вода, как обычно, не тратится.
 */
final class CoralCare implements Listener {

    private final JavaPlugin plugin;
    private final Set<String> preserved = ConcurrentHashMap.newKeySet();
    private final File file;
    private final YamlConfiguration yaml;
    private final BatchedYamlFile storage;

    CoralCare(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "corals.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
        load();
        this.storage = new BatchedYamlFile(plugin, writer, file.toPath(), () -> yaml.saveToString());
    }

    /** Живой коралл: имя материала содержит {@code _coral} и это не {@code dead_*} вариант. */
    static boolean isCoral(String materialName) {
        if (materialName == null) return false;
        String name = materialName.toLowerCase(Locale.ROOT);
        return name.contains("_coral") && !name.startsWith("dead_");
    }

    static boolean isCoral(Material material) {
        return material != null && !material.isAir() && isCoral(material.name());
    }

    /** Ключ блока для файла: {@code мир|x|y|z}. */
    static String key(Block block) {
        return block.getWorld().getName() + "|" + block.getX() + "|" + block.getY() + "|" + block.getZ();
    }

    boolean preserved(Block block) {
        return block != null && preserved.contains(key(block));
    }

    int size() {
        return preserved.size();
    }

    // ===== СОБЫТИЯ =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void water(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.isSneaking()) return;
        if (!isCoral(block.getType())) return;
        if (!WaterBottle.isWaterBottle(event.getItem())) return;

        boolean fresh = preserved.add(key(block));
        if (fresh) save();

        block.getWorld().spawnParticle(
                Particle.SPLASH,
                block.getLocation().add(0.5, 0.6, 0.5),
                12, 0.3, 0.3, 0.3, 0.0
        );

        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, EquipmentSlot.HAND);
        }
        event.setCancelled(true);
    }

    /** Увядание коралла: для политых блоков отменяется — они остаются цветными. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        if (!preserved(event.getBlock())) return;
        event.setCancelled(true);
    }

    /** Сломанный коралл больше не храним: «навсегда» кончилось. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (preserved.remove(key(event.getBlock()))) save();
    }

    // ===== ФАЙЛ =====

    private void load() {
        List<String> saved = yaml.getStringList("corals");
        if (saved.isEmpty()) return;
        for (String entry : saved) {
            if (entry != null && !entry.isBlank()) preserved.add(entry.trim());
        }
        plugin.getLogger().info("Политые кораллы: " + preserved.size() + " шт., файл " + file.getName());
    }

    private void save() {
        List<String> list = new ArrayList<>(preserved);
        list.sort(null);
        yaml.set("corals", list);
        storage.markDirty();
    }

    /** Записать файл не откладывая — на выключении плагина. */
    void flush() {
        storage.flush();
    }
}
