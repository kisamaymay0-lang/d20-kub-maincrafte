package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
    /** Клиент заполняет промежутки между пакетами; при высоком пинге буфер больше. */
    private static final int MIN_MOVEMENT_INTERPOLATION_TICKS = 3;
    private static final int MAX_MOVEMENT_INTERPOLATION_TICKS = 8;
    private static final int INTERPOLATION_REFRESH_TICKS = 20;
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
    /** Любой удар владельца останавливает питомца ровно на 2 секунды. */
    private static final int HIT_ATTENTION_TICKS = 40;
    private static final int HELP_GAZE_DELAY_TICKS = 60;
    private static final int HELP_VISIBLE_TICKS = 80;
    private static final int HELP_FADE_TICKS = 20;
    private static final float HELP_SCALE = 0.5f;
    private static final double HELP_HEIGHT = 0.45;
    private static final Component HELP_TEXT = Component.text("Могу ли я чем нибудь тебе помочь?");
    private static final String PET_TALK_SOUND = "f8resurs:pet-talking1";
    private static final double PET_TALK_SOUND_RADIUS_SQUARED = 16.0 * 16.0;
    private static final Component[] PET_CHAT_PHRASES = {
            Component.text("Это ты мне?"),
            Component.text("Думаю да!"),
            Component.text("Я не знаю("),
            Component.text("НЕТ! Не за что!"),
            Component.text("Не думай об этом...")
    };
    /** Во время возвращения взгляд игрока полностью игнорируется. */
    private static final int RETURN_DURATION_TICKS = 20;
    /** На таком расстоянии питомец прекращает стоять и возвращается к игроку. */
    private static final double RETURN_DISTANCE_SQUARED = 3.0 * 3.0;
    /** Только спереди и по бокам: смещения {вперёд, вправо}, без позиций за спиной. */
    private static final double[][] HOME_SLOTS = {
            {0.40, 0.45},
            {0.20, 0.80},
            {0.20, -0.80},
            {0.55, -0.45}
    };
    private static final float HITBOX_WIDTH = 0.4f;
    private static final float HITBOX_HEIGHT = 0.4f;

    private final AdaptationPlugin plugin;
    private final NamespacedKey petMarkerKey;
    private final NamespacedKey hitboxMarkerKey;
    private final NamespacedKey helpMarkerKey;
    private final Map<UUID, ItemDisplay> pets = new HashMap<>();
    private final Map<UUID, Interaction> hitboxes = new HashMap<>();
    private final Map<UUID, TextDisplay> helpTexts = new HashMap<>();
    private final Map<UUID, Integer> helpTextAges = new HashMap<>();
    private final Set<UUID> helpShownThisGaze = new HashSet<>();
    private final Set<UUID> helpFadingOut = new HashSet<>();
    private final Map<UUID, Integer> helpFadeStartOpacities = new HashMap<>();
    private final Set<UUID> petsFacingPlayer = new HashSet<>();
    private final Map<UUID, ReturnMovement> petsReturning = new HashMap<>();
    private final Map<UUID, Integer> gazeTicks = new HashMap<>();
    private final Map<UUID, Integer> hitAttentionTicks = new HashMap<>();
    private final Map<UUID, Integer> homeSlots = new HashMap<>();
    private final Map<UUID, Integer> petInterpolationTicks = new HashMap<>();
    private final Map<UUID, Double> rotationStrengths = new HashMap<>();
    private final Map<UUID, Long> lastHitTimestamps = new HashMap<>();
    private long hoverTicks;

    public PetManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
        this.petMarkerKey = new NamespacedKey(plugin, "test_pet_display");
        this.hitboxMarkerKey = new NamespacedKey(plugin, "test_pet_hitbox");
        this.helpMarkerKey = new NamespacedKey(plugin, "test_pet_help_text");
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
        int interpolationTicks = clientInterpolationTicks(player);

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
            display.setTeleportDuration(interpolationTicks);
            display.setInterpolationDuration(interpolationTicks);
            display.setInterpolationDelay(0);
            display.getPersistentDataContainer().set(petMarkerKey, PersistentDataType.BYTE, (byte) 1);
        });

        pets.put(uuid, pet);
        petInterpolationTicks.put(uuid, interpolationTicks);
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
        if (hoverTicks % INTERPOLATION_REFRESH_TICKS == 0) {
            syncClientInterpolation(uuid, player, pet);
        }
        applyHover(pet, hoverOffset);
        processHits(uuid, pet, hitbox, hoverOffset);
        tickHelpText(uuid, pet, hoverOffset);

        try {
            Location eye = player.getEyeLocation();
            Location current = pet.getLocation();
            double rotationStrength = rotationStrengths.getOrDefault(uuid, 0.08);

            Integer attentionTicks = hitAttentionTicks.get(uuid);
            if (attentionTicks != null) {
                if (!eye.getWorld().equals(current.getWorld())) {
                    hitAttentionTicks.remove(uuid);
                } else if (attentionTicks < HIT_ATTENTION_TICKS) {
                    // Взгляд владельца не отменяет этот режим: после любого
                    // удара питомец стоит и смотрит на него полные 2 секунды.
                    facePlayer(pet, eye, current, rotationStrength);
                    hitAttentionTicks.put(uuid, attentionTicks + 1);
                    return;
                } else {
                    hitAttentionTicks.remove(uuid);
                }
            }

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
                    int continuousTicks = gazeTicks.getOrDefault(uuid, 0) + 1;
                    gazeTicks.put(uuid, continuousTicks);
                    facePlayer(pet, eye, current, rotationStrength);
                    showHelpTextIfNeeded(uuid, pet, continuousTicks, hoverOffset);
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
                gazeTicks.put(uuid, ticks);
                if (ticks >= GAZE_DURATION_TICKS) {
                    petsFacingPlayer.add(uuid);
                    rotationStrength = randomRotationStrength();
                    rotationStrengths.put(uuid, rotationStrength);
                    facePlayer(pet, eye, current, rotationStrength);
                    return;
                }
            } else {
                // Время должно быть непрерывным: мимолётный взгляд ничего не включает.
                resetGaze(uuid);
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

        if (attack.getPlayer().getUniqueId().equals(uuid)) {
            // Даже во время обычного взгляда или возвращения удар даёт
            // гарантированные 2 секунды неподвижного внимания к владельцу.
            resetGaze(uuid);
            hitAttentionTicks.put(uuid, 0);
            rotationStrengths.put(uuid, randomRotationStrength());
        }

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

    private void showHelpTextIfNeeded(UUID uuid, ItemDisplay pet, int continuousTicks, float hoverOffset) {
        if (helpTexts.containsKey(uuid) || helpShownThisGaze.contains(uuid)
                || continuousTicks < HELP_GAZE_DELAY_TICKS) {
            return;
        }

        showPetText(uuid, pet, HELP_TEXT, hoverOffset);
        helpShownThisGaze.add(uuid);
    }

    private void respondToChat(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        ItemDisplay pet = pets.get(uuid);
        if (player == null || !player.isOnline() || pet == null || pet.isDead()
                || !petsFacingPlayer.contains(uuid) || petsReturning.containsKey(uuid)) {
            return;
        }

        Location eye = player.getEyeLocation();
        Location current = pet.getLocation();
        if (!isPetInView(eye, current, true)
                || (eye.getWorld().equals(current.getWorld())
                && eye.distanceSquared(current) >= RETURN_DISTANCE_SQUARED)) {
            return;
        }

        Component phrase = PET_CHAT_PHRASES[ThreadLocalRandom.current().nextInt(PET_CHAT_PHRASES.length)];
        float hoverOffset = pet.getTransformation().getTranslation().y;
        showPetText(uuid, pet, phrase, hoverOffset);
    }

    private void showPetText(UUID uuid, ItemDisplay pet, Component content, float hoverOffset) {
        removeHelpText(uuid);
        TextDisplay text = spawnHelpText(pet.getLocation(), hoverOffset, content,
                petInterpolationTicks.getOrDefault(uuid, MIN_MOVEMENT_INTERPOLATION_TICKS));
        helpTexts.put(uuid, text);
        helpTextAges.put(uuid, 0);
    }

    private void tickHelpText(UUID uuid, ItemDisplay pet, float hoverOffset) {
        TextDisplay text = helpTexts.get(uuid);
        if (text == null) {
            return;
        }
        if (!text.isValid()) {
            removeHelpText(uuid);
            return;
        }

        int age = helpTextAges.getOrDefault(uuid, 0) + 1;
        helpTextAges.put(uuid, age);

        if (helpFadingOut.contains(uuid)) {
            if (age >= HELP_FADE_TICKS) {
                removeHelpText(uuid);
                return;
            }
            int startOpacity = helpFadeStartOpacities.getOrDefault(uuid, 255);
            double remaining = 1.0 - age / (double) HELP_FADE_TICKS;
            int opacity = (int) Math.round(4.0 + (startOpacity - 4.0) * remaining);
            text.setTextOpacity((byte) Math.max(4, opacity));
        } else {
            if (age >= HELP_VISIBLE_TICKS) {
                // Пока взгляд не прерван, после исчезновения надпись не появляется заново.
                removeHelpText(uuid);
                return;
            }
            text.setTextOpacity(helpOpacity(age));
        }
        syncHelpText(text, pet.getLocation(), hoverOffset);
    }

    private TextDisplay spawnHelpText(
            Location petLocation, float hoverOffset, Component content, int interpolationTicks
    ) {
        Location at = helpLocation(petLocation, hoverOffset);
        TextDisplay text = at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.text(content);
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setTextOpacity(helpOpacity(0));
            Transformation transformation = display.getTransformation();
            transformation.getScale().set(HELP_SCALE, HELP_SCALE, HELP_SCALE);
            display.setTransformation(transformation);
            display.setLineWidth(300);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setTeleportDuration(interpolationTicks);
            display.setInterpolationDuration(0);
            display.getPersistentDataContainer().set(helpMarkerKey, PersistentDataType.BYTE, (byte) 1);
        });
        playPetTalkSound(at);
        return text;
    }

    private static void playPetTalkSound(Location at) {
        // Отправляем кастомный звук непосредственно каждому клиенту рядом.
        // MASTER не зависит от отдельно выключенного ползунка friendly/neutral.
        for (Player listener : at.getWorld().getPlayers()) {
            if (listener.getLocation().distanceSquared(at) <= PET_TALK_SOUND_RADIUS_SQUARED) {
                listener.playSound(at, PET_TALK_SOUND, SoundCategory.MASTER, 1.0f, 1.0f);
            }
        }
    }

    private void syncClientInterpolation(UUID uuid, Player player, ItemDisplay pet) {
        int ticks = clientInterpolationTicks(player);
        if (petInterpolationTicks.getOrDefault(uuid, -1) == ticks) {
            return;
        }

        // Display-entity интерполируется уже на клиенте: при потере/задержке
        // отдельных пакетов он не дёргается к последней серверной позиции.
        pet.setTeleportDuration(ticks);
        pet.setInterpolationDuration(ticks);
        TextDisplay text = helpTexts.get(uuid);
        if (text != null && text.isValid()) {
            text.setTeleportDuration(ticks);
        }
        petInterpolationTicks.put(uuid, ticks);
    }

    private static int clientInterpolationTicks(Player player) {
        int ping = Math.max(0, player.getPing());
        return Math.min(MAX_MOVEMENT_INTERPOLATION_TICKS,
                MIN_MOVEMENT_INTERPOLATION_TICKS + ping / 150);
    }

    private static byte helpOpacity(int age) {
        double progress;
        if (age < HELP_FADE_TICKS) {
            progress = age / (double) HELP_FADE_TICKS;
        } else if (age > HELP_VISIBLE_TICKS - HELP_FADE_TICKS) {
            progress = (HELP_VISIBLE_TICKS - age) / (double) HELP_FADE_TICKS;
        } else {
            progress = 1.0;
        }

        progress = Math.max(0.0, Math.min(1.0, progress));
        // Значения 0..3 клиент трактует как непрозрачные, поэтому минимум — 4.
        return (byte) Math.round(4.0 + 251.0 * progress);
    }

    private static void syncHelpText(TextDisplay text, Location petLocation, float hoverOffset) {
        Location target = helpLocation(petLocation, hoverOffset);
        if (!text.getWorld().equals(target.getWorld())
                || text.getLocation().distanceSquared(target) > 1.0E-6) {
            text.teleport(target);
        }
    }

    private static Location helpLocation(Location petLocation, float hoverOffset) {
        Location target = petLocation.clone().add(0.0, HELP_HEIGHT + hoverOffset, 0.0);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        return target;
    }

    private void removeHelpText(UUID uuid) {
        TextDisplay text = helpTexts.remove(uuid);
        if (text != null) {
            text.remove();
        }
        helpTextAges.remove(uuid);
        helpFadingOut.remove(uuid);
        helpFadeStartOpacities.remove(uuid);
    }

    private void fadeHelpText(UUID uuid) {
        TextDisplay text = helpTexts.get(uuid);
        if (text == null || helpFadingOut.contains(uuid)) {
            return;
        }

        helpFadingOut.add(uuid);
        helpFadeStartOpacities.put(uuid, Byte.toUnsignedInt(text.getTextOpacity()));
        helpTextAges.put(uuid, 0);
    }

    private void resetGaze(UUID uuid) {
        gazeTicks.remove(uuid);
        helpShownThisGaze.remove(uuid);
        fadeHelpText(uuid);
    }

    private void beginReturn(UUID uuid, Location current, Location eye) {
        resetGaze(uuid);
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
            teleportPet(pet, target);
            return;
        }

        Location next = current.clone();
        next.setX(current.getX() + (target.getX() - current.getX()) * FOLLOW_STRENGTH);
        next.setY(current.getY() + (target.getY() - current.getY()) * FOLLOW_STRENGTH);
        next.setZ(current.getZ() + (target.getZ() - current.getZ()) * FOLLOW_STRENGTH);
        rotateTowards(current, next, target.getYaw(), target.getPitch(), rotationStrength);
        teleportPet(pet, next);
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
            teleportPet(pet, target);
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
        teleportPet(pet, next);
    }

    private static void facePlayer(ItemDisplay pet, Location eye, Location current, double rotationStrength) {
        Location facing = current.clone();
        Vector toPlayer = eye.toVector().subtract(current.toVector());
        if (toPlayer.lengthSquared() >= 1.0E-6) {
            Location desired = current.clone();
            desired.setDirection(toPlayer);
            rotateTowards(current, facing, desired.getYaw(), desired.getPitch(), rotationStrength);
        }
        teleportPet(pet, facing);
    }

    private static void teleportPet(ItemDisplay pet, Location target) {
        Location current = pet.getLocation();
        if (!current.getWorld().equals(target.getWorld())
                || current.distanceSquared(target) > 1.0E-8
                || Math.abs(current.getYaw() - target.getYaw()) > 0.001f
                || Math.abs(current.getPitch() - target.getPitch()) > 0.001f) {
            pet.teleport(target);
        }
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
        for (TextDisplay text : helpTexts.values()) {
            removeEntity(text, removed);
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
            for (TextDisplay text : world.getEntitiesByClass(TextDisplay.class)) {
                if (text.getPersistentDataContainer().has(helpMarkerKey, PersistentDataType.BYTE)) {
                    removeEntity(text, removed);
                }
            }
        }

        pets.clear();
        hitboxes.clear();
        helpTexts.clear();
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
        removeHelpText(uuid);
        clearState(uuid);
    }

    private void clearState(UUID uuid) {
        petsFacingPlayer.remove(uuid);
        petsReturning.remove(uuid);
        gazeTicks.remove(uuid);
        hitAttentionTicks.remove(uuid);
        homeSlots.remove(uuid);
        petInterpolationTicks.remove(uuid);
        rotationStrengths.remove(uuid);
        lastHitTimestamps.remove(uuid);
        helpTextAges.remove(uuid);
        helpShownThisGaze.remove(uuid);
        helpFadingOut.remove(uuid);
        helpFadeStartOpacities.remove(uuid);
    }

    private void clearAllStates() {
        petsFacingPlayer.clear();
        petsReturning.clear();
        gazeTicks.clear();
        hitAttentionTicks.clear();
        homeSlots.clear();
        petInterpolationTicks.clear();
        rotationStrengths.clear();
        lastHitTimestamps.clear();
        helpTextAges.clear();
        helpShownThisGaze.clear();
        helpFadingOut.clear();
        helpFadeStartOpacities.clear();
    }

    public void stopAll() {
        killAll();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        try {
            // Сущности и их состояние трогаем только на основном серверном потоке.
            Bukkit.getScheduler().runTask(plugin, () -> respondToChat(uuid));
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Плагин уже выключается.
        }
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
