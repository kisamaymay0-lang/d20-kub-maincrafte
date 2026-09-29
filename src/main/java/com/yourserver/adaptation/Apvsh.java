package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * АПВШ — аппарат для создания и наполнения сигарет и другого.
 *
 * <p>Кастомный блок CraftEngine {@code f8resurs:apvsh} с моделью и текстурой
 * {@code copper_note_block.json}.
 *
 * <p>Меню АПВШ: 3 ряда по 9 слотов (27 слотов).
 * <ul>
 *   <li>1-й ряд (слоты 0..8) и 3-й ряд (слоты 18..26) забиты серой стеклянной
 *       панелью, с которой нельзя взаимодействовать.</li>
 *   <li>2-й ряд:
 *       <ul>
 *         <li>1-й, 7-й, 9-й слоты (абсолютные 9, 15, 17) заблокированы серой панелью;</li>
 *         <li>слоты 2..6 (абсолютные 10..14) — 5 слотов для наполнения (пока что
 *             только порох до 64 шт.);</li>
 *         <li>8-й слот (абсолютный 16) — предмет для наполнения (только бумага
 *             в количестве 1 шт., либо готовая сигарета после сборки).</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Сборка по редстоун-сигналу: при подаче сигнала на блок АПВШ бумага и весь
 * порох из слотов наполнения расходуются, проигрывается звук удара наковальни
 * и на месте бумаги появляется готовая сигарета. Модель зависит от количества
 * начинки: 1..8 — маленькая, 9..20 — обычная, 21+ — большая.
 */
public final class Apvsh implements Listener {

    public static final String MENU_TITLE = "§8АПВШ";
    public static final int INVENTORY_SIZE = 27;

    /** Слоты, заблокированные стеклянными панелями: ряды 1 и 3, а также 1, 7, 9 слоты ряда 2. */
    public static final Set<Integer> PANE_SLOTS = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 15, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26
    );

    /** 5 слотов для наполнения во втором ряду (индексы 10..14). */
    public static final Set<Integer> FILLING_SLOTS = Set.of(10, 11, 12, 13, 14);

    /** Слот для наполняемого предмета (бумага) во втором ряду (индекс 16). */
    public static final int PAPER_SLOT = 16;

    private static final BlockFace[] CARTESIAN_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final JavaPlugin plugin;
    private final NamespacedKey apvshBlockKey;
    private final File configFile;
    private final FileConfiguration apvshData;
    private final BatchedYamlFile storage;

    private final Map<String, Inventory> openInventories = new HashMap<>();
    private final Map<String, Location> trackedBlocks = new HashMap<>();
    private final PowerEdgeTracker powerEdges = new PowerEdgeTracker();
    private BukkitTask powerTask;
    private boolean shuttingDown;

    public Apvsh(JavaPlugin plugin, AsyncTextWriter dataWriter) {
        this.plugin = plugin;
        this.apvshBlockKey = new NamespacedKey(plugin, "apvsh_block");
        this.configFile = new File(plugin.getDataFolder(), "apvsh.yml");

        if (!configFile.exists()) {
            try {
                configFile.getParentFile().mkdirs();
                configFile.createNewFile();
            } catch (IOException ex) {
                plugin.getLogger().severe("Не удалось создать apvsh.yml: " + ex.getMessage());
            }
        }
        this.apvshData = YamlConfiguration.loadConfiguration(configFile);
        this.storage = new BatchedYamlFile(plugin, dataWriter, configFile.toPath(), apvshData::saveToString);

        loadAllBlocks();
        powerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRedstone, 1L, 1L);

        String diagnosis = CraftEngineApvsh.diagnosis();
        plugin.getLogger().info("АПВШ: "
                + (diagnosis.isEmpty()
                ? "блок CraftEngine " + CraftEngineApvsh.ID + " зарегистрирован."
                : diagnosis));
    }

    /** Держатель инвентаря для однозначного распознавания меню блока АПВШ. */
    public static final class ApvshHolder implements InventoryHolder {
        private final String blockKey;
        private final Location location;
        private Inventory inventory;

        ApvshHolder(String blockKey, Location location) {
            this.blockKey = blockKey;
            this.location = location;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public String getBlockKey() {
            return blockKey;
        }

        public Location getLocation() {
            return location;
        }
    }

    /** Создаёт стеклянную панель-заглушку. */
    public static ItemStack createPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /** Создаёт предмет блока АПВШ для инвентаря игрока и меню /f8. */
    public ItemStack createApvshBlockItem() {
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§6АПВШ"));
            meta.lore(List.of(
                    Component.text("§7Аппарат для создания и наполнения сигарет"),
                    Component.text("§7Поставьте и нажмите ПКМ, чтобы открыть меню")
            ));
            meta.setItemModel(new NamespacedKey("f8resurs", "copper_note_block"));
            meta.getPersistentDataContainer().set(apvshBlockKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Проверяет, является ли предмет блоком АПВШ. */
    public boolean isApvshItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(apvshBlockKey, PersistentDataType.BYTE);
    }

    /** Проверяет, является ли блок в мире кастомным блоком АПВШ. */
    public boolean isApvshBlock(Block block) {
        return CraftEngineApvsh.isApvsh(block);
    }

    static String blockKey(Block block) {
        return block.getWorld().getName() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    static String blockKey(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    private Location locationFromKey(String key) {
        int zSeparator = key.lastIndexOf('_');
        int ySeparator = key.lastIndexOf('_', zSeparator - 1);
        int xSeparator = key.lastIndexOf('_', ySeparator - 1);
        if (xSeparator <= 0) {
            return null;
        }
        String worldName = key.substring(0, xSeparator);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(key.substring(xSeparator + 1, ySeparator));
            int y = Integer.parseInt(key.substring(ySeparator + 1, zSeparator));
            int z = Integer.parseInt(key.substring(zSeparator + 1));
            return new Location(world, x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void loadAllBlocks() {
        ConfigurationSection section = apvshData.getConfigurationSection("blocks");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Location loc = locationFromKey(key);
            if (loc != null) {
                trackedBlocks.put(key, loc);
            }
        }
    }

    /** Получает или создаёт инвентарь для указанного блока АПВШ. */
    public Inventory getOrCreateInventory(Block block, String key) {
        Inventory existing = openInventories.get(key);
        if (existing != null) {
            return existing;
        }
        ApvshHolder holder = new ApvshHolder(key, block.getLocation());
        Inventory inv = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text(MENU_TITLE));
        holder.setInventory(inv);

        // Заполняем ряды и слоты заблокированными панелями
        ItemStack pane = createPane();
        for (int slot : PANE_SLOTS) {
            inv.setItem(slot, pane);
        }

        // Загружаем сохранённые предметы наполнения и бумаги
        ConfigurationSection blockSection = apvshData.getConfigurationSection("blocks." + key);
        if (blockSection != null) {
            for (int s : FILLING_SLOTS) {
                ItemStack item = blockSection.getItemStack("slot_" + s);
                if (item != null) {
                    inv.setItem(s, item);
                }
            }
            ItemStack paper = blockSection.getItemStack("slot_" + PAPER_SLOT);
            if (paper != null) {
                inv.setItem(PAPER_SLOT, paper);
            }
        }

        openInventories.put(key, inv);
        return inv;
    }

    private void saveInventory(String key, Inventory inv) {
        boolean hasAny = false;
        for (int s : FILLING_SLOTS) {
            ItemStack item = inv.getItem(s);
            if (item != null && item.getType() != Material.AIR) {
                apvshData.set("blocks." + key + ".slot_" + s, item);
                hasAny = true;
            } else {
                apvshData.set("blocks." + key + ".slot_" + s, null);
            }
        }
        ItemStack paper = inv.getItem(PAPER_SLOT);
        if (paper != null && paper.getType() != Material.AIR) {
            apvshData.set("blocks." + key + ".slot_" + PAPER_SLOT, paper);
            hasAny = true;
        } else {
            apvshData.set("blocks." + key + ".slot_" + PAPER_SLOT, null);
        }

        if (!hasAny) {
            apvshData.set("blocks." + key, null);
        }
        storage.markDirty();
    }

    /**
     * Сборка сигареты: при наличии бумаги в 8 слоте (PAPER_SLOT) и пороха в
     * слотах наполнения (10..14) бумага и порох расходуются, проигрывается
     * звук наковальни, и на месте бумаги появляется готовая сигарета.
     */
    public boolean craft(Block block, String key) {
        Inventory inv = getOrCreateInventory(block, key);

        ItemStack paperItem = inv.getItem(PAPER_SLOT);
        if (paperItem == null || paperItem.getType() != Material.PAPER || Cigarette.isCigarette(paperItem)) {
            return false;
        }

        int totalGunpowder = 0;
        for (int s : FILLING_SLOTS) {
            ItemStack filling = inv.getItem(s);
            if (filling != null && filling.getType() == Material.GUNPOWDER) {
                totalGunpowder += filling.getAmount();
            }
        }

        if (totalGunpowder <= 0) {
            return false;
        }

        // 1. Расходуем бумагу
        if (paperItem.getAmount() > 1) {
            paperItem.setAmount(paperItem.getAmount() - 1);
        } else {
            inv.setItem(PAPER_SLOT, null);
        }

        // 2. Расходуем весь порох из слотов наполнения
        for (int s : FILLING_SLOTS) {
            inv.setItem(s, null);
        }

        // 3. Создаём готовую сигарету с порохом
        ItemStack cigarette = Cigarette.createWithGunpowder(totalGunpowder);
        inv.setItem(PAPER_SLOT, cigarette);

        // 4. Проигрываем звук удара наковальни
        Location soundLoc = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().playSound(soundLoc, Sound.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 1.0F, 1.0F);

        // 5. Сохраняем обновлённый инвентарь блока
        saveInventory(key, inv);
        return true;
    }

    // ===== РЕДСТОУН =====

    private void tickRedstone() {
        var iterator = trackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Location location = entry.getValue();
            World world = location.isWorldLoaded() ? location.getWorld() : null;
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                powerEdges.remove(entry.getKey());
                continue;
            }
            Block block = world.getBlockAt(location);
            if (!isApvshBlock(block)) {
                powerEdges.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            observePower(entry.getKey(), block);
        }
    }

    private void observePower(String key, Block block) {
        if (powerEdges.update(key, hasPower(block))) {
            craft(block, key);
        }
    }

    private boolean hasPower(Block block) {
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block source = event.getBlock();
        for (BlockFace face : CARTESIAN_FACES) {
            Block neighbor = source.getRelative(face);
            if (isApvshBlock(neighbor)) {
                String key = blockKey(neighbor);
                observePower(key, neighbor);
            }
        }
    }

    // ===== УСТАНОВКА И РАЗРУШЕНИЕ БЛОКА =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isApvshItem(item)) {
            return;
        }

        Block block = event.getBlockPlaced();
        if (!CraftEngineApvsh.ready()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "§cНе удалось поставить АПВШ: " + CraftEngineApvsh.diagnosis()));
            return;
        }

        CraftEngineApvsh.place(block);
        String key = blockKey(block);
        trackedBlocks.put(key, block.getLocation());
        powerEdges.update(key, hasPower(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isApvshBlock(block)) {
            return;
        }

        String key = blockKey(block);
        Inventory inv = openInventories.get(key);
        if (inv != null) {
            for (var viewer : new ArrayList<>(inv.getViewers())) {
                viewer.closeInventory();
            }
        } else {
            inv = getOrCreateInventory(block, key);
        }

        event.setDropItems(false);

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            block.getWorld().dropItemNaturally(block.getLocation(), createApvshBlockItem());
        }

        dropContents(block, inv);

        openInventories.remove(key);
        trackedBlocks.remove(key);
        powerEdges.remove(key);
        apvshData.set("blocks." + key, null);
        storage.markDirty();

        CraftEngineApvsh.remove(block);
    }

    private void dropContents(Block block, Inventory inv) {
        if (inv == null) {
            return;
        }
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        for (int s : FILLING_SLOTS) {
            ItemStack item = inv.getItem(s);
            if (item != null && item.getType() != Material.AIR) {
                block.getWorld().dropItemNaturally(loc, item);
            }
        }
        ItemStack paper = inv.getItem(PAPER_SLOT);
        if (paper != null && paper.getType() != Material.AIR) {
            block.getWorld().dropItemNaturally(loc, paper);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explodeCleanup(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explodeCleanup(event.blockList());
    }

    private void explodeCleanup(List<Block> blocks) {
        boolean changed = false;
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            if (!isApvshBlock(block)) {
                continue;
            }
            String key = blockKey(block);
            Inventory inv = openInventories.remove(key);
            if (inv == null) {
                inv = getOrCreateInventory(block, key);
            }
            dropContents(block, inv);
            block.getWorld().dropItemNaturally(block.getLocation(), createApvshBlockItem());
            trackedBlocks.remove(key);
            powerEdges.remove(key);
            apvshData.set("blocks." + key, null);
            it.remove();
            block.setType(Material.AIR, false);
            CraftEngineApvsh.remove(block);
            changed = true;
        }
        if (changed) {
            storage.markDirty();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsApvshBlock(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsApvshBlock(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean containsApvshBlock(List<Block> blocks) {
        for (Block b : blocks) {
            if (isApvshBlock(b)) {
                return true;
            }
        }
        return false;
    }

    // ===== ВЗАИМОДЕЙСТВИЕ И МЕНЮ =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isApvshBlock(block)) {
            return;
        }

        Player player = event.getPlayer();
        if (ContainerInteraction.bypassMenu(player.isSneaking(),
                player.getInventory().getItemInMainHand().getType().isAir(),
                player.getInventory().getItemInOffHand().getType().isAir())) {
            return;
        }

        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            String key = blockKey(block);
            Inventory inv = getOrCreateInventory(block, key);
            player.openInventory(inv);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ApvshHolder holder)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }

        Inventory top = event.getView().getTopInventory();

        if (rawSlot < INVENTORY_SIZE) {
            // Клик в верхнем инвентаре (меню АПВШ)
            if (PANE_SLOTS.contains(rawSlot) || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }

            if (FILLING_SLOTS.contains(rawSlot)) {
                // В слоты для наполнения можно класть только порох (до 64 шт.)
                if (event.getClick() == ClickType.NUMBER_KEY) {
                    ItemStack hotbar = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
                    if (hotbar != null && hotbar.getType() != Material.AIR && hotbar.getType() != Material.GUNPOWDER) {
                        event.setCancelled(true);
                        return;
                    }
                } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                    ItemStack offhand = event.getWhoClicked().getInventory().getItemInOffHand();
                    if (offhand != null && offhand.getType() != Material.AIR && offhand.getType() != Material.GUNPOWDER) {
                        event.setCancelled(true);
                        return;
                    }
                } else {
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR && cursor.getType() != Material.GUNPOWDER) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } else if (rawSlot == PAPER_SLOT) {
                // В слот для бумаги можно класть только бумагу в количестве 1 шт.
                // Забирать предметы (бумагу или готовую сигарету) разрешено свободно.
                if (event.getClick() == ClickType.NUMBER_KEY) {
                    ItemStack hotbar = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
                    if (hotbar != null && hotbar.getType() != Material.AIR) {
                        if (hotbar.getType() != Material.PAPER || Cigarette.isCigarette(hotbar) || hotbar.getAmount() > 1) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                    ItemStack offhand = event.getWhoClicked().getInventory().getItemInOffHand();
                    if (offhand != null && offhand.getType() != Material.AIR) {
                        if (offhand.getType() != Material.PAPER || Cigarette.isCigarette(offhand) || offhand.getAmount() > 1) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                } else {
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        if (cursor.getType() != Material.PAPER || Cigarette.isCigarette(cursor)) {
                            event.setCancelled(true);
                            return;
                        }
                        ItemStack current = top.getItem(PAPER_SLOT);
                        if (current != null && current.getType() != Material.AIR) {
                            event.setCancelled(true);
                            return;
                        }
                        if (cursor.getAmount() > 1) {
                            event.setCancelled(true);
                            ItemStack singlePaper = cursor.clone();
                            singlePaper.setAmount(1);
                            top.setItem(PAPER_SLOT, singlePaper);
                            cursor.setAmount(cursor.getAmount() - 1);
                            event.getWhoClicked().setItemOnCursor(cursor);
                            saveInventory(holder.getBlockKey(), top);
                            return;
                        }
                    }
                }
            }
        } else {
            // Клик в инвентаре игрока
            if (event.isShiftClick()) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR) {
                    return;
                }
                event.setCancelled(true);

                if (clicked.getType() == Material.GUNPOWDER) {
                    int toAdd = clicked.getAmount();
                    // 1 проход: складываем в уже существующие неполные стопки пороха
                    for (int s : FILLING_SLOTS) {
                        ItemStack existing = top.getItem(s);
                        if (existing != null && existing.getType() == Material.GUNPOWDER) {
                            int space = 64 - existing.getAmount();
                            if (space > 0) {
                                int add = Math.min(space, toAdd);
                                existing.setAmount(existing.getAmount() + add);
                                top.setItem(s, existing);
                                toAdd -= add;
                                if (toAdd <= 0) break;
                            }
                        }
                    }
                    // 2 проход: выкладываем в пустые слоты наполнения
                    if (toAdd > 0) {
                        for (int s : FILLING_SLOTS) {
                            ItemStack existing = top.getItem(s);
                            if (existing == null || existing.getType() == Material.AIR) {
                                int add = Math.min(64, toAdd);
                                ItemStack stack = clicked.clone();
                                stack.setAmount(add);
                                top.setItem(s, stack);
                                toAdd -= add;
                                if (toAdd <= 0) break;
                            }
                        }
                    }
                    if (toAdd <= 0) {
                        event.getClickedInventory().setItem(event.getSlot(), null);
                    } else {
                        clicked.setAmount(toAdd);
                        event.getClickedInventory().setItem(event.getSlot(), clicked);
                    }
                    saveInventory(holder.getBlockKey(), top);
                    return;
                } else if (clicked.getType() == Material.PAPER && !Cigarette.isCigarette(clicked)) {
                    ItemStack current = top.getItem(PAPER_SLOT);
                    if (current == null || current.getType() == Material.AIR) {
                        ItemStack singlePaper = clicked.clone();
                        singlePaper.setAmount(1);
                        top.setItem(PAPER_SLOT, singlePaper);
                        if (clicked.getAmount() > 1) {
                            clicked.setAmount(clicked.getAmount() - 1);
                            event.getClickedInventory().setItem(event.getSlot(), clicked);
                        } else {
                            event.getClickedInventory().setItem(event.getSlot(), null);
                        }
                        saveInventory(holder.getBlockKey(), top);
                    }
                    return;
                }
                // Любые другие предметы при шифт-клике блокируются
                return;
            }
        }

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> saveInventory(holder.getBlockKey(), top));
        } else {
            saveInventory(holder.getBlockKey(), top);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ApvshHolder holder)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < INVENTORY_SIZE) {
                if (PANE_SLOTS.contains(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
                if (FILLING_SLOTS.contains(rawSlot)) {
                    ItemStack dragged = event.getOldCursor();
                    if (dragged == null || dragged.getType() != Material.GUNPOWDER) {
                        event.setCancelled(true);
                        return;
                    }
                }
                if (rawSlot == PAPER_SLOT) {
                    ItemStack dragged = event.getOldCursor();
                    if (dragged == null || dragged.getType() != Material.PAPER || Cigarette.isCigarette(dragged)) {
                        event.setCancelled(true);
                        return;
                    }
                    ItemStack newSlotItem = event.getNewItems().get(rawSlot);
                    if (newSlotItem != null && newSlotItem.getAmount() > 1) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
        saveInventory(holder.getBlockKey(), event.getInventory());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ApvshHolder holder)) {
            return;
        }
        saveInventory(holder.getBlockKey(), event.getInventory());
    }

    public void disable() {
        shuttingDown = true;
        if (powerTask != null) {
            powerTask.cancel();
            powerTask = null;
        }
        for (Inventory inv : openInventories.values()) {
            for (var viewer : new ArrayList<>(inv.getViewers())) {
                viewer.closeInventory();
            }
        }
        openInventories.clear();
        trackedBlocks.clear();
        powerEdges.clear();
        storage.flushBlocking();
    }
}
