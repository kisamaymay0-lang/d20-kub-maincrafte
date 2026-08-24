package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CopperBlockListener implements Listener {
    private static final int GUI_SIZE = 36;
    private static final Material TECHNICAL_BLOCK = Material.BARRIER;
    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final JavaPlugin plugin;
    private final File configFile;
    private FileConfiguration blockData;
    private final Map<String, ItemStack[]> inventories = new HashMap<>();
    private final Set<Integer> blockedSlots = new HashSet<>();
    private final NamespacedKey blockKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey itemKey;

    public CopperBlockListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.blockKey = new NamespacedKey(plugin, "copper_noteblock");
        this.displayKey = new NamespacedKey(plugin, "copper_noteblock_display");
        this.itemKey = new NamespacedKey(plugin, "copper_noteblock_item");

        configFile = new File(plugin.getDataFolder(), "blocks.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        blockData = YamlConfiguration.loadConfiguration(configFile);

        blockedSlots.add(0); blockedSlots.add(9); blockedSlots.add(18); blockedSlots.add(27);
        blockedSlots.add(1); blockedSlots.add(19); blockedSlots.add(28);
        blockedSlots.add(2); blockedSlots.add(11); blockedSlots.add(20); blockedSlots.add(29);
        blockedSlots.add(3); blockedSlots.add(12); blockedSlots.add(21); blockedSlots.add(30);
        blockedSlots.add(8); blockedSlots.add(17); blockedSlots.add(26); blockedSlots.add(35);

        loadAllBlocksFromFile();
        Bukkit.getScheduler().runTask(plugin, this::restoreDisplays);
    }

    public ItemStack createCopperBlockItem() {
        ItemStack item = new ItemStack(TECHNICAL_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Медный нотный блок");
            meta.setItemModel(new NamespacedKey("f8resurs", "medni_notny"));
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(itemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isCopperItem(ItemStack item) {
        if (item == null || item.getType() != TECHNICAL_BLOCK || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private String getBlockKey(Block block) {
        return getBlockKey(block.getLocation());
    }

    private String getBlockKey(Location location) {
        return location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }

    private Location parseLocation(String key) {
        int last = key.lastIndexOf('_');
        int third = key.lastIndexOf('_', last - 1);
        int second = key.lastIndexOf('_', third - 1);
        if (last < 0 || third < 0 || second < 0) return null;

        String worldName = key.substring(0, second);
        try {
            int x = Integer.parseInt(key.substring(second + 1, third));
            int y = Integer.parseInt(key.substring(third + 1, last));
            int z = Integer.parseInt(key.substring(last + 1));
            World world = Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new Location(world, x, y, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!isCopperItem(event.getItemInHand())) return;

        Block block = event.getBlockPlaced();
        if (block.getType() != TECHNICAL_BLOCK) return;

        block.setType(TECHNICAL_BLOCK, false);
        String key = getBlockKey(block);
        inventories.put(key, new ItemStack[GUI_SIZE]);
        saveInventory(key, inventories.get(key));
        spawnDisplay(block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (!isCopperBlock(block)) return;

        event.setCancelled(true);
        openCopperMenu(event.getPlayer(), block);
    }

    private boolean isCopperBlock(Block block) {
        return block.getType() == TECHNICAL_BLOCK && blockData.contains("blocks." + getBlockKey(block));
    }

    private void openCopperMenu(Player player, Block block) {
        Inventory gui = Bukkit.createInventory(new CopperHolder(block), GUI_SIZE, "§6Медный нотный блок");
        String key = getBlockKey(block);
        ItemStack[] contents = inventories.get(key);
        if (contents != null) gui.setContents(contents.clone());

        ItemStack separator = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = separator.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            separator.setItemMeta(meta);
        }
        for (int slot : blockedSlots) {
            if (gui.getItem(slot) == null) gui.setItem(slot, separator);
        }

        player.openInventory(gui);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CopperHolder holder)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < GUI_SIZE && blockedSlots.contains(rawSlot)) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> saveInventory(getBlockKey(holder.getBlock()), event.getInventory().getContents()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CopperHolder holder)) return;
        saveInventory(getBlockKey(holder.getBlock()), event.getInventory().getContents());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (!isCopperBlock(block)) return;

        event.setCancelled(true);
        breakCopperBlock(block);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isCopperBlock(event.getBlock())) return;
        event.setCancelled(true);
        breakCopperBlock(event.getBlock());
    }

    private void breakCopperBlock(Block block) {
        String key = getBlockKey(block);
        ItemStack[] contents = inventories.get(key);
        if (contents == null) contents = new ItemStack[GUI_SIZE];

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR && !blockedSlots.contains(i)) {
                block.getWorld().dropItemNaturally(block.getLocation(), item.clone());
            }
        }

        block.getWorld().dropItemNaturally(block.getLocation(), createCopperBlockItem());
        removeDisplay(block.getLocation());
        inventories.remove(key);
        blockData.set("blocks." + key, null);
        saveData();
        block.setType(Material.AIR, false);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        List<Block> affected = new ArrayList<>(event.blockList());
        for (Block block : affected) {
            if (isCopperBlock(block)) {
                event.blockList().remove(block);
                breakCopperBlock(block);
            }
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().removeIf(this::isCopperBlock);
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().removeIf(this::isCopperBlock);
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        Block wireBlock = event.getBlock();
        for (BlockFace face : FACES) {
            Block target = wireBlock.getRelative(face);
            if (isCopperBlock(target) && event.getNewCurrent() > 0 && event.getOldCurrent() == 0) {
                triggerCopperBlock(target);
            }
        }
    }

    private void triggerCopperBlock(Block block) {
        String key = getBlockKey(block);
        ItemStack[] items = inventories.get(key);
        if (items == null) return;

        ItemStack timeItem = items.length > 10 ? items[10] : null;
        int amount = timeItem != null && timeItem.getType() != Material.AIR ? timeItem.getAmount() : 0;
        int delayTicks = Math.max(2, Math.min(amount > 0 ? amount * 2 : 4, 100));

        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4) || step >= 4) {
                    cancel();
                    return;
                }

                int[] slots = {4 + step, 13 + step, 22 + step, 31 + step};
                float[] pitches = {1.0f, 0.79f, 0.63f, 0.5f};
                float[] volumes = {1.2f, 1.0f, 0.8f, 0.9f};
                boolean played = false;

                for (int i = 0; i < slots.length; i++) {
                    ItemStack item = items[slots[i]];
                    if (item == null || item.getType() == Material.AIR) continue;
                    block.getWorld().playSound(block.getLocation(), getInstrumentByMaterial(item.getType()), volumes[i], pitches[i]);
                    played = true;
                }

                if (played) block.getWorld().spawnParticle(org.bukkit.Particle.NOTE, block.getLocation().add(0.5, 1.2, 0.5), 1, 0, 0, 0, 0);
                step++;
            }
        }.runTaskTimer(plugin, 0L, delayTicks);
    }

    private Sound getInstrumentByMaterial(Material material) {
        String name = material.name();
        if (name.contains("BONE_BLOCK")) return Sound.BLOCK_NOTE_BLOCK_XYLOPHONE;
        if (name.contains("GOLD_BLOCK")) return Sound.BLOCK_NOTE_BLOCK_BELL;
        if (name.contains("CLAY")) return Sound.BLOCK_NOTE_BLOCK_FLUTE;
        if (name.contains("PACKED_ICE")) return Sound.BLOCK_NOTE_BLOCK_CHIME;
        if (name.contains("WOOL") || name.contains("CARPET")) return Sound.BLOCK_NOTE_BLOCK_GUITAR;
        if (name.contains("IRON_BLOCK")) return Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE;
        if (name.contains("SOUL_SAND")) return Sound.BLOCK_NOTE_BLOCK_COW_BELL;
        if (name.contains("PUMPKIN")) return Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
        if (name.contains("EMERALD_BLOCK")) return Sound.BLOCK_NOTE_BLOCK_BIT;
        if (name.contains("HAY_BLOCK")) return Sound.BLOCK_NOTE_BLOCK_BANJO;
        if (name.contains("GLOWSTONE")) return Sound.BLOCK_NOTE_BLOCK_PLING;
        if (name.contains("AMETHYST")) return Sound.BLOCK_NOTE_BLOCK_CHIME;
        if (name.contains("COPPER")) return Sound.BLOCK_NOTE_BLOCK_BASS;
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANKS")) return Sound.BLOCK_NOTE_BLOCK_BASS;
        if (name.contains("STONE") || name.contains("COBBLESTONE") || name.contains("OBSIDIAN") || name.contains("ORE")) return Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
        if (name.contains("SAND") || name.contains("GRAVEL")) return Sound.BLOCK_NOTE_BLOCK_SNARE;
        if (name.contains("GLASS")) return Sound.BLOCK_NOTE_BLOCK_HAT;
        return Sound.BLOCK_NOTE_BLOCK_HARP;
    }

    private void spawnDisplay(Location location) {
        removeDisplay(location);
        ItemStack item = createCopperBlockItem();
        ItemDisplay display = location.getWorld().spawn(location.clone().add(0.5, 0.5, 0.5), ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
        });
        display.setViewRange(64.0f);
    }

    private void removeDisplay(Location location) {
        if (location.getWorld() == null) return;
        for (ItemDisplay display : location.getWorld().getEntitiesByClass(ItemDisplay.class)) {
            Location displayLocation = display.getLocation();
            if (display.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)
                    && displayLocation.getBlockX() == location.getBlockX()
                    && displayLocation.getBlockY() == location.getBlockY()
                    && displayLocation.getBlockZ() == location.getBlockZ()) {
                display.remove();
            }
        }
    }

    private void restoreDisplays() {
        for (String key : new ArrayList<>(inventories.keySet())) {
            Location location = parseLocation(key);
            if (location == null) continue;
            if (location.getBlock().getType() != TECHNICAL_BLOCK) {
                location.getBlock().setType(TECHNICAL_BLOCK, false);
            }
            spawnDisplay(location);
        }
    }

    private void loadAllBlocksFromFile() {
        if (!blockData.contains("blocks")) return;
        var section = blockData.getConfigurationSection("blocks");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Object value = blockData.get("blocks." + key);
            if (value instanceof List<?> list) {
                ItemStack[] items = new ItemStack[GUI_SIZE];
                for (int i = 0; i < Math.min(list.size(), GUI_SIZE); i++) {
                    Object entry = list.get(i);
                    if (entry instanceof ItemStack stack) items[i] = stack;
                }
                inventories.put(key, items);
            }
        }
    }

    private void saveInventory(String key, ItemStack[] contents) {
        inventories.put(key, contents.clone());
        blockData.set("blocks." + key, contents);
        saveData();
    }

    private void saveData() {
        try {
            blockData.save(configFile);
        } catch (IOException ignored) {
        }
    }

    private static class CopperHolder implements org.bukkit.inventory.InventoryHolder {
        private final Block block;
        CopperHolder(Block block) {
            this.block = block;
        }
        Block getBlock() {
            return block;
        }
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
