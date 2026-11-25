package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class F8Command implements CommandExecutor, Listener {

    private static final String MAIN_TITLE = "§8F8";
    private static final String ENCHANT_TITLE = "§8Новые чарки";
    private static final String BLOCK_TITLE = "§8Новые блоки";
    private static final String ITEM_TITLE = "§8Новые предметы";
    private static final String DRINK_TITLE = "§8Напитки";
    /** Ключи, которые плагин просит у пака: их и проверяем по /f8 sound. */
    static final String[] TEST_SOUND_KEYS = {"f8resurs:pet-talking1", "f8resurs:pet-talking2"};

    private final AdaptationPlugin plugin;
    private final DiceRollListener diceRollListener;
    private final FlaskListener flaskListener;
    private final RollbackListener rollbackListener;
    private final CopperBlockListener copperBlockListener;
    private final CaviarListener caviarListener;
    private final WinterFishing winterFishing;
    private final AncientJug ancientJug;
    private final PetManager petManager;
    private final CrownPet crownPet;
    private final Apvsh apvsh;

    private final NamespacedKey menuKey;

    public F8Command(
            AdaptationPlugin plugin,
            DiceRollListener diceRollListener,
            FlaskListener flaskListener,
            RollbackListener rollbackListener,
            CopperBlockListener copperBlockListener,
            CaviarListener caviarListener,
            WinterFishing winterFishing,
            AncientJug ancientJug,
            PetManager petManager,
            CrownPet crownPet,
            Apvsh apvsh
    ) {
        this.plugin = plugin;
        this.diceRollListener = diceRollListener;
        this.flaskListener = flaskListener;
        this.rollbackListener = rollbackListener;
        this.copperBlockListener = copperBlockListener;
        this.caviarListener = caviarListener;
        this.winterFishing = winterFishing;
        this.ancientJug = ancientJug;
        this.petManager = petManager;
        this.crownPet = crownPet;
        this.apvsh = apvsh;
        this.menuKey = new NamespacedKey(plugin, "f8_menu");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        // /f8 reload — перечитать settings/*.yml (доступно и из консоли).
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            String summary = plugin.reloadPluginSettings();
            sender.sendMessage("§aНастройки f8-plugin перечитаны из settings/*.yml.");
            if (summary != null && !summary.isBlank()) sender.sendMessage(summary);
            return true;
        }

        // /f8 config — где что лежит: settings/, content/, data/.
        if (args.length > 0 && args[0].equalsIgnoreCase("config")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            for (String line : Layout.describe(plugin)) sender.sendMessage(line);
            return true;
        }

        // /f8 rp — что плагин видит из RP Engine: посуда, API, модели рядом.
        if (args.length > 0 && args[0].equalsIgnoreCase("rp")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            ShotGlass glass = plugin.shotGlass();
            if (glass == null) {
                sender.sendMessage("§cПосуда RP Engine не подключена.");
                return true;
            }
            Player viewer = sender instanceof Player player ? player : null;
            for (String line : glass.report(viewer)) sender.sendMessage(line);
            return true;
        }

        // /f8 sound — проиграть оба звука пака самому себе (диагностика пака).
        if (args.length > 0 && args[0].equalsIgnoreCase("sound")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cПроверка звука доступна только игроку.");
                return true;
            }
            playTestSounds(player);
            return true;
        }

        // /f8 pets kill — убрать активных и застрявших питомцев (доступно и из консоли).
        if (args.length >= 2 && args[0].equalsIgnoreCase("pets") && args[1].equalsIgnoreCase("kill")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            int removed = petManager.killAll() + (crownPet != null ? crownPet.killAll() : 0);
            sender.sendMessage("§aОчистка завершена: удалено сущностей питомцев — §f" + removed + "§a.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("apvsh")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player && apvsh != null) {
                giveItem(player, apvsh.createApvshBlockItem());
                player.sendMessage("§aВы получили блок АПВШ.");
            }
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("shaker") || args[0].equalsIgnoreCase("sheiker"))) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player) {
                giveItem(player, Shaker.create());
                player.sendMessage("§aВы получили Шейкер.");
            }
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("hot_water") || args[0].equalsIgnoreCase("hot_water_bottle"))) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player) {
                giveItem(player, Shaker.createHotWaterBottle());
                player.sendMessage("§aВы получили Бутылочку горячей воды.");
            }
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("pirate_rum") || args[0].equalsIgnoreCase("rum"))) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player) {
                giveItem(player, BambooBarrel.createPirateRum());
                player.sendMessage("§aВы получили Пиратский ром.");
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("low_sweetie_berry")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player) {
                giveItem(player, SweetBerryBlock.createLowSweetieBerry());
                player.sendMessage("§aВы получили Помятые сладкие ягоды.");
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("hard_sweetie_berry")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (sender instanceof Player player) {
                giveItem(player, SweetBerryBlock.createHardSweetieBerry());
                player.sendMessage("§aВы получили Продавленные сладкие ягоды.");
            }
            return true;
        }

        // /f8 drinks — что плагин на самом деле прочитал из файлов напитков.
        if (args.length > 0 && (args[0].equalsIgnoreCase("drinks")
                || args[0].equalsIgnoreCase("напитки"))) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            for (String line : drinkReport()) sender.sendMessage(line);
            return true;
        }

        // /f8 drunk — шкала опьянения: своя или чужая (для админа).
        if (args.length > 0 && args[0].equalsIgnoreCase("drunk")) {
            return drunk(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§7Использование: /f8 reload — перечитать settings/*.yml, косметику и новые YAML-файлы");
            sender.sendMessage("§7             /f8 config — где что лежит и что не на месте");
            sender.sendMessage("§7             /f8 drinks — что прочитано из recipe-poilo.yml и napitki.yml");
            return true;
        }

        if (!player.hasPermission("f8.admin")) {
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("pet")) {
            // /f8 pet — первый тестовый питомец, /f8 pet 2 — живая корона.
            if (args.length > 1 && args[1].equals("2")) {
                if (crownPet == null) {
                    player.sendMessage("§cПитомец-корона недоступен.");
                    return true;
                }
                crownPet.toggle(player);
                return true;
            }
            if (args.length > 1 && !args[1].equals("1")) {
                player.sendMessage("§7Использование: §f/f8 pet [1|2]§7 — 1: тестовый питомец, 2: живая корона.");
                return true;
            }
            petManager.toggle(player);
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                MAIN_TITLE
        );

        fill(inventory);

        inventory.setItem(
                10,
                createMenuItem(
                        Material.ENCHANTED_BOOK,
                        "§bНовые чарки",
                        List.of("§7Новые зачарования"),
                        "enchants"
                )
        );

        inventory.setItem(
                13,
                createMenuItem(
                        Material.NOTE_BLOCK,
                        "§6Новые блоки",
                        List.of("§7Новые блоки"),
                        "blocks"
                )
        );

        inventory.setItem(
                16,
                createMenuItem(
                        Material.POTION,
                        "§bНовые предметы",
                        List.of("§7Новые предметы"),
                        "items"
                )
        );

        player.openInventory(inventory);
    }

    private void openEnchantMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                36,
                ENCHANT_TITLE
        );

        fill(inventory);

        inventory.setItem(
                10,
                createEnchantmentBook(
                        "Адаптация I",
                        "adaptation_1"
                )
        );

        inventory.setItem(
                12,
                createEnchantmentBook(
                        "Адаптация II",
                        "adaptation_2"
                )
        );

        inventory.setItem(
                14,
                createEnchantmentBook(
                        "Адаптация III",
                        "adaptation_3"
                )
        );

        inventory.setItem(
                16,
                createEnchantmentBook(
                        "Бросок I",
                        "d20"
                )
        );

        inventory.setItem(
                22,
                createTaggedItem(
                        rollbackListener.createRollbackTotem(),
                        "rollback_totem"
                )
        );

        inventory.setItem(
                31,
                createMenuItem(
                        Material.ARROW,
                        "§7Назад",
                        Collections.emptyList(),
                        "back"
                )
        );

        player.openInventory(inventory);
    }

    private void openBlockMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                BLOCK_TITLE
        );

        fill(inventory);

        // Медный нотный блок — четыре ступени окисления, каждая своим предметом:
        // поставленная ступень сохраняется, дальше блок стареет сам.
        for (int stage = CopperNoteStages.FIRST; stage <= CopperNoteStages.LAST; stage++) {
            inventory.setItem(
                    9 + stage,
                    createMenuItem(
                            Material.NOTE_BLOCK,
                            "§6" + CopperNoteStages.itemName(stage),
                            List.of("§7Нажмите, чтобы получить блок"),
                            "copper_note_" + stage
                    )
            );
        }

        inventory.setItem(
                15,
                createMenuItem(
                        Material.NOTE_BLOCK,
                        "§6АПВШ",
                        List.of("§7Аппарат для создания и наполнения сигарет", "§7Нажмите, чтобы получить блок"),
                        "apvsh"
                )
        );

        inventory.setItem(
                22,
                createMenuItem(
                        Material.ARROW,
                        "§7Назад",
                        Collections.emptyList(),
                        "back"
                )
        );

        player.openInventory(inventory);
    }

    static final List<String> ITEM_CATALOG = List.of("water_flask", "poison_flask", "red_caviar", "black_caviar",
            "empty_cod", "empty_salmon", "caviar_sandwich_red", "caviar_sandwich_black",
            "icy_rime", "rime", "depleted_rime", "ice_caviar", "ice_caviar_sandwich", "ancient_jug",
            "crab_claw", "cigarette", "cigarette_lit", "cigarette_small", "cigarette_small_lit",
            "cigarette_regular", "cigarette_regular_lit",
            "rime_potion", "rime_potion_splash", "rime_potion_lingering", "shaker", "hot_water", "pirate_rum");

    private ItemStack catalogItem(String id) {
        return switch (id) {
            case "water_flask" -> flaskListener.createWaterFlask();
            case "poison_flask" -> flaskListener.createPoisonFlask();
            case "red_caviar" -> caviarListener.createRedCaviar();
            case "black_caviar" -> caviarListener.createBlackCaviar();
            case "empty_cod" -> caviarListener.createDepletedFish(Material.COD);
            case "empty_salmon" -> caviarListener.createDepletedFish(Material.SALMON);
            case "caviar_sandwich_red" -> caviarListener.createCaviarSandwich("red");
            case "caviar_sandwich_black" -> caviarListener.createCaviarSandwich("black");
            case "icy_rime" -> winterFishing.items.create(WinterItems.Kind.TOOL);
            case "rime" -> winterFishing.items.create(WinterItems.Kind.RAW);
            case "depleted_rime" -> winterFishing.items.create(WinterItems.Kind.DEPLETED);
            case "ice_caviar" -> winterFishing.items.create(WinterItems.Kind.ROE);
            case "ice_caviar_sandwich" -> winterFishing.items.create(WinterItems.Kind.SANDWICH);
            case "rime_potion" -> winterFishing.items.create(WinterItems.Kind.RIME_POTION);
            case "rime_potion_splash" -> winterFishing.items.create(WinterItems.Kind.RIME_POTION_SPLASH);
            case "rime_potion_lingering" -> winterFishing.items.create(WinterItems.Kind.RIME_POTION_LINGERING);
            case "ancient_jug" -> ancientJug.createEmpty();
            case "crab_claw" -> winterFishing.items.create(WinterItems.Kind.CLAW);
            case "cigarette" -> Cigarette.cold();
            case "cigarette_lit" -> Cigarette.lit();
            case "cigarette_small" -> Cigarette.coldSmall();
            case "cigarette_small_lit" -> Cigarette.litSmall();
            case "cigarette_regular" -> Cigarette.coldRegular();
            case "cigarette_regular_lit" -> Cigarette.litRegular();
            case "shaker" -> Shaker.create();
            case "hot_water" -> Shaker.createHotWaterBottle();
            case "pirate_rum" -> BambooBarrel.createPirateRum();
            default -> throw new IllegalArgumentException("Неизвестный предмет каталога");
        };
    }

    /** Напитки из комплекта — они в меню всегда и в этом порядке. */
    static final List<String> DRINK_CATALOG = List.of(
            "drink_mead", "drink_matcha_tea", "drink_daiquiri", "drink_iced_latte", "drink_murk", "drink_pirate_rum");

    /**
     * Все напитки, которые можно получить из /f8 → предметы → напитки: сначала
     * привычный комплект, потом всё, что владелец дописал в
     * {@code settings/recipe-poilo.yml}. Новый напиток появляется в меню сам.
     */
    static List<String> drinkCatalog() {
        List<String> ids = new ArrayList<>(DRINK_CATALOG);
        for (DrinkRecipes.Drink drink : DrinkRecipes.current().list()) {
            String id = "drink_" + drink.id();
            if (!ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private ItemStack drinkItem(String id) {
        return switch (id) {
            case "drink_mead" -> Shaker.createDrinkItem(Shaker.RECIPE_MEAD);
            case "drink_matcha_tea" -> Shaker.createDrinkItem(Shaker.RECIPE_MATCHA_TEA);
            case "drink_daiquiri" -> Shaker.createDrinkItem(Shaker.RECIPE_DAIQUIRI);
            case "drink_iced_latte" -> Shaker.createDrinkItem(Shaker.RECIPE_ICED_LATTE);
            case "drink_murk" -> Shaker.createDrinkItem(Shaker.RECIPE_MURK);
            case "drink_pirate_rum" -> BambooBarrel.createPirateRum();
            default -> {
                // Напиток, добавленный в settings/recipe-poilo.yml: готовим по рецепту из файла.
                if (id.startsWith("drink_")) {
                    DrinkRecipes.Drink drink = DrinkRecipes.current().get(id.substring("drink_".length()));
                    if (drink != null) {
                        // Бочковый напиток выдаём бутылочкой из бочки: у неё годы, качество и глотки.
                        yield drink.shaker() ? Shaker.createDrinkItem(drink.id())
                                : BambooBarrel.createBarrelDrink(drink.id(), 0, 1, 3);
                    }
                }
                throw new IllegalArgumentException("Неизвестный напиток каталога");
            }
        };
    }

    /** Меню со всеми напитками: те же предметы, что игрок получает из шейкера и бочки. */
    private void openDrinkMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, DRINK_TITLE);
        fill(inventory);
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        List<String> drinks = drinkCatalog();
        for (int i = 0; i < drinks.size() && i < slots.length; i++) {
            String id = drinks.get(i);
            inventory.setItem(slots[i], createTaggedItem(drinkItem(id), id));
        }
        inventory.setItem(49, createMenuItem(Material.ARROW, "§7Назад", Collections.emptyList(), "back_items"));
        player.openInventory(inventory);
    }

    private void openItemMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ITEM_TITLE);
        fill(inventory);
        int[] slots = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
        for (int i = 0; i < ITEM_CATALOG.size(); i++) {
            String id = ITEM_CATALOG.get(i);
            inventory.setItem(slots[i], createTaggedItem(catalogItem(id), id));
        }
        inventory.setItem(47, createMenuItem(
                Material.POTION,
                "§bНапитки",
                List.of("§7Все напитки плагина"),
                "drinks"
        ));
        inventory.setItem(49, createMenuItem(Material.ARROW, "§7Назад", Collections.emptyList(), "back"));
        player.openInventory(inventory);
    }

    private ItemStack createEnchantmentBook(
            String enchantment,
            String id
    ) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);

        ItemMeta meta = book.getItemMeta();

        if (meta == null) {
            return book;
        }

        meta.setDisplayName("§bЧародейская книга");
        meta.setLore(
                Collections.singletonList(
                        "§d" + enchantment
                )
        );
        meta.setEnchantmentGlintOverride(true);

        meta.getPersistentDataContainer().set(
                menuKey,
                PersistentDataType.STRING,
                id
        );

        book.setItemMeta(meta);

        return book;
    }

    private ItemStack createTaggedItem(
            ItemStack item,
            String id
    ) {
        ItemStack result = item.clone();

        ItemMeta meta = result.getItemMeta();

        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    menuKey,
                    PersistentDataType.STRING,
                    id
            );

            result.setItemMeta(meta);
        }

        return result;
    }

    private ItemStack createMenuItem(
            Material material,
            String name,
            List<String> lore,
            String id
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(name);

        if (lore != null && !lore.isEmpty()) {
            meta.setLore(new ArrayList<>(lore));
        }

        if (id != null) {
            meta.getPersistentDataContainer().set(
                    menuKey,
                    PersistentDataType.STRING,
                    id
            );
        }

        item.setItemMeta(meta);

        return item;
    }

    private String getId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(
                menuKey,
                PersistentDataType.STRING
        );
    }

    private void fill(Inventory inventory) {
        ItemStack inner = fillerPane(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack border = fillerPane(Material.BLACK_STAINED_GLASS_PANE);

        int size = inventory.getSize();
        int cols = 9;
        int rows = size / cols;

        for (int i = 0; i < size; i++) {
            int row = i / cols;
            int col = i % cols;
            boolean isBorder = row == 0 || row == rows - 1 || col == 0 || col == cols - 1;
            inventory.setItem(i, isBorder ? border : inner);
        }
    }

    /** Фоновая стеклянная панель (рамка/заполнитель) без видимого имени. */
    private ItemStack fillerPane(Material material) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /**
     * Играет оба ключа пака прямо в ушах игрока, без питомцев и без условий.
     * Если тут тишина — виноват пак, а не логика питомцев.
     */
    void playTestSounds(Player player) {
        for (int i = 0; i < TEST_SOUND_KEYS.length; i++) {
            final String key = TEST_SOUND_KEYS[i];
            final long delay = i * 40L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.playSound(player.getLocation(), key, SoundCategory.MASTER, 1.0f, 1.0f);
                plugin.getLogger().info("Проверка звука: отправлен " + key
                        + " игроку " + player.getName());
            }, delay);
        }
        player.sendMessage("§7Проверка звуков пака: сейчас §f" + TEST_SOUND_KEYS[0]
                + "§7, через 2 секунды §f" + TEST_SOUND_KEYS[1] + "§7.");
        player.sendMessage("§7Тишина? Смотрите клиентский §flogs/latest.log§7: "
                + "§fUnable to play unknown soundEvent§7 — нет ключа в sounds.json; "
                + "§fis stereo§7 — ogg должен быть моно.");
    }

    // ===== /f8 drinks: что реально прочитано =====

    /**
     * Подробный отчёт по напиткам: откуда читаются файлы, какие напитки загрузились,
     * что каждый просит в бочку, сколько варится и когда какое качество. Нужен, когда
     * «добавил рецепт — а он не работает»: тут видно и ошибку файла, и забытый id,
     * и рецепт, который пишет ингредиенты не в том виде.
     */
    List<String> drinkReport() {
        List<String> lines = new ArrayList<>();
        DrinkRecipes recipes = DrinkRecipes.current();
        BarrelDrinks barrel = BarrelDrinks.current();

        lines.add("§8==== §6Напитки §8====");
        Path recipePath = Layout.settings(plugin, "recipe-poilo.yml");
        Path barrelPath = Layout.data(plugin, BarrelDrinks.FILE);
        lines.add("§7Рецепты: §f" + recipePath + (Files.exists(recipePath) ? "" : " §c(файла нет)"));
        lines.add("§7Выдержка: §f" + barrelPath + (Files.exists(barrelPath) ? "" : " §c(файла нет)"));
        lines.add("§7Загружено: §f" + recipes.size() + " §7(шейкер: §f" + recipes.shakerDrinks().size()
                + "§7, бочка: §f" + recipes.barrelDrinks().size() + "§7), профилей в napitki: §f"
                + barrel.ids().size());

        StringBuilder allowed = new StringBuilder();
        for (DrinkRecipes.BarrelIngredient ingredient : DrinkRecipes.BarrelIngredient.values()) {
            if (allowed.length() > 0) allowed.append(", ");
            allowed.append(ingredient.label());
        }
        lines.add("§7Бочка берёт только это: §f" + allowed);
        lines.add("§7Бочковому напитку нужен свой блок в napitki.yml — без него он не создаётся.");

        if (plugin.lastDrinkError() != null) {
            lines.add("§cОшибка recipe-poilo.yml: §f" + plugin.lastDrinkError());
            lines.add("§cПока файл с ошибкой, работают СТАРЫЕ напитки — новых в списке не будет.");
        }
        if (plugin.lastBarrelError() != null) {
            lines.add("§cОшибка napitki.yml: §f" + plugin.lastBarrelError());
        }
        if (!recipes.problems().isEmpty()) {
            lines.add("§8— пропущено при чтении —");
            for (String problem : recipes.problems()) lines.add("§c" + problem);
        }

        lines.add("§8— бочковые —");
        for (DrinkRecipes.Drink drink : recipes.barrelDrinks()) {
            String id = drink.id();
            String text = recipes.recipeText(id);
            BarrelDrinks.Profile profile = barrel.of(id);
            StringBuilder line = new StringBuilder("§e" + id + " §f" + drink.name());
            if (text.isEmpty()) {
                line.append(" §c— рецепта нет!");
            } else {
                line.append(" §8· §f").append(text);
                line.append(" §8· варка ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f", profile.brewTicks() / 20.0))
                        .append(" с");
                line.append(" §8· ").append(profile.bottles()).append(" бут.");
                line.append(" §8· год ").append(profile.ticksPerYear()).append(" тиков");
                line.append(" §8· годы ").append(yearsText(profile));
                line.append(" §8· за глоток ").append(drunkText(profile));
                if (drink.alcohol() > 0) line.append(" §8· alcohol: §f").append(drink.alcohol());
            }
            lines.add(line.toString());
            if (text.isEmpty()) {
                lines.add("  §c  почему: у напитка method: barrel, но ingredients пустые "
                        + "или записаны списком. Бочка читает только «предмет: сколько».");
            } else if (barrel.ownKeys(id).isEmpty()) {
                lines.add("  §e  своих настроек в napitki.yml нет: сроки и качества — из default "
                        + "(а там годы рома, 10 лет до безупречного и ромовая крепость).");
            } else if (!barrel.ownKeys(id).contains("drunk")) {
                lines.add("  §e  в napitki.yml нет drunk: крепость взята из default — как у рома.");
            }
        }

        lines.add("§8— шейкер —");
        for (DrinkRecipes.Drink drink : recipes.shakerDrinks()) {
            StringBuilder line = new StringBuilder("§e" + drink.id() + " §f" + drink.name());
            String text = recipes.shakerText(drink.id());
            if (!text.isEmpty()) line.append(" §8· §f").append(text);
            if (!drink.baseDrinks().isEmpty()) {
                line.append(" §8· §fнапитки: ").append(String.join(" + ", drink.baseDrinks()));
            }
            line.append(" §8· alcohol: §f").append(drink.alcohol());
            lines.add(line.toString());
        }

        List<String> notes = new ArrayList<>();
        for (String id : barrel.ids()) {
            if (recipes.get(id) == null) {
                notes.add("§cпрофиль «" + id + "» в napitki.yml ни к чему не относится: "
                        + "в recipe-poilo.yml нет напитка с таким id");
            }
        }
        if (recipes.barrelDrinks().isEmpty()) {
            notes.add("§cбочковых напитков нет вообще: в recipe-poilo.yml ни у кого нет method: barrel");
        }
        if (!notes.isEmpty()) {
            lines.add("§8— замечания —");
            lines.addAll(notes);
        }
        lines.add("§8Годы пишутся как «до какого года»: 1, 2, 5, '*' (и дальше). "
                + "Ступени — tier из раздела quality.");
        return lines;
    }

    /** «1→1, 2→2, 5→5, 7→3, 8+→2». */
    private static String yearsText(BarrelDrinks.Profile profile) {
        StringBuilder text = new StringBuilder();
        for (BarrelDrinks.Years entry : profile.years()) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.upToYears() < 0 ? "старше" : String.valueOf(entry.upToYears()))
                    .append("→").append(entry.tier());
        }
        return text.length() == 0 ? "нет" : text.toString();
    }

    /** «1→1, 2→1, 3→2, 4→2, 5→3» — единицы за глоток по ступеням. */
    private static String drunkText(BarrelDrinks.Profile profile) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : new java.util.TreeMap<>(profile.drunk()).entrySet()) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.getKey()).append("→").append(entry.getValue());
        }
        return text.length() == 0 ? "не пьянит" : text.toString();
    }

    private void giveItem(Player player, ItemStack item) {
        if (item == null) {
            return;
        }

        var leftovers = player.getInventory().addItem(item);

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.equals(MAIN_TITLE)
                && !title.equals(ENCHANT_TITLE)
                && !title.equals(BLOCK_TITLE)
                && !title.equals(ITEM_TITLE)
                && !title.equals(DRINK_TITLE)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        String id = getId(clicked);

        if (id == null) {
            return;
        }

        if (!player.hasPermission("f8.admin")) return;
        if (title.equals(DRINK_TITLE) && drinkCatalog().contains(id)) {
            Inventory source = event.getView().getTopInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.hasPermission("f8.admin") && player.getOpenInventory().getTopInventory() == source) {
                    giveItem(player, drinkItem(id)); // Настоящий напиток с PDC, иначе меню-метка ломает шейкер.
                    player.closeInventory();
                }
            });
            return;
        }

        if (title.equals(ITEM_TITLE) && ITEM_CATALOG.contains(id)) {
            Inventory source = event.getView().getTopInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.hasPermission("f8.admin") && player.getOpenInventory().getTopInventory() == source) {
                    giveItem(player, catalogItem(id)); // Настоящий предмет с игровыми PDC/едой, без метки кнопки меню.
                    player.closeInventory();
                }
            });
            return;
        }

        switch (id) {
            case "enchants" -> openEnchantMenu(player);

            case "blocks" -> openBlockMenu(player);

            case "items" -> openItemMenu(player);

            case "drinks" -> openDrinkMenu(player);

            case "back_items" -> openItemMenu(player);

            case "back" -> openMainMenu(player);

            case "adaptation_1" -> {
                giveItem(
                        player,
                        createAdaptationBook(1)
                );
                player.closeInventory();
            }

            case "adaptation_2" -> {
                giveItem(
                        player,
                        createAdaptationBook(2)
                );
                player.closeInventory();
            }

            case "adaptation_3" -> {
                giveItem(
                        player,
                        createAdaptationBook(3)
                );
                player.closeInventory();
            }

            case "d20" -> {
                giveItem(
                        player,
                        createD20Book()
                );
                player.closeInventory();
            }

            case "rollback_totem" -> {
                giveItem(
                        player,
                        rollbackListener.createRollbackTotem()
                );
                player.closeInventory();
            }

            case "water_flask" -> {
                giveItem(
                        player,
                        flaskListener.createWaterFlask()
                );
                player.closeInventory();
            }

            case "poison_flask" -> {
                giveItem(
                        player,
                        flaskListener.createPoisonFlask()
                );
                player.closeInventory();
            }

            case "copper_note_1", "copper_note_2", "copper_note_3", "copper_note_4" -> {
                // Ступень — в предмете: поставленный блок сразу нужной ступени.
                giveItem(
                        player,
                        copperBlockListener.createCopperBlockItem(
                                id.charAt(id.length() - 1) - '0', false)
                );
                player.closeInventory();
            }

            case "copper_note_block" -> {
                giveItem(
                        player,
                        createCopperBlock()
                );
                player.closeInventory();
            }

            case "apvsh" -> {
                if (apvsh != null) {
                    giveItem(
                            player,
                            apvsh.createApvshBlockItem()
                    );
                }
                player.closeInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(MAIN_TITLE)
                || title.equals(ENCHANT_TITLE)
                || title.equals(BLOCK_TITLE)
                || title.equals(ITEM_TITLE)
                || title.equals(DRINK_TITLE)) {
            event.setCancelled(true);
        }
    }

    private ItemStack createAdaptationBook(int level) {
        ItemStack book = new ItemStack(
                Material.ENCHANTED_BOOK
        );

        ItemMeta meta = book.getItemMeta();

        if (meta == null) {
            return book;
        }

        String roman = switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            default -> "III";
        };

        meta.setDisplayName("§bЧародейская книга");

        meta.setLore(
                Collections.singletonList(
                        "§dАдаптация " + roman
                )
        );

        meta.setEnchantmentGlintOverride(true);

        book.setItemMeta(meta);

        return book;
    }

    private ItemStack createD20Book() {
        ItemStack book = new ItemStack(
                Material.ENCHANTED_BOOK
        );

        ItemMeta meta = book.getItemMeta();

        if (meta == null) {
            return book;
        }

        meta.setDisplayName("§bЧародейская книга");

        meta.setLore(
                Collections.singletonList(
                        "§dБросок I"
                )
        );

        meta.setEnchantmentGlintOverride(true);

        book.setItemMeta(meta);

        return book;
    }

    private ItemStack createCopperBlock() {
        return copperBlockListener.createCopperBlockItem();
    }

    // ===== ОПЬЯНЕНИЕ =====

    /**
     * {@code /f8 drunk} — своё состояние; {@code /f8 drunk <ник>} — чужое;
     * {@code /f8 drunk <ник|me> <число|clear>} — выставить (только админ).
     */
    private boolean drunk(CommandSender sender, String[] args) {
        DrunkManager manager = DrunkManager.current();
        if (manager == null) {
            sender.sendMessage("§cОпьянение ещё не загружено.");
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§7Использование: /f8 drunk <ник> <число|clear>");
                return true;
            }
            showDrunk(sender, manager, player);
            return true;
        }

        if (!sender.hasPermission("f8.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }

        // /f8 drunk <ник> — посмотреть чужую шкалу
        if (args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                if (sender instanceof Player self && isNumberOrClear(args[1])) {
                    applyDrunk(sender, manager, self, args[1]);
                    return true;
                }
                sender.sendMessage("§cИгрок " + args[1] + " не в сети.");
                return true;
            }
            showDrunk(sender, manager, target);
            return true;
        }

        Player target = "me".equalsIgnoreCase(args[1]) || "я".equalsIgnoreCase(args[1])
                ? (sender instanceof Player self ? self : null)
                : Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cИгрок " + args[1] + " не в сети.");
            return true;
        }
        applyDrunk(sender, manager, target, args[2]);
        return true;
    }

    private void showDrunk(CommandSender sender, DrunkManager manager, Player target) {
        int points = manager.points(target.getUniqueId());
        DrunkStage stage = DrunkStage.of(points, manager.scale());
        int hangover = manager.hangoverTicks(target.getUniqueId()) / 20;
        sender.sendMessage("§6Опьянение " + target.getName() + "§7: §f" + points + "/" + manager.scale()
                + " §7— §f" + stage.title() + (hangover > 0 ? " §8(похмелье " + hangover + " c)" : ""));
    }

    private void applyDrunk(CommandSender sender, DrunkManager manager, Player target, String raw) {
        if (raw.equalsIgnoreCase("clear") || raw.equalsIgnoreCase("трезв")) {
            manager.clear(target);
            sender.sendMessage("§a" + target.getName() + " протрезвел.");
            return;
        }
        try {
            int value = Integer.parseInt(raw);
            manager.set(target, value);
            sender.sendMessage("§aОпьянение " + target.getName() + ": "
                    + manager.points(target.getUniqueId()) + "/" + manager.scale());
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cНужно число, clear или ник игрока.");
        }
    }

    private static boolean isNumberOrClear(String raw) {
        if (raw.equalsIgnoreCase("clear") || raw.equalsIgnoreCase("трезв")) return true;
        try {
            Integer.parseInt(raw);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
