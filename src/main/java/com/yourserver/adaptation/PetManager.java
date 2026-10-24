package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;
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
                continue;
            }

            Location eye = player.getEyeLocation();

            // Направление взгляда игрока по горизонтали
            Vector forward = eye.getDirection().clone().setY(0).normalize();
            // Вектор вправо (перпендикулярно направлению взгляда)
            Vector right = forward.crossProduct(new Vector(0, 1, 0)).normalize();

            // Цель: перед игроком, правее (от первого лица) и чуть ниже глаз
            Location target = eye.clone()
                    .add(forward.multiply(0.45))
                    .add(right.multiply(0.55))
                    .add(0, -0.1, 0);

            Vector toTarget = target.subtract(pet.getLocation()).toVector();
            double dist = toTarget.length();

            // Плавное движение через клиентское ускорение, а не телепорт
            if (dist > 0.05) {
                if (dist > 1.0) {
                    toTarget.normalize().multiply(1.0);
                }
                pet.setVelocity(toTarget);
            } else {
                pet.setVelocity(new Vector(0, 0, 0));
            }

            // Поворачиваем питомца лицом к игроку
            pet.setRotation(player.getLocation().getYaw(), player.getLocation().getPitch());
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
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ItemDisplay pet = pets.get(uuid);
        if (pet != null) {
            pet.remove();
            pets.remove(uuid);
            modelTwo.remove(uuid);
        }
    }
}
