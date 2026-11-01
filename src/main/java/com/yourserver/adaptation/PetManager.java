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
    private static final double FOLLOW_STRENGTH = 0.12;
    /** Поворот идёт с инерцией, а не мгновенно вслед за камерой. */
    private static final double ROTATION_STRENGTH = 0.08;
    /** Во время возвращения взгляд игрока полностью игнорируется. */
    private static final int RETURN_DURATION_TICKS = 30;
    /** На таком расстоянии питомец прекращает стоять и возвращается к игроку. */
    private static final double RETURN_DISTANCE_SQUARED = 4.0 * 4.0;

    private final AdaptationPlugin plugin;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();
    private final Set<UUID> petsFacingPlayer = new HashSet<>();
    private final Map<UUID, ReturnMovement> petsReturning = new HashMap<>();
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
            clearState(uuid);
            return;
        }
        clearState(uuid);
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
                clearState(uuid);
                continue;
            }

            applyHover(pet, hoverOffset);

            Location eye = player.getEyeLocation();
            Location current = pet.getLocation();

            ReturnMovement returning = petsReturning.get(uuid);
            if (returning != null) {
                updateReturning(pet, current, returning);
                if (returning.isFinished()) {
                    petsReturning.remove(uuid);
                }
                continue;
            }

            boolean facingPlayer = petsFacingPlayer.contains(uuid);
            if (facingPlayer) {
                boolean playerWalkedAway = eye.getWorld().equals(current.getWorld())
                        && eye.distanceSquared(current) >= RETURN_DISTANCE_SQUARED;
                if (!playerWalkedAway && isPetInView(eye, current, true)) {
                    facePlayer(pet, eye, current);
                    continue;
                }

                // Отвод взгляда или отход на 4 блока запускает одно и то же
                // возвращение. До его конца камера не двигает цель и не может
                // снова включить режим взгляда.
                petsFacingPlayer.remove(uuid);
                petsReturning.put(uuid, new ReturnMovement(current, followPose(eye)));
                continue;
            }

            if (isPetInView(eye, current, false)) {
                petsFacingPlayer.add(uuid);
                facePlayer(pet, eye, current);
                continue;
            }

            follow(pet, current, followPose(eye));
        }
    }

    private static Location followPose(Location eye) {
        Vector forward = eye.getDirection().clone().setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            forward = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
        } else {
            forward.normalize();
        }
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        Location target = eye.clone()
                .add(forward.clone().multiply(0.45))
                .add(right.clone().multiply(0.55))
                .add(0, -0.1, 0);
        target.setYaw(eye.getYaw());
        target.setPitch(eye.getPitch());
        return target;
    }

    private static void follow(ItemDisplay pet, Location current, Location target) {
        Location next = current.clone();
        next.setX(current.getX() + (target.getX() - current.getX()) * FOLLOW_STRENGTH);
        next.setY(current.getY() + (target.getY() - current.getY()) * FOLLOW_STRENGTH);
        next.setZ(current.getZ() + (target.getZ() - current.getZ()) * FOLLOW_STRENGTH);
        rotateTowards(current, next, target.getYaw(), target.getPitch());
        pet.teleport(next);
    }

    private static void updateReturning(ItemDisplay pet, Location current, ReturnMovement movement) {
        movement.ticks++;
        double progress = Math.min(1.0, movement.ticks / (double) RETURN_DURATION_TICKS);
        // Smoothstep: спокойный старт, небольшой разгон и мягкое завершение.
        double eased = progress * progress * (3.0 - 2.0 * progress);

        Location start = movement.start;
        Location target = movement.target;
        Location next = start.clone();
        next.setX(start.getX() + (target.getX() - start.getX()) * eased);
        next.setY(start.getY() + (target.getY() - start.getY()) * eased);
        next.setZ(start.getZ() + (target.getZ() - start.getZ()) * eased);
        rotateTowards(current, next, target.getYaw(), target.getPitch());
        pet.teleport(next);
    }

    private static void facePlayer(ItemDisplay pet, Location eye, Location current) {
        Location facing = current.clone();
        Vector toPlayer = eye.toVector().subtract(current.toVector());
        if (toPlayer.lengthSquared() >= 1.0E-6) {
            Location desired = current.clone();
            desired.setDirection(toPlayer);
            rotateTowards(current, facing, desired.getYaw(), desired.getPitch());
        }
        pet.teleport(facing);
    }

    private static void rotateTowards(Location current, Location next, float targetYaw, float targetPitch) {
        float yawDelta = (targetYaw - current.getYaw()) % 360.0f;
        if (yawDelta > 180.0f) {
            yawDelta -= 360.0f;
        } else if (yawDelta < -180.0f) {
            yawDelta += 360.0f;
        }

        next.setYaw(current.getYaw() + (float) (yawDelta * ROTATION_STRENGTH));
        next.setPitch(current.getPitch() + (float) ((targetPitch - current.getPitch()) * ROTATION_STRENGTH));
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

    private void clearState(UUID uuid) {
        petsFacingPlayer.remove(uuid);
        petsReturning.remove(uuid);
    }

    public void stopAll() {
        for (ItemDisplay pet : pets.values()) {
            pet.remove();
        }
        pets.clear();
        petsFacingPlayer.clear();
        petsReturning.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ItemDisplay pet = pets.get(uuid);
        if (pet != null) {
            pet.remove();
            pets.remove(uuid);
        }
        clearState(uuid);
    }

    private static final class ReturnMovement {
        private final Location start;
        private final Location target;
        private int ticks;

        private ReturnMovement(Location start, Location target) {
            this.start = start.clone();
            this.target = target.clone();
        }

        private boolean isFinished() {
            return ticks >= RETURN_DURATION_TICKS;
        }
    }
}
