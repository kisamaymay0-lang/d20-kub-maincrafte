package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PetManager implements Listener {

    private final AdaptationPlugin plugin;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();
    private final Map<UUID, Boolean> modelTwo = new HashMap<>();
    private final Map<UUID, Location> previous = new HashMap<>();

    public PetManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        ItemDisplay current = pets.get(uuid);
        if (current != null) {
            current.remove();
            pets.remove(uuid);
            modelTwo.remove(uuid);
            previous.remove(uuid);
            return;
        }
        spawn(player);
    }

    private void spawn(Player player) {
        Location eye = player.getEyeLocation().clone();

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(1);
            item.setItemMeta(meta);
        }

        ItemDisplay pet = player.getWorld().spawn(eye, ItemDisplay.class);
        pet.setItemStack(item);
        pet.setGravity(false);
        pet.setInvulnerable(true);
        pet.setSilent(true);
        pet.setPersistent(true);

        pets.put(player.getUniqueId(), pet);
        modelTwo.put(player.getUniqueId(), false);
        previous.put(player.getUniqueId(), eye.clone());
    }

    public void tick() {
        for (UUID uuid : new ArrayList<>(pets.keySet())) {
            ItemDisplay pet = pets.get(uuid);
            Player player = Bukkit.getPlayer(uuid);

            if (player == null || !player.isOnline() || pet.isDead()) {
                if (pet != null) {
                    pet.remove();
                }
                pets.remove(uuid);
                modelTwo.remove(uuid);
                previous.remove(uuid);
                continue;
            }

            Location target = player.getEyeLocation().clone();
            target.add(target.getDirection().multiply(0.35));
            target.add(0, 0.15, 0);

            Location prev = previous.getOrDefault(uuid, target.clone());
            prev = prev.add(
                    (target.getX() - prev.getX()) * 0.2,
                    (target.getY() - prev.getY()) * 0.2,
                    (target.getZ() - prev.getZ()) * 0.2
            );

            double bob = Math.sin(System.currentTimeMillis() / 150.0) * 0.12;
            prev.add(0, bob, 0);

            pet.teleport(prev);
            pet.setRotation(player.getLocation().getYaw(), player.getLocation().getPitch());

            previous.put(uuid, prev);
        }
    }

    public void switchModel() {
        for (Map.Entry<UUID, ItemDisplay> entry : pets.entrySet()) {
            UUID uuid = entry.getKey();
            ItemDisplay pet = entry.getValue();
            boolean isTwo = modelTwo.getOrDefault(uuid, false);
            boolean next = !isTwo;
            modelTwo.put(uuid, next);

            ItemStack item = pet.getItemStack();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(next ? 2 : 1);
                item.setItemMeta(meta);
            }
            pet.setItemStack(item);
        }
    }

    public void stopAll() {
        for (ItemDisplay pet : pets.values()) {
            pet.remove();
        }
        pets.clear();
        modelTwo.clear();
        previous.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ItemDisplay pet = pets.get(uuid);
        if (pet != null) {
            pet.remove();
            pets.remove(uuid);
            modelTwo.remove(uuid);
            previous.remove(uuid);
        }
    }
}
