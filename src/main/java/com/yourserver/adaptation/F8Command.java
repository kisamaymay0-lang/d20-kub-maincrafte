package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

public class F8Command implements CommandExecutor, Listener {

    private static final String TITLE = "§8§lМеню F8";

    private final AdaptationPlugin plugin;

    public F8Command(AdaptationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("f8.admin")) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        openMenu(player);
        return true;
    }

    private void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                TITLE
        );

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();

        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ItemStack adaptation = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta adaptationMeta = adaptation.getItemMeta();

        if (adaptationMeta != null) {
            adaptationMeta.setDisplayName("§b§lАдаптация");
            adaptationMeta.setLore(Collections.singletonList(
                    "§7Система адаптации"
            ));
            adaptation.setItemMeta(adaptationMeta);
        }

        inventory.setItem(11, adaptation);

        ItemStack dice = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta diceMeta = dice.getItemMeta();

        if (diceMeta != null) {
            diceMeta.setDisplayName("§d§lD20");
            diceMeta.setLore(Collections.singletonList(
                    "§7Система броска кубика"
            ));
            dice.setItemMeta(diceMeta);
        }

        inventory.setItem(13, dice);

        ItemStack flask = new ItemStack(Material.POTION);
        ItemMeta flaskMeta = flask.getItemMeta();

        if (flaskMeta != null) {
            flaskMeta.setDisplayName("§5§lФлаконы");
            flaskMeta.setLore(Collections.singletonList(
                    "§7Дополнительные возможности"
            ));
            flask.setItemMeta(flaskMeta);
        }

        inventory.setItem(15, flask);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);
    }
}
