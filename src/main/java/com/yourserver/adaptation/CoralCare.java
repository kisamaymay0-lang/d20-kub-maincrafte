package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
 * до следующего ломания, даже вне воды. Мёртвый коралл водой оживает: он
 * снова становится цветным и больше не высыхает.
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
 * Ваниль оживить коралл не умеет, поэтому мёртвый блок просто подменяется на
 * свой живой двойник: {@code dead_brain_coral} → {@code brain_coral},
 * {@code dead_brain_coral_wall_fan} → {@code brain_coral_wall_fan}. Меняется
 * только имя материала в строке {@code BlockData}, поэтому поворот настенного
 * веера и водность сохраняются. Оживлённый коралл попадает в тот же список,
 * что и политые: никакого таймера и срока — он цветной, пока его не сломают.
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
 * - **Креатив.** Вода, как обычно, не тратится.
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

    /**
     * Живое имя материала для мёртвого коралла или null, если это не мёртвый
     * коралл: dead_brain_coral даёт brain_coral, dead_brain_coral_wall_fan —
     * brain_coral_wall_fan.
     */
    static String liveName(String materialName) {
        if (materialName == null) return null;
        String name = materialName.toLowerCase(Locale.ROOT);
        if (!name.startsWith("dead_")) return null;
        String live = name.substring("dead_".length());
        return live.contains("_coral") ? live : null;
    }

    /** Мёртвый коралл: тот, которого водой можно оживить. */
    static boolean isDeadCoral(String materialName) {
        return liveName(materialName) != null;
    }

    static boolean isDeadCoral(Material material) {
        return material != null && !material.isAir() && isDeadCoral(material.name());
    }

    /**
     * Переписать состояние мёртвого коралла на живое, сохранив все свойства:
     * minecraft:dead_brain_coral_wall_fan[facing=north] становится
     * minecraft:brain_coral_wall_fan[facing=north].
     *
     * @return новое состояние или null, если оживлять нечего.
     */
    static String reviveBlockData(String asString) {
        if (asString == null || asString.isEmpty()) return null;
        int bracket = asString.indexOf('[');
        String id = bracket < 0 ? asString : asString.substring(0, bracket);
        String states = bracket < 0 ? "" : asString.substring(bracket);
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String name = colon < 0 ? id : id.substring(colon + 1);
        String live = liveName(name);
        if (live == null) return null;
        return namespace + ":" + live + states;
    }

    /** Живой материал для мёртвого коралла или null, если такого нет. */
    static Material liveMaterial(Material dead) {
        String live = dead == null ? null : liveName(dead.name());
        return live == null ? null : Material.getMaterial(live.toUpperCase(Locale.ROOT));
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
        if (hand == null) return;
        // Событие приходит на каждую руку: работает только та, в которой бутылка.
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        // Бутылки в обеих руках — считаем клик один раз, по главной.
        if (hand == EquipmentSlot.OFF_HAND
                && WaterBottle.isWaterBottle(event.getPlayer().getInventory().getItemInMainHand())) return;

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.isSneaking()) return;
        if (!isCoral(block.getType()) && !isDeadCoral(block.getType())) return;

        revive(block);

        boolean fresh = preserved.add(key(block));
        if (fresh) save();

        block.getWorld().spawnParticle(
                Particle.SPLASH,
                block.getLocation().add(0.5, 0.6, 0.5),
                12, 0.3, 0.3, 0.3, 0.0
        );

        player.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.8F, 1.0F);

        if (player.getGameMode() != GameMode.CREATIVE) {
            WaterBottle.consume(player, hand);
        }
        event.setCancelled(true);
    }

    /**
     * Оживить мёртвый коралл: подменить материал на живой двойник, сохранив
     * поворот настенного веера и водность. Живой коралл не трогаем.
     */
    private static void revive(Block block) {
        if (!isDeadCoral(block.getType())) return;
        String data = reviveBlockData(block.getBlockData().getAsString());
        if (data != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(data), false);
                return;
            } catch (IllegalArgumentException ignored) {
                // Состояние живого варианта не совпало — подменим только материал.
            }
        }
        Material live = liveMaterial(block.getType());
        if (live != null) block.setType(live, false);
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
