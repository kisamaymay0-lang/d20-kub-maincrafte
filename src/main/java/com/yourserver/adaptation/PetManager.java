package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.*;

public class PetManager implements Listener {

    /** Клиент сглаживает позицию и поворот между серверными обновлениями. */
    private static final int MOVEMENT_INTERPOLATION_TICKS = 2;
    /** Разные углы входа и выхода не дают режиму дёргаться на границе взгляда. */
    private static final double LOOK_ENTER_DOT = Math.cos(Math.toRadians(25.0));
    private static final double LOOK_EXIT_DOT = Math.cos(Math.toRadians(35.0));
    /** ±4 см, полный цикл вверх–вниз занимает 120 тиков (6 секунд). */
    private static final double HOVER_AMPLITUDE_BLOCKS = 0.04;
    private static final double HOVER_RADIANS_PER_TICK = Math.PI * 2.0 / 120.0;

    private final AdaptationPlugin plugin;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();
    private final Set<UUID> petsFacingPlayer = new HashSet<>();
    private long hoverTicks;

    public PetManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        ItemDisplay current = pets.get(uuid);
        if (current != null) {
            current.remove();
            pets.remove(uuid);
            petsFacingPlayer.remove(uuid);
            return;
        }
        petsFacingPlayer.remove(uuid);
        spawn(player);
    }

    private void spawn(Player player) {
        Location eye = player.getEyeLocation().clone();

        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey("f8resurs", "test-pets"));
            item.setItemMeta(meta);
        }

        World world = player.getWorld();
        ItemDisplay pet = world.spawn(eye, ItemDisplay.class);
        pet.setItemStack(item);
        pet.setGravity(false);
        pet.setInvulnerable(true);
        pet.setSilent(true);
        pet.setPersistent(true);
        pet.setTeleportDuration(MOVEMENT_INTERPOLATION_TICKS);
        pet.setInterpolationDuration(MOVEMENT_INTERPOLATION_TICKS);
        pet.setInterpolationDelay(0);

        pets.put(player.getUniqueId(), pet);
    }

    public void tick() {
        float hoverOffset = (float) (Math.sin(hoverTicks * HOVER_RADIANS_PER_TICK) * HOVER_AMPLITUDE_BLOCKS);
        hoverTicks++;

        for (UUID uuid : new ArrayList<>(pets.keySet())) {
            ItemDisplay pet = pets.get(uuid);
            Player player = Bukkit.getPlayer(uuid);

            if (player == null || !player.isOnline() || pet.isDead()) {
                if (pet != null) {
                    pet.remove();
                }
                pets.remove(uuid);
                petsFacingPlayer.remove(uuid);
                continue;
            }

            applyHover(pet, hoverOffset);

            Location eye = player.getEyeLocation();
            Location current = pet.getLocation();
            boolean facingPlayer = petsFacingPlayer.contains(uuid);

            if (isPetInView(eye, current, facingPlayer)) {
                petsFacingPlayer.add(uuid);
                facePlayer(pet, eye, current);
                continue;
            }
            petsFacingPlayer.remove(uuid);

            Vector forward = eye.getDirection().clone().setY(0).normalize();
            Vector right = forward.crossProduct(new Vector(0, 1, 0)).normalize();

            Location target = eye.clone()
                    .add(forward.clone().multiply(0.45))
                    .add(right.clone().multiply(0.55))
                    .add(0, -0.1, 0);

            Location next = current.add(
                    (target.getX() - current.getX()) * 0.12,
                    (target.getY() - current.getY()) * 0.12,
                    (target.getZ() - current.getZ()) * 0.12
            );

            next.setYaw(eye.getYaw());
            next.setPitch(eye.getPitch());
            pet.teleport(next);
        }
    }

    private static void applyHover(ItemDisplay pet, float hoverOffset) {
        Transformation transformation = pet.getTransformation();
        transformation.getTranslation().set(0.0f, hoverOffset, 0.0f);
        pet.setTransformation(transformation);
    }

    private static boolean isPetInView(Location eye, Location petLocation, boolean wasInView) {
        if (!eye.getWorld().equals(petLocation.getWorld())) {
            return false;
        }

        Vector toPet = petLocation.toVector().subtract(eye.toVector());
        if (toPet.lengthSquared() < 1.0E-6) {
            return wasInView;
        }

        double alignment = eye.getDirection().dot(toPet.normalize());
        double threshold = wasInView ? LOOK_EXIT_DOT : LOOK_ENTER_DOT;
        return alignment >= threshold;
    }

    private static void facePlayer(ItemDisplay pet, Location eye, Location current) {
        Location facing = current.clone();
        Vector toPlayer = eye.toVector().subtract(current.toVector());
        if (toPlayer.lengthSquared() >= 1.0E-6) {
            facing.setDirection(toPlayer);
        }
        pet.teleport(facing);
    }

    public void stopAll() {
        for (ItemDisplay pet : pets.values()) {
            pet.remove();
        }
        pets.clear();
        petsFacingPlayer.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ItemDisplay pet = pets.get(uuid);
        if (pet != null) {
            pet.remove();
            pets.remove(uuid);
        }
        petsFacingPlayer.remove(uuid);
    }
}
