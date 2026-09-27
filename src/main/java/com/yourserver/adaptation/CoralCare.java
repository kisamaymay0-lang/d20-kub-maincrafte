package com.yourserver.adaptation;

import org.bukkit.Bukkit;
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
 * Шифт + ПКМ бутылкой с водой по кораллу — коралл становится цветным и остаётся
 * таким до следующего ломания: и живой вне воды, и оживлённый мёртвый.
 *
 * <h2>Как ваниль сушит кораллы</h2>
 *
 * Живой коралл вне воды умирает случайным тиком: блок меняется на свой
 * {@code dead_*} вариант. Bukkit об этом сообщает событием
 * {@link BlockFadeEvent} (в его списке прямо значится «coral fading to dead
 * coral due to lack of water»), поэтому «оставить цветным» — это отменить
 * увядание у нужных координат, а не переписывать блок.
 *
 * <h2>Мёртвый коралл</h2>
 *
 * В ванили мёртвый коралл оживить нельзя. Здесь можно: {@code dead_brain_coral}
 * → {@code brain_coral} (то же для {@code _block}, {@code _fan} и
 * {@code _wall_fan}), состояние блока сохраняется — поворот веера и
 * водность переезжают как есть. Оживлённый коралл сразу попадает в тот же
 * список политых, поэтому обратно сохнуть не начинает.
 *
 * <h2>Где хранится список</h2>
 *
 * В {@code plugins/f8-plugin/corals.yml}: список координат
 * {@code мир|x|y|z}. Запись уходит на диск тем же батчевым писателем
 * ({@link BatchedYamlFile}), что и кувшины, — тик сохранения один на плагин.
 * Сломанный блок из списка убирается: «навсегда» до следующего ломания, а не
 * навсегда на этом месте. Никаких таймеров у эффекта нет: полили — и коралл
 * цветной, пока его не сломают.
 *
 * <h2>Чего здесь нет</h2>
 *
 * В креативе вода, как обычно, не тратится.
 */
final class CoralCare implements Listener {

    private static final String DEAD = "dead_";

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
        return name.contains("_coral") && !name.startsWith(DEAD);
    }

    static boolean isCoral(Material material) {
        return material != null && !material.isAir() && isCoral(material.name());
    }

    /** Мёртвый коралл: {@code dead_brain_coral}, {@code dead_tube_coral_block}. */
    static boolean isDeadCoral(String materialName) {
        if (materialName == null) return false;
        String name = materialName.toLowerCase(Locale.ROOT);
        return name.startsWith(DEAD) && name.contains("_coral");
    }

    static boolean isDeadCoral(Material material) {
        return material != null && !material.isAir() && isDeadCoral(material.name());
    }

    /** Полить можно и живой (оставить цветным), и мёртвый (оживить). */
    static boolean isWaterable(Material material) {
        return isCoral(material) || isDeadCoral(material);
    }

    /**
     * Живая пара мёртвого коралла: {@code dead_brain_coral_block} →
     * {@code brain_coral_block}. {@code null}, если это не мёртвый коралл.
     */
    static String reviveName(String materialName) {
        if (!isDeadCoral(materialName)) return null;
        return materialName.toLowerCase(Locale.ROOT).substring(DEAD.length());
    }

    /** Живой материал для мёртвого или {@code null}, если оживлять нечего. */
    static Material revive(Material material) {
        String name = reviveName(material == null ? null : material.name());
        if (name == null) return null;
        return Material.getMaterial(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Переписать состояние блока ({@code BlockData#getAsString()}) на живую
     * пару, сохранив свойства: {@code minecraft:dead_brain_coral_wall_fan[
     * facing=north,waterlogged=false]} → {@code minecraft:brain_coral_wall_fan[
     * facing=north,waterlogged=false]}.
     */
    static String reviveBlockData(String asString) {
        if (asString == null || asString.isEmpty()) return null;
        int bracket = asString.indexOf('[');
        String id = bracket < 0 ? asString : asString.substring(0, bracket);
        String states = bracket < 0 ? "" : asString.substring(bracket);
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String name = colon < 0 ? id : id.substring(colon + 1);
        String alive = reviveName(name);
        if (alive == null) return null;
        return namespace + ":" + alive + states;
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

        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.isSneaking()) return;
        if (!isWaterable(block.getType())) return;
        if (!WaterBottle.isWaterBottle(event.getItem())) return;

        revive(block);
        boolean fresh = preserved.add(key(block));
        if (fresh) save();

        block.getWorld().spawnParticle(
                Particle.SPLASH,
                block.getLocation().add(0.5, 0.6, 0.5),
                12, 0.3, 0.3, 0.3, 0.0
        );

        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, hand);
        }
        event.setCancelled(true);
    }

    /** Увядание коралла: для политых блоков отменяется — они остаются цветными. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (!preserved(block)) return;
        if (!isWaterable(block.getType())) {
            // Коралла на этом месте больше нет — запись больше не нужна.
            if (preserved.remove(key(block))) save();
            return;
        }
        event.setCancelled(true);
    }

    /** Сломанный коралл больше не храним: «навсегда» кончилось. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (preserved.remove(key(event.getBlock()))) save();
    }

    // ===== БЛОК =====

    /** Мёртвый коралл становится живым; состояние (поворот, вода) сохраняется. */
    private static void revive(Block block) {
        Material alive = revive(block.getType());
        if (alive == null) return;
        String data = reviveBlockData(block.getBlockData().getAsString());
        if (data != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(data), false);
                return;
            } catch (IllegalArgumentException ignored) {
                // Состояние живого варианта не совпало — подменим только материал.
            }
        }
        block.setType(alive, false);
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
