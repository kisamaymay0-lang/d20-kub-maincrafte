package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Медный нотный блок.
 *
 * В основе лежит обычный NOTE_BLOCK:
 *  - запуск ТОЛЬКО по изменению физического питания с 0 на 1;
 *  - ванильная нота, в том числе от удара рукой, всегда заглушена;
 *  - Paper не посылает BlockRedstoneEvent для самого NOTE_BLOCK, поэтому
 *    заполненные блоки проверяются раз в тик (без загрузки чанков);
 *  - нота №24 (MARKER_NOTE) зарезервирована для модели поставленного блока.
 *
 * В слоте "таймера" (слот №10) может лежать:
 *  - пластинка — тогда блок работает как проигрыватель (см. ниже);
 *  - обычный предмет — тогда его количество задаёт задержку между нотами.
 */
public class CopperBlockListener implements Listener {

    private static final int TIMER_SLOT = 10;
    private static final int MARKER_NOTE = 24;
    private static final double DISC_RANGE = 65.0;
    private static final long DEBOUNCE_MS = 400L;

    private final JavaPlugin plugin;
    private final File configFile;
    private FileConfiguration blockData;

    private final HashMap<String, ItemStack[]> sessionInventories =
            new HashMap<>();

    // У всех зрителей блока один инвентарь. Проверка поршней читает его
    // напрямую, а не снимок прошлого тика: drag/shift-клик не обходят запрет.
    private final Map<String, Inventory> openInventories = new HashMap<>();
    private boolean shuttingDown;

    private final Map<String, DiscSession> discSessions =
            new HashMap<>();

    private final Set<Integer> blockedSlots =
            new HashSet<>();

    private final NamespacedKey copperBlockKey;
    private final Map<String, Location> powerTrackedBlocks = new HashMap<>();
    private final PowerEdgeTracker powerEdges = new PowerEdgeTracker();
    private BukkitTask powerTask;

    public CopperBlockListener(JavaPlugin plugin) {
        this.plugin = plugin;

        this.copperBlockKey =
                new NamespacedKey(plugin, "copper_block");

        this.configFile =
                new File(plugin.getDataFolder(), "blocks.yml");

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.blockData =
                YamlConfiguration.loadConfiguration(configFile);

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

        loadAllBlocksFromFile();
        registerRecipe();
        powerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRedstone, 1L, 1L);
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "copper_note_block");
        ShapelessRecipe recipe = new ShapelessRecipe(key, createCopperBlockItem());
        recipe.addIngredient(Material.NOTE_BLOCK);
        recipe.addIngredient(Material.COPPER_NUGGET);
        recipe.addIngredient(Material.REDSTONE);
        recipe.addIngredient(new RecipeChoice.MaterialChoice(List.of(
                Material.COPPER_GRATE,
                Material.EXPOSED_COPPER_GRATE,
                Material.WEATHERED_COPPER_GRATE,
                Material.OXIDIZED_COPPER_GRATE,
                Material.WAXED_COPPER_GRATE,
                Material.WAXED_EXPOSED_COPPER_GRATE,
                Material.WAXED_WEATHERED_COPPER_GRATE,
                Material.WAXED_OXIDIZED_COPPER_GRATE
        )));
        Bukkit.removeRecipe(key);
        Bukkit.addRecipe(recipe);
    }

    // ===== ПРЕДМЕТ БЛОКА =====

    public ItemStack createCopperBlockItem() {
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§6Медный нотный блок");
            meta.setLore(List.of(
                    "§7Поставьте и нажмите ПКМ, чтобы настроить ноты"
            ));
            meta.setItemModel(new NamespacedKey(
                    "f8resurs",
                    "copper_note_block"
            ));
            meta.getPersistentDataContainer().set(
                    copperBlockKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            item.setItemMeta(meta);
        }

        return item;
    }

    // ===== ОПРЕДЕЛЕНИЕ "НАШЕГО" БЛОКА =====

    private boolean isCopperBlockItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(copperBlockKey, PersistentDataType.BYTE);
    }

    private boolean isCopperBlock(Block block) {
        if (block == null
                || block.getType() != Material.NOTE_BLOCK) {
            return false;
        }

        if (!(block.getBlockData() instanceof NoteBlock noteBlock)) {
            return false;
        }

        return noteBlock.getNote().getId() == MARKER_NOTE;
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

    private ItemStack getSlot(ItemStack[] items, int slot) {
        if (items == null
                || slot < 0
                || slot >= items.length) {
            return null;
        }

        ItemStack it = items[slot];

        if (it == null || it.getType().isAir() || it.getAmount() <= 0) {
            return null;
        }

        return it;
    }

    // ===== УСТАНОВКА БЛОКА =====

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (!isCopperBlockItem(event.getItemInHand())) {
            return;
        }

        Block block = event.getBlockPlaced();

        if (block.getType() != Material.NOTE_BLOCK) {
            return;
        }

        NoteBlock data = (NoteBlock) block.getBlockData();
        data.setNote(new Note(MARKER_NOTE));
        // Не вызываем лишнюю физику во время BlockPlaceEvent. Свойства
        // powered/instrument сохраняются; ресурспак выбирает модель по note.
        block.setBlockData(data, false);
    }

    // ===== ОТКРЫТИЕ МЕНЮ =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        if (!isCopperBlock(block)) {
            return;
        }

        if (event.getPlayer().isSneaking()) {
            // Shift + ПКМ — размещение/использование предмета, а не меню.
            // Но useWithoutItem самого NOTE_BLOCK не допускаем даже с пустой
            // рукой, иначе ваниль переключит маркер note=24 на note=0.
            event.setUseInteractedBlock(Event.Result.DENY);
            if (event.useItemInHand() != Event.Result.DENY) {
                event.setUseItemInHand(Event.Result.ALLOW);
            }
            return;
        }

        event.setCancelled(true);

        // Запрещаем ванильную настройку обеими руками, иначе второй
        // interact может переключить зарезервированную ноту 24 обратно на 0.
        if (event.getHand() == EquipmentSlot.HAND) {
            openCopperMenu(event.getPlayer(), block);
        }
    }

    private void openCopperMenu(Player player, Block block) {
        String key = getBlockKey(block);
        Inventory gui = openInventories.get(key);
        if (gui == null) {
            CopperHolder holder = new CopperHolder(block);
            gui = Bukkit.createInventory(holder, 36, "§6Медный нотный блок");
            holder.inventory = gui;
            ItemStack[] saved = sessionInventories.get(key);
            if (saved != null) {
                for (int i = 0; i < Math.min(saved.length, gui.getSize()); i++) {
                    if (!blockedSlots.contains(i) && saved[i] != null) {
                        gui.setItem(i, saved[i].clone());
                    }
                }
            }

            ItemStack separator = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta meta = separator.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(" ");
                separator.setItemMeta(meta);
            }
            for (int slot : blockedSlots) {
                gui.setItem(slot, separator);
            }
            openInventories.put(key, gui);
        }
        player.openInventory(gui);
    }

    // ===== СОХРАНЕНИЕ ИНВЕНТАРЯ =====

    private ItemStack[] contentsOf(String key) {
        Inventory live = openInventories.get(key);
        return live != null ? live.getContents() : sessionInventories.get(key);
    }

    private void saveBlockData() {
        try {
            blockData.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить blocks.yml: " + ex.getMessage());
        }
    }

    private void saveInventory(Inventory inventory) {
        if (!(inventory.getHolder() instanceof CopperHolder holder)) {
            return;
        }
        Block block = holder.getBlock();
        String key = getBlockKey(block);
        // Старый GUI после разрушения/сдвига не должен воскресить содержимое.
        if (openInventories.get(key) != inventory || !isCopperBlock(block)) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        sessionInventories.put(key, contents);
        blockData.set("blocks." + key, contents);
        saveBlockData();
        if (!shuttingDown) {
            if (hasContents(block)) {
                if (powerTrackedBlocks.putIfAbsent(key, block.getLocation()) == null) {
                    // Вложение предметов — не новый фронт сигнала для нот.
                    powerEdges.update(key, hasPower(block));
                }
            } else {
                powerTrackedBlocks.remove(key);
                powerEdges.remove(key);
            }
            reevaluateDisc(block, key, contents);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CopperHolder)) {
            return;
        }
        if (blockedSlots.contains(event.getRawSlot())
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        Inventory inventory = event.getView().getTopInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) {
                saveInventory(inventory);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CopperHolder)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(blockedSlots::contains)) {
            event.setCancelled(true);
            return;
        }
        Inventory inventory = event.getView().getTopInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) {
                saveInventory(inventory);
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof CopperHolder holder) || shuttingDown) {
            return;
        }
        saveInventory(inventory);
        String key = getBlockKey(holder.getBlock());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (inventory.getViewers().isEmpty() && openInventories.get(key) == inventory) {
                saveInventory(inventory);
                openInventories.remove(key);
            }
        });
    }

    // ===== РЕДСТОУН: ТОЛЬКО ФРОНТ СИГНАЛА, НЕ УДАР РУКОЙ =====

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNotePlay(NotePlayEvent event) {
        Block block = event.getBlock();
        if (isCopperBlock(block)) {
            // Отменяется лишь звук. ЛКМ по-прежнему позволяет ломать блок.
            event.setCancelled(true);
            // Не запускаем музыку здесь вообще: событие бывает и от ЛКМ.
            // Фронты физического питания независимо проверяет tickRedstone().
        }
    }

    private void observePower(Block block) {
        if (powerEdges.update(getBlockKey(block), hasPower(block))) {
            handleTrigger(block);
        }
    }

    private void tickRedstone() {
        var iterator = powerTrackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Location location = entry.getValue();
            World world = location.getWorld();
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                powerEdges.remove(entry.getKey());
                continue;
            }
            Block block = world.getBlockAt(location);
            if (!isCopperBlock(block)) {
                powerEdges.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            observePower(block);
        }
    }

    private boolean hasPower(Block block) {
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    private void handleTrigger(Block block) {
        String key = getBlockKey(block);

        ItemStack[] items = contentsOf(key);

        ItemStack timerItem = getSlot(items, TIMER_SLOT);

        // В слоте таймера пластинка — режим проигрывателя.
        if (DiscTracks.songKey(timerItem) != null) {
            startOrResumeDisc(block, key, timerItem);

            return;
        }

        // Иначе — обычная последовательность нот.
        playNoteSequence(block, items);
    }

    // ===== ПОСЛЕДОВАТЕЛЬНОСТЬ НОТ =====

    private void playNoteSequence(Block block, ItemStack[] items) {
        if (block.hasMetadata("copper_playing")) {
            return;
        }

        long now = System.currentTimeMillis();

        long lastUsed = 0L;

        if (block.hasMetadata("last_copper_trigger")) {
            lastUsed = block.getMetadata("last_copper_trigger")
                    .get(0)
                    .asLong();
        }

        if (now - lastUsed < DEBOUNCE_MS) {
            return;
        }

        block.setMetadata(
                "last_copper_trigger",
                new FixedMetadataValue(plugin, now)
        );

        block.setMetadata(
                "copper_playing",
                new FixedMetadataValue(plugin, true)
        );

        if (items == null) {
            block.removeMetadata("copper_playing", plugin);
            return;
        }

        int itemsInTimeSlot = 0;

        ItemStack timeItem = getSlot(items, TIMER_SLOT);

        if (timeItem != null) {
            itemsInTimeSlot = timeItem.getAmount();
        }

        int delayTicks =
                itemsInTimeSlot > 0
                        ? itemsInTimeSlot * 2
                        : 4;

        delayTicks = Math.clamp(delayTicks, 2, 100);

        final int finalDelay = delayTicks;
        final ItemStack[] finalItems = items;
        final Location particleLoc =
                block.getLocation()
                        .clone()
                        .add(0.5, 1.2, 0.5);

        new BukkitRunnable() {

            int step = 0;

            @Override
            public void run() {
                if (step >= 4 || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                        || !isCopperBlock(block)) {
                    block.removeMetadata(
                            "copper_playing",
                            plugin
                    );
                    cancel();
                    return;
                }

                int highSlot = 4 + step;
                int midSlot = 13 + step;
                int lowSlot = 22 + step;
                int subSlot = 31 + step;

                boolean playedAny = false;

                playedAny |= playNote(
                        finalItems, highSlot, 1.2f, 1.0f
                );
                playedAny |= playNote(
                        finalItems, midSlot, 1.0f, 0.79f
                );
                playedAny |= playNote(
                        finalItems, lowSlot, 0.8f, 0.63f
                );
                playedAny |= playNote(
                        finalItems, subSlot, 0.9f, 0.5f
                );

                if (playedAny) {
                    block.getWorld().spawnParticle(
                            Particle.NOTE,
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

            private boolean playNote(
                    ItemStack[] arr,
                    int slot,
                    float volume,
                    float pitch
            ) {
                ItemStack it = getSlot(arr, slot);

                if (it == null) {
                    return false;
                }

                block.getWorld().playSound(
                        block.getLocation(),
                        getInstrumentByMaterial(it.getType()),
                        volume,
                        pitch
                );

                return true;
            }

        }.runTaskTimer(plugin, 0L, finalDelay);
    }

    // ===== ПЛАСТИНКИ =====

    private void startOrResumeDisc(
            Block block,
            String key,
            ItemStack disc
    ) {
        if (!hasPower(block)) {
            return;
        }
        DiscTracks.Track track = DiscTracks.resolve(disc);
        if (track == null) {
            return;
        }
        DiscSession session = discSessions.get(key);

        if (session != null) {
            if (session.track.equals(track)) {
                // Та же пластинка — возобновляем после паузы.
                // Точный "перемотать на то же место" API Bukkit не умеет,
                // поэтому запускаем трек с начала и сбрасываем счётчик,
                // чтобы время цикла совпадало со звуком.
                if (!session.playing) {
                    session.elapsedTicks = 0;
                    session.particleTicks = 0;
                    session.playing = true;
                    playDiscSound(session);
                }
                return;
            }

            // Пластинку сменили — старая сессия сбрасывается.
            stopDiscSession(session);
        }

        DiscSession fresh = new DiscSession(block.getLocation().clone(), key, track);

        fresh.playing = true;
        discSessions.put(key, fresh);
        playDiscSound(fresh);

        fresh.task = new BukkitRunnable() {
            @Override
            public void run() {
                tickDisc(fresh);
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void tickDisc(DiscSession session) {
        World world = session.loc.getWorld();

        if (world == null) {
            stopDiscSession(session);
            return;
        }

        if (!world.isChunkLoaded(
                session.loc.getBlockX() >> 4,
                session.loc.getBlockZ() >> 4
        )) {
            // Чанк выгружен — пауза, но сессию не удаляем.
            if (session.playing) {
                session.playing = false;
                session.particleTicks = 0;
                stopDiscSound(session);
            }
            return;
        }

        Block block = world.getBlockAt(session.loc);

        if (!isCopperBlock(block)) {
            stopDiscSession(session);
            return;
        }

        ItemStack[] items =
                contentsOf(session.key);

        ItemStack timerItem = getSlot(items, TIMER_SLOT);

        NamespacedKey currentSong = DiscTracks.songKey(timerItem);
        if (!session.track.key().equals(currentSong)) {
            // Учитываем замену песни даже у предмета с тем же Material.
            stopDiscSession(session);
            if (currentSong != null && hasPower(block)) {
                startOrResumeDisc(block, session.key, timerItem);
            }
            return;
        }

        // Используем косвенное питание: так учитывается и рычаг на блоке,
        // и редстоун-линия, направленная в блок (как у обычного нотного блока).
        if (!hasPower(block)) {
            if (session.playing) {
                session.playing = false;
                session.particleTicks = 0;
                stopDiscSound(session);
            }
            return;
        }

        // Питание есть.
        if (!session.playing) {
            session.elapsedTicks = 0;
            session.particleTicks = 0;
            session.playing = true;
            playDiscSound(session);
        }

        session.elapsedTicks += 2;
        session.particleTicks += 2;
        if (session.particleTicks >= 20) {
            session.particleTicks = 0;
            // NOTE, как у проигрывателя: одна цветная нота в секунду,
            // только пока трек действительно играет и блок запитан.
            world.spawnParticle(
                    Particle.NOTE, session.loc.clone().add(0.5, 1.2, 0.5),
                    0, ThreadLocalRandom.current().nextInt(25) / 24.0, 0.0, 0.0, 1.0
            );
        }

        if (session.elapsedTicks >= session.track.durationTicks()) {
            // Пластинка доиграла, а сигнал всё ещё есть —
            // начинаем с начала.
            session.elapsedTicks = 0;
            playDiscSound(session);
        }
    }

    private void reevaluateDisc(
            Block block,
            String key,
            ItemStack[] items
    ) {
        ItemStack timerItem = getSlot(items, TIMER_SLOT);

        DiscSession session = discSessions.get(key);

        NamespacedKey songKey = DiscTracks.songKey(timerItem);
        if (songKey == null) {

            if (session != null) {
                // Пластинку убрали — песня заканчивается.
                stopDiscSession(session);
            }
            return;
        }

        if (session != null && !session.track.key().equals(songKey)) {
            stopDiscSession(session);
            session = null;
        }

        // Пластинку положили в уже запитанный блок — запускаем сразу.
        if (session == null && hasPower(block)) {
            startOrResumeDisc(block, key, timerItem);
        }
    }

    private void stopDiscSession(DiscSession session) {
        if (session == null) {
            return;
        }

        if (session.task != null) {
            session.task.cancel();
            session.task = null;
        }

        if (session.playing) {
            stopDiscSound(session);
            session.playing = false;
        }

        discSessions.remove(session.key);
    }

    private void playDiscSound(DiscSession session) {
        Sound sound = session.track.sound();
        World world = session.loc.getWorld();

        if (sound == null || world == null) {
            return;
        }

        for (Player p : world.getPlayers()) {
            if (p.getLocation()
                    .distance(session.loc) <= DISC_RANGE) {

                p.playSound(
                        session.loc,
                        sound,
                        SoundCategory.RECORDS,
                        4.0f,
                        1.0f
                );
            }
        }
    }

    private void stopDiscSound(DiscSession session) {
        Sound sound = session.track.sound();
        World world = session.loc.getWorld();

        if (sound == null || world == null) {
            return;
        }

        for (Player p : world.getPlayers()) {
            p.stopSound(sound, SoundCategory.RECORDS);
        }
    }

    private Sound getInstrumentByMaterial(Material material) {
        String name = material.name();

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

    // ===== ПОРШНИ =====

    private boolean hasContents(Block block) {
        ItemStack[] contents = contentsOf(getBlockKey(block));
        if (contents == null) {
            return false;
        }
        for (int i = 0; i < contents.length; i++) {
            if (!blockedSlots.contains(i) && getSlot(contents, i) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFilledCopperBlock(List<Block> blocks) {
        return blocks.stream().anyMatch(block -> isCopperBlock(block) && hasContents(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsFilledCopperBlock(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsFilledCopperBlock(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterPistonExtend(BlockPistonExtendEvent event) {
        forgetMovingEmptyBlocks(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterPistonRetract(BlockPistonRetractEvent event) {
        forgetMovingEmptyBlocks(event.getBlocks());
    }

    private void closeBlockInventory(String key) {
        Inventory inventory = openInventories.remove(key);
        if (inventory != null) {
            // Сначала убираем ссылку, затем закрываем: обработчик Close и
            // отложенные Click/Drag уже не смогут записать старый инвентарь.
            for (var viewer : new ArrayList<>(inventory.getViewers())) {
                viewer.closeInventory();
            }
        }
    }

    private void forgetMovingEmptyBlocks(List<Block> blocks) {
        boolean changed = false;
        for (Block block : blocks) {
            if (!isCopperBlock(block) || hasContents(block)) {
                continue;
            }
            String key = getBlockKey(block);
            closeBlockInventory(key);
            stopDiscSession(discSessions.get(key));
            sessionInventories.remove(key);
            powerTrackedBlocks.remove(key);
            powerEdges.remove(key);
            blockData.set("blocks." + key, null);
            block.removeMetadata("copper_playing", plugin);
            block.removeMetadata("last_copper_trigger", plugin);
            // Сам MARKER_NOTE перемещает поршень вместе с BlockData.
            // Данных в новом месте пока нет: блок гарантированно пустой.
            changed = true;
        }
        if (changed) {
            saveBlockData();
        }
    }

    // ===== РАЗРУШЕНИЕ БЛОКА =====

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!isCopperBlock(block)) {
            return;
        }

        String key = getBlockKey(block);

        // Останавливаем пластинку, если она играла.
        DiscSession session = discSessions.get(key);

        if (session != null) {
            stopDiscSession(session);
        }

        ItemStack[] items = contentsOf(key);
        closeBlockInventory(key);
        sessionInventories.remove(key);
        powerTrackedBlocks.remove(key);
        powerEdges.remove(key);
        block.removeMetadata("copper_playing", plugin);
        block.removeMetadata("last_copper_trigger", plugin);

        // Отменяем ванильный дроп нотного блока.
        event.setDropItems(false);

        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null
                        && items[i].getType() != Material.AIR
                        && !blockedSlots.contains(i)) {

                    block.getWorld().dropItemNaturally(
                            block.getLocation(),
                            items[i]
                    );
                }
            }
        }

        // В творческом режиме ванильный блок не дропается.
        if (event.getPlayer().getGameMode()
                != GameMode.CREATIVE) {

            block.getWorld().dropItemNaturally(
                    block.getLocation(),
                    createCopperBlockItem()
            );
        }

        blockData.set("blocks." + key, null);
        saveBlockData();
    }

    // ===== ЗАГРУЗКА ИЗ ФАЙЛА =====

    @SuppressWarnings("unchecked")
    private void loadAllBlocksFromFile() {
        if (!blockData.contains("blocks")) {
            return;
        }

        for (String key :
                blockData.getConfigurationSection("blocks")
                        .getKeys(false)) {

            List<ItemStack> list =
                    (List<ItemStack>) blockData.get(
                            "blocks." + key
                    );

            if (list != null) {
                sessionInventories.put(key, list.toArray(new ItemStack[0]));
                Location location = locationFromKey(key);
                if (location != null) {
                    // Пока не загружаем чанк и не вызываем getBlockAt().
                    // При первой проверке загруженного блока считываем питание.
                    powerTrackedBlocks.put(key, location);
                }
            }
        }
    }

    private Location locationFromKey(String key) {
        // Имя мира тоже может содержать '_': координаты отделяем с конца.
        int zSeparator = key.lastIndexOf('_');
        int ySeparator = key.lastIndexOf('_', zSeparator - 1);
        int xSeparator = key.lastIndexOf('_', ySeparator - 1);
        if (xSeparator <= 0) {
            return null;
        }
        World world = Bukkit.getWorld(key.substring(0, xSeparator));
        if (world == null) {
            return null;
        }
        try {
            return new Location(world,
                    Integer.parseInt(key.substring(xSeparator + 1, ySeparator)),
                    Integer.parseInt(key.substring(ySeparator + 1, zSeparator)),
                    Integer.parseInt(key.substring(zSeparator + 1)));
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("Некорректная позиция медного блока в blocks.yml: " + key);
            return null;
        }
    }

    // ===== ВЫКЛЮЧЕНИЕ =====

    public void disable() {
        shuttingDown = true;
        if (powerTask != null) {
            powerTask.cancel();
        }
        powerTrackedBlocks.clear();
        powerEdges.clear();
        for (Inventory inventory : new ArrayList<>(openInventories.values())) {
            saveInventory(inventory);
        }
        for (String key : new ArrayList<>(openInventories.keySet())) {
            closeBlockInventory(key);
        }
        for (DiscSession session :
                new ArrayList<>(discSessions.values())) {

            stopDiscSession(session);
        }

        discSessions.clear();
    }

    private static class DiscSession {

        final Location loc;
        final String key;
        final DiscTracks.Track track;

        boolean playing;
        long elapsedTicks;
        int particleTicks;
        BukkitTask task;

        DiscSession(
                Location loc,
                String key,
                DiscTracks.Track track
        ) {
            this.loc = loc;
            this.key = key;
            this.track = track;
        }
    }

    private static class CopperHolder
            implements org.bukkit.inventory.InventoryHolder {

        private final Block block;
        private Inventory inventory;

        public CopperHolder(Block block) {
            this.block = block;
        }

        public Block getBlock() {
            return block;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
