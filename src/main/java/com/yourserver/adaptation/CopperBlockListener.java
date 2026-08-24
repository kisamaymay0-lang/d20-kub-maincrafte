package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class CopperBlockListener implements Listener {

    private final JavaPlugin plugin;
    private final File configFile;
    private FileConfiguration blockData;

    private final HashMap<String, ItemStack[]> sessionInventories =
            new HashMap<>();

    private static final BlockFace[] FACES = {
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

    private final Set<Integer> blockedSlots =
            new HashSet<>();

    public CopperBlockListener(JavaPlugin plugin) {
        this.plugin = plugin;

        this.configFile =
                new File(
                        plugin.getDataFolder(),
                        "blocks.yml"
                );

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.blockData =
                YamlConfiguration.loadConfiguration(
                        configFile
                );

        loadAllBlocksFromFile();

        blockedSlots.add(0);
        blockedSlots.add(9);
        blockedSlots.add(18);
        blockedSlots.add(27);

        blockedSlots.add(1);
        blockedSlots.add(19);
        blockedSlots.add(28);

        blockedSlots.add(2);
        blockedSlots.add(11);
        blockedSlots.add(20);
        blockedSlots.add(29);

        blockedSlots.add(3);
        blockedSlots.add(12);
        blockedSlots.add(21);
        blockedSlots.add(30);

        blockedSlots.add(8);
        blockedSlots.add(17);
        blockedSlots.add(26);
        blockedSlots.add(35);
    }

    private String getBlockKey(Block block) {
        return block.getWorld().getName()
                + "_"
                + block.getX()
                + "_"
                + block.getY()
                + "_"
                + block.getZ();
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        if (block == null) {
            return;
        }

        if (block.getType() != Material.WAXED_CHISELED_COPPER) {
            return;
        }

        event.setCancelled(true);

        openCopperMenu(
                event.getPlayer(),
                block
        );
    }

    private void openCopperMenu(
            Player player,
            Block block
    ) {
        Inventory gui = Bukkit.createInventory(
                new CopperHolder(block),
                36,
                "§6Медный нотный блок"
        );

        String key = getBlockKey(block);

        if (sessionInventories.containsKey(key)) {
            gui.setContents(
                    sessionInventories.get(key)
            );
        } else {
            ItemStack separator =
                    new ItemStack(
                            Material.BLACK_STAINED_GLASS_PANE
                    );

            ItemMeta meta =
                    separator.getItemMeta();

            if (meta != null) {
                meta.setDisplayName(" ");
                separator.setItemMeta(meta);
            }

            for (int slot : blockedSlots) {
                gui.setItem(
                        slot,
                        separator
                );
            }
        }

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!(event.getInventory().getHolder()
                instanceof CopperHolder holder)) {
            return;
        }

        int slot = event.getRawSlot();

        if (blockedSlots.contains(slot)) {
            event.setCancelled(true);
            return;
        }

        Bukkit.getScheduler().runTask(
                plugin,
                () -> {
                    ItemStack[] contents =
                            event.getInventory().getContents();

                    String key =
                            getBlockKey(
                                    holder.getBlock()
                            );

                    sessionInventories.put(
                            key,
                            contents
                    );

                    blockData.set(
                            "blocks." + key,
                            contents
                    );

                    try {
                        blockData.save(configFile);
                    } catch (IOException ignored) {
                    }
                }
        );
    }

    @EventHandler
    public void onBlockBreak(
            BlockBreakEvent event
    ) {
        Block block =
                event.getBlock();

        if (block.getType()
                != Material.WAXED_CHISELED_COPPER) {
            return;
        }

        String key =
                getBlockKey(block);

        ItemStack[] items =
                sessionInventories.remove(key);

        if (items == null
                && blockData.contains("blocks." + key)) {

            items =
                    ((java.util.List<ItemStack>)
                            blockData.get(
                                    "blocks." + key
                            ))
                            .toArray(
                                    new ItemStack[0]
                            );
        }

        if (items != null) {
            for (int i = 0;
                 i < items.length;
                 i++) {

                if (items[i] != null
                        && items[i].getType()
                        != Material.AIR
                        && !blockedSlots.contains(i)) {

                    block.getWorld()
                            .dropItemNaturally(
                                    block.getLocation(),
                                    items[i]
                            );
                }
            }
        }

        blockData.set(
                "blocks." + key,
                null
        );

        try {
            blockData.save(configFile);
        } catch (IOException ignored) {
        }
    }

    @EventHandler
    public void onBlockRedstone(
            BlockRedstoneEvent event
    ) {
        Block wireBlock =
                event.getBlock();

        for (BlockFace face : FACES) {

            Block targetBlock =
                    wireBlock.getRelative(face);

            if (targetBlock.getType()
                    != Material.WAXED_CHISELED_COPPER) {
                continue;
            }

            if (event.getNewCurrent() > 0
                    && event.getOldCurrent() == 0) {

                triggerCopperBlock(
                        targetBlock
                );
            }
        }
    }

    private void triggerCopperBlock(
            Block block
    ) {
        if (block.hasMetadata(
                "copper_playing"
        )) {
            return;
        }

        long currentTime =
                System.currentTimeMillis();

        long lastUsed = 0;

        if (block.hasMetadata(
                "last_copper_trigger"
        )) {
            lastUsed =
                    block.getMetadata(
                            "last_copper_trigger"
                    )
                    .get(0)
                    .asLong();
        }

        if (currentTime - lastUsed < 400) {
            return;
        }

        block.setMetadata(
                "last_copper_trigger",
                new FixedMetadataValue(
                        plugin,
                        currentTime
                )
        );

        block.setMetadata(
                "copper_playing",
                new FixedMetadataValue(
                        plugin,
                        true
                )
        );

        String key =
                getBlockKey(block);

        ItemStack[] items =
                sessionInventories.get(key);

        if (items == null) {
            block.removeMetadata(
                    "copper_playing",
                    plugin
            );
            return;
        }

        int itemsInTimeSlot = 0;

        ItemStack timeItem =
                items[10];

        if (timeItem != null
                && timeItem.getType()
                != Material.AIR) {

            itemsInTimeSlot =
                    timeItem.getAmount();
        }

        int delayTicks =
                itemsInTimeSlot > 0
                        ? itemsInTimeSlot * 2
                        : 4;

        delayTicks =
                Math.clamp(
                        delayTicks,
                        2,
                        100
                );

        final int finalDelay =
                delayTicks;

        final ItemStack[] finalItems =
                items;

        org.bukkit.Location particleLoc =
                block.getLocation()
                        .clone()
                        .add(
                                0.5,
                                1.2,
                                0.5
                        );

        new BukkitRunnable() {

            int step = 0;

            @Override
            public void run() {

                if (step >= 4) {

                    block.removeMetadata(
                            "copper_playing",
                            plugin
                    );

                    cancel();
                    return;
                }

                int highSlot =
                        4 + step;

                int midSlot =
                        13 + step;

                int lowSlot =
                        22 + step;

                int subSlot =
                        31 + step;

                boolean playedAny =
                        false;

                if (finalItems[highSlot] != null
                        && finalItems[highSlot].getType()
                        != Material.AIR) {

                    Sound sound =
                            getInstrumentByMaterial(
                                    finalItems[highSlot]
                                            .getType()
                            );

                    block.getWorld().playSound(
                            block.getLocation(),
                            sound,
                            1.2f,
                            1.0f
                    );

                    playedAny = true;
                }

                if (finalItems[midSlot] != null
                        && finalItems[midSlot].getType()
                        != Material.AIR) {

                    Sound sound =
                            getInstrumentByMaterial(
                                    finalItems[midSlot]
                                            .getType()
                            );

                    block.getWorld().playSound(
                            block.getLocation(),
                            sound,
                            1.0f,
                            0.79f
                    );

                    playedAny = true;
                }

                if (finalItems[lowSlot] != null
                        && finalItems[lowSlot].getType()
                        != Material.AIR) {

                    Sound sound =
                            getInstrumentByMaterial(
                                    finalItems[lowSlot]
                                            .getType()
                            );

                    block.getWorld().playSound(
                            block.getLocation(),
                            sound,
                            0.8f,
                            0.63f
                    );

                    playedAny = true;
                }

                if (finalItems[subSlot] != null
                        && finalItems[subSlot].getType()
                        != Material.AIR) {

                    Sound sound =
                            getInstrumentByMaterial(
                                    finalItems[subSlot]
                                            .getType()
                            );

                    block.getWorld().playSound(
                            block.getLocation(),
                            sound,
                            0.9f,
                            0.5f
                    );

                    playedAny = true;
                }

                if (playedAny) {
                    block.getWorld().spawnParticle(
                            org.bukkit.Particle.NOTE,
                            particleLoc,
                            1,
                            0.0,
                            0.0,
                            0.0,
                            0.0
                    );
                }

                step++;
            }

        }.runTaskTimer(
                plugin,
                0L,
                finalDelay
        );
    }

    private Sound getInstrumentByMaterial(
            Material material
    ) {
        String name =
                material.name();

        if (name.contains("BONE_BLOCK")) {
            return Sound.BLOCK_NOTE_BLOCK_XYLOPHONE;
        }

        if (name.contains("GOLD_BLOCK")) {
            return Sound.BLOCK_NOTE_BLOCK_BELL;
        }

        if (name.contains("CLAY")) {
            return Sound.BLOCK_NOTE_BLOCK_FLUTE;
        }

        if (name.contains("PACKED_ICE")) {
            return Sound.BLOCK_NOTE_BLOCK_CHIME;
        }

        if (name.contains("WOOL")
                || name.contains("CARPET")) {
            return Sound.BLOCK_NOTE_BLOCK_GUITAR;
        }

        if (name.contains("IRON_BLOCK")) {
            return Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE;
        }

        if (name.contains("SOUL_SAND")) {
            return Sound.BLOCK_NOTE_BLOCK_COW_BELL;
        }

        if (name.contains("PUMPKIN")) {
            return Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
        }

        if (name.contains("EMERALD_BLOCK")) {
            return Sound.BLOCK_NOTE_BLOCK_BIT;
        }

        if (name.contains("HAY_BLOCK")) {
            return Sound.BLOCK_NOTE_BLOCK_BANJO;
        }

        if (name.contains("GLOWSTONE")) {
            return Sound.BLOCK_NOTE_BLOCK_PLING;
        }

        if (name.contains("AMETHYST")) {
            return Sound.BLOCK_NOTE_BLOCK_CHIME;
        }

        if (name.contains("COPPER")) {
            return Sound.BLOCK_NOTE_BLOCK_BASS;
        }

        if (name.contains("WOOD")
                || name.contains("LOG")
                || name.contains("PLANKS")) {
            return Sound.BLOCK_NOTE_BLOCK_BASS;
        }

        if (name.contains("STONE")
                || name.contains("COBBLESTONE")
                || name.contains("OBSIDIAN")
                || name.contains("ORE")) {
            return Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
        }

        if (name.contains("SAND")
                || name.contains("GRAVEL")) {
            return Sound.BLOCK_NOTE_BLOCK_SNARE;
        }

        if (name.contains("GLASS")) {
            return Sound.BLOCK_NOTE_BLOCK_HAT;
        }

        return Sound.BLOCK_NOTE_BLOCK_HARP;
    }

    @SuppressWarnings("unchecked")
    private void loadAllBlocksFromFile() {

        if (!blockData.contains("blocks")) {
            return;
        }

        for (String key :
                blockData
                        .getConfigurationSection("blocks")
                        .getKeys(false)) {

            java.util.List<ItemStack> list =
                    (java.util.List<ItemStack>)
                            blockData.get(
                                    "blocks." + key
                            );

            if (list != null) {
                sessionInventories.put(
                        key,
                        list.toArray(
                                new ItemStack[0]
                        )
                );
            }
        }
    }

    private static class CopperHolder
            implements org.bukkit.inventory.InventoryHolder {

        private final Block block;

        public CopperHolder(Block block) {
            this.block = block;
        }

        public Block getBlock() {
            return block;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
