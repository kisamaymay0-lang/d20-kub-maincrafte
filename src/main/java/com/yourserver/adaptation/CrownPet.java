package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
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
 * <p>Корона всегда висит над макушкой игрока: при беге она не сползает к
 * плечу, а просто чуть отстаёт и плавно возвращается на своё место. Голова
 * игрока не тащит её за собой: позиция считается от тела игрока, поэтому
 * наклон камеры вверх/вниз ничего не меняет. Поворот тоже только
 * горизонтальный — в обычном полёте корона разворачивается по взгляду
 * игрока, а когда игрок смотрит на неё или бьёт, поворачивается лицом к нему,
 * но никогда не задирается по вертикали.</p>
 *
 * <p>Чтобы корона не выглядела идеально приклеенной, у неё есть собственное
 * «дыхание»: две медленные волны по высоте, лёгкий боковой и передний дрейф,
 * неспешное покачивание по горизонтали, едва заметное изменение масштаба и
 * редкое «встрепенулась» — короткий плавный подъём. Смещения измеряются
 * сантиметрами и не мешают ей оставаться над головой.</p>
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
    /** Плавное отставание: доля пути к цели за тик. */
    private static final double HOVER_FOLLOW = 0.32D;
    private static final double YAW_FOLLOW = 0.14D;
    /*
     * «Живость»: корона не стоит идеально ровно. Все смещения — сантиметры,
     * а не второй полёт: медленное покачивание, лёгкий боковой дрейф,
     * едва заметное «дыхание» и редкое «встрепенулась».
     */
    private static final double IDLE_LIFT_SLOW = 0.055D;
    private static final long IDLE_LIFT_SLOW_PERIOD = 130L;
    private static final double IDLE_LIFT_FAST = 0.012D;
    private static final long IDLE_LIFT_FAST_PERIOD = 47L;
    private static final double IDLE_SIDE = 0.032D;
    private static final long IDLE_SIDE_PERIOD = 173L;
    private static final double IDLE_FORWARD = 0.022D;
    private static final long IDLE_FORWARD_PERIOD = 227L;
    /** Медленное «оглядывание» вокруг направления взгляда игрока, в градусах. */
    private static final double IDLE_YAW_MIN_DEGREES = 2.5D;
    private static final double IDLE_YAW_MAX_DEGREES = 5.0D;
    private static final int IDLE_YAW_MIN_PERIOD = 80;
    private static final int IDLE_YAW_MAX_PERIOD = 140;
    /** Едва заметное «дыхание» масштаба: ±1,2%. */
    private static final double IDLE_SCALE = 0.012D;
    private static final long IDLE_SCALE_PERIOD = 141L;
    /** Редкое «встрепенулась»: короткий плавный подъём и обратно. */
    private static final int PERK_MIN_DELAY_TICKS = 100;
    private static final int PERK_MAX_DELAY_TICKS = 260;
    private static final int PERK_DURATION_TICKS = 20;
    private static final double PERK_LIFT = 0.07D;
    private static final double PERK_SCALE = 0.02D;
    /** 0,2 секунды непрерывного взгляда останавливают корону. */
    private static final int GAZE_FREEZE_TICKS = 4;
    /** 4 секунды взгляда — первая фраза. */
    private static final int GAZE_SPEECH_TICKS = 80;
    private static final double LOOK_ENTER_DOT = Math.cos(Math.toRadians(25.0));
    private static final double LOOK_EXIT_DOT = Math.cos(Math.toRadians(35.0));
    private static final int HIT_FREEZE_TICKS = 40;
    private static final int TRAIL_INTERVAL_TICKS = 20;
    private static final int GAZE_SPEECH_LIFETIME_TICKS = 80;
    /** «...» висит ровно две секунды, затем корона дописывает вторую часть. */
    private static final int CHAT_LEAD_IN_LIFETIME_TICKS = 40;
    private static final int CHAT_REPLY_LIFETIME_TICKS = 60;
    /** Вторая реплика приходит ровно через 2 секунды после первой. */
    private static final int CHAT_REPLY_DELAY_TICKS = 40;
    private static final int CHAT_FOLLOW_UP_LIFETIME_TICKS = 60;
    private static final int MIN_INTERPOLATION_TICKS = 3;
    private static final int MAX_INTERPOLATION_TICKS = 8;
    /** Высота и размер реплик берутся из config.yml (pets.speech.pet-2-*). */
    private static final double DEFAULT_SPEECH_HEIGHT = 0.45D;
    private static final float DEFAULT_SPEECH_SCALE = 0.5f;
    /** След: цвет #ef3059, одна плавно падающая частица в секунду. */
    private static final Particle.DustOptions TRAIL_DUST =
            new Particle.DustOptions(Color.fromRGB(0xEF, 0x30, 0x59), 1.1f);
    private static final double TRAIL_FALL_SPEED = 0.06D;
    /** Даже «замороженная» корона догоняет игрока, если он ушёл слишком далеко. */
    private static final double MAX_FROZEN_DISTANCE_SQUARED = 8.0D * 8.0D;
    /** У короны свой голос, отличный от первого питомца. */
    private static final String TALK_SOUND = "f8resurs:pet-talking2";
    private static final double TALK_SOUND_RADIUS_SQUARED = 16.0D * 16.0D;

    private static final String GAZE_SPEECH = "Я тут не по своей воле...";
    /**
     * Двойная надпись только одна по смыслу: сначала «...», через две секунды
     * вторая часть. Вторая часть выбирается из этих трёх фраз.
     */
    private static final String CHAT_LEAD_IN = "...";
    private static final String[] CHAT_TWO_PART_REPLIES = {
            "Зачем ты вообще существуешь?",
            "Нет.",
            "Отстань"
    };
    /**
     * Одиночные реплики: те же фразы, но без «...», плюс «Наверное?».
     * «Нет.» и «Отстань» тоже могут звучать сами по себе.
     */
    private static final String[] CHAT_SINGLE_REPLIES = {
            "Наверное?",
            "Зачем ты вообще существуешь?",
            "Нет.",
            "Отстань"
    };

    private final AdaptationPlugin plugin;
    private final NamespacedKey displayMarker;
    private final NamespacedKey hitboxMarker;
    private final NamespacedKey speechMarker;
    private final Map<UUID, Crown> crowns = new HashMap<>();
    private NamespacedKey modelKey = parseModel(DEFAULT_MODEL);
    private double crownGap = DEFAULT_CROWN_GAP;
    private double speechHeight = DEFAULT_SPEECH_HEIGHT;
    private float speechScale = DEFAULT_SPEECH_SCALE;

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
        speechHeight = finiteOrDefault(
                plugin.getConfig().getDouble("pets.speech.pet-2-height", DEFAULT_SPEECH_HEIGHT),
                DEFAULT_SPEECH_HEIGHT);
        speechScale = SpeechText.clampScale((float) finiteOrDefault(
                plugin.getConfig().getDouble("pets.speech.pet-2-scale", DEFAULT_SPEECH_SCALE),
                DEFAULT_SPEECH_SCALE));
        for (Crown crown : crowns.values()) {
            ItemDisplay display = crown.display;
            if (display != null && display.isValid()) display.setItemStack(modelItem());
        }
    }

    /** Активна ли корона у игрока — надписи над головой нужно знать, где она висит. */
    boolean isActive(UUID playerId) {
        Crown crown = crowns.get(playerId);
        return crown != null && crown.display != null
                && crown.display.isValid() && !crown.display.isDead();
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
        Location at = spawnAnchor(player);
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
        // У каждой короны своя фаза и свой период «оглядывания», чтобы две
        // короны рядом не двигались синхронно, как на рельсах.
        crown.phase = ThreadLocalRandom.current().nextDouble(0.0D, Math.PI * 2.0D);
        crown.yawAmplitude = IDLE_YAW_MIN_DEGREES
                + ThreadLocalRandom.current().nextDouble(IDLE_YAW_MAX_DEGREES - IDLE_YAW_MIN_DEGREES);
        crown.yawPeriod = IDLE_YAW_MIN_PERIOD
                + ThreadLocalRandom.current().nextInt(IDLE_YAW_MAX_PERIOD - IDLE_YAW_MIN_PERIOD + 1);
        crown.perkCooldown = PERK_MIN_DELAY_TICKS / 2
                + ThreadLocalRandom.current().nextInt(PERK_MAX_DELAY_TICKS);
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
            applyBreathing(crown);
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

    /** Корона всегда летит над макушкой, плавно догоняя игрока и покачиваясь. */
    private void moveCrown(Player player, Crown crown) {
        Location current = crown.display.getLocation();
        Location target = crownAnchor(player, crown);
        if (!current.getWorld().equals(target.getWorld())) {
            crown.display.teleport(target);
            return;
        }

        Location next = current.clone();
        next.setX(current.getX() + (target.getX() - current.getX()) * HOVER_FOLLOW);
        next.setY(current.getY() + (target.getY() - current.getY()) * HOVER_FOLLOW);
        next.setZ(current.getZ() + (target.getZ() - current.getZ()) * HOVER_FOLLOW);
        applyYawOnly(current, next, (float) (player.getYaw() + yawSway(crown)));
        teleport(crown.display, next);
    }

    /** Стартовая точка до первого тика: над головой, без покачивания. */
    private Location spawnAnchor(Player player) {
        Location at = player.getLocation().clone();
        at.setY(player.getBoundingBox().getMaxY() + crownGap);
        at.setPitch(0.0f);
        return at;
    }

    /**
     * Когда на корону смотрят или её ударили, она разворачивается лицом к
     * игроку. Иначе игрок видел бы её затылок: раньше корона повторяла
     * направление его взгляда и «отворачивалась».
     */
    private void rotateCrown(Player player, Crown crown) {
        Location current = crown.display.getLocation();
        Location next = current.clone();
        Vector toPlayer = player.getEyeLocation().toVector().subtract(current.toVector());
        float yaw = (float) (facingYaw(toPlayer.getX(), toPlayer.getZ(), player.getYaw()) + yawSway(crown));
        applyYawOnly(current, next, yaw);
        teleport(crown.display, next);
    }

    /**
     * Горизонтальный разворот к игроку по смещению {@code toPlayerX/Z}.
     *
     * <p>Корона висит почти точно над головой, и тогда горизонтальное
     * направление на игрока вырождено: в этом случае разворачиваемся навстречу
     * взгляду игрока (ровно лицом к нему), чтобы модель не дёргалась.</p>
     */
    static double facingYaw(double toPlayerX, double toPlayerZ, float playerYaw) {
        double lengthSquared = toPlayerX * toPlayerX + toPlayerZ * toPlayerZ;
        if (lengthSquared < 0.0625D) {
            return playerYaw + 180.0D;
        }
        return Math.toDegrees(Math.atan2(-toPlayerX, toPlayerZ));
    }

    /**
     * Целевая точка короны. Считается от тела игрока (не от камеры), поэтому
     * взгляд вверх или вниз положение не меняет.
     */
    private Location crownAnchor(Player player, Crown crown) {
        long tick = Bukkit.getCurrentTick();
        double phase = crown.phase;

        Location target = player.getLocation().clone();
        double top = player.getBoundingBox().getMaxY();
        target.setY(top + crownGap + idleLift(tick, phase));

        // Дрейф считается относительно взгляда игрока: корона смещается вбок
        // и чуть вперёд, а не просто вверх-вниз по одной линии.
        target.add(rightVector(target.getYaw()).multiply(IDLE_SIDE * wave(tick, IDLE_SIDE_PERIOD, phase * 1.7D)));
        target.add(forwardVector(target.getYaw())
                .multiply(IDLE_FORWARD * wave(tick, IDLE_FORWARD_PERIOD, phase * 2.9D)));
        target.add(0.0D, perkLift(crown), 0.0D);

        // Yaw берётся у тела игрока, pitch всегда нулевой: вертикального
        // вращения у короны нет.
        target.setPitch(0.0f);
        return target;
    }

    /** Сумма двух волн разной длины: покачивание не выглядит механическим. */
    static double idleLift(long tick, double phase) {
        return IDLE_LIFT_SLOW * wave(tick, IDLE_LIFT_SLOW_PERIOD, phase)
                + IDLE_LIFT_FAST * wave(tick, IDLE_LIFT_FAST_PERIOD, phase * 2.3D);
    }

    /**
     * Единая волна от -1 до 1. Периоды у всех движений разные, поэтому
     * корона не замирает в одной точке и не повторяет рисунок слишком быстро.
     */
    static double wave(long tick, long period, double phase) {
        return Math.sin(tick * (Math.PI * 2.0D / period) + phase);
    }

    /** Медленное «оглядывание» вокруг направления взгляда игрока, в градусах. */
    private static double yawSway(Crown crown) {
        // Во время «встрепенулась» корона оглядывается чуть активнее.
        double amplitude = crown.perkAge >= 0 ? crown.yawAmplitude * 1.8D : crown.yawAmplitude;
        return amplitude * wave(Bukkit.getCurrentTick(), crown.yawPeriod, crown.phase * 1.3D);
    }

    /**
     * Редкое «встрепенулась»: короткий плавный подъём и мягкий возврат.
     * Состояние перка двигается здесь, потому что метод вызывается раз в тик.
     */
    private static double perkLift(Crown crown) {
        if (crown.perkAge < 0) {
            if (--crown.perkCooldown > 0) return 0.0D;
            crown.perkAge = 0;
        }
        int age = crown.perkAge++;
        if (age >= PERK_DURATION_TICKS) {
            crown.perkAge = -1;
            crown.perkCooldown = PERK_MIN_DELAY_TICKS
                    + ThreadLocalRandom.current().nextInt(PERK_MAX_DELAY_TICKS - PERK_MIN_DELAY_TICKS);
            return 0.0D;
        }
        return PERK_LIFT * Math.sin(Math.PI * (age / (double) PERK_DURATION_TICKS));
    }

    /** «Дыхание» масштаба: корона чуть увеличивается и сжимается, как живая. */
    private void applyBreathing(Crown crown) {
        long tick = Bukkit.getCurrentTick();
        double scale = 1.0D + IDLE_SCALE * wave(tick, IDLE_SCALE_PERIOD, crown.phase * 0.7D);
        if (crown.perkAge >= 0) {
            double progress = Math.min(1.0D, crown.perkAge / (double) PERK_DURATION_TICKS);
            scale += PERK_SCALE * Math.sin(Math.PI * progress);
        }
        Transformation transformation = crown.display.getTransformation();
        transformation.getScale().set((float) scale, (float) scale, (float) scale);
        crown.display.setTransformation(transformation);
    }

    private static Vector rightVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.cos(radians), 0.0D, -Math.sin(radians));
    }

    private static Vector forwardVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0.0D, Math.cos(radians));
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

    private void showSpeech(Crown crown, String content, int lifetime) {
        removeSpeech(crown);
        Location at = speechLocation(crown.display.getLocation());
        crown.speech = SpeechText.spawn(at, content, lifetime, crown.interpolationTicks,
                speechScale, speechMarker);
        playTalkSound(at);
    }

    private void tickSpeech(Crown crown) {
        SpeechText speech = crown.speech;
        if (speech == null) return;
        if (!speech.tick(speechLocation(crown.display.getLocation()))) {
            removeSpeech(crown);
        }
    }

    private Location speechLocation(Location petLocation) {
        Location target = petLocation.clone().add(0.0D, speechHeight, 0.0D);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        return target;
    }

    private void removeSpeech(Crown crown) {
        SpeechText speech = crown.speech;
        crown.speech = null;
        if (speech != null) speech.remove();
    }

    private static String randomSingleReply() {
        return CHAT_SINGLE_REPLIES[ThreadLocalRandom.current().nextInt(CHAT_SINGLE_REPLIES.length)];
    }

    private static String randomTwoPartReply() {
        return CHAT_TWO_PART_REPLIES[ThreadLocalRandom.current().nextInt(CHAT_TWO_PART_REPLIES.length)];
    }

    /** «...» — первая часть единственной двойной надписи. */
    static String chatLeadIn() {
        return CHAT_LEAD_IN;
    }

    /** Вторые части двойной надписи. */
    static String[] chatTwoPartReplies() {
        return CHAT_TWO_PART_REPLIES.clone();
    }

    /** Одиночные реплики, которые корона говорит без «...». */
    static String[] chatSingleReplies() {
        return CHAT_SINGLE_REPLIES.clone();
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

        if (ThreadLocalRandom.current().nextBoolean()) {
            // Двойная надпись: сначала «...», через две секунды — вторая часть.
            showSpeech(crown, CHAT_LEAD_IN, CHAT_LEAD_IN_LIFETIME_TICKS);
            try {
                Bukkit.getScheduler().runTaskLater(plugin, () -> followUpReply(uuid), CHAT_REPLY_DELAY_TICKS);
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Плагин уже выключается.
            }
            return;
        }
        showSpeech(crown, randomSingleReply(), CHAT_REPLY_LIFETIME_TICKS);
    }

    /** Вторая реплика приходит через две секунды даже если игрок уже отвёл взгляд. */
    private void followUpReply(UUID uuid) {
        Crown crown = crowns.get(uuid);
        if (crown == null) return;
        ItemDisplay display = crown.display;
        if (display == null || display.isDead() || !display.isValid()) return;
        showSpeech(crown, randomTwoPartReply(), CHAT_FOLLOW_UP_LIFETIME_TICKS);
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
            if (crown.speech != null) removeEntity(crown.speech.display(), removed);
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
        /** Личная фаза и период покачивания: у каждой короны свой рисунок движения. */
        private double phase;
        private double yawAmplitude = IDLE_YAW_MIN_DEGREES;
        private int yawPeriod = IDLE_YAW_MIN_PERIOD;
        private int perkCooldown = PERK_MIN_DELAY_TICKS;
        private int perkAge = -1;
        private int gazeTicks;
        private boolean speechShownForGaze;
        private int hitFreezeTicks;
        private long lastHitTimestamp;
        private SpeechText speech;

        private Crown(ItemDisplay display, int interpolationTicks) {
            this.display = display;
            this.interpolationTicks = interpolationTicks;
        }
    }
}
