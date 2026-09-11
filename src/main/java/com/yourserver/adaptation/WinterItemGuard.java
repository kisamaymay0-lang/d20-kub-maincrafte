package com.yourserver.adaptation;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Запрет стола/книг/переноса чаров и обновление прежнего оформления по событиям, без фонового сканирования. */
final class WinterItemGuard implements Listener {
    private final WinterItems items;

    WinterItemGuard(JavaPlugin plugin, WinterItems items) {
        this.items = items;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player.getInventory());
    }

    private boolean tool(ItemStack item) { return items.kind(item) == WinterItems.Kind.TOOL; }
    private boolean rune(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var lore = item.getItemMeta().lore();
        return lore != null && lore.stream().anyMatch(line -> WinterItems.rune(PlainTextComponentSerializer.plainText().serialize(line)));
    }

    private boolean forbidden(Inventory inventory, ItemStack result) {
        ItemStack left = inventory.getItem(0), right = inventory.getItem(1);
        if (!tool(left) && !tool(right) && !tool(result)) return false;
        return WinterRules.forbiddenEnchant(true,
                right != null && right.getType() == Material.ENCHANTED_BOOK,
                result != null && !result.getEnchantments().isEmpty(), rune(result));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepare(PrepareItemEnchantEvent event) { if (tool(event.getItem())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void enchant(EnchantItemEvent event) { if (tool(event.getItem())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void anvil(PrepareAnvilEvent event) {
        if (forbidden(event.getInventory(), event.getResult())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void takeAnvil(InventoryClickEvent event) {
        if (event.getView().getType() == InventoryType.ANVIL && event.getRawSlot() == 2
                && forbidden(event.getView().getTopInventory(), event.getCurrentItem())) event.setCancelled(true);
    }

    private void refresh(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (items.refresh(item)) inventory.setItem(slot, item);
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) { refresh(event.getPlayer().getInventory()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void open(InventoryOpenEvent event) {
        refresh(event.getInventory()); refresh(event.getPlayer().getInventory());
    }
    @EventHandler public void close(InventoryCloseEvent event) { refresh(event.getPlayer().getInventory()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (items.refresh(item)) event.getPlayer().getInventory().setItem(event.getNewSlot(), item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem(), off = event.getOffHandItem();
        if (items.refresh(main)) event.setMainHandItem(main);
        if (items.refresh(off)) event.setOffHandItem(off);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void use(PlayerInteractEvent event) {
        if (event.getHand() == null) return;
        var inventory = event.getPlayer().getInventory();
        ItemStack item = inventory.getItem(event.getHand());
        if (tool(item) && items.refresh(item)) inventory.setItem(event.getHand(), item);
    }

    @EventHandler
    public void equipment(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        for (EquipmentSlot slot : event.getEquipmentChanges().keySet()) {
            if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) continue;
            ItemStack item = player.getInventory().getItem(slot);
            if (items.refresh(item)) player.getInventory().setItem(slot, item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (items.refresh(item)) event.getItem().setItemStack(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void creative(InventoryCreativeEvent event) {
        ItemStack item = event.getCursor();
        if (items.refresh(item)) event.setCursor(item);
    }
}
