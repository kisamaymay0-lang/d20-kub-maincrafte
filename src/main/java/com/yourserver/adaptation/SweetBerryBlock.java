package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Механика блока сладких ягод (sweetie_bery_block).
 *
 * <p>Не имеет предмета блока и крафта.
 * Создаётся нажатием Shift + ПКM сладкими ягодами (>= 12 шт.) в яме из полных блоков
 * (North, South, East, West, Down).
 *
 * <p>При обычном разрушении выпадает 12 обычных сладких ягод.
 *
 * <p>Топтание:
 * <ul>
 *   <li>Shift стоя на блоке: слабый топ, зелёные частицы; после 8 топов выпадают «Помятые сладкие ягоды».</li>
 *   <li>Падение на блок: сильный топ, зелёные частицы; после 8 топов выпадают «Продавленные сладкие ягоды».</li>
 * </ul>
 */
public class SweetBerryBlock implements Listener {

    public static final int REQUIRED_BERRIES = 12;
    public static final int REQUIRED_STAMPS = 8;
    public static final int CRUSHED_BERRIES_DROP = 4;
    public static final long CRUSH_DELAY_TICKS = 10L; // 0.5 секунды (10 тиков)

    private static final NamespacedKey LOW_BERRY_KEY = new NamespacedKey("f8resurs", "low_sweetie_berry");
    private static final NamespacedKey HARD_BERRY_KEY = new NamespacedKey("f8resurs", "hard_sweetie_berry");

    private final AdaptationPlugin plugin;
    private final File configFile;
    private final FileConfiguration berriesData;
    private final BatchedYamlFile storage;

    private final Map<String, StampData> stampsMap = new ConcurrentHashMap<>();
    private final Set<String> breakingBlocks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> sneakDebounce = new HashMap<>();
    private final Map<UUID, Long> fallDebounce = new HashMap<>();

    public static class StampData {
        public int weakStamps;
        public int strongStamps;

        public StampData(int weakStamps, int strongStamps) {
            this.weakStamps = weakStamps;
            this.strongStamps = strongStamps;
        }
    }

    public SweetBerryBlock(AdaptationPlugin plugin, AsyncTextWriter dataWriter) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "sweet_berries.yml");
        if (!configFile.exists()) {
            try {
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                configFile.createNewFile();
            } catch (IOException ex) {
                plugin.getLogger().severe("Не удалось создать sweet_berries.yml: " + ex.getMessage());
            }
        }
        this.berriesData = YamlConfiguration.loadConfiguration(configFile);
        this.storage = new BatchedYamlFile(plugin, dataWriter, configFile.toPath(), berriesData::saveToString);

        loadAllBlocksFromFile();
    }

    // ===== ПРЕДМЕТЫ =====

    public static ItemStack createLowSweetieBerry() {
        return createLowSweetieBerry(1);
    }

    public static ItemStack createLowSweetieBerry(int amount) {
        ItemStack item = new ItemStack(Material.SWEET_BERRIES, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text("Помятые сладкие ягоды", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.setItemModel(new NamespacedKey("f8resurs", "low_sweetie_berry"));
            meta.getPersistentDataContainer().set(LOW_BERRY_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createHardSweetieBerry() {
        return createHardSweetieBerry(1);
    }

    public static ItemStack createHardSweetieBerry(int amount) {
        ItemStack item = new ItemStack(Material.SWEET_BERRIES, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text("Продавленные сладкие ягоды", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.setItemModel(new NamespacedKey("f8resurs", "hard_sweetie_berry"));
            meta.getPersistentDataContainer().set(HARD_BERRY_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isLowSweetieBerry(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(LOW_BERRY_KEY, PersistentDataType.BYTE);
    }

    public static boolean isHardSweetieBerry(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(HARD_BERRY_KEY, PersistentDataType.BYTE);
    }

    public static boolean isCustomBerryItem(ItemStack item) {
        return isLowSweetieBerry(item) || isHardSweetieBerry(item);
    }

    // ===== ОПРЕДЕЛЕНИЕ ЯМЫ И БЛОКОВ =====

    public static boolean isFullSolidBlock(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat.isAir() || block.isLiquid()) return false;
        return mat.isSolid() && mat.isOccluding();
    }

    public static boolean isPit(Block block) {
        if (block == null || !block.getType().isAir()) {
            return false;
        }
        return isFullSolidBlock(block.getRelative(BlockFace.DOWN))
                && isFullSolidBlock(block.getRelative(BlockFace.NORTH))
                && isFullSolidBlock(block.getRelative(BlockFace.SOUTH))
                && isFullSolidBlock(block.getRelative(BlockFace.EAST))
                && isFullSolidBlock(block.getRelative(BlockFace.WEST));
    }

    public boolean isSweetBerryBlock(Block block) {
        if (block == null) return false;
        if (CraftEngineSweetBerry.isSweetBerryBlock(block)) {
            return true;
        }
        return stampsMap.containsKey(blockKey(block));
    }

    public static String blockKey(Block block) {
        return block.getWorld().getName() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    public static String blockKey(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    // ===== СОЗДАНИЕ И РАЗРУШЕНИЕ БЛОКА =====

    public void placeSweetBerryBlock(Block block) {
        if (CraftEngineSweetBerry.ready()) {
            CraftEngineSweetBerry.place(block);
        } else {
            block.setType(Material.MUSHROOM_STEM);
        }
        String key = blockKey(block);
        StampData data = new StampData(0, 0);
        stampsMap.put(key, data);
        saveBlockStamp(key, data);

        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        world.playSound(center, Sound.BLOCK_SWEET_BERRY_BUSH_PLACE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0, 0.3, 0), 10, 0.25, 0.2, 0.25, 0.05);
    }

    public void removeSweetBerryBlock(Block block) {
        String key = blockKey(block);
        stampsMap.remove(key);
        removeBlockStamp(key);
        if (CraftEngineSweetBerry.available()) {
            CraftEngineSweetBerry.remove(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.SWEET_BERRIES || isCustomBerryItem(item)) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE && item.getAmount() < REQUIRED_BERRIES) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Block target = clicked.getRelative(event.getBlockFace());
        if (!isPit(target)) {
            return;
        }

        event.setCancelled(true);

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.subtract(REQUIRED_BERRIES);
        }

        placeSweetBerryBlock(target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isSweetBerryBlock(block)) {
            return;
        }

        String key = blockKey(block);
        breakingBlocks.remove(key);

        event.setDropItems(false);
        removeSweetBerryBlock(block);

        World world = block.getWorld();
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            world.dropItemNaturally(loc, new ItemStack(Material.SWEET_BERRIES, REQUIRED_BERRIES));
        }
        world.playSound(loc, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, SoundCategory.BLOCKS, 1.0f, 0.9f);
    }

    // ===== ТОПТАНИЕ =====

    private Block getStandingBerryBlock(Player player) {
        Location loc = player.getLocation();
        Block down = loc.getBlock().getRelative(BlockFace.DOWN);
        if (isSweetBerryBlock(down)) {
            return down;
        }
        Block at = loc.getBlock();
        if (isSweetBerryBlock(at)) {
            return at;
        }
        Location footLoc = loc.clone().subtract(0, 0.2, 0);
        Block foot = footLoc.getBlock();
        if (isSweetBerryBlock(foot)) {
            return foot;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = sneakDebounce.get(player.getUniqueId());
        if (last != null && now - last < 200L) {
            return;
        }
        sneakDebounce.put(player.getUniqueId(), now);

        Block berryBlock = getStandingBerryBlock(player);
        if (berryBlock != null) {
            handleWeakStamp(player, berryBlock);
        }
    }

    public void handleWeakStamp(Player player, Block block) {
        String key = blockKey(block);
        if (breakingBlocks.contains(key)) {
            return;
        }

        StampData data = stampsMap.computeIfAbsent(key, k -> new StampData(0, 0));
        data.weakStamps++;
        saveBlockStamp(key, data);

        World world = block.getWorld();
        Location top = block.getLocation().add(0.5, 1.05, 0.5);

        world.spawnParticle(Particle.HAPPY_VILLAGER, top, 8, 0.25, 0.05, 0.25, 0.05);
        world.playSound(top, Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, SoundCategory.BLOCKS, 0.9f, 1.2f);

        if (data.weakStamps >= REQUIRED_STAMPS && breakingBlocks.add(key)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!breakingBlocks.remove(key)) {
                    return;
                }
                removeSweetBerryBlock(block);
                block.setType(Material.AIR);
                world.playSound(top, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, SoundCategory.BLOCKS, 1.0f, 0.8f);
                world.spawnParticle(Particle.HAPPY_VILLAGER, top, 16, 0.3, 0.2, 0.3, 0.08);
                world.dropItemNaturally(top, createLowSweetieBerry(CRUSHED_BERRIES_DROP));
            }, CRUSH_DELAY_TICKS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();
        float fallDistance = player.getFallDistance();

        if (fallDistance >= 0.75f && (player.isOnGround() || to.getY() <= from.getY())) {
            Block berryBlock = getStandingBerryBlock(player);
            if (berryBlock != null) {
                long now = System.currentTimeMillis();
                Long last = fallDebounce.get(player.getUniqueId());
                if (last != null && now - last < 250L) {
                    return;
                }
                fallDebounce.put(player.getUniqueId(), now);
                handleStrongStamp(player, berryBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            Block berryBlock = getStandingBerryBlock(player);
            if (berryBlock != null) {
                long now = System.currentTimeMillis();
                Long last = fallDebounce.get(player.getUniqueId());
                if (last != null && now - last < 250L) {
                    return;
                }
                fallDebounce.put(player.getUniqueId(), now);
                handleStrongStamp(player, berryBlock);
            }
        }
    }

    public void handleStrongStamp(Player player, Block block) {
        String key = blockKey(block);
        if (breakingBlocks.contains(key)) {
            return;
        }

        StampData data = stampsMap.computeIfAbsent(key, k -> new StampData(0, 0));
        data.strongStamps++;
        saveBlockStamp(key, data);

        World world = block.getWorld();
        Location top = block.getLocation().add(0.5, 1.05, 0.5);

        world.spawnParticle(Particle.HAPPY_VILLAGER, top, 14, 0.3, 0.1, 0.3, 0.08);
        world.playSound(top, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, SoundCategory.BLOCKS, 0.9f, 1.4f);

        if (data.strongStamps >= REQUIRED_STAMPS && breakingBlocks.add(key)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!breakingBlocks.remove(key)) {
                    return;
                }
                removeSweetBerryBlock(block);
                block.setType(Material.AIR);
                world.playSound(top, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, SoundCategory.BLOCKS, 1.0f, 0.7f);
                world.spawnParticle(Particle.HAPPY_VILLAGER, top, 20, 0.35, 0.3, 0.35, 0.1);
                world.dropItemNaturally(top, createHardSweetieBerry(CRUSHED_BERRIES_DROP));
            }, CRUSH_DELAY_TICKS);
        }
    }

    // ===== ПОРШНИ И ВЗРЫВЫ =====

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isSweetBerryBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isSweetBerryBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    private void handleExplodedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            if (isSweetBerryBlock(block)) {
                removeSweetBerryBlock(block);
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                        new ItemStack(Material.SWEET_BERRIES, REQUIRED_BERRIES));
            }
        }
    }

    // ===== СОХРАНЕНИЕ И ЗАГРУЗКА ДАННЫХ =====

    private void saveBlockStamp(String key, StampData data) {
        berriesData.set("blocks." + key + ".weak_stamps", data.weakStamps);
        berriesData.set("blocks." + key + ".strong_stamps", data.strongStamps);
        storage.markDirty();
    }

    private void removeBlockStamp(String key) {
        berriesData.set("blocks." + key, null);
        storage.markDirty();
    }

    private void loadAllBlocksFromFile() {
        ConfigurationSection section = berriesData.getConfigurationSection("blocks");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            int weak = berriesData.getInt("blocks." + key + ".weak_stamps", 0);
            int strong = berriesData.getInt("blocks." + key + ".strong_stamps", 0);
            stampsMap.put(key, new StampData(weak, strong));
        }
    }

    public void disable() {
        storage.flush();
        stampsMap.clear();
        breakingBlocks.clear();
        sneakDebounce.clear();
        fallDebounce.clear();
    }
}
