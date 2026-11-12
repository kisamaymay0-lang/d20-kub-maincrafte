package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Медный нотный блок.
 *
 * Блок — кастомный блок CraftEngine {@code f8resurs:1s_copper_note_block}
 * (ступени 1s…4s, см. {@link CraftEngineCopper}):
 *  - запуск ТОЛЬКО по изменению физического питания с 0 на 1;
 *  - Paper не посылает BlockRedstoneEvent для самого блока, поэтому
 *    заполненные блоки проверяются раз в тик (без загрузки чанков);
 *  - свой блок плагин узнаёт по id в CraftEngine, а не по состоянию носителя.
 *    До 10.21 признаком была нота №24 обычного нотного блока, и любой
 *    настроенный до ноты 24 нотный блок становился медным — теперь это
 *    невозможно: медный нотный блок получается только крафтом.
 *
 * В слоте "таймера" (слот №10) может лежать:
 *  - пластинка — тогда блок работает как проигрыватель (см. ниже);
 *  - обычный предмет — тогда его количество задаёт задержку между нотами.
 */
public class CopperBlockListener implements Listener {

    private static final int TIMER_SLOT = 10;
    /** Ванильное окисление меди — примерно 50–80 минут на ступень. */
    private static final long DEFAULT_OXIDATION_MINUTES = 60L;
    private static final int DEFAULT_OXIDATION_TICKS = (int) (DEFAULT_OXIDATION_MINUTES * 60L * 20L);
    /** Период проверки старения: раз в секунду, как случайные тики у ванили. */
    /** Сколько тиков ждём возвращения своего блока на месте, прежде чем забыть его. */
    static final int POWER_MISS_GRACE_TICKS = 40;

    private static final long AGING_INTERVAL_TICKS = 20L;
    private static final double DISC_RANGE = 65.0;
    private static final long DEBOUNCE_MS = 400L;

    private final JavaPlugin plugin;
    private final File configFile;
    private FileConfiguration blockData;
    private final BatchedYamlFile storage;
    private final Set<Inventory> pendingInventorySaves = Collections.newSetFromMap(new IdentityHashMap<>());

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
    /** Ступень окисления в предмете: сломанный блок возвращается той же ступенью. */
    private final NamespacedKey stageKey;
    /** Воск в предмете: вощёный блок и ломается вощёным. */
    private final NamespacedKey waxedKey;
    /** Сказали ли уже, почему блок не ставится. */
    private boolean placeWarned;
    private final Map<String, Location> powerTrackedBlocks = new HashMap<>();
    /**
     * Сколько тиков подряд блок не был нашим, пока менял ступень. Смена ступени —
     * это снятие старого блока и постановка нового, между ними место пустое, и
     * терять из-за этого ноты нельзя.
     */
    private final Map<String, Integer> powerMisses = new HashMap<>();
    /** Блоки, которые стареют сами: ключ места -> позиция. */
    private final Map<String, Location> agingBlocks = new HashMap<>();
    /** Сколько тиков осталось до следующей ступени (у незагруженных чанков время стоит). */
    private final Map<String, Integer> agingTicksLeft = new HashMap<>();
    /** О каких ступенях уже предупредили: не спамим в лог на каждое старение. */
    private final Set<String> missingStageWarned = new HashSet<>();
    private BukkitTask agingTask;
    /** Время одной ступени окисления, тики; 0 — окисление выключено. */
    private int oxidationTicks = DEFAULT_OXIDATION_TICKS;
    private final PowerEdgeTracker powerEdges = new PowerEdgeTracker();
    private BukkitTask powerTask;

    public CopperBlockListener(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;

        this.copperBlockKey =
                new NamespacedKey(plugin, "copper_block");
        this.stageKey =
                new NamespacedKey(plugin, "copper_stage");
        this.waxedKey =
                new NamespacedKey(plugin, "copper_waxed");

        this.configFile =
                new File(plugin.getDataFolder(), "blocks.yml");

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.blockData =
                YamlConfiguration.loadConfiguration(configFile);
        storage = new BatchedYamlFile(plugin, writer, configFile.toPath(), () -> blockData.saveToString());

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
        reloadSettings();
        loadAgingFromFile();
        registerRecipe();
        powerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRedstone, 1L, 1L);
        if (CraftEngineCopper.available()) {
            // Старение работает только с загруженными чанками: как случайные
            // тики ванили, вне загруженных чанков медь не стареет.
            agingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickOxidation,
                    AGING_INTERVAL_TICKS, AGING_INTERVAL_TICKS);
            String stageProblem = CraftEngineCopper.stageDiagnosis();
            if (!stageProblem.isEmpty()) {
                plugin.getLogger().warning(stageProblem);
            }
        }
        if (!CraftEngineCopper.available()) {
            plugin.getLogger().warning("CraftEngine не найден: медный нотный блок — кастомный "
                    + "блок, и без CraftEngine он не ставится вовсе. Поставьте CraftEngine с "
                    + "https://modrinth.com/plugin/craftengine и скопируйте конфиги блоков в свой "
                    + "пак (список и образец: " + CraftEngineSupport.CONFIG_DOC + ").");
        }
    }

    /**
     * Крафт медного нотного блока — бесформенный: нотный блок, редстоун, алмаз
     * и любая медная решётка, порядок в сетке не важен.
     *
     * До 10.20 вместо алмаза был кусочек меди.
     */
    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "copper_note_block");
        ShapelessRecipe recipe = new ShapelessRecipe(key, createCopperBlockItem());
        for (Material material : recipeSingles()) {
            recipe.addIngredient(material);
        }
        recipe.addIngredient(new RecipeChoice.MaterialChoice(copperGrates()));
        Bukkit.removeRecipe(key);
        Bukkit.addRecipe(recipe);
    }

    /**
     * Одиночные ингредиенты крафта: по одному каждого, плюс четвёртым слотом
     * любая медная решётка из {@link #copperGrates()}.
     *
     * Метод возвращает просто список материалов, а не готовые
     * {@code RecipeChoice.MaterialChoice}: конструктор {@code MaterialChoice}
     * спрашивает у каждого материала {@code isAir()}, а это идёт в реестр
     * {@code Registry.BLOCK} — без запущенного сервера он не существует. Так
     * крафт можно проверить тестом.
     */
    static List<Material> recipeSingles() {
        return List.of(Material.NOTE_BLOCK, Material.REDSTONE, Material.DIAMOND);
    }

    /**
     * Ступень по решётке: из какой решётки крафтят, такой блок и получают.
     * Обычная и вощёная решётка одной ступени дают одну ступень блока, воск
     * переезжает в предмет отдельным флагом.
     */
    static int grateStage(Material material) {
        if (material == null) return CopperNoteStages.FIRST;
        return switch (material) {
            case EXPOSED_COPPER_GRATE, WAXED_EXPOSED_COPPER_GRATE -> 2;
            case WEATHERED_COPPER_GRATE, WAXED_WEATHERED_COPPER_GRATE -> 3;
            case OXIDIZED_COPPER_GRATE, WAXED_OXIDIZED_COPPER_GRATE -> CopperNoteStages.LAST;
            default -> CopperNoteStages.FIRST;
        };
    }

    /** Вощёная ли решётка: тогда и блок получается вощёным. */
    static boolean grateWaxed(Material material) {
        return material == Material.WAXED_COPPER_GRATE
                || material == Material.WAXED_EXPOSED_COPPER_GRATE
                || material == Material.WAXED_WEATHERED_COPPER_GRATE
                || material == Material.WAXED_OXIDIZED_COPPER_GRATE;
    }

    /** Решётка в сетке крафта (любая из {@link #copperGrates()}), иначе null. */
    static Material grateInMatrix(ItemStack[] matrix) {
        if (matrix == null) return null;
        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) continue;
            if (copperGrates().contains(item.getType())) return item.getType();
        }
        return null;
    }

    /**
     * Результат крафта зависит от решётки, а рецепт один: подменяем результат
     * прямо в сетке. Потемневшая решётка даёт потемневший блок, вощёная — ещё
     * и вощёный.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();

        Material grate = grateInMatrix(matrix);

        if (grate == null || !hasRecipeSingles(matrix)) {
            return;
        }

        event.getInventory().setResult(
                createCopperBlockItem(grateStage(grate), grateWaxed(grate))
        );
    }

    /** В сетке лежат все одиночные ингредиенты крафта (нотный блок, редстоун, алмаз). */
    static boolean hasRecipeSingles(ItemStack[] matrix) {
        if (matrix == null) return false;

        for (Material material : recipeSingles()) {
            boolean found = false;

            for (ItemStack item : matrix) {
                if (item != null && !item.getType().isAir() && item.getType() == material) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    /** Решётки, которые годятся в крафт: все стадии окисления и все вощёные. */
    static List<Material> copperGrates() {
        return List.of(
                Material.COPPER_GRATE,
                Material.EXPOSED_COPPER_GRATE,
                Material.WEATHERED_COPPER_GRATE,
                Material.OXIDIZED_COPPER_GRATE,
                Material.WAXED_COPPER_GRATE,
                Material.WAXED_EXPOSED_COPPER_GRATE,
                Material.WAXED_WEATHERED_COPPER_GRATE,
                Material.WAXED_OXIDIZED_COPPER_GRATE);
    }

    // ===== ПРЕДМЕТ БЛОКА =====

    /** Предмет первой ступени: так его выдаёт крафт. */
    public ItemStack createCopperBlockItem() {
        return createCopperBlockItem(CopperNoteStages.FIRST, false);
    }

    /**
     * Предмет нужной ступени. Ступень и воск едут в предмете, поэтому
     * сломанный блок ставится обратно таким, каким был.
     */
    public ItemStack createCopperBlockItem(int stage, boolean waxed) {
        int step = CopperNoteStages.clamp(stage);
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.itemName(Component.text(CopperNoteStages.itemName(step, waxed), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Поставьте и нажмите ПКМ, чтобы настроить ноты", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            // У каждой ступени своя модель предмета: 1s…4s_copper_note_block.
            meta.setItemModel(new NamespacedKey(
                    "f8resurs",
                    CopperNoteStages.itemModel(step)
            ));
            meta.getPersistentDataContainer().set(
                    copperBlockKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            meta.getPersistentDataContainer().set(
                    stageKey,
                    PersistentDataType.BYTE,
                    (byte) step
            );
            if (waxed) {
                meta.getPersistentDataContainer().set(
                        waxedKey,
                        PersistentDataType.BYTE,
                        (byte) 1
                );
            }
            item.setItemMeta(meta);
        }

        return item;
    }

    /** Ступень из предмета: у старых предметов её нет — считаем первой. */
    private int itemStage(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return CopperNoteStages.FIRST;
        Byte stage = item.getItemMeta().getPersistentDataContainer()
                .get(stageKey, PersistentDataType.BYTE);
        return stage == null ? CopperNoteStages.FIRST : CopperNoteStages.clamp(stage);
    }

    /** Воск из предмета: вощёный блок ставится вощёным. */
    private boolean itemWaxed(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                        .has(waxedKey, PersistentDataType.BYTE);
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

    /**
     * Наш ли это медный нотный блок — только по id кастомного блока CraftEngine.
     *
     * Раньше признаком была нота №24 обычного нотного блока, и любой настроенный
     * до ноты 24 нотный блок становился медным: открывал меню и дропал предмет
     * медного блока при разрушении. Так медный блок можно было получить без
     * крафта, поэтому путь по ноте убран целиком — ни в распознавании, ни в
     * переносе.
     */
    private boolean isCopperBlock(Block block) {
        return CraftEngineCopper.isCopper(block);
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

        // Предмет — обычный нотный блок с подменённой моделью, поэтому клиент
        // первым делом ставит именно нотный блок. Если кастомный блок не
        // встанет, он так и остался бы стоять — обычным нотным блоком, который
        // ещё и звенит. Отменяем.
        if (block.getType() != Material.NOTE_BLOCK) {
            return;
        }

        ItemStack held = event.getItemInHand();
        int stage = itemStage(held);

        if (!CraftEngineCopper.ready() || !CraftEngineCopper.place(block, stage)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                    + "Медный нотный блок не ставится: CraftEngine не зарегистрировал блок "
                    + CraftEngineCopper.idFor(stage) + ". Причина — в консоли сервера.");
            warnOnce();
            return;
        }

        // Блок теперь под присмотром старения: ступень видна по id, воск — в blocks.yml.
        registerAging(block);
        if (itemWaxed(held)) {
            setWaxed(block, true);
        }

        // Клиент предсказал нотный блок. Возвращаем ему настоящее состояние,
        // чтобы модель появилась сразу.
        event.getPlayer().sendBlockChange(block.getLocation(), block.getBlockData());
    }

    /** Один раз за запуск говорит, почему блок не ставится. */
    private void warnOnce() {
        if (placeWarned) {
            return;
        }
        placeWarned = true;
        String diagnosis = CraftEngineCopper.diagnosis();
        if (diagnosis.isEmpty()) {
            diagnosis = CraftEngineCopper.stageDiagnosis();
        }
        plugin.getLogger().warning("CraftEngine не принял блок " + CraftEngineCopper.ID + ". "
                + (diagnosis.isEmpty()
                ? "Проверьте консоль CraftEngine; блоки, которые нужны плагину, описаны в "
                        + CraftEngineSupport.CONFIG_DOC + "."
                : diagnosis));
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

        Player player = event.getPlayer();

        // Воск и топор — ванильные действия по меди: они важнее меню и работают
        // в том числе с зажатым шифтом. Ступень меняется на самом блоке.
        if (event.getHand() == EquipmentSlot.HAND && handleWaxOrScrape(player, block, event)) {
            event.setCancelled(true);
            return;
        }

        if (ContainerInteraction.bypassMenu(player.isSneaking(),
                player.getInventory().getItemInMainHand().getType().isAir(),
                player.getInventory().getItemInOffHand().getType().isAir())) {
            // Как печка: вообще не меняем результаты события. Ванильный
            // secondary-use сам пропускает меню и вызывает ItemStack.useOn.
            // DENY здесь раньше заставлял Paper выйти ДО размещения блока.
            return;
        }

        event.setCancelled(true);

        // Запрещаем ванильную настройку обеими руками, иначе второй
        // interact может переключить зарезервированную ноту 24 обратно на 0.
        if (event.getHand() == EquipmentSlot.HAND) {
            openCopperMenu(event.getPlayer(), block);
        }
    }

    public static final NamespacedKey COPPER_MENU_FONT = new NamespacedKey("f8resurs", "profile_ui");
    public static final Component COPPER_MENU_TITLE = Component.text("\uF808\uE120", NamedTextColor.WHITE).font(COPPER_MENU_FONT);

    private void openCopperMenu(Player player, Block block) {
        String key = getBlockKey(block);
        Inventory gui = openInventories.get(key);
        if (gui == null) {
            CopperHolder holder = new CopperHolder(block);
            gui = Bukkit.createInventory(holder, 36, COPPER_MENU_TITLE);
            holder.inventory = gui;
            ItemStack[] saved = sessionInventories.get(key);
            if (saved != null) {
                for (int i = 0; i < Math.min(saved.length, gui.getSize()); i++) {
                    if (!blockedSlots.contains(i) && saved[i] != null) {
                        gui.setItem(i, saved[i].clone());
                    }
                }
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
        storage.markDirty();
    }

    private void queueInventorySave(Inventory inventory) {
        if (shuttingDown || !pendingInventorySaves.add(inventory)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingInventorySaves.remove(inventory);
            saveInventory(inventory); // Реальное итоговое состояние, не предсказание клика.
        });
    }

    private ItemStack contentSlot(String key, int slot) {
        Inventory live = openInventories.get(key);
        return live != null ? live.getItem(slot) : getSlot(sessionInventories.get(key), slot);
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
        boolean filled = hasWorkingContents(contents);
        if (!filled) {
            // Маркер note=24 хранится в самом блоке: пустому GUI запись не нужна.
            boolean recorded = sessionInventories.remove(key) != null || blockData.contains("blocks." + key);
            if (recorded) {
                blockData.set("blocks." + key, null);
                saveBlockData();
            }
        } else if (!Arrays.equals(sessionInventories.get(key), contents)) {
            // Снимок не должен разделять изменяемые ItemStack с живым GUI.
            ItemStack[] snapshot = Arrays.stream(contents)
                    .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
            sessionInventories.put(key, snapshot);
            blockData.set("blocks." + key, snapshot);
            saveBlockData();
        }
        if (!shuttingDown) {
            if (filled) {
                if (powerTrackedBlocks.putIfAbsent(key, block.getLocation()) == null) {
                    // Вложение предметов — не новый фронт сигнала для нот.
                    powerEdges.update(key, hasPower(block));
                }
            } else {
                powerTrackedBlocks.remove(key);
                powerEdges.remove(key);
                powerMisses.remove(key);
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
        if (event.getRawSlot() >= 0 && (event.getRawSlot() < inventory.getSize() || event.isShiftClick())) {
            queueInventorySave(inventory);
        }
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
        if (event.getRawSlots().stream().anyMatch(slot -> slot < inventory.getSize())) {
            queueInventorySave(inventory);
        }
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

    private void observePower(String key, Block block) {
        if (powerEdges.update(key, hasPower(block))) {
            handleTrigger(block);
        }
    }

    private void tickRedstone() {
        var iterator = powerTrackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Location location = entry.getValue();
            World world = location.isWorldLoaded() ? location.getWorld() : null;
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                powerEdges.remove(entry.getKey());
                continue;
            }
            Block block = world.getBlockAt(location);

            if (!isCopperBlock(block)) {
                // Место пустое — блок как раз меняет ступень (или чанк догружается).
                // Ждём: иначе после окисления блок замолкает, и ноты приходится
                // расставлять заново.
                int misses = powerMisses.merge(entry.getKey(), 1, Integer::sum);

                if (block.getType().isAir() && misses <= POWER_MISS_GRACE_TICKS) {
                    powerEdges.remove(entry.getKey());
                    continue;
                }

                powerMisses.remove(entry.getKey());
                powerEdges.remove(entry.getKey());
                iterator.remove();
                continue;
            }

            powerMisses.remove(entry.getKey());
            observePower(entry.getKey(), block);
        }
    }

    private boolean hasPower(Block block) {
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    private void handleTrigger(Block block) {
        String key = getBlockKey(block);

        // Блок сработал — значит он наш. Заодно это подхватывает блоки,
        // поставленные до обновления: своего раздела в blocks.yml у них ещё нет.
        registerAging(block);

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

        // Ступень окисления: чем она выше, тем сильнее расстроен блок.
        final int stage = Math.max(CopperNoteStages.FIRST, CraftEngineCopper.stageAt(block));
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

                // Частица — на каждую сыгранную ноту и её цветом: раньше на весь
                // шаг спавнилась одна частица ноты 0 (всегда один и тот же цвет),
                // и четырем звукам отвечала одна картинка.
                playNote(
                        finalItems, highSlot, 1.2f, 1.0f
                );
                playNote(
                        finalItems, midSlot, 1.0f, 0.79f
                );
                playNote(
                        finalItems, lowSlot, 0.8f, 0.63f
                );
                playNote(
                        finalItems, subSlot, 0.9f, 0.5f
                );

                step++;
            }

            private boolean playNote(
                    ItemStack[] arr,
                    int slot,
                    float volume,
                    float pitch
            ) {
                return CopperBlockListener.this.playNote(
                        block, arr, slot, volume, pitch, stage, particleLoc
                );
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

        ItemStack timerItem = contentSlot(session.key, TIMER_SLOT);

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

    /** Ключи инструментов: те же звуки, что у ванильного нотного блока. */
    static final String KEY_HARP = "block.note_block.harp";
    static final String KEY_BASS = "block.note_block.bass";
    static final String KEY_BASEDRUM = "block.note_block.basedrum";
    static final String KEY_SNARE = "block.note_block.snare";
    static final String KEY_HAT = "block.note_block.hat";
    static final String KEY_BELL = "block.note_block.bell";
    static final String KEY_FLUTE = "block.note_block.flute";
    static final String KEY_CHIME = "block.note_block.chime";
    static final String KEY_GUITAR = "block.note_block.guitar";
    static final String KEY_XYLOPHONE = "block.note_block.xylophone";
    static final String KEY_IRON_XYLOPHONE = "block.note_block.iron_xylophone";
    static final String KEY_COW_BELL = "block.note_block.cow_bell";
    static final String KEY_DIDGERIDOO = "block.note_block.didgeridoo";
    static final String KEY_BIT = "block.note_block.bit";
    static final String KEY_BANJO = "block.note_block.banjo";
    static final String KEY_PLING = "block.note_block.pling";

    /** 26.1: у медных блоков своя труба, звук зависит от ступени окисления. */
    static final String KEY_TRUMPET = "block.note_block.trumpet";
    static final String KEY_TRUMPET_EXPOSED = "block.note_block.trumpet_exposed";
    static final String KEY_TRUMPET_WEATHERED = "block.note_block.trumpet_weathered";
    static final String KEY_TRUMPET_OXIDIZED = "block.note_block.trumpet_oxidized";

    /** Головы вместо инструмента дают свой звук. */
    static final String KEY_SKELETON = "block.note_block.imitate.skeleton";
    static final String KEY_WITHER_SKELETON = "block.note_block.imitate.wither_skeleton";
    static final String KEY_ZOMBIE = "block.note_block.imitate.zombie";

    /**
     * Инструмент по предмету в слоте — те же группы, что у ванильного нотного
     * блока над этим блоком. Возвращается ключ звука, а не {@code Sound}: таблицу
     * можно проверить тестом без сервера, а новые звуки ванили (труба меди из
     * 26.1) не требуют правок, пока у них есть ключ.
     */
    static String instrumentKey(Material material) {
        if (material == null) {
            return KEY_HARP;
        }

        String name = material.name();

        String copper = copperInstrumentKey(name);

        if (copper != null) {
            return copper;
        }

        // Головы: звук вместо инструмента.
        if (name.equals("SKELETON_SKULL") || name.equals("SKELETON_WALL_SKULL")) {
            return KEY_SKELETON;
        }

        if (name.equals("WITHER_SKELETON_SKULL") || name.equals("WITHER_SKELETON_WALL_SKULL")) {
            return KEY_WITHER_SKELETON;
        }

        if (name.equals("ZOMBIE_HEAD") || name.equals("ZOMBIE_WALL_HEAD")) {
            return KEY_ZOMBIE;
        }

        if (name.contains("BONE_BLOCK")) {
            return KEY_XYLOPHONE;
        }

        if (name.contains("GOLD_BLOCK")) {
            return KEY_BELL;
        }

        if (name.contains("CLAY")) {
            return KEY_FLUTE;
        }

        if (name.contains("ICE") || name.contains("AMETHYST")) {
            return KEY_CHIME;
        }

        if (name.contains("WOOL") || name.contains("CARPET")) {
            return KEY_GUITAR;
        }

        if (name.contains("IRON_BLOCK")) {
            return KEY_IRON_XYLOPHONE;
        }

        if (name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) {
            return KEY_COW_BELL;
        }

        if (name.contains("PUMPKIN") || name.contains("JACK_O_LANTERN")) {
            return KEY_DIDGERIDOO;
        }

        if (name.contains("EMERALD_BLOCK")) {
            return KEY_BIT;
        }

        if (name.contains("HAY_BLOCK")) {
            return KEY_BANJO;
        }

        if (name.contains("GLOWSTONE")) {
            return KEY_PLING;
        }

        // Дерево: доски, брёвна, заборы, двери, сундуки, полки, верстак, нотный блок.
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANKS")
                || name.contains("FENCE") || name.contains("DOOR") || name.contains("TRAPDOOR")
                || name.contains("SIGN") || name.contains("CRAFTING_TABLE")
                || name.contains("BOOKSHELF") || name.contains("LADDER") || name.contains("BARREL")
                || name.equals("CHEST") || name.equals("TRAPPED_CHEST")
                || name.contains("JUKEBOX") || name.contains("NOTE_BLOCK")
                || name.equals("BAMBOO") || name.contains("SCAFFOLDING")) {
            return KEY_BASS;
        }

        if (name.contains("GLASS") || name.contains("BEACON") || name.contains("SEA_LANTERN")) {
            return KEY_HAT;
        }

        // Порошок бетона — как песок, а не как бетон. Песчаник — не песок:
        // у него свой звук, поэтому SANDSTONE проверяется ниже, в камне.
        if (name.contains("CONCRETE_POWDER") || name.equals("SAND") || name.equals("RED_SAND")
                || name.contains("GRAVEL")) {
            return KEY_SNARE;
        }

        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF")
                || name.contains("CALCITE") || name.contains("BASALT") || name.contains("BLACKSTONE")
                || name.contains("NETHERRACK") || name.contains("NETHER_BRICK") || name.contains("BRICK")
                || name.contains("OBSIDIAN") || name.contains("QUARTZ") || name.contains("SANDSTONE")
                || name.contains("PURPUR") || name.contains("PRISMARINE") || name.contains("TERRACOTTA")
                || name.contains("CONCRETE") || name.contains("ORE") || name.contains("RAW_")
                || name.contains("FURNACE") || name.contains("SMOKER") || name.contains("DISPENSER")
                || name.contains("DROPPER") || name.contains("OBSERVER") || name.contains("MAGMA")
                || name.contains("ANCIENT_DEBRIS") || name.contains("SPAWNER")
                || name.contains("END_STONE") || name.contains("RESPAWN_ANCHOR")
                || name.contains("ENDER_CHEST") || name.contains("ENCHANTING_TABLE")
                || name.contains("CAULDRON") || name.contains("ANVIL") || name.contains("MUD")) {
            return KEY_BASEDRUM;
        }

        return KEY_HARP;
    }

    /**
     * Ключ звука → {@link Sound}. Если сервер такого звука не знает (труба
     * появилась в 26.1), откатываемся на арфу: молчащий блок хуже старого.
     */
    private Sound resolveInstrument(String key) {
        try {
            Sound sound = Registry.SOUND_EVENT.get(NamespacedKey.minecraft(key));

            if (sound != null) {
                return sound;
            }
        } catch (Throwable ignored) {
            // старый сервер: звука ещё нет в реестре
        }

        if (KEY_HARP.equals(key)) {
            return Sound.BLOCK_NOTE_BLOCK_HARP;
        }

        return resolveInstrument(KEY_HARP);
    }

    /**
     * Медь: с 26.1 нотный блок на медном блоке играет трубой, и звук свой на
     * каждую ступень окисления. Руды, слитки, самородки и медная электрика
     * трубой не считаются — у них остаётся прежний инструмент.
     */
    private static String copperInstrumentKey(String name) {
        if (!name.contains("COPPER")) {
            return null;
        }

        if (name.contains("ORE") || name.contains("RAW_") || name.contains("INGOT")
                || name.contains("NUGGET") || name.contains("BULB") || name.contains("LIGHTNING_ROD")
                || name.contains("CHAIN") || name.contains("DOOR") || name.contains("TRAPDOOR")) {
            return null;
        }

        if (name.contains("OXIDIZED")) {
            return KEY_TRUMPET_OXIDIZED;
        }

        if (name.contains("WEATHERED")) {
            return KEY_TRUMPET_WEATHERED;
        }

        if (name.contains("EXPOSED")) {
            return KEY_TRUMPET_EXPOSED;
        }

        return KEY_TRUMPET;
    }

    // ===== ПОРШНИ =====

    private boolean hasContents(Block block) {
        return hasWorkingContents(contentsOf(getBlockKey(block)));
    }

    private boolean hasWorkingContents(ItemStack[] contents) {
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
            powerMisses.remove(key);
            blockData.set("blocks." + key, null);
            forgetAging(key);
            block.removeMetadata("copper_playing", plugin);
            block.removeMetadata("last_copper_trigger", plugin);
            // Поршень перемещает блок вместе с его состоянием. Данных в новом
            // месте пока нет: блок гарантированно пустой.
            changed = true;
        }
        if (changed) {
            saveBlockData();
        }
    }

    // ===== НОТЫ С УЧЁТОМ СТУПЕНИ =====

    /**
     * Одна нота раскладки. Высоту расстраивает ступень, а у окисленного блока
     * нота ещё и приходит с задержкой — четыре голоса «расползаются» во времени,
     * как у настоящего ржавого механизма.
     */
    private boolean playNote(
            Block block,
            ItemStack[] arr,
            int slot,
            float volume,
            float basePitch,
            int stage,
            Location particleLoc
    ) {
        ItemStack it = getSlot(arr, slot);

        if (it == null) {
            return false;
        }

        int delay = CopperNoteStages.noteDelayTicks(stage, ThreadLocalRandom.current());

        if (delay <= 0) {
            emitNote(block, it, volume, basePitch, stage, particleLoc);
            return true;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isCopperBlock(block)) return;
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
            emitNote(block, it, volume, basePitch, stage, particleLoc);
        }, delay);

        return true;
    }

    private void emitNote(
            Block block,
            ItemStack instrument,
            float volume,
            float basePitch,
            int stage,
            Location particleLoc
    ) {
        float pitch = CopperNoteStages.detunedPitch(stage, basePitch, ThreadLocalRandom.current());

        block.getWorld().playSound(
                block.getLocation(),
                resolveInstrument(instrumentKey(instrument.getType())),
                SoundCategory.RECORDS,
                volume,
                pitch
        );

        // Как у ванильного нотного блока: одна нота — одна частица, цвет
        // частицы — нота (смещение note / 24 при count = 0).
        block.getWorld().spawnParticle(
                Particle.NOTE,
                particleLoc,
                0,
                NoteTones.offset(pitch),
                0.0,
                0.0,
                1.0
        );
    }

    // ===== СТАРЕНИЕ: ВОСК, ТОПОР, ВОДА И ВРЕМЯ =====

    /** /f8 reload: время окисления можно менять без перезапуска. */
    void reloadSettings() {
        long minutes = plugin.getConfig()
                .getLong("copper-note-block.oxidation-minutes", DEFAULT_OXIDATION_MINUTES);
        long ticks = minutes <= 0 ? 0L : minutes * 60L * 20L;
        oxidationTicks = ticks <= 0L ? 0 : (int) Math.min(ticks, Integer.MAX_VALUE);
    }

    /** Кто уже под присмотром старения: содержимое blocks.yml, раздел stages. */
    private void loadAgingFromFile() {
        ConfigurationSection stages = blockData.getConfigurationSection("stages");
        if (stages == null) {
            return;
        }
        for (String key : stages.getKeys(false)) {
            Location location = locationFromKey(key);
            if (location == null) continue;
            agingBlocks.put(key, location);
            agingTicksLeft.put(key, randomOxidationTicks());
        }
    }

    /** Сколько ждать до следующей ступени: ±25 %, чтобы блоки не старели хором. */
    private int randomOxidationTicks() {
        if (oxidationTicks <= 0) return Integer.MAX_VALUE;
        double factor = 0.75D + ThreadLocalRandom.current().nextDouble() * 0.5D;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(oxidationTicks * factor));
    }

    private void registerAging(Block block) {
        String key = getBlockKey(block);
        agingBlocks.put(key, block.getLocation().clone());
        agingTicksLeft.putIfAbsent(key, randomOxidationTicks());
        if (!blockData.contains("stages." + key)) {
            blockData.set("stages." + key, Boolean.FALSE);
            saveBlockData();
        }
    }

    /** Блок ушёл из мира или уехал поршнем: данные старения больше не нужны. */
    private void forgetAging(String key) {
        agingBlocks.remove(key);
        agingTicksLeft.remove(key);
        blockData.set("stages." + key, null);
    }

    private boolean isWaxed(String key) {
        return blockData.getBoolean("stages." + key, false);
    }

    private void setWaxed(Block block, boolean waxed) {
        String key = getBlockKey(block);
        agingBlocks.putIfAbsent(key, block.getLocation().clone());
        agingTicksLeft.putIfAbsent(key, randomOxidationTicks());
        blockData.set("stages." + key, waxed);
        saveBlockData();
    }

    /**
     * Окисление временем. Ваниль старит медь случайными тиками в загруженных
     * чанках, поэтому и здесь счётчик идёт только у загруженных чанков: пока
     * чанк выгружен, медь не стареет. Воск останавливает счёт.
     */
    private void tickOxidation() {
        if (oxidationTicks <= 0 || agingBlocks.isEmpty() || shuttingDown) {
            return;
        }

        boolean changed = false;
        var iterator = agingBlocks.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            String key = entry.getKey();
            Location location = entry.getValue();
            World world = location.isWorldLoaded() ? location.getWorld() : null;

            if (world == null
                    || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }

            Block block = world.getBlockAt(location);
            int stage = CraftEngineCopper.stageAt(block);

            if (stage <= 0) {
                // Блок убрали не нашим способом (или он уже не наш) — забываем.
                iterator.remove();
                agingTicksLeft.remove(key);
                blockData.set("stages." + key, null);
                changed = true;
                continue;
            }

            if (!CopperNoteStages.canOxidize(stage, isWaxed(key))) {
                continue;
            }

            int left = agingTicksLeft.getOrDefault(key, oxidationTicks) - (int) AGING_INTERVAL_TICKS;

            if (left > 0) {
                agingTicksLeft.put(key, left);
                continue;
            }

            if (advanceStage(block, stage + 1)) {
                changed = true;
            }
            agingTicksLeft.put(key, randomOxidationTicks());
        }

        if (changed) {
            saveBlockData();
        }
    }

    /** Поставить следующую ступень на то же место. */
    private boolean advanceStage(Block block, int stage) {
        int target = CopperNoteStages.clamp(stage);

        if (CraftEngineCopper.stageAt(block) == target) {
            return false;
        }

        if (!CraftEngineCopper.place(block, target)) {
            warnMissingStage(CraftEngineCopper.idFor(target));
            return false;
        }

        restoreTrigger(block);
        return true;
    }

    /**
     * Вернуть блок в список играющих после смены ступени: CraftEngine снимает
     * старый блок и ставит новый, а следом {@link #tickRedstone()} мог решить,
     * что блока больше нет. Ноты при этом остались в blocks.yml — терять их
     * незачем.
     */
    private void restoreTrigger(Block block) {
        String key = getBlockKey(block);

        powerMisses.remove(key);

        if (!hasWorkingContents(contentsOf(key))) {
            return;
        }

        powerTrackedBlocks.put(key, block.getLocation());
        powerEdges.update(key, hasPower(block));
    }

    private void warnMissingStage(String id) {
        if (!missingStageWarned.add(id)) {
            return;
        }
        plugin.getLogger().warning("CraftEngine не зарегистрировал ступень " + id
                + ": медный нотный блок не стареет дальше. Список блоков и образец конфига: "
                + CraftEngineSupport.CONFIG_DOC + ".");
    }

    private void ageEffects(Block block, Particle particle, Sound sound) {
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(particle, at, 18, 0.35, 0.35, 0.35, 0.0);
        block.getWorld().playSound(at, sound, SoundCategory.BLOCKS, 0.8f, 1.0f);
    }

    /** Мёд: воск останавливает окисление, как у ванильной меди. */
    private boolean wax(Block block, Player player, ItemStack item, EquipmentSlot hand) {
        String key = getBlockKey(block);

        if (isWaxed(key)) {
            // Ваниль на уже вощёный блок не реагирует вовсе.
            return false;
        }

        setWaxed(block, true);
        ageEffects(block, Particle.WAX_ON, Sound.ITEM_HONEYCOMB_WAX_ON);
        consumeItem(player, hand, item);
        // Клиент не предсказывает действие по нашему блоку, поэтому взмах руки
        // посылаем сами: без него инструмент в руке «не двигается».
        swing(player, hand);
        return true;
    }

    /** Топор: сначала снимает воск, потом окисление — по ступени за раз. */
    private boolean scrape(Block block, Player player, ItemStack item, EquipmentSlot hand) {
        String key = getBlockKey(block);
        int stage = CraftEngineCopper.stageAt(block);

        if (stage <= 0) {
            return false;
        }

        boolean changed;

        if (isWaxed(key)) {
            setWaxed(block, false);
            ageEffects(block, Particle.WAX_OFF, Sound.ITEM_AXE_WAX_OFF);
            changed = true;
        } else if (stage > CopperNoteStages.FIRST) {
            changed = advanceStage(block, stage - 1);
            if (changed) {
                ageEffects(block, Particle.SCRAPE, Sound.ITEM_AXE_SCRAPE);
            }
        } else {
            // Обычная медь без воска: снимать нечего, как и в ванили.
            return false;
        }

        if (changed) {
            damageTool(player, hand, item);
            swing(player, hand);
        }

        return changed;
    }

    /** Вода по блоку: снимает воск, а обычную медь старит на ступень. */
    boolean ageWithWater(Block block) {
        int stage = CraftEngineCopper.stageAt(block);

        if (stage <= 0) {
            return false;
        }

        if (isWaxed(getBlockKey(block))) {
            setWaxed(block, false);
            ageEffects(block, Particle.WAX_OFF, Sound.ITEM_BOTTLE_FILL);
            return true;
        }

        if (stage >= CopperNoteStages.LAST) {
            return false;
        }

        if (!advanceStage(block, stage + 1)) {
            return false;
        }

        ageEffects(block, Particle.WAX_OFF, Sound.ITEM_BOTTLE_FILL);
        return true;
    }

    /** ПКМ мёдом или топором по блоку. */
    private boolean handleWaxOrScrape(Player player, Block block, PlayerInteractEvent event) {
        ItemStack item = event.getItem();

        if (item == null) {
            return false;
        }

        Material type = item.getType();

        if (type == Material.HONEYCOMB) {
            return wax(block, player, item, EquipmentSlot.HAND);
        }

        if (type.name().endsWith("_AXE")) {
            return scrape(block, player, item, EquipmentSlot.HAND);
        }

        return false;
    }

    /** Взмах руки — та же анимация, что клиент проигрывает при работе инструментом. */
    private static void swing(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
    }

    private static void consumeItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        item.setAmount(Math.max(0, item.getAmount() - 1));

        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    /** Как ваниль: одно действие топора — единица прочности. */
    private static void damageTool(Player player, EquipmentSlot hand, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        if (!(item.getItemMeta() instanceof Damageable damageable)) {
            return;
        }

        int max = item.getType().getMaxDurability();

        if (max <= 0) {
            return;
        }

        int next = damageable.getDamage() + 1;

        if (next >= max) {
            item.setAmount(0);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
        } else {
            damageable.setDamage(next);
            item.setItemMeta(damageable);
        }

        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
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
        powerMisses.remove(key);
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

        // Ступень и воск запоминаем до того, как блок уйдёт из мира: дроп
        // возвращается таким же, как в ванили у медных блоков.
        int stage = Math.max(CopperNoteStages.FIRST, CraftEngineCopper.stageAt(block));
        boolean waxed = isWaxed(key);

        // В творческом режиме ванильный блок не дропается.
        if (event.getPlayer().getGameMode()
                != GameMode.CREATIVE) {

            block.getWorld().dropItemNaturally(
                    block.getLocation(),
                    createCopperBlockItem(stage, waxed)
            );
        }

        blockData.set("blocks." + key, null);
        forgetAging(key);
        saveBlockData();

        // Кастомный блок гасим сами: ванильное «поставить воздух» к блоку
        // CraftEngine отношения не имеет.
        CraftEngineCopper.remove(block);
    }

    // ===== ЗАГРУЗКА ИЗ ФАЙЛА =====

    @SuppressWarnings("unchecked")
    private void loadAllBlocksFromFile() {
        if (!blockData.contains("blocks")) {
            return;
        }

        boolean loadStageMigration = false;

        for (String key :
                blockData.getConfigurationSection("blocks")
                        .getKeys(false)) {

            List<ItemStack> list =
                    (List<ItemStack>) blockData.get(
                            "blocks." + key
                    );

            if (list != null) {
                ItemStack[] contents = list.toArray(new ItemStack[0]);
                if (!hasWorkingContents(contents)) continue;
                sessionInventories.put(key, contents);
                Location location = locationFromKey(key);
                if (location != null) {
                    // Блоки, поставленные до ступеней, попадают под старение
                    // при первом запуске: их место уже известно из blocks.yml.
                    if (!blockData.contains("stages." + key)) {
                        blockData.set("stages." + key, Boolean.FALSE);
                        loadStageMigration = true;
                    }
                    agingBlocks.putIfAbsent(key, location);
                    agingTicksLeft.putIfAbsent(key, randomOxidationTicks());
                    // Пустые сохранённые GUI не нуждаются в опросе питания.
                    // Пока не загружаем чанк и не вызываем getBlockAt().
                    // При первой проверке загруженного блока считываем питание.
                    powerTrackedBlocks.put(key, location);
                }
            }
        }

        if (loadStageMigration) {
            saveBlockData();
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
        if (agingTask != null) {
            agingTask.cancel();
        }
        agingBlocks.clear();
        agingTicksLeft.clear();
        powerTrackedBlocks.clear();
        powerEdges.clear();
        powerMisses.clear();
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
        pendingInventorySaves.clear();
        storage.flush();
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
