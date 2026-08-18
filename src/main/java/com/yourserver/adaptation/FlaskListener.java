package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class FlaskListener implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Map<Integer, FlaskData>> activeFlasks = new HashMap<>();
    private final Map<UUID, BukkitTask> updateTasks = new HashMap<>();

    public FlaskListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static class FlaskData {
        String type;
        int duration;
        BukkitTask task;

        FlaskData(String type, int duration) {
            this.type = type;
            this.duration = duration;
        }
    }

    private boolean isFlask(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return false;
        String name = ChatColor.stripColor(meta.getDisplayName());
        return name.equals("Флакон с водой") || name.equals("Флакон с отравлением");
    }

    private String getFlaskType(ItemStack item) {
        if (!isFlask(item)) return null;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (name.equals("Флакон с водой")) return "water";
        if (name.equals("Флакон с отравлением")) return "poison";
        return null;
    }

    private boolean hasFlaskEffect(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        for (String line : item.getItemMeta().getLore()) {
            if (line.contains("Отравление I")) return true;
        }
        return false;
    }

    private void applyFlaskToSword(Player player, ItemStack flask, ItemStack sword) {
        if (sword == null || !sword.getType().name().contains("SWORD")) {
            player.sendMessage(ChatColor.RED + "Флакон можно нанести только на меч!");
            return;
        }

        if (hasFlaskEffect(sword)) {
            player.sendMessage(ChatColor.RED + "На этом мече уже есть эффект флакона!");
            return;
        }

        String flaskType = getFlaskType(flask);
        if (flaskType == null) return;

        if (flaskType.equals("water")) {
            player.sendMessage(ChatColor.RED + "Этот меч не имеет эффекта флакона!");
            return;
        }

        if (flaskType.equals("poison")) {
            flask.setAmount(flask.getAmount() - 1);

            ItemMeta meta = sword.getItemMeta();
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add("§2Отравление I §f- §a3:00");
            meta.setLore(lore);
            sword.setItemMeta(meta);

            int slot = player.getInventory().getHeldItemSlot();
            UUID uuid = player.getUniqueId();
            activeFlasks.putIfAbsent(uuid, new HashMap<>());
            FlaskData data = new FlaskData("poison", 180);
            activeFlasks.get(uuid).put(slot, data);

            if (!updateTasks.containsKey(uuid)) {
                startUpdateTask(player);
            }

            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            // Исправлено: используем CRIT (гарантированно работает)
            player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.1);
            player.sendMessage(ChatColor.GREEN + "Вы нанесли флакон отравления на меч! Длительность: 3 минуты.");
        }
    }

    private void removeFlaskFromSword(Player player, ItemStack sword) {
        if (sword == null || !sword.getType().name().contains("SWORD")) {
            player.sendMessage(ChatColor.RED + "Флакон можно нанести только на меч!");
            return;
        }

        if (!hasFlaskEffect(sword)) {
            player.sendMessage(ChatColor.RED + "На этом мече нет эффекта флакона!");
            return;
        }

        ItemMeta meta = sword.getItemMeta();
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.removeIf(line -> line.contains("Отравление I"));
        meta.setLore(lore);
        sword.setItemMeta(meta);

        int slot = player.getInventory().getHeldItemSlot();
        UUID uuid = player.getUniqueId();
        if (activeFlasks.containsKey(uuid)) {
            activeFlasks.get(uuid).remove(slot);
            if (activeFlasks.get(uuid).isEmpty()) {
                activeFlasks.remove(uuid);
                if (updateTasks.containsKey(uuid)) {
                    updateTasks.get(uuid).cancel();
                    updateTasks.remove(uuid);
                }
            }
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
        // Исправлено: используем SMOKE (гарантированно работает)
        player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.1);
        player.sendMessage(ChatColor.GREEN + "Вы смыли эффект флакона с меча!");
    }

    private void startUpdateTask(Player player) {
        UUID uuid = player.getUniqueId();
        updateTasks.put(uuid, new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                Map<Integer, FlaskData> flasks = activeFlasks.get(uuid);
                if (flasks == null || flasks.isEmpty()) {
                    this.cancel();
                    updateTasks.remove(uuid);
                    return;
                }

                List<Integer> toRemove = new ArrayList<>();
                for (Map.Entry<Integer, FlaskData> entry : flasks.entrySet()) {
                    int slot = entry.getKey();
                    FlaskData data = entry.getValue();

                    data.duration--;

                    ItemStack item = p.getInventory().getItem(slot);
                    if (item == null || !item.getType().name().contains("SWORD")) {
                        toRemove.add(slot);
                        continue;
                    }

                    ItemMeta meta = item.getItemMeta();
                    if (meta == null || !meta.hasLore()) {
                        toRemove.add(slot);
                        continue;
                    }

                    List<String> lore = meta.getLore();
                    for (int i = 0; i < lore.size(); i++) {
                        if (lore.get(i).contains("Отравление I")) {
                            int minutes = data.duration / 60;
                            int seconds = data.duration % 60;
                            String timeStr = String.format("%02d:%02d", minutes, seconds);
                            lore.set(i, "§2Отравление I §f- §a" + timeStr);
                            break;
                        }
                    }
                    meta.setLore(lore);
                    item.setItemMeta(meta);

                    if (data.duration <= 0) {
                        toRemove.add(slot);
                        lore.removeIf(line -> line.contains("Отравление I"));
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                        p.sendMessage(ChatColor.RED + "Эффект флакона на мече закончился!");
                        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
                        p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.05);
                    }
                }

                for (int slot : toRemove) {
                    flasks.remove(slot);
                }

                if (flasks.isEmpty()) {
                    activeFlasks.remove(uuid);
                    this.cancel();
                    updateTasks.remove(uuid);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!event.isRightClick()) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().getType() != InventoryType.PLAYER) return;

        ItemStack cursor = event.getCursor();
        if (!isFlask(cursor)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.getType().name().contains("SWORD")) return;

        event.setCancelled(true);
        String flaskType = getFlaskType(cursor);

        if (flaskType.equals("water")) {
            removeFlaskFromSword(player, clicked);
            cursor.setAmount(cursor.getAmount() - 1);
            if (cursor.getAmount() <= 0) {
                event.setCursor(null);
            }
        } else if (flaskType.equals("poison")) {
            if (hasFlaskEffect(clicked)) {
                player.sendMessage(ChatColor.RED + "На этом мече уже есть эффект флакона!");
                return;
            }
            applyFlaskToSword(player, cursor, clicked);
            if (cursor.getAmount() <= 0) {
                event.setCursor(null);
            }
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (activeFlasks.containsKey(uuid)) {
            int oldSlot = event.getPreviousSlot();
            int newSlot = event.getNewSlot();

            if (activeFlasks.get(uuid).containsKey(oldSlot)) {
                FlaskData data = activeFlasks.get(uuid).remove(oldSlot);
                activeFlasks.get(uuid).put(newSlot, data);

                ItemStack newItem = player.getInventory().getItem(newSlot);
                if (newItem != null && newItem.getType().name().contains("SWORD")) {
                    ItemMeta meta = newItem.getItemMeta();
                    if (meta != null) {
                        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                        int minutes = data.duration / 60;
                        int seconds = data.duration % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);
                        boolean hasLore = false;
                        for (String line : lore) {
                            if (line.contains("Отравление I")) {
                                hasLore = true;
                                break;
                            }
                        }
                        if (!hasLore) {
                            lore.add("§2Отравление I §f- §a" + timeStr);
                        } else {
                            for (int i = 0; i < lore.size(); i++) {
                                if (lore.get(i).contains("Отравление I")) {
                                    lore.set(i, "§2Отравление I §f- §a" + timeStr);
                                    break;
                                }
                            }
                        }
                        meta.setLore(lore);
                        newItem.setItemMeta(meta);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activeFlasks.remove(uuid);
        if (updateTasks.containsKey(uuid)) {
            updateTasks.get(uuid).cancel();
            updateTasks.remove(uuid);
        }
    }

    public void disable() {
        activeFlasks.clear();
        for (BukkitTask task : updateTasks.values()) {
            task.cancel();
        }
        updateTasks.clear();
    }

    public boolean giveFlask(Player player, String type, int amount) {
        if (player == null) return false;

        ItemStack flask = new ItemStack(Material.POTION);
        ItemMeta meta = flask.getItemMeta();

        if (type.equalsIgnoreCase("water")) {
            meta.setDisplayName("§bФлакон с водой");
            meta.setLore(Arrays.asList("§7Смывает эффекты флаконов с меча"));
            meta.setCustomModelData(1001);
        } else if (type.equalsIgnoreCase("poison")) {
            meta.setDisplayName("§aФлакон с отравлением");
            meta.setLore(Arrays.asList("§7Наносит отравление на меч на 3 минуты"));
            meta.setCustomModelData(1002);
        } else {
            return false;
        }

        flask.setItemMeta(meta);
        flask.setAmount(Math.min(amount, 64));

        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItem(player.getLocation(), flask);
            player.sendMessage(ChatColor.RED + "Ваш инвентарь полон! Флакон упал на землю.");
        } else {
            player.getInventory().addItem(flask);
        }

        return true;
    }
}
