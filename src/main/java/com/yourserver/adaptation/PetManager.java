package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class PetManager implements Listener {

    private static final NamespacedKey PET_MODEL = new NamespacedKey("f8resurs", "test-pets");
    /** Клиент сглаживает позицию и поворот между серверными обновлениями. */
    private static final int MOVEMENT_INTERPOLATION_TICKS = 2;
    /** Разные углы входа и выхода не дают режиму дёргаться на границе взгляда. */
    private static final double LOOK_ENTER_DOT = Math.cos(Math.toRadians(25.0));
    private static final double LOOK_EXIT_DOT = Math.cos(Math.toRadians(35.0));
    /** ±4 см, полный цикл вверх–вниз занимает 120 тиков (6 секунд). */
    private static final double HOVER_AMPLITUDE_BLOCKS = 0.04;
    private static final double HOVER_RADIANS_PER_TICK = Math.PI * 2.0 / 120.0;
    private static final double FOLLOW_STRENGTH = 0.18;
    /** Два темпа поворота: немного медленнее и немного быстрее прежнего. */
    private static final double[] ROTATION_STRENGTHS = {0.065, 0.095};
    /** Остановка включается после 0,2 секунды непрерывного наведения. */
    private static final int GAZE_DURATION_TICKS = 4;
    /** Во время возвращения взгляд игрока полностью игнорируется. */
    private static final int RETURN_DURATION_TICKS = 20;
    /** На таком расстоянии питомец прекращает стоять и возвращается к игроку. */
    private static final double RETURN_DISTANCE_SQUARED = 3.0 * 3.0;
    /** Только спереди и по бокам: смещения {вперёд, вправо}, без позиций за спиной. */
    private static final double[][] HOME_SLOTS = {
            {0.55, 0.45},
            {0.20, 0.80},
            {0.20, -0.80},
            {0.55, -0.45}
    };
    private static final float HITBOX_WIDTH = 0.4f;
    private static final float HITBOX_HEIGHT = 0.4f;

    private final AdaptationPlugin plugin;
    private final NamespacedKey petMarkerKey;
    private final NamespacedKey hitboxMarkerKey;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();
    private final Map<UUID, Interaction> hitboxes = new HashMap<>();
    private final Set<UUID> petsFacingPlayer = new HashSet<>();
    private final Map<UUID, ReturnMovement> petsReturning = new HashMap<>();
    private final Map<UUID, Integer> gazeTicks = new HashMap<>();
    private final Map<UUID, Integer> homeSlots = new HashMap<>();
    private final Map<UUID, Double> rotationStrengths = new HashMap<>();
    private final Map<UUID, Long> lastHitTimestamps = new HashMap<>();
    private long hoverTicks;

    public PetManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
        this.petMarkerKey = new NamespacedKey(plugin, "test_pet_display");
        this.hitboxMarkerKey = new NamespacedKey(plugin, "test_pet_hitbox");
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        ItemDisplay current = pets.get(uuid);
        if (current != null) {
            removeTracked(uuid);
            return;
        }
        removeTracked(uuid);
        spawn(player);
    }

    private void spawn(Player player) {
        UUID uuid = player.getUniqueId();
        Location eye = player.getEyeLocation().clone();

        // Служебный носитель не пересекается с ванильной бумагой и её ресурсами.
        ItemStack item = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(PET_MODEL);
            item.setItemMeta(meta);
        }

        World world = player.getWorld();
        ItemDisplay pet = world.spawn(eye, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setPersistent(false);
            display.setTeleportDuration(MOVEMENT_INTERPOLATION_TICKS);
            display.setInterpolationDuration(MOVEMENT_INTERPOLATION_TICKS);
            display.setInterpolationDelay(0);
            display.getPersistentDataContainer().set(petMarkerKey, PersistentDataType.BYTE, (byte) 1);
        });

        pets.put(uuid, pet);
        hitboxes.put(uuid, spawnHitbox(eye));
        rotationStrengths.put(uuid, randomRotationStrength());
        lastHitTimestamps.put(uuid, 0L);
    }

    private Interaction spawnHitbox(Location location) {
        return location.getWorld().spawn(location, Interaction.class, hitbox -> {
            hitbox.setInteractionWidth(HITBOX_WIDTH);
            hitbox.setInteractionHeight(HITBOX_HEIGHT);
            hitbox.setResponsive(true);
            hitbox.setPersistent(false);
            hitbox.setGravity(false);
            hitbox.setSilent(true);
            hitbox.getPersistentDataContainer().set(hitboxMarkerKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public void tick() {
        float hoverOffset = (float) (Math.sin(hoverTicks * HOVER_RADIANS_PER_TICK) * HOVER_AMPLITUDE_BLOCKS);
        hoverTicks++;

        for (UUID uuid : new ArrayList<>(pets.keySet())) {
            ItemDisplay pet = pets.get(uuid);
            Player player = Bukkit.getPlayer(uuid);

            if (player == null || !player.isOnline() || pet == null || pet.isDead()) {
                removeTracked(uuid);
                continue;
            }

            Interaction hitbox = hitboxes.get(uuid);
            if (hitbox == null || hitbox.isDead()) {
                if (hitbox != null) {
                    hitbox.remove();
                }
                hitbox = spawnHitbox(pet.getLocation());
                hitboxes.put(uuid, hitbox);
                lastHitTimestamps.put(uuid, 0L);
            }

            tickPet(uuid, player, pet, hitbox, hoverOffset);
        }
    }

    private void tickPet(UUID uuid, Player player, ItemDisplay pet, Interaction hitbox, float hoverOffset) {
        applyHover(pet, hoverOffset);
        processHits(uuid, pet, hitbox, hoverOffset);

        try {
            Location eye = player.getEyeLocation();
            Location current = pet.getLocation();
            double rotationStrength = rotationStrengths.getOrDefault(uuid, 0.08);

            ReturnMovement returning = petsReturning.get(uuid);
            if (returning != null) {
                updateReturning(pet, current, returning, rotationStrength);
                if (returning.isFinished()) {
                    petsReturning.remove(uuid);
                }
                return;
            }

            boolean facingPlayer = petsFacingPlayer.contains(uuid);
            if (facingPlayer) {
                boolean playerWalkedAway = eye.getWorld().equals(current.getWorld())
                        && eye.distanceSquared(current) >= RETURN_DISTANCE_SQUARED;
                if (!playerWalkedAway && isPetInView(eye, current, true)) {
                    facePlayer(pet, eye, current, rotationStrength);
                    return;
                }

                // Отвод взгляда или отход на 3 блока запускает одно и то же
                // возвращение. До его конца камера не двигает цель и не может
                // снова включить режим взгляда.
                petsFacingPlayer.remove(uuid);
                beginReturn(uuid, current, eye);
                return;
            }

            if (isPetInView(eye, current, false)) {
                int ticks = gazeTicks.getOrDefault(uuid, 0) + 1;
                if (ticks >= GAZE_DURATION_TICKS) {
                    gazeTicks.remove(uuid);
                    petsFacingPlayer.add(uuid);
                    rotationStrength = randomRotationStrength();
                    rotationStrengths.put(uuid, rotationStrength);
                    facePlayer(pet, eye, current, rotationStrength);
                    return;
                }
                gazeTicks.put(uuid, ticks);
            } else {
                // Время должно быть непрерывным: мимолётный взгляд ничего не включает.
                gazeTicks.remove(uuid);
            }

            follow(pet, current, followPose(eye, homeSlots.getOrDefault(uuid, 0)), rotationStrength);
        } finally {
            syncHitbox(hitbox, pet.getLocation(), hoverOffset);
        }
    }

    private void processHits(UUID uuid, ItemDisplay pet, Interaction hitbox, float hoverOffset) {
        Interaction.PreviousInteraction attack = hitbox.getLastAttack();
        if (attack == null) {
            return;
        }

        long timestamp = attack.getTimestamp();
        if (timestamp <= lastHitTimestamps.getOrDefault(uuid, 0L)) {
            return;
        }
        lastHitTimestamps.put(uuid, timestamp);

        Location at = pet.getLocation().clone().add(0.0, hoverOffset, 0.0);
        World world = pet.getWorld();
        world.spawnParticle(Particle.CRIT, at, 5, 0.16, 0.16, 0.16, 0.02);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.45f, 1.3f);
    }

    private static void syncHitbox(Interaction hitbox, Location petLocation, float hoverOffset) {
        Location target = petLocation.clone().add(0.0, hoverOffset - HITBOX_HEIGHT / 2.0, 0.0);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        if (!hitbox.getWorld().equals(target.getWorld())
                || hitbox.getLocation().distanceSquared(target) > 1.0E-6) {
            hitbox.teleport(target);
        }
    }

    private void beginReturn(UUID uuid, Location current, Location eye) {
        gazeTicks.remove(uuid);
        int slot = ThreadLocalRandom.current().nextInt(HOME_SLOTS.length);
        homeSlots.put(uuid, slot);
        rotationStrengths.put(uuid, randomRotationStrength());
        petsReturning.put(uuid, new ReturnMovement(current, followPose(eye, slot)));
    }

    private static Location followPose(Location eye, int slot) {
        Vector forward = eye.getDirection().clone().setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            forward = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
        } else {
            forward.normalize();
        }
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        double[] offset = HOME_SLOTS[slot];
        Location target = eye.clone()
                .add(forward.clone().multiply(offset[0]))
                .add(right.clone().multiply(offset[1]))
                .add(0, -0.1, 0);
        target.setYaw(eye.getYaw());
        target.setPitch(eye.getPitch());
        return target;
    }

    private static void follow(ItemDisplay pet, Location current, Location target, double rotationStrength) {
        if (!current.getWorld().equals(target.getWorld())) {
            pet.teleport(target);
            return;
        }

        Location next = current.clone();
        next.setX(current.getX() + (target.getX() - current.getX()) * FOLLOW_STRENGTH);
        next.setY(current.getY() + (target.getY() - current.getY()) * FOLLOW_STRENGTH);
        next.setZ(current.getZ() + (target.getZ() - current.getZ()) * FOLLOW_STRENGTH);
        rotateTowards(current, next, target.getYaw(), target.getPitch(), rotationStrength);
        pet.teleport(next);
    }

    private static void updateReturning(
            ItemDisplay pet,
            Location current,
            ReturnMovement movement,
            double rotationStrength
    ) {
        movement.ticks++;
        Location start = movement.start;
        Location target = movement.target;
        if (!start.getWorld().equals(target.getWorld())) {
            pet.teleport(target);
            movement.ticks = RETURN_DURATION_TICKS;
            return;
        }

        double progress = Math.min(1.0, movement.ticks / (double) RETURN_DURATION_TICKS);
        // Smoothstep: спокойный старт, небольшой разгон и мягкое завершение.
        double eased = progress * progress * (3.0 - 2.0 * progress);

        Location next = start.clone();
        next.setX(start.getX() + (target.getX() - start.getX()) * eased);
        next.setY(start.getY() + (target.getY() - start.getY()) * eased);
        next.setZ(start.getZ() + (target.getZ() - start.getZ()) * eased);
        rotateTowards(current, next, target.getYaw(), target.getPitch(), rotationStrength);
        pet.teleport(next);
    }

    private static void facePlayer(ItemDisplay pet, Location eye, Location current, double rotationStrength) {
        Location facing = current.clone();
        Vector toPlayer = eye.toVector().subtract(current.toVector());
        if (toPlayer.lengthSquared() >= 1.0E-6) {
            Location desired = current.clone();
            desired.setDirection(toPlayer);
            rotateTowards(current, facing, desired.getYaw(), desired.getPitch(), rotationStrength);
        }
        pet.teleport(facing);
    }

    private static void rotateTowards(
            Location current,
            Location next,
            float targetYaw,
            float targetPitch,
            double rotationStrength
    ) {
        float yawDelta = (targetYaw - current.getYaw()) % 360.0f;
        if (yawDelta > 180.0f) {
            yawDelta -= 360.0f;
        } else if (yawDelta < -180.0f) {
            yawDelta += 360.0f;
        }

        next.setYaw(current.getYaw() + (float) (yawDelta * rotationStrength));
        next.setPitch(current.getPitch() + (float) ((targetPitch - current.getPitch()) * rotationStrength));
    }

    private static double randomRotationStrength() {
        return ROTATION_STRENGTHS[ThreadLocalRandom.current().nextInt(ROTATION_STRENGTHS.length)];
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

    /** Удаляет активных и потерянных питомцев во всех загруженных мирах. */
    public int killAll() {
        Set<UUID> removed = new HashSet<>();
        for (ItemDisplay pet : pets.values()) {
            removeEntity(pet, removed);
        }
        for (Interaction hitbox : hitboxes.values()) {
            removeEntity(hitbox, removed);
        }

        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (isPetDisplay(display)) {
                    removeEntity(display, removed);
                }
            }
            for (Interaction hitbox : world.getEntitiesByClass(Interaction.class)) {
                if (hitbox.getPersistentDataContainer().has(hitboxMarkerKey, PersistentDataType.BYTE)) {
                    removeEntity(hitbox, removed);
                }
            }
        }

        pets.clear();
        hitboxes.clear();
        clearAllStates();
        return removed.size();
    }

    private boolean isPetDisplay(ItemDisplay display) {
        if (display.getPersistentDataContainer().has(petMarkerKey, PersistentDataType.BYTE)) {
            return true;
        }

        // Старые питомцы до появления маркера тоже находятся по их item model.
        ItemStack item = display.getItemStack();
        return item != null && item.hasItemMeta() && PET_MODEL.equals(item.getItemMeta().getItemModel());
    }

    private static void removeEntity(Entity entity, Set<UUID> removed) {
        if (removed.add(entity.getUniqueId())) {
            entity.remove();
        }
    }

    private void removeTracked(UUID uuid) {
        ItemDisplay pet = pets.remove(uuid);
        if (pet != null) {
            pet.remove();
        }
        Interaction hitbox = hitboxes.remove(uuid);
        if (hitbox != null) {
            hitbox.remove();
        }
        clearState(uuid);
    }

    private void clearState(UUID uuid) {
        petsFacingPlayer.remove(uuid);
        petsReturning.remove(uuid);
        gazeTicks.remove(uuid);
        homeSlots.remove(uuid);
        rotationStrengths.remove(uuid);
        lastHitTimestamps.remove(uuid);
    }

    private void clearAllStates() {
        petsFacingPlayer.clear();
        petsReturning.clear();
        gazeTicks.clear();
        homeSlots.clear();
        rotationStrengths.clear();
        lastHitTimestamps.clear();
    }

    public void stopAll() {
        killAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeTracked(event.getPlayer().getUniqueId());
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
