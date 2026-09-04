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

    private final NamespacedKey menuKey;

    public F8Command(
            AdaptationPlugin plugin,
            DiceRollListener diceRollListener,
            FlaskListener flaskListener,
            RollbackListener rollbackListener,
            CopperBlockListener copperBlockListener
    ) {
        this.plugin = plugin;
        this.diceRollListener = diceRollListener;
        this.flaskListener = flaskListener;
        this.rollbackListener = rollbackListener;
        this.copperBlockListener = copperBlockListener;
        this.menuKey = new NamespacedKey(plugin, "f8_menu");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
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
                rollbackListener.createRollbackTotem()
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

    private void openItemMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                ITEM_TITLE
        );

        fill(inventory);

        inventory.setItem(
                11,
                createFlaskMenuItem(
                        flaskListener.createWaterFlask(),
                        "water_flask"
                )
        );

        inventory.setItem(
                15,
                createFlaskMenuItem(
                        flaskListener.createPoisonFlask(),
                        "poison_flask"
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

    private ItemStack createFlaskMenuItem(
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
        ItemStack filler = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE
        );

        ItemMeta meta = filler.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
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
