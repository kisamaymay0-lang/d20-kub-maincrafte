package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class F8Command implements CommandExecutor, Listener {

    private static final String MAIN_TITLE = "§8F8";
    private static final String ENCHANT_TITLE = "§8Новые чарки";
    private static final String BLOCK_TITLE = "§8Новые блоки";
    private static final String ITEM_TITLE = "§8Новые предметы";

    private final AdaptationPlugin plugin;
    private final DiceRollListener diceRollListener;
    private final FlaskListener flaskListener;
    private final RollbackListener rollbackListener;
    private final CopperBlockListener copperBlockListener;
    private final CaviarListener caviarListener;
    private final WinterFishing winterFishing;
    private final AncientJug ancientJug;

    private final NamespacedKey menuKey;

    public F8Command(
            AdaptationPlugin plugin,
            DiceRollListener diceRollListener,
            FlaskListener flaskListener,
            RollbackListener rollbackListener,
            CopperBlockListener copperBlockListener,
            CaviarListener caviarListener,
            WinterFishing winterFishing,
            AncientJug ancientJug
    ) {
        this.plugin = plugin;
        this.diceRollListener = diceRollListener;
        this.flaskListener = flaskListener;
        this.rollbackListener = rollbackListener;
        this.copperBlockListener = copperBlockListener;
        this.caviarListener = caviarListener;
        this.winterFishing = winterFishing;
        this.ancientJug = ancientJug;
        this.menuKey = new NamespacedKey(plugin, "f8_menu");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        // /f8 reload — перечитать config.yml (доступно и из консоли).
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("f8.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            String summary = plugin.reloadPluginSettings();
            sender.sendMessage("§aНастройки f8-plugin перечитаны из config.yml.");
            if (summary != null && !summary.isBlank()) sender.sendMessage(summary);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§7Использование: /f8 reload — перечитать config.yml");
            return true;
        }

        if (!player.hasPermission("f8.admin")) {
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

        inventory.setItem(
                13,
                createMenuItem(
                        Material.NOTE_BLOCK,
                        "§6Медный нотный блок",
                        List.of("§7Нажмите, чтобы получить блок"),
                        "copper_note_block"
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
            "icy_rime", "rime", "depleted_rime", "ice_caviar", "ice_caviar_sandwich", "ancient_jug");

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
            case "ancient_jug" -> ancientJug.createEmpty();
            default -> throw new IllegalArgumentException("Неизвестный предмет каталога");
        };
    }

    private void openItemMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 36, ITEM_TITLE);
        fill(inventory);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < ITEM_CATALOG.size(); i++) {
            String id = ITEM_CATALOG.get(i);
            inventory.setItem(slots[i], createTaggedItem(catalogItem(id), id));
        }
        inventory.setItem(31, createMenuItem(Material.ARROW, "§7Назад", Collections.emptyList(), "back"));
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
                && !title.equals(ITEM_TITLE)) {
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

            case "copper_note_block" -> {
                giveItem(
                        player,
                        createCopperBlock()
                );
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
                || title.equals(ITEM_TITLE)) {
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
}
