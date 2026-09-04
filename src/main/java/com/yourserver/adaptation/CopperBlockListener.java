package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
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

/**
 * Медный нотный блок.
 *
 * В основе лежит обычный NOTE_BLOCK:
 *  - редстоун-поведение (рычаг на блоке, линия, направленная в блок и т.п.)
 *    обрабатывается самой игрой через NotePlayEvent — это даёт точно такое же
 *    поведение, как у ванильного нотного блока;
 *  - наш блок помечается нотой №24 (MARKER_NOTE), которая выставляется при
 *    установке, поэтому обычные нотные блоки не затрагиваются.
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

    private final Map<String, DiscSession> discSessions =
            new HashMap<>();

    private final Set<Integer> blockedSlots =
            new HashSet<>();

    private final NamespacedKey copperBlockKey;

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

        if (it == null || it.getType() == Material.AIR) {
            return null;
        }

        return it;
    }

    // ===== УСТАНОВКА БЛОКА =====

    @EventHandler
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
        block.setBlockData(data);
    }

    // ===== ОТКРЫТИЕ МЕНЮ =====

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        if (!isCopperBlock(block)) {
            return;
        }

        event.setCancelled(true);

        openCopperMenu(event.getPlayer(), block);
    }

    private void openCopperMenu(Player player, Block block) {
        Inventory gui = Bukkit.createInventory(
                new CopperHolder(block),
                36,
                "§6Медный нотный блок"
        );

        String key = getBlockKey(block);

        if (sessionInventories.containsKey(key)) {
            gui.setContents(sessionInventories.get(key));
        } else {
            ItemStack separator = new ItemStack(
                    Material.BLACK_STAINED_GLASS_PANE
            );

            ItemMeta meta = separator.getItemMeta();

            if (meta != null) {
                meta.setDisplayName(" ");
                separator.setItemMeta(meta);
            }

            for (int slot : blockedSlots) {
                gui.setItem(slot, separator);
            }
        }

        player.openInventory(gui);
    }

    // ===== СОХРАНЕНИЕ ИНВЕНТАРЯ =====

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder()
                instanceof CopperHolder holder)) {
            return;
        }

        int slot = event.getRawSlot();

        if (blockedSlots.contains(slot)) {
            event.setCancelled(true);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack[] contents =
                    event.getInventory().getContents();

            String key = getBlockKey(holder.getBlock());

            sessionInventories.put(key, contents);

            blockData.set("blocks." + key, contents);

            try {
                blockData.save(configFile);
            } catch (IOException ignored) {
            }

            reevaluateDisc(holder.getBlock(), key, contents);
        });
    }

    // ===== РЕДСТОУН (как у обычного нотного блока) =====

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNotePlay(NotePlayEvent event) {
        Block block = event.getBlock();

        if (!isCopperBlock(block)) {
            return;
        }

        // Глушим ванильный звук нотного блока — играем сами.
        event.setCancelled(true);

        handleTrigger(block);
    }

    private void handleTrigger(Block block) {
        String key = getBlockKey(block);

        ItemStack[] items = sessionInventories.get(key);

        ItemStack timerItem = getSlot(items, TIMER_SLOT);

        // В слоте таймера пластинка — режим проигрывателя.
        if (timerItem != null
                && timerItem.getType().isRecord()) {

            startOrResumeDisc(
                    block,
                    key,
                    timerItem.getType()
            );

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
                if (step >= 4) {
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
            Material disc
    ) {
        DiscSession session = discSessions.get(key);

        if (session != null) {
            if (session.disc == disc) {
                // Та же пластинка — возобновляем после паузы.
                // Точный "перемотать на то же место" API Bukkit не умеет,
                // поэтому запускаем трек с начала и сбрасываем счётчик,
                // чтобы время цикла совпадало со звуком.
                if (!session.playing) {
                    session.elapsedTicks = 0;
                    session.playing = true;
                    playDiscSound(session);
                }
                return;
            }

            // Пластинку сменили — старая сессия сбрасывается.
            stopDiscSession(session);
        }

        long totalTicks = discDurationTicks(disc);

        if (totalTicks <= 0) {
            return;
        }

        DiscSession fresh = new DiscSession(
                block.getLocation().clone(),
                key,
                disc,
                totalTicks
        );

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
                sessionInventories.get(session.key);

        ItemStack timerItem = getSlot(items, TIMER_SLOT);

        if (timerItem == null
                || !timerItem.getType().isRecord()
                || timerItem.getType() != session.disc) {

            // Пластинку забрали или заменили — песня заканчивается.
            stopDiscSession(session);
            return;
        }

        if (!block.isBlockPowered()) {
            if (session.playing) {
                session.playing = false;
                stopDiscSound(session);
            }
            return;
        }

        // Питание есть.
        if (!session.playing) {
            session.elapsedTicks = 0;
            session.playing = true;
            playDiscSound(session);
        }

        session.elapsedTicks += 2;

        if (session.elapsedTicks >= session.totalTicks) {
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

        if (timerItem == null
                || !timerItem.getType().isRecord()) {

            if (session != null) {
                // Пластинку убрали — песня заканчивается.
                stopDiscSession(session);
            }
            return;
        }

        Material disc = timerItem.getType();

        if (session != null && session.disc != disc) {
            stopDiscSession(session);
            session = null;
        }

        // Пластинку положили в уже запитанный блок — запускаем сразу.
        if (session == null && block.isBlockPowered()) {
            startOrResumeDisc(block, key, disc);
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
        Sound sound = discSound(session.disc);
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
        Sound sound = discSound(session.disc);
        World world = session.loc.getWorld();

        if (sound == null || world == null) {
            return;
        }

        for (Player p : world.getPlayers()) {
            p.stopSound(sound, SoundCategory.RECORDS);
        }
    }

    private static Sound discSound(Material disc) {
        // Ключ звука пластинки совпадает с ключом предмета
        // (например, minecraft:music_disc_13), поэтому ищем в реестре
        // напрямую — без deprecated Sound.valueOf(...).
        return Registry.SOUNDS.get(disc.getKey());
    }

    // Длительности пластинок в тиках (20 тиков = 1 секунда).
    // Указаны приблизительно — для корректного зацикливания.
    private static long discDurationTicks(Material disc) {
        switch (disc) {
            case MUSIC_DISC_13: return 178L * 20;
            case MUSIC_DISC_CAT: return 185L * 20;
            case MUSIC_DISC_BLOCKS: return 345L * 20;
            case MUSIC_DISC_CHIRP: return 185L * 20;
            case MUSIC_DISC_FAR: return 174L * 20;
            case MUSIC_DISC_MALL: return 197L * 20;
            case MUSIC_DISC_MELLOHI: return 96L * 20;
            case MUSIC_DISC_STAL: return 150L * 20;
            case MUSIC_DISC_STRAD: return 188L * 20;
            case MUSIC_DISC_WARD: return 251L * 20;
            case MUSIC_DISC_11: return 71L * 20;
            case MUSIC_DISC_WAIT: return 238L * 20;
            case MUSIC_DISC_OTHERSIDE: return 195L * 20;
            case MUSIC_DISC_5: return 178L * 20;
            case MUSIC_DISC_PIGSTEP: return 148L * 20;
            case MUSIC_DISC_RELIC: return 218L * 20;
            case MUSIC_DISC_CREATOR: return 177L * 20;
            case MUSIC_DISC_CREATOR_MUSIC_BOX: return 74L * 20;
            case MUSIC_DISC_PRECIPICE: return 287L * 20;
            default: return 0L;
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

    // ===== РАЗРУШЕНИЕ БЛОКА =====

    @EventHandler
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

        ItemStack[] items = sessionInventories.remove(key);

        if (items == null
                && blockData.contains("blocks." + key)) {

            items = ((List<ItemStack>)
                    blockData.get("blocks." + key))
                    .toArray(new ItemStack[0]);
        }

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

        try {
            blockData.save(configFile);
        } catch (IOException ignored) {
        }
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
                sessionInventories.put(
                        key,
                        list.toArray(new ItemStack[0])
                );
            }
        }
    }

    // ===== ВЫКЛЮЧЕНИЕ =====

    public void disable() {
        for (DiscSession session :
                new ArrayList<>(discSessions.values())) {

            stopDiscSession(session);
        }

        discSessions.clear();
    }

    private static class DiscSession {

        final Location loc;
        final String key;
        final Material disc;
        final long totalTicks;

        boolean playing;
        long elapsedTicks;
        BukkitTask task;

        DiscSession(
                Location loc,
                String key,
                Material disc,
                long totalTicks
        ) {
            this.loc = loc;
            this.key = key;
            this.disc = disc;
            this.totalTicks = totalTicks;
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
