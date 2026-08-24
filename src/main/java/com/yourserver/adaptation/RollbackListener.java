package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class RollbackListener implements Listener {
    private static RollbackListener instance;
    private final JavaPlugin plugin;
    private final Map<UUID, Queue<Location>> positionHistory = new HashMap<>();
    private final Map<UUID, BukkitTask> saveTasks = new HashMap<>();
    private final String ROLLBACK_LORE = "§dОткат I";
    private final int HISTORY_SECONDS = 5;
    private final int SAVE_INTERVAL_TICKS = 10;

    public RollbackListener(JavaPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        startPositionSaver();
    }

    public static RollbackListener getInstance() {
        return instance;
    }

    private void startPositionSaver() {
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    savePlayerPosition(player);
                }
            }
        }.runTaskTimer(plugin, 0L, SAVE_INTERVAL_TICKS);
        saveTasks.put(UUID.randomUUID(), task);
    }

    private void savePlayerPosition(Player player) {
        UUID uuid = player.getUniqueId();
        Queue<Location> history = positionHistory.computeIfAbsent(uuid, k -> new LinkedList<>());
        history.add(player.getLocation().clone());
        int maxSize = HISTORY_SECONDS * (20 / SAVE_INTERVAL_TICKS);
        while (history.size() > maxSize) history.poll();
    }

    private boolean hasRollbackLore(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        for (String line : meta.getLore()) {
            if (ChatColor.stripColor(line).trim().equals("Откат I")) return true;
        }
        return false;
    }

    private ItemStack findRollbackTotem(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (hasRollbackLore(mainHand)) return mainHand;

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (hasRollbackLore(offHand)) return offHand;

        for (ItemStack item : player.getInventory().getContents()) {
            if (hasRollbackLore(item)) return item;
        }
        return null;
    }

    public ItemStack createRollbackTotem() {
        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = totem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Тотем Отката");
            meta.setLore(Collections.singletonList(ROLLBACK_LORE));
            meta.setEnchantmentGlintOverride(true);
            totem.setItemMeta(meta);
        }
        return totem;
    }

    public boolean giveRollbackTotem(Player target) {
        if (target == null) return false;

        ItemStack totem = createRollbackTotem();

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), totem);
        } else {
            target.getInventory().addItem(totem);
        }
        return true;
    }

    @EventHandler
    public void onTotemUse(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack rollbackTotem = findRollbackTotem(player);
        if (rollbackTotem == null) return;

        UUID uuid = player.getUniqueId();
        Queue<Location> history = positionHistory.get(uuid);
        if (history == null || history.isEmpty()) return;

        Location rollbackPos = history.poll();
        if (rollbackPos == null || rollbackPos.getY() < 0) {
            World world = player.getWorld();
            rollbackPos = world.getSpawnLocation();
        }

        Location currentPos = player.getLocation();
        player.getWorld().spawnParticle(Particle.PORTAL, currentPos, 30, 0.5, 0.5, 0.5, 0.1);
        player.getWorld().playSound(currentPos, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);

        Location finalPos = rollbackPos.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.teleport(finalPos, PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.getWorld().spawnParticle(Particle.PORTAL, finalPos, 50, 0.5, 0.5, 0.5, 0.2);
            player.getWorld().playSound(finalPos, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        positionHistory.remove(uuid);
    }

    public void disable() {
        positionHistory.clear();
        saveTasks.values().forEach(BukkitTask::cancel);
        saveTasks.clear();
        if (instance == this) instance = null;
    }
}
