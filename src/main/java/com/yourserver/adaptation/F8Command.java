package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
    private final FlaskListener flaskListener;
    private final RollbackListener rollbackListener;

    private final NamespacedKey menuItemKey;

    public F8Command(
            AdaptationPlugin plugin,
            FlaskListener flaskListener,
            RollbackListener rollbackListener
    ) {
        this.plugin = plugin;
        this.flaskListener = flaskListener;
        this.rollbackListener = rollbackListener;
        this.menuItemKey = new NamespacedKey(plugin, "f8_menu_item");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
            return true;
        }

        if (!player.hasPermission("f8.admin")) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, MAIN_TITLE);

        ItemStack filler = createItem(
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                Collections.emptyList(),
                null
        );

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(
                10,
                createItem(
                        Material.ENCHANTED_BOOK,
                        "§dНовые чарки",
                        List.of("§7Новые зачарования и их предметы"),
                        "enchants"
                )
        );

        inventory.setItem(
                13,
                createItem(
                        Material.NOTE_BLOCK,
                        "§6Новые блоки",
                        List.of("§7Новые блоки плагина"),
                        "blocks"
                )
        );

        inventory.setItem(
                16,
                createItem(
                        Material.POTION,
                        "§bНовые предметы",
                        List.of("§7Предметы, не относящиеся к другим разделам"),
                        "items"
                )
        );

        player.openInventory(inventory);
    }

    private void openEnchantMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ENCHANT_TITLE);

        fill(inventory);

        ItemStack d20 = createItem(
                Material.ENCHANTED_BOOK,
                "§dБросок I",
                List.of(
                        "§7Наложение зачарования",
                        "§7«Бросок I»"
                ),
                "d20"
        );

        ItemStack rollback = rollbackListener.createRollbackTotem();
        ItemMeta rollbackMeta = rollback.getItemMeta();

        if (rollbackMeta != null) {
            rollbackMeta.getPersistentDataContainer().set(
                    menuItemKey,
                    PersistentDataType.STRING,
                    "rollback"
            );
            rollback.setItemMeta(rollbackMeta);
        }

        inventory.setItem(11, d20);
        inventory.setItem(15, rollback);

        inventory.setItem(
                22,
                createItem(
                        Material.ARROW,
                        "§7Назад",
                        Collections.emptyList(),
                        "back"
                )
        );

        player.openInventory(inventory);
    }

    private void openBlockMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, BLOCK_TITLE);

        fill(inventory);

        ItemStack copperNoteBlock = createItem(
                Material.NOTE_BLOCK,
                "§6Медный нотный блок",
                List.of(
                        "§7Новый блок"
                ),
                "copper_note_block"
        );

        inventory.setItem(13, copperNoteBlock);

        inventory.setItem(
                22,
                createItem(
                        Material.ARROW,
                        "§7Назад",
                        Collections.emptyList(),
                        "back"
                )
        );

        player.openInventory(inventory);
    }

    private void openItemMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, ITEM_TITLE);

        fill(inventory);

        inventory.setItem(
                11,
                createItem(
                        Material.POTION,
                        "§fФлакон с водой",
                        List.of(
                                "§7Очищает меч от отравления",
                                "§7Количество: 1"
                        ),
                        "water_flask"
                )
        );

        inventory.setItem(
                15,
                createItem(
                        Material.POTION,
                        "§fФлакон с отравлением",
                        List.of(
                                "§7Наносит отравление на меч",
                                "§7Количество: 1"
                        ),
                        "poison_flask"
                )
        );

        inventory.setItem(
                22,
                createItem(
                        Material.ARROW,
                        "§7Назад",
                        Collections.emptyList(),
                        "back"
                )
        );

        player.openInventory(inventory);
    }

    private void fill(Inventory inventory) {
        ItemStack filler = createItem(
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                Collections.emptyList(),
                null
        );

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack createItem(
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
                    menuItemKey,
                    PersistentDataType.STRING,
                    id
            );
        }

        item.setItemMeta(meta);
        return item;
    }

    private String getMenuId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(
                menuItemKey,
                PersistentDataType.STRING
        );
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
        String id = getMenuId(clicked);

        if (id == null) {
            return;
        }

        switch (id) {
            case "enchants" -> openEnchantMenu(player);

            case "blocks" -> openBlockMenu(player);

            case "items" -> openItemMenu(player);

            case "back" -> openMainMenu(player);

            case "water_flask" -> {
                flaskListener.giveFlask(player, "water", 1);
                player.closeInventory();
            }

            case "poison_flask" -> {
                flaskListener.giveFlask(player, "poison", 1);
                player.closeInventory();
            }

            case "d20" -> {
                player.getInventory().addItem(createD20Book());
                player.closeInventory();
            }

            case "rollback" -> {
                player.getInventory().addItem(
                        rollbackListener.createRollbackTotem()
                );
                player.closeInventory();
            }

            case "copper_note_block" -> {
                player.getInventory().addItem(
                        createCopperNoteBlock()
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

    private ItemStack createD20Book() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§dБросок I");
            meta.setLore(Collections.singletonList("§dБросок I"));
            meta.setEnchantmentGlintOverride(true);
            book.setItemMeta(meta);
        }

        return book;
    }

    private ItemStack createCopperNoteBlock() {
        ItemStack block = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = block.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§6Медный нотный блок");
            meta.setItemModel(
                    new NamespacedKey(
                            "f8resurs",
                            "copper_note_block"
                    )
            );
            block.setItemMeta(meta);
        }

        return block;
    }
}
