package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Второй тестовый питомец — «живая корона» из /f8 pet 2.
 *
 * <p>Корона висит над макушкой игрока, а при беге сползает к левому или
 * правому плечу. Голова игрока не тащит её за собой: позиция считается от
 * тела игрока, поэтому наклон камеры вверх/вниз ничего не меняет. Поворот
 * тоже только горизонтальный — корона разворачивается по взгляду игрока, но
 * никогда не задирается по вертикали.</p>
 *
 * <p>За короной остаётся редкий след из частиц цвета #ef3059: одна частица в
 * секунду, плавно опускающаяся вниз. Если игрок смотрит на корону или бьёт
 * её, она замирает на месте. Четыре секунды взгляда дают реплику «Я тут не по
 * своей воле...», а сообщение в чат во время взгляда — ответ и продолжение
 * через две секунды.</p>
 */
final class CrownPet implements Listener {
    private static final String DEFAULT_MODEL = "f8resurs:pet-2";
    private static final float HITBOX_WIDTH = 0.4f;
    private static final float HITBOX_HEIGHT = 0.4f;
    /** Низ модели всегда чуть выше макушки игрока (можно поправить в config.yml). */
    private static final double DEFAULT_CROWN_GAP = 0.35D;
    /** В беге корона уходит к плечу и летит сбоку. */
    private static final double DEFAULT_SHOULDER_OFFSET = 0.40D;
    private static final double SHOULDER_DROP = 0.42D;
    /** Плавное отставание: доля пути к цели за один тик. */
    private static final double HOVER_FOLLOW = 0.30D;
    private static final double RUN_FOLLOW = 0.42D;
    private static final double YAW_FOLLOW = 0.18D;
    private static final double HOVER_AMPLITUDE = 0.05D;
    private static final double HOVER_RADIANS_PER_TICK = Math.PI * 2.0 / 110.0;
    /** Спринт или быстрое смещение за тик считаются бегом. */
    private static final double RUN_MOVE_SQUARED = 0.06D;
    /** 0,2 секунды непрерывного взгляда останавливают корону. */
    private static final int GAZE_FREEZE_TICKS = 4;
    /** 4 секунды взгляда — первая фраза. */
    private static final int GAZE_SPEECH_TICKS = 80;
    private static final double LOOK_ENTER_DOT = Math.cos(Math.toRadians(25.0));
    private static final double LOOK_EXIT_DOT = Math.cos(Math.toRadians(35.0));
    private static final int HIT_FREEZE_TICKS = 40;
    private static final int TRAIL_INTERVAL_TICKS = 20;
    private static final int SPEECH_FADE_TICKS = 20;
    private static final int GAZE_SPEECH_LIFETIME_TICKS = 80;
    private static final int CHAT_REPLY_LIFETIME_TICKS = 40;
    /** Вторая реплика приходит ровно через 2 секунды после первой. */
    private static final int CHAT_REPLY_DELAY_TICKS = 40;
    private static final int CHAT_FOLLOW_UP_LIFETIME_TICKS = 60;
    private static final int MIN_INTERPOLATION_TICKS = 3;
    private static final int MAX_INTERPOLATION_TICKS = 8;
    private static final float SPEECH_SCALE = 0.5f;
    private static final double SPEECH_HEIGHT = 0.45D;
    private static final int SPEECH_LINE_WIDTH = 300;
    /** След: цвет #ef3059, одна плавно падающая частица в секунду. */
    private static final Particle.DustOptions TRAIL_DUST =
            new Particle.DustOptions(Color.fromRGB(0xEF, 0x30, 0x59), 1.1f);
    private static final double TRAIL_FALL_SPEED = 0.06D;
    /** Даже «замороженная» корона догоняет игрока, если он ушёл слишком далеко. */
    private static final double MAX_FROZEN_DISTANCE_SQUARED = 8.0D * 8.0D;
    private static final String TALK_SOUND = "f8resurs:pet-talking1";
    private static final double TALK_SOUND_RADIUS_SQUARED = 16.0D * 16.0D;

    private static final Component GAZE_SPEECH = Component.text("Я тут не по своей воле...");
    private static final Component[] CHAT_FIRST_REPLIES = {
            Component.text("Наверное?"),
            Component.text("Боже...")
    };
    private static final Component[] CHAT_FOLLOW_UP_REPLIES = {
            Component.text("Зачем ты вообще существуешь?"),
            Component.text("Нет."),
            Component.text("Отстань")
    };

    private final AdaptationPlugin plugin;
    private final NamespacedKey displayMarker;
    private final NamespacedKey hitboxMarker;
    private final NamespacedKey speechMarker;
    private final Map<UUID, Crown> crowns = new HashMap<>();
    private NamespacedKey modelKey = parseModel(DEFAULT_MODEL);
    private double crownGap = DEFAULT_CROWN_GAP;
    private double shoulderOffset = DEFAULT_SHOULDER_OFFSET;

    CrownPet(AdaptationPlugin plugin) {
        this.plugin = plugin;
        this.displayMarker = new NamespacedKey(plugin, "crown_pet_display");
        this.hitboxMarker = new NamespacedKey(plugin, "crown_pet_hitbox");
        this.speechMarker = new NamespacedKey(plugin, "crown_pet_speech");
        reloadSettings();
    }

    /** /f8 reload: модель можно поменять в config.yml без перезапуска. */
    void reloadSettings() {
        NamespacedKey parsed = parseModel(plugin.getConfig().getString("pets.crown-model", DEFAULT_MODEL));
        modelKey = parsed != null ? parsed : parseModel(DEFAULT_MODEL);
        crownGap = finiteOrDefault(plugin.getConfig().getDouble("pets.crown-gap", DEFAULT_CROWN_GAP),
                DEFAULT_CROWN_GAP);
        shoulderOffset = finiteOrDefault(
                plugin.getConfig().getDouble("pets.shoulder-offset", DEFAULT_SHOULDER_OFFSET),
                DEFAULT_SHOULDER_OFFSET);
        for (Crown crown : crowns.values()) {
            ItemDisplay display = crown.display;
            if (display != null && display.isValid()) display.setItemStack(modelItem());
        }
    }

    String describe() {
        return "Питомец-корона: модель f8resurs:" + modelKey.getKey()
                + ", высота над головой " + crownGap + ".";
    }

    private static double finiteOrDefault(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    static NamespacedKey parseModel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ItemStack modelItem() {
        ItemStack item = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(modelKey);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (crowns.containsKey(uuid)) {
            removeTracked(uuid);
            return;
        }
        removeTracked(uuid);
        spawn(player);
    }

    private void spawn(Player player) {
        UUID uuid = player.getUniqueId();
        int interpolationTicks = clientInterpolationTicks(player);
        Location at = crownAnchor(player, false, 1);
        World world = player.getWorld();

        ItemDisplay display = world.spawn(at, ItemDisplay.class, entity -> {
            entity.setItemStack(modelItem());
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setPersistent(false);
            entity.setTeleportDuration(interpolationTicks);
            entity.setInterpolationDuration(interpolationTicks);
            entity.setInterpolationDelay(0);
            entity.getPersistentDataContainer().set(displayMarker, PersistentDataType.BYTE, (byte) 1);
        });

        Crown crown = new Crown(display, interpolationTicks);
        crown.hitbox = spawnHitbox(at);
        crown.lastPlayerLocation = player.getLocation().clone();
        crown.shoulderSide = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        crowns.put(uuid, crown);
        syncHitbox(crown);
    }

    private Interaction spawnHitbox(Location location) {
        return location.getWorld().spawn(location, Interaction.class, hitbox -> {
            hitbox.setInteractionWidth(HITBOX_WIDTH);
            hitbox.setInteractionHeight(HITBOX_HEIGHT);
            hitbox.setResponsive(true);
            hitbox.setPersistent(false);
            hitbox.setGravity(false);
            hitbox.setSilent(true);
            hitbox.getPersistentDataContainer().set(hitboxMarker, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Один серверный тик: позиция, поворот, след, реплики и удары. */
    void tick() {
        for (UUID uuid : new ArrayList<>(crowns.keySet())) {
            Crown crown = crowns.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (crown == null) continue;
            if (player == null || !player.isOnline()
                    || crown.display == null || crown.display.isDead() || !crown.display.isValid()) {
                removeTracked(uuid);
                continue;
            }
            tickCrown(uuid, player, crown);
        }
    }

    private void tickCrown(UUID uuid, Player player, Crown crown) {
        ItemDisplay display = crown.display;
        if (crown.hitbox == null || crown.hitbox.isDead() || !crown.hitbox.isValid()) {
            if (crown.hitbox != null) crown.hitbox.remove();
            crown.hitbox = spawnHitbox(display.getLocation());
            crown.lastHitTimestamp = 0L;
        }

        updateMovementMode(player, crown);
        updateGaze(player, crown);
        processHit(crown);

        if (crown.hitFreezeTicks > 0) crown.hitFreezeTicks--;

        boolean frozen = crown.gazeTicks >= GAZE_FREEZE_TICKS || crown.hitFreezeTicks > 0;
        if (frozen && tooFarFromPlayer(player, display.getLocation())) {
            // Если игрок ушёл далеко, корона всё-таки догоняет его: иначе она
            // осталась бы висеть в пустом чанке.
            frozen = false;
        }
        if (!frozen) {
            moveCrown(player, crown);
        } else {
            rotateCrown(player, crown);
        }

        if (Bukkit.getCurrentTick() % TRAIL_INTERVAL_TICKS == 0) {
            spawnTrail(display.getLocation());
        }
        tickSpeech(crown);
        syncHitbox(crown);
    }

    private static boolean tooFarFromPlayer(Player player, Location petLocation) {
        return !player.getWorld().equals(petLocation.getWorld())
                || player.getLocation().distanceSquared(petLocation) > MAX_FROZEN_DISTANCE_SQUARED;
    }

    private void updateMovementMode(Player player, Crown crown) {
        Location previous = crown.lastPlayerLocation;
        double movedSquared = 0.0D;
        if (previous != null && previous.getWorld().equals(player.getWorld())) {
            double dx = player.getX() - previous.getX();
            double dz = player.getZ() - previous.getZ();
            movedSquared = dx * dx + dz * dz;
        }
        crown.lastPlayerLocation = player.getLocation().clone();

        boolean running = player.isSprinting() || movedSquared > RUN_MOVE_SQUARED;
        if (running && !crown.running) {
            // Сторону плеча выбираем один раз за пробежку.
            crown.shoulderSide = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        }
        crown.running = running;
    }

    private void updateGaze(Player player, Crown crown) {
        Location eye = player.getEyeLocation();
        boolean inView = isPetInView(eye, crown.display.getLocation(), crown.gazeTicks > 0);
        if (inView) {
            crown.gazeTicks++;
            if (crown.gazeTicks >= GAZE_SPEECH_TICKS && !crown.speechShownForGaze) {
                showSpeech(crown, GAZE_SPEECH, GAZE_SPEECH_LIFETIME_TICKS);
                crown.speechShownForGaze = true;
            }
        } else {
            crown.gazeTicks = 0;
            crown.speechShownForGaze = false;
        }
    }

    private void processHit(Crown crown) {
        Interaction hitbox = crown.hitbox;
        if (hitbox == null) return;
        Interaction.PreviousInteraction attack = hitbox.getLastAttack();
        if (attack == null) return;

        long timestamp = attack.getTimestamp();
        if (timestamp <= crown.lastHitTimestamp) return;
        crown.lastHitTimestamp = timestamp;
        // Любой удар оставляет корону на месте на две секунды.
        crown.hitFreezeTicks = HIT_FREEZE_TICKS;

        Location at = crown.display.getLocation().clone().add(0.0D, 0.2D, 0.0D);
        World world = at.getWorld();
        world.spawnParticle(Particle.CRIT, at, 5, 0.16D, 0.16D, 0.16D, 0.02D);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.45f, 1.3f);
    }

    /** Корона летит над макушкой или сбоку у плеча, плавно догоняя игрока. */
    private void moveCrown(Player player, Crown crown) {
        Location current = crown.display.getLocation();
        Location target = crownAnchor(player, crown.running, crown.shoulderSide);
        if (!current.getWorld().equals(target.getWorld())) {
            crown.display.teleport(target);
            return;
        }

        double follow = crown.running ? RUN_FOLLOW : HOVER_FOLLOW;
        Location next = current.clone();
        next.setX(current.getX() + (target.getX() - current.getX()) * follow);
        next.setY(current.getY() + (target.getY() - current.getY()) * follow);
        next.setZ(current.getZ() + (target.getZ() - current.getZ()) * follow);
        applyYawOnly(current, next, player.getYaw());
        teleport(crown.display, next);
    }

    private void rotateCrown(Player player, Crown crown) {
        Location current = crown.display.getLocation();
        Location next = current.clone();
        applyYawOnly(current, next, player.getYaw());
        teleport(crown.display, next);
    }

    /**
     * Целевая точка короны. Считается от тела игрока (не от камеры), поэтому
     * взгляд вверх или вниз положение не меняет.
     */
    private Location crownAnchor(Player player, boolean running, int shoulderSide) {
        Location target = player.getLocation().clone();
        double top = player.getBoundingBox().getMaxY();
        if (running) {
            Vector right = rightVector(target.getYaw());
            target.add(right.multiply(shoulderOffset * shoulderSide));
            target.setY(top - SHOULDER_DROP);
        } else {
            target.setY(top + crownGap);
        }

        double bob = Math.sin(Bukkit.getCurrentTick() * HOVER_RADIANS_PER_TICK) * HOVER_AMPLITUDE;
        target.add(0.0D, bob, 0.0D);
        // Yaw берётся у тела игрока, pitch всегда нулевой: вертикального
        // вращения у короны нет.
        target.setPitch(0.0f);
        return target;
    }

    private static Vector rightVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.cos(radians), 0.0D, -Math.sin(radians)).normalize();
    }

    /** Только горизонтальный поворот: вертикальное вращение у короны заблокировано. */
    private static void applyYawOnly(Location current, Location next, float targetYaw) {
        float delta = (targetYaw - current.getYaw()) % 360.0f;
        if (delta > 180.0f) delta -= 360.0f;
        else if (delta < -180.0f) delta += 360.0f;
        next.setYaw(current.getYaw() + (float) (delta * YAW_FOLLOW));
        next.setPitch(0.0f);
    }

    private static void teleport(ItemDisplay display, Location target) {
        Location current = display.getLocation();
        if (!current.getWorld().equals(target.getWorld())
                || current.distanceSquared(target) > 1.0E-8
                || Math.abs(current.getYaw() - target.getYaw()) > 0.001f
                || Math.abs(current.getPitch()) > 0.001f) {
            display.teleport(target);
        }
    }

    private void spawnTrail(Location at) {
        Location spawn = at.clone().add(0.0D, 0.1D, 0.0D);
        try {
            // count = 0: клиент трактует смещения как вектор скорости, поэтому
            // единственная частица плавно опускается вниз.
            spawn.getWorld().spawnParticle(Particle.DUST, spawn, 0, 0.0D, -TRAIL_FALL_SPEED, 0.0D,
                    0.0D, TRAIL_DUST, true);
        } catch (RuntimeException ex) {
            // Если сервер не принял нулевой count, оставляем частицу на месте:
            // след должен оставаться видимым в любом случае.
            spawn.getWorld().spawnParticle(Particle.DUST, spawn, 1, 0.0D, 0.0D, 0.0D,
                    0.0D, TRAIL_DUST, true);
        }
    }

    private void syncHitbox(Crown crown) {
        Interaction hitbox = crown.hitbox;
        if (hitbox == null || !hitbox.isValid()) return;
        Location target = crown.display.getLocation().clone()
                .add(0.0D, -HITBOX_HEIGHT / 2.0D, 0.0D);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        if (!hitbox.getWorld().equals(target.getWorld())
                || hitbox.getLocation().distanceSquared(target) > 1.0E-6) {
            hitbox.teleport(target);
        }
    }

    private static boolean isPetInView(Location eye, Location petLocation, boolean wasInView) {
        if (!eye.getWorld().equals(petLocation.getWorld())) {
            return false;
        }
        Vector toPet = petLocation.toVector().subtract(eye.toVector());
        if (toPet.lengthSquared() < 1.0E-6) return wasInView;
        double alignment = eye.getDirection().dot(toPet.normalize());
        double threshold = wasInView ? LOOK_EXIT_DOT : LOOK_ENTER_DOT;
        return alignment >= threshold;
    }

    // ------------------------------------------------------------------
    // Реплики короны
    // ------------------------------------------------------------------

    private void showSpeech(Crown crown, Component content, int lifetime) {
        removeSpeech(crown);
        Location at = speechLocation(crown.display.getLocation());
        int interpolationTicks = crown.interpolationTicks;
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
            display.setTextOpacity(speechOpacity(0, lifetime));
            Transformation transformation = display.getTransformation();
            transformation.getScale().set(SPEECH_SCALE, SPEECH_SCALE, SPEECH_SCALE);
            display.setTransformation(transformation);
            display.setLineWidth(SPEECH_LINE_WIDTH);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setTeleportDuration(interpolationTicks);
            display.setInterpolationDuration(0);
            display.getPersistentDataContainer().set(speechMarker, PersistentDataType.BYTE, (byte) 1);
        });
        crown.speech = text;
        crown.speechAge = 0;
        crown.speechLifetime = lifetime;
        playTalkSound(at);
    }

    private void tickSpeech(Crown crown) {
        TextDisplay text = crown.speech;
        if (text == null) return;
        if (!text.isValid() || text.isDead()) {
            crown.speech = null;
            return;
        }

        crown.speechAge++;
        if (crown.speechAge >= crown.speechLifetime) {
            removeSpeech(crown);
            return;
        }
        text.setTextOpacity(speechOpacity(crown.speechAge, crown.speechLifetime));

        Location target = speechLocation(crown.display.getLocation());
        if (!text.getWorld().equals(target.getWorld())
                || text.getLocation().distanceSquared(target) > 1.0E-6) {
            text.teleport(target);
        }
    }

    /** Прозрачность реплики: секунда появления, полная видимость и секунда затухания. */
    static byte speechOpacity(int age, int lifetime) {
        int fade = Math.min(SPEECH_FADE_TICKS, Math.max(1, lifetime / 2));
        double progress;
        if (age < fade) {
            progress = age / (double) fade;
        } else if (age > lifetime - fade) {
            progress = (lifetime - age) / (double) fade;
        } else {
            progress = 1.0D;
        }
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        // Значения 0..3 клиент трактует как непрозрачные, поэтому минимум — 4.
        return (byte) Math.round(4.0D + 251.0D * progress);
    }

    private static Location speechLocation(Location petLocation) {
        Location target = petLocation.clone().add(0.0D, SPEECH_HEIGHT, 0.0D);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        return target;
    }

    private void removeSpeech(Crown crown) {
        TextDisplay text = crown.speech;
        crown.speech = null;
        crown.speechAge = 0;
        if (text != null) text.remove();
    }

    private static void playTalkSound(Location at) {
        for (Player listener : at.getWorld().getPlayers()) {
            if (listener.getLocation().distanceSquared(at) <= TALK_SOUND_RADIUS_SQUARED) {
                listener.playSound(at, TALK_SOUND, org.bukkit.SoundCategory.MASTER, 1.0f, 1.0f);
            }
        }
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

    private void respondToChat(UUID uuid) {
        Crown crown = crowns.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (crown == null || player == null || !player.isOnline()) return;
        ItemDisplay display = crown.display;
        if (display == null || display.isDead() || !display.isValid()) return;
        // Отвечает только когда на неё действительно смотрят.
        if (crown.gazeTicks <= 0 || !isPetInView(player.getEyeLocation(), display.getLocation(), true)) {
            return;
        }

        Component reply = CHAT_FIRST_REPLIES[ThreadLocalRandom.current().nextInt(CHAT_FIRST_REPLIES.length)];
        showSpeech(crown, reply, CHAT_REPLY_LIFETIME_TICKS);
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> followUpReply(uuid), CHAT_REPLY_DELAY_TICKS);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Плагин уже выключается.
        }
    }

    /** Вторая реплика приходит через две секунды даже если игрок уже отвёл взгляд. */
    private void followUpReply(UUID uuid) {
        Crown crown = crowns.get(uuid);
        if (crown == null) return;
        ItemDisplay display = crown.display;
        if (display == null || display.isDead() || !display.isValid()) return;
        Component reply = CHAT_FOLLOW_UP_REPLIES[
                ThreadLocalRandom.current().nextInt(CHAT_FOLLOW_UP_REPLIES.length)];
        showSpeech(crown, reply, CHAT_FOLLOW_UP_LIFETIME_TICKS);
    }

    // ------------------------------------------------------------------
    // Очистка
    // ------------------------------------------------------------------

    /** Убирает активных и потерянных крон во всех загруженных мирах. */
    int killAll() {
        Set<UUID> removed = new HashSet<>();
        for (Crown crown : crowns.values()) {
            removeEntity(crown.display, removed);
            removeEntity(crown.hitbox, removed);
            removeEntity(crown.speech, removed);
        }
        crowns.clear();

        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(displayMarker, PersistentDataType.BYTE)) {
                    removeEntity(display, removed);
                }
            }
            for (Interaction hitbox : world.getEntitiesByClass(Interaction.class)) {
                if (hitbox.getPersistentDataContainer().has(hitboxMarker, PersistentDataType.BYTE)) {
                    removeEntity(hitbox, removed);
                }
            }
            for (TextDisplay text : world.getEntitiesByClass(TextDisplay.class)) {
                if (text.getPersistentDataContainer().has(speechMarker, PersistentDataType.BYTE)) {
                    removeEntity(text, removed);
                }
            }
        }
        return removed.size();
    }

    private static void removeEntity(Entity entity, Set<UUID> removed) {
        if (entity != null && entity.isValid() && removed.add(entity.getUniqueId())) {
            entity.remove();
        }
    }

    private void removeTracked(UUID uuid) {
        Crown crown = crowns.remove(uuid);
        if (crown == null) return;
        removeSpeech(crown);
        if (crown.hitbox != null) crown.hitbox.remove();
        if (crown.display != null) crown.display.remove();
    }

    public void stopAll() {
        killAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeTracked(event.getPlayer().getUniqueId());
    }

    private static int clientInterpolationTicks(Player player) {
        int ping = Math.max(0, player.getPing());
        return Math.min(MAX_INTERPOLATION_TICKS, MIN_INTERPOLATION_TICKS + ping / 150);
    }

    private static final class Crown {
        private final ItemDisplay display;
        private final int interpolationTicks;
        private Interaction hitbox;
        private boolean running;
        private int shoulderSide = 1;
        private Location lastPlayerLocation;
        private int gazeTicks;
        private boolean speechShownForGaze;
        private int hitFreezeTicks;
        private long lastHitTimestamp;
        private TextDisplay speech;
        private int speechAge;
        private int speechLifetime;

        private Crown(ItemDisplay display, int interpolationTicks) {
            this.display = display;
            this.interpolationTicks = interpolationTicks;
        }
    }
}
