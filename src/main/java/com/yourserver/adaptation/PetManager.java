package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PetManager implements Listener {

    private final AdaptationPlugin plugin;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();

    public PetManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        ItemDisplay current = pets.get(uuid);
        if (current != null) {
            current.remove();
            pets.remove(uuid);
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
                continue;
            }

            Location eye = player.getEyeLocation();

            // Направление взгляда игрока по горизонтали
            Vector forward = eye.getDirection().clone().setY(0).normalize();
            // Вектор вправо (перпендикулярно направлению взгляда, от первого лица)
            Vector right = forward.crossProduct(new Vector(0, 1, 0)).normalize();

            // Цель: перед игроком, правее (от первого лица) и чуть ниже глаз
            Location target = eye.clone()
                    .add(forward.clone().multiply(0.45))
                    .add(right.clone().multiply(0.55))
                    .add(0, -0.1, 0);

            // Плавное движение: очень медленная интерполяция (15 % за тик)
            Location current = pet.getLocation();
            Location next = current.add(
                    (target.getX() - current.getX()) * 0.15,
                    (target.getY() - current.getY()) * 0.15,
                    (target.getZ() - current.getZ()) * 0.15
            );

            pet.teleport(next);

            // Поворачиваем питомца лицом к игроку
            pet.setRotation(player.getLocation().getYaw(), player.getLocation().getPitch());
        }
    }

    public void stopAll() {
        for (ItemDisplay pet : pets.values()) {
            pet.remove();
        }
        pets.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ItemDisplay pet = pets.get(uuid);
        if (pet != null) {
            pet.remove();
            pets.remove(uuid);
        }
    }
}
