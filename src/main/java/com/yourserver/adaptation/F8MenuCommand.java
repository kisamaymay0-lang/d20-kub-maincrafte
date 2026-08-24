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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class F8MenuCommand implements CommandExecutor, Listener {
    private static final String MAIN_TITLE = "§6F8";
    private static final String ENCHANT_TITLE = "§6Новые чарки";
    private static final String BLOCK_TITLE = "§6Новые блоки";
    private static final String ITEM_TITLE = "§6Новые предметы";

    private final JavaPlugin plugin;
    private final NamespacedKey menuKey;
    private final NamespacedKey itemKey;
    private final CopperBlockListener copperBlockListener;

    public F8MenuCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "f8_menu_item");
        this.itemKey = new NamespacedKey(plugin, "f8_item_id");
        this.copperBlockListener = new CopperBlockListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(copperBlockListener, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
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
        fillBackground(inventory);
        inventory.setItem(10, menuItem(Material.ENCHANTED_BOOK, "§dНовые чарки", "enchantments"));
        inventory.setItem(13, menuItem(Material.NOTE_BLOCK, "§6Новые блоки", "blocks"));
        inventory.setItem(16, menuItem(Material.POTION, "§fНовые предметы", "items"));
        player.openInventory(inventory);
    }

    private void openEnchantments(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ENCHANT_TITLE);
        fillBackground(inventory);

        inventory.setItem(10, createAdaptationBook(1));
        inventory.setItem(12, createAdaptationBook(2));
        inventory.setItem(14, createAdaptationBook(3));
        inventory.setItem(16, createD20Book());
        inventory.setItem(22, createRollbackTotem());
        inventory.setItem(49, menuItem(Material.ARROW, "§fНазад", "back"));

        player.openInventory(inventory);
    }

    private void openBlocks(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, BLOCK_TITLE);
        fillBackground(inventory);
        inventory.setItem(22, copperBlockListener.createCopperBlockItem());
        inventory.setItem(49, menuItem(Material.ARROW, "§fНазад", "back"));
        player.openInventory(inventory);
    }

    private void openItems(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, ITEM_TITLE);
        fillBackground(inventory);

        FlaskListener flaskListener = FlaskListener.getInstance();
        if (flaskListener != null) {
            inventory.setItem(20, flaskListener.createWaterFlask());
            inventory.setItem(24, flaskListener.createPoisonFlask());
        }

        inventory.setItem(49, menuItem(Material.ARROW, "§fНазад", "back"));
        player.openInventory(inventory);
    }

    private ItemStack createAdaptationBook(int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            String roman = level == 1 ? "I" : level == 2 ? "II" : "III";
            meta.setDisplayName("§bЧародейская книга");
            meta.setLore(Collections.singletonList("§dАдаптация " + roman));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "adaptation_" + level);
            book.setItemMeta(meta);
        }
        return book;
    }

    private ItemStack createD20Book() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bЧародейская книга");
            meta.setLore(Collections.singletonList("§dБросок I"));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "d20");
            book.setItemMeta(meta);
        }
        return book;
    }

    private ItemStack createRollbackTotem() {
        RollbackListener rollbackListener = RollbackListener.getInstance();
        if (rollbackListener != null) {
            ItemStack totem = rollbackListener.createRollbackTotem();
            ItemMeta meta = totem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "rollback");
                totem.setItemMeta(meta);
            }
            return totem;
        }
        return new ItemStack(Material.TOTEM_OF_UNDYING);
    }

    private ItemStack menuItem(Material material, String name, String id) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBackground(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(MAIN_TITLE) && !title.equals(ENCHANT_TITLE) && !title.equals(BLOCK_TITLE) && !title.equals(ITEM_TITLE)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() == InventoryType.PLAYER) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String menuId = meta.getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        String itemId = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);

        if (title.equals(MAIN_TITLE) && menuId != null) {
            switch (menuId) {
                case "enchantments" -> openEnchantments(player);
                case "blocks" -> openBlocks(player);
                case "items" -> openItems(player);
            }
            return;
        }

        if (menuId != null && menuId.equals("back")) {
            openMainMenu(player);
            return;
        }

        if (itemId != null) {
            giveSelectedItem(player, clicked);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(MAIN_TITLE) || title.equals(ENCHANT_TITLE) || title.equals(BLOCK_TITLE) || title.equals(ITEM_TITLE)) {
            event.setCancelled(true);
        }
    }

    private void giveSelectedItem(Player player, ItemStack template) {
        ItemStack give = template.clone();
        ItemMeta meta = give.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(menuKey);
            meta.getPersistentDataContainer().remove(itemKey);
            give.setItemMeta(meta);
        }
        give.setAmount(1);
        player.getInventory().addItem(give).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
