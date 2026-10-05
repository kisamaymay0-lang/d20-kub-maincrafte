package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.Dropper;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Dolphin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сигарета — тестовый админский предмет из меню {@code /f8}.
 *
 * <p>Модели из ресурспака игрока: большая ({@code sigareta-big} /
 * {@code sigareta-big-fire}, запас 64), маленькая ({@code sigareta-small} /
 * {@code sigareta-small-fire}, запас 16) и обычная ({@code sigareta} /
 * {@code sigareta-fire}, запас 32). Все называются одинаково, просто
 * «Сигарета»: имя не меняется от поджига, описания у предмета нет.
 *
 * <p>Поджиг: огниво во второй руке, сигарета в главной, шифт + ПКМ — модель
 * меняется на горящую. Любой ПКМ горящей сигаретой начинает тягу, и в воздух,
 * и по блоку. Тягу держат, отпускают — идёт выдох; для использования задан
 * {@code TOOT_HORN}. Само «съедение» запрещено в {@link #onEat}. Сигарета
 * перехватывает ПКМ у блока, чтобы действие работало в обоих случаях.
 *
 * <p>Шкала пополняется на палочку каждые 0,2 секунды. После отпускания изо рта
 * непрерывно вылетают частицы дымка серного гейзера в текущем направлении
 * взгляда; каждая набранная палочка даёт ровно 0,2 секунды выдоха. Запас
 * зависит от модели: 64, 16 или 32 палочки; когда запас кончается, сигарета
 * ломается и исчезает. Стак — до восьми сигарет.
 */
final class Cigarette implements Listener {

    /** Название обоих видов: обычное, белое, без описаний. */
    private static final String TITLE = "Сигарета";

    /** Палочек в одной тяге и скрытый запас вариантов сигареты. */
    static final int BARS_PER_PUFF = 16;
    static final int BIG_BARS_PER_PUFF = 32;
    static final int RESERVE = BIG_BARS_PER_PUFF * 2;
    static final int SMALL_RESERVE = 16;
    static final int REGULAR_RESERVE = 32;

    /** Метки состояния и варианта модели в самом предмете. */
    private static final NamespacedKey LIT = new NamespacedKey("f8-plugin", "cigarette_lit");
    private static final NamespacedKey VARIANT = new NamespacedKey("f8-plugin", "cigarette_variant");
    /** Невидимый запас: сколько палочек тяги осталось. */
    private static final NamespacedKey BARS = new NamespacedKey("f8-plugin", "cigarette_bars");
    /** Невидимое количество пороха в начинке сигареты. */
    static final NamespacedKey FILLING_GUNPOWDER = new NamespacedKey("f8-plugin", "cigarette_filling_gunpowder");
    /** Невидимое количество сахара в начинке сигареты. */
    static final NamespacedKey FILLING_SUGAR = new NamespacedKey("f8-plugin", "cigarette_filling_sugar");
    /** Невидимое количество кристаллов призмарина в начинке сигареты. */
    static final NamespacedKey FILLING_PRISMARINE = new NamespacedKey("f8-plugin", "cigarette_filling_prismarine");
    /** Порог передоза пороха (больше 4, т.е. 5+ пороха — взрыв крипера). */
    static final int GUNPOWDER_OVERDOSE_THRESHOLD = 5;
    /** Порог кристаллов призмарина для получения тошноты I (5+ кристаллов). */
    static final int PRISMARINE_NAUSEA_THRESHOLD = 5;
    /** Длительность эффектов призмарина: 4 секунды за каждую потраченную палочку затяжки. */
    static final int PRISMARINE_SECONDS_PER_BAR = 4;

    record SugarTier(
            int speedAmplifier,
            boolean haste,
            int secondsPerBar,
            boolean involuntaryLmb,
            boolean involuntaryWalk,
            int walkDurationTicks,
            double deathChance,
            boolean nausea,
            boolean waxParticles,
            int cameraJerkTier
    ) {}

    /**
     * Градация эффектов сахара по количеству крупиц:
     * 1..4: Скорость I на 1с за палочку затяжки;
     * 5..8: Скорость I + Спешка I на 1с за палочку;
     * 9..12: Скорость II + Спешка I на 1с за палочку + непроизвольные клики ЛКМ;
     * 13..16: Скорость II + Спешка I на 2с за палочку + ЛКМ + непрерывная ходьба ~0.5с (10 тиков) + частицы воска + рывки камеры (тир 1);
     * 17..23: Скорость II + Спешка I + Тошнота I на 4с за палочку + ЛКМ + ходьба ~1с (20 тиков) + частицы воска + резкие рывки камеры (тир 2) + 1% шанс мгновенной смерти от остановки сердца в тик;
     * 24+: Скорость II + Спешка I + Тошнота I на 4с за палочку + ЛКМ + ходьба ~1с (20 тиков) + частицы воска + частые и сильные рывки камеры (тир 3) + 5% шанс мгновенной смерти от остановки сердца в тик.
     */
    static SugarTier sugarTier(int sugar) {
        if (sugar <= 0) {
            return null;
        }
        if (sugar <= 4) {
            return new SugarTier(0, false, 1, false, false, 0, 0.0, false, false, 0);
        }
        if (sugar <= 8) {
            return new SugarTier(0, true, 1, false, false, 0, 0.0, false, false, 0);
        }
        if (sugar <= 12) {
            return new SugarTier(1, true, 1, true, false, 0, 0.0, false, false, 0);
        }
        if (sugar <= 16) {
            return new SugarTier(1, true, 2, true, true, 10, 0.0, false, true, 1);
        }
        if (sugar <= 23) {
            return new SugarTier(1, true, 4, true, true, 20, 0.01, true, true, 2);
        }
        return new SugarTier(1, true, 4, true, true, 20, 0.05, true, true, 3);
    }

    enum Variant {
        BIG("big", "sigareta-big", "sigareta-big-fire", RESERVE),
        SMALL("small", "sigareta-small", "sigareta-small-fire", SMALL_RESERVE),
        REGULAR("regular", "sigareta", "sigareta-fire", REGULAR_RESERVE);

        final String id;
        final String coldModel;
        final String litModel;
        final int reserve;

        Variant(String id, String coldModel, String litModel, int reserve) {
            this.id = id;
            this.coldModel = coldModel;
            this.litModel = litModel;
            this.reserve = reserve;
        }
    }

    /** Одна палочка — 0,2 секунды тяги. */
    private static final int TICKS_PER_BAR = 4;
    /** Сигареты объединяются максимум по восемь штук в стаке. */
    static final int MAX_STACK_SIZE = 8;
    /** Порог и скользящее окно для эффекта тошноты. */
    static final int NAUSEA_THRESHOLD_BARS = 64;
    static final int SMOKING_WINDOW_TICKS = 20 * 60;
    static final int NAUSEA_DURATION_TICKS = 20 * 15;
    static final int FIRST_WITHDRAWAL_TICKS = 24_000;
    static final int WITHDRAWAL_STAGE_TICKS = 20 * 60 * 4;
    private static final long WITHDRAWAL_MESSAGE_REPEAT_TICKS = 40L;
    private static final Component EARLY_CRAVING_MESSAGE = Component.text(
            "Эх... сигаретку бы закурить...", NamedTextColor.GRAY);
    private static final Component SEVERE_CRAVING_MESSAGE = Component.text(
            "Ну же! Закури чего нибудь!...", NamedTextColor.RED);
    /** Долгое использование: выдох происходит по отпусканию ПКМ, не по таймеру. */
    private static final float USE_SECONDS = 3600.0F;
    /** Тихий ванильный треск горящего огня, повторяемый при затяжке. */
    private static final String INHALE_SOUND = "minecraft:block.fire.ambient";
    private static final long INHALE_SOUND_REPEAT_TICKS = 20L;
    /** Ванильные идентификаторы: строковые ключи не требуют Bukkit registry в unit-тестах. */
    private static final String LIGHT_HISS_SOUND = "minecraft:block.fire.extinguish";
    private static final String EXHALE_SOUND = "minecraft:block.campfire.crackle";
    /** Звука во время тяги быть не должно: специально «пустой» ванильный звук. */
    private static final Key SILENT_SOUND = Key.key("intentionally_empty");
    /** Как часто перерисовываем шкалу и проверяем, не кончился ли запас. */
    private static final long PERIOD = 2L;
    /** Начало струи дыма: чуть впереди и ниже глаз, чтобы шёл изо рта. */
    private static final double MOUTH_AHEAD = 0.28D;
    private static final double MOUTH_DOWN = 0.12D;
    /** Расстояние выброса дыма перед ртом. */
    private static final double SMOKE_PUSH = 0.04D;

    private final JavaPlugin plugin;
    private final int firstWithdrawalTicks;
    private final int withdrawalStageTicks;

    /** Кто сейчас тянет сигарету: рука и тик начала. */
    private final Map<UUID, Puff> puffs = new HashMap<>();

    /** Летящий дым: по одному потоку на игрока, новый обрывает старый. */
    private final Map<UUID, Smoke> smoke = new HashMap<>();

    /** Дымы, запущенные редстоун-выбрасывателями. */
    private final Set<DeviceSmoke> deviceSmokes = new HashSet<>();
    private final Map<Block, Integer> machineActivationTicks = new HashMap<>();

    /** Задачи повторения звука вдоха, пока игрок держит ПКМ. */
    private final Map<UUID, BukkitTask> inhaleSounds = new HashMap<>();

    /** Тик поджига: второе событие руки в том же нажатии не начинает тягу. */
    private final Map<UUID, Integer> litAtTick = new HashMap<>();

    /** Скользящие окна тяг: записи добавляются только при фактическом списании палочек. */
    private final Map<UUID, SmokingWindow> smokingWindows = new HashMap<>();

    /** Активные эффекты и передозировки сахара. */
    private final Map<UUID, SugarSession> sugarSessions = new HashMap<>();
    private final Set<UUID> cardiacArrestVictims = new HashSet<>();
    private BukkitTask sugarTicker;

    /** Активные эффекты призмарина и галлюцинации. */
    private final Map<UUID, PrismarineSession> prismarineSessions = new HashMap<>();
    private final List<HallucinationEntity> hallucinations = new ArrayList<>();

    /** Зависимость: один отложенный переход на игрока, активные сообщения — общая задача. */
    private final Map<UUID, AddictionState> addictions = new HashMap<>();
    private final Set<UUID> warningPlayers = new HashSet<>();
    private BukkitTask withdrawalMessagesTicker;

    private BukkitTask ticker;

    /** Тяга: рука, в которой сигарета, и тик, с которого игрок держит ПКМ. */
    private record Puff(EquipmentSlot hand, int since, int capacity) { }

    private record SavedPotionEffect(PotionEffect effect, int capturedTick) { }

    private static final class SugarSession {
        private int remainingTicks;
        private final boolean hasLmb;
        private final boolean hasWalk;
        private final int walkDurationTicks;
        private final double deathChance;
        private final boolean hasNausea;
        private final boolean hasWaxParticles;
        private final int cameraJerkTier;

        private int lmbCooldown;
        private int walkCooldown;
        private int walkTicksRemaining;
        private Vector walkDirection;
        private int cameraJerkCooldown;

        SugarSession(int remainingTicks, boolean hasLmb, boolean hasWalk, int walkDurationTicks,
                     double deathChance, boolean hasNausea, boolean hasWaxParticles, int cameraJerkTier) {
            this.remainingTicks = remainingTicks;
            this.hasLmb = hasLmb;
            this.hasWalk = hasWalk;
            this.walkDurationTicks = walkDurationTicks;
            this.deathChance = deathChance;
            this.hasNausea = hasNausea;
            this.hasWaxParticles = hasWaxParticles;
            this.cameraJerkTier = cameraJerkTier;

            this.lmbCooldown = ThreadLocalRandom.current().nextInt(15, 35);
            this.walkCooldown = ThreadLocalRandom.current().nextInt(20, 45);
            this.walkTicksRemaining = 0;
            this.walkDirection = new Vector(0, 0, 0);
            this.cameraJerkCooldown = nextCameraJerkCooldown(cameraJerkTier);
        }

        static int nextCameraJerkCooldown(int jerkTier) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (jerkTier == 1) {
                return random.nextInt(25, 45);
            } else if (jerkTier == 2) {
                return random.nextInt(14, 28);
            } else if (jerkTier >= 3) {
                return random.nextInt(7, 15);
            }
            return 100;
        }
    }

    static final NamedTextColor[] DOLPHIN_COLORS = {
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.AQUA,
            NamedTextColor.YELLOW,
            NamedTextColor.GREEN,
            NamedTextColor.GOLD,
            NamedTextColor.DARK_PURPLE,
            NamedTextColor.BLUE,
            NamedTextColor.RED
    };

    private static String dolphinTeamName(NamedTextColor color) {
        String name = "apvsh_d_" + color.toString().toLowerCase();
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    private static final class PrismarineSession {
        private int remainingTicks;
        private final int crystalCount;
        private int creatureCooldown;

        PrismarineSession(int remainingTicks, int crystalCount) {
            this.remainingTicks = remainingTicks;
            this.crystalCount = crystalCount;
            this.creatureCooldown = ThreadLocalRandom.current().nextInt(15, 45);
        }
    }

    private static final class HallucinationEntity {
        final Entity entity;
        final Player player;
        final String teamName;
        double posX;
        double posY;
        double posZ;
        final double velocityX;
        final double velocityZ;
        double pitchPhase;
        final double pitchSpeed;
        final float yaw;
        int lifeTicks;

        HallucinationEntity(Entity entity, Player player, String teamName,
                            double posX, double posY, double posZ,
                            double velocityX, double velocityZ,
                            float yaw, int lifeTicks) {
            this.entity = entity;
            this.player = player;
            this.teamName = teamName;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.velocityX = velocityX;
            this.velocityZ = velocityZ;
            this.pitchPhase = ThreadLocalRandom.current().nextDouble(0, 2 * Math.PI);
            this.pitchSpeed = ThreadLocalRandom.current().nextDouble(0.025, 0.055);
            this.yaw = yaw;
            this.lifeTicks = lifeTicks;
        }
    }

    private static void removeHallucination(HallucinationEntity h) {
        if (h.entity != null) {
            if (h.teamName != null) {
                try {
                    Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team team = scoreboard.getTeam(h.teamName);
                    if (team != null) {
                        team.removeEntry(h.entity.getUniqueId().toString());
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                if (h.entity.isValid()) {
                    h.entity.remove();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class AddictionState {
        /** 0 — отсчёт суток; 1..3 — стадии ломки. */
        private int stage;
        private int lastSmokeTick;
        private BukkitTask transitionTask;
        private final Map<PotionEffectType, SavedPotionEffect> previousEffects = new HashMap<>();
        private final Map<PotionEffectType, PotionEffect> managedEffects = new HashMap<>();
    }

    Cigarette(JavaPlugin plugin) {
        this.plugin = plugin;
        this.firstWithdrawalTicks = configuredTicks(plugin, "cigarette.addiction.first-stage-ticks",
                FIRST_WITHDRAWAL_TICKS);
        this.withdrawalStageTicks = configuredTicks(plugin, "cigarette.addiction.interval-ticks",
                WITHDRAWAL_STAGE_TICKS);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        sugarTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickEffects, 1L, 1L);
    }

    /** Выключение: гасим шкалу, дым и текущие тяги. */
    void disable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (sugarTicker != null) {
            sugarTicker.cancel();
            sugarTicker = null;
        }
        sugarSessions.clear();
        for (HallucinationEntity h : hallucinations) {
            removeHallucination(h);
        }
        hallucinations.clear();
        prismarineSessions.clear();
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            for (NamedTextColor color : DOLPHIN_COLORS) {
                Team team = scoreboard.getTeam(dolphinTeamName(color));
                if (team != null) {
                    team.unregister();
                }
            }
        } catch (Throwable ignored) {
        }
        cardiacArrestVictims.clear();
        for (Smoke flow : smoke.values()) {
            flow.stop();
        }
        smoke.clear();
        for (DeviceSmoke flow : new ArrayList<>(deviceSmokes)) flow.stop();
        deviceSmokes.clear();
        machineActivationTicks.clear();
        puffs.clear();
        litAtTick.clear();
        smokingWindows.clear();
        if (withdrawalMessagesTicker != null) {
            withdrawalMessagesTicker.cancel();
            withdrawalMessagesTicker = null;
        }
        for (Map.Entry<UUID, AddictionState> entry : addictions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                clearWithdrawalEffects(player, entry.getValue());
                if (entry.getValue().stage > 0) {
                    player.sendActionBar(Component.empty());
                }
            }
            cancelTransition(entry.getValue());
        }
        addictions.clear();
        warningPlayers.clear();
        for (BukkitTask task : inhaleSounds.values()) {
            task.cancel();
        }
        inhaleSounds.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.stopSound(INHALE_SOUND, SoundCategory.BLOCKS);
        }
    }

    /** Холодная большая сигарета: модель sigareta-big, запас 64. */
    static ItemStack cold() {
        return create(false, Variant.BIG);
    }

    /** Горящая большая сигарета: модель sigareta-big-fire, запас 64. */
    static ItemStack lit() {
        return create(true, Variant.BIG);
    }

    static ItemStack coldSmall() {
        return create(false, Variant.SMALL);
    }

    static ItemStack litSmall() {
        return create(true, Variant.SMALL);
    }

    static ItemStack coldRegular() {
        return create(false, Variant.REGULAR);
    }

    static ItemStack litRegular() {
        return create(true, Variant.REGULAR);
    }

    /**
     * Вариант сигареты по количеству начинки:
     * 1..8 — маленькая (sigareta-small, запас 16),
     * 9..20 — обычная (sigareta, запас 32),
     * 21+ — большая (sigareta-big, запас 64).
     */
    static Variant variantForFilling(int count) {
        if (count <= 8) {
            return Variant.SMALL;
        }
        if (count <= 20) {
            return Variant.REGULAR;
        }
        return Variant.BIG;
    }

    /** Создаёт сигарету с заданной начинкой кристаллов призмарина. */
    static ItemStack createWithPrismarine(int prismarine) {
        return createWithFilling(0, 0, prismarine);
    }

    /** Создаёт сигарету с заданной начинкой пороха. Модель выбирается по количеству пороха. */
    static ItemStack createWithGunpowder(int gunpowder) {
        return createWithFilling(gunpowder, 0, 0);
    }

    /** Создаёт сигарету с заданной начинкой сахара. */
    static ItemStack createWithSugar(int sugar) {
        return createWithFilling(0, sugar, 0);
    }

    /** Создаёт сигарету с заданной начинкой пороха и сахара. Модель выбирается по суммарной начинке. */
    static ItemStack createWithFilling(int gunpowder, int sugar) {
        return createWithFilling(gunpowder, sugar, 0);
    }

    /** Создаёт сигарету с заданной начинкой пороха, сахара и призмарина. Модель выбирается по суммарной начинке. */
    static ItemStack createWithFilling(int gunpowder, int sugar, int prismarine) {
        int total = gunpowder + sugar + prismarine;
        Variant variant = variantForFilling(total > 0 ? total : 1);
        return create(false, variant, gunpowder, sugar, prismarine);
    }

    static ItemStack create(boolean lit, Variant variant, int gunpowder) {
        return create(lit, variant, gunpowder, 0, 0);
    }

    static ItemStack create(boolean lit, Variant variant, int gunpowder, int sugar) {
        return create(lit, variant, gunpowder, sugar, 0);
    }

    static ItemStack create(boolean lit, Variant variant, int gunpowder, int sugar, int prismarine) {
        ItemStack item = create(lit, variant);
        if (gunpowder > 0 || sugar > 0 || prismarine > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (gunpowder > 0) {
                    meta.getPersistentDataContainer().set(FILLING_GUNPOWDER, PersistentDataType.INTEGER, gunpowder);
                }
                if (sugar > 0) {
                    meta.getPersistentDataContainer().set(FILLING_SUGAR, PersistentDataType.INTEGER, sugar);
                }
                if (prismarine > 0) {
                    meta.getPersistentDataContainer().set(FILLING_PRISMARINE, PersistentDataType.INTEGER, prismarine);
                }
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /** Сколько пороха содержится в сигарете. */
    static int gunpowderFilling(ItemStack item) {
        if (!isCigarette(item)) {
            return 0;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer().get(FILLING_GUNPOWDER, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    /** Сколько сахара содержится в сигарете. */
    static int sugarFilling(ItemStack item) {
        if (!isCigarette(item)) {
            return 0;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer().get(FILLING_SUGAR, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    /** Сколько кристаллов призмарина содержится в сигарете. */
    static int prismarineFilling(ItemStack item) {
        if (!isCigarette(item)) {
            return 0;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer().get(FILLING_PRISMARINE, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    /**
     * Сама сигарета: бумага, белое имя без описаний, модель из ресурспака и
     * съедобный компонент — он и даёт анимацию трубения в козий рог с держанием
     * ПКМ. Звука нет, частиц нет, «съесть» предмет нельзя: {@link #onEat}
     * запрещает завершение. На старом ядре без компонента сигарета просто
     * останется бумажкой, как и кувшин без своего компонента.
     */
    static ItemStack create(boolean lit) {
        return create(lit, Variant.BIG);
    }

    private static ItemStack create(boolean lit, Variant variant) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(TITLE, NamedTextColor.WHITE));
        meta.setItemModel(new NamespacedKey("f8resurs", lit ? variant.litModel : variant.coldModel));
        meta.getPersistentDataContainer().set(LIT, PersistentDataType.INTEGER, lit ? 1 : 0);
        meta.getPersistentDataContainer().set(VARIANT, PersistentDataType.STRING, variant.id);
        meta.getPersistentDataContainer().set(BARS, PersistentDataType.INTEGER, variant.reserve);
        item.setItemMeta(meta);
        // Метка еды с canAlwaysEat: без неё ваниль не начинает «использование»
        // предмета, когда игрок сыт, и тяга не запускалась бы. Сама еда нулевая,
        // а её строка в подсказке спрятана, чтобы у сигареты не было лишнего текста.
        apply(item, DataComponentTypes.FOOD, FoodProperties.food()
                .nutrition(0)
                .saturation(0f)
                .canAlwaysEat(true)
                .build(), "food");
        apply(item, DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.FOOD)
                .build(), "tooltip_display");
        // Длительное использование: тяга продолжается до отпускания ПКМ.
        // TOOT_HORN задаёт стандартную клиентскую анимацию использования предмета.
        apply(item, DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(USE_SECONDS)
                .animation(ItemUseAnimation.TOOT_HORN)
                .sound(SILENT_SOUND)
                .hasConsumeParticles(false)
                .build(), "consumable");
        apply(item, DataComponentTypes.MAX_STACK_SIZE, MAX_STACK_SIZE, "max_stack_size");
        try {
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                    .swappable(false)
                    .dispensable(false)
                    .build());
        } catch (Throwable ignored) {
        }
        return item;
    }

    /**
     * Ставит компонент, не давая одному сломанному утащить остальные: без
     * {@code consumable} ваниль не начинает «использование» предмета, и тяга не
     * запускалась бы совсем. Причина видна в консоли, а не тонет в тишине.
     */
    private static <T> void apply(ItemStack item, DataComponentType.Valued<T> type, T value, String name) {
        try {
            item.setData(type, value);
        } catch (Throwable failure) {
            Bukkit.getLogger().warning("[Сигарета] компонент " + name + " не встал: " + failure);
        }
    }

    /** Сигарета ли это: у предмета есть метка вида. */
    static boolean isCigarette(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(LIT, PersistentDataType.INTEGER);
    }

    /** Горящая сигарета или ещё нет. */
    static boolean isLit(ItemStack item) {
        if (!isCigarette(item)) {
            return false;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer().get(LIT, PersistentDataType.INTEGER);
        return value != null && value == 1;
    }

    /** Сколько палочек тяги осталось в запасе. */
    static int bars(ItemStack item) {
        if (!isCigarette(item)) {
            return 0;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer().get(BARS, PersistentDataType.INTEGER);
        int reserve = reserve(item);
        return value == null ? reserve : Math.clamp(value, 0, reserve);
    }

    private static Variant variant(ItemStack item) {
        if (!isCigarette(item)) {
            return Variant.BIG;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(VARIANT, PersistentDataType.STRING);
        if (id != null) {
            for (Variant variant : Variant.values()) {
                if (variant.id.equals(id)) {
                    return variant;
                }
            }
        }
        // Предметы из предыдущей версии не имели метки варианта: они большие.
        return Variant.BIG;
    }

    private static int reserve(ItemStack item) {
        return variant(item).reserve;
    }

    private static int barsPerPuff(ItemStack item) {
        return variant(item) == Variant.BIG ? BIG_BARS_PER_PUFF : BARS_PER_PUFF;
    }

    /** Redstone droppers smoke 16 bars from a cigarette instead of ejecting it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMachineDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        Material blockType = block.getType();
        if (blockType != Material.DROPPER) return;
        Inventory inventory = machineInventory(block);
        if (inventory == null || (!isCigarette(event.getItem()) && !containsCigarette(inventory))) return;
        if (isDeviceSmoking(block)) {
            event.setCancelled(true);
            return;
        }
        if (!claimMachineActivation(block)) {
            event.setCancelled(true);
            return;
        }
        // The dispensed slot is not guaranteed to be the cigarette's slot. If a
        // dropper fires with a cigarette in it, consume it and suppress this activation.
        event.setCancelled(true);
        if (!smokeFromInventory(block, inventory, event.getItem())) {
            // Some implementations remove the selected stack before firing the
            // event. Once cancelled, its return to the source is completed by next tick.
            scheduleMachineSmoke(block, event.getItem().clone());
        }
    }

    /** Dropper-to-container transfers do not consistently fire BlockDispenseEvent. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropperTransfer(InventoryMoveItemEvent event) {
        if (!(event.getSource().getHolder() instanceof Dropper source)) return;
        // A hopper can pull items out of a dropper. Only treat transfers initiated
        // by the dropper itself as its activation; equality also covers wrappers
        // whose inventory holder is not exposed as a Dropper instance.
        if (event.getInitiator() != event.getSource()
                && (!(event.getInitiator().getHolder() instanceof Dropper initiator)
                || !source.getBlock().equals(initiator.getBlock()))) return;
        Block block = source.getBlock();
        ItemStack requested = event.getItem().clone();
        if (!isCigarette(requested) && !containsCigarette(event.getSource())) return;
        if (isDeviceSmoking(block)) {
            event.setCancelled(true);
            return;
        }
        if (!claimMachineActivation(block)) {
            event.setCancelled(true);
            return;
        }
        // The selected cigarette can already be removed from source by event time.
        // Cancel first; its return completes before the scheduled inventory update.
        event.setCancelled(true);
        scheduleMachineSmoke(block, requested);
    }

    private void scheduleMachineSmoke(Block block, ItemStack requested) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inventory = machineInventory(block);
            if (inventory != null && !isDeviceSmoking(block)) {
                smokeFromInventory(block, inventory, requested);
            }
        });
    }

    private boolean isDeviceSmoking(Block block) {
        for (DeviceSmoke flow : deviceSmokes) {
            if (flow.block.equals(block)) return true;
        }
        return false;
    }

    private static boolean containsCigarette(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isCigarette(inventory.getItem(slot))) return true;
        }
        return false;
    }

    private boolean claimMachineActivation(Block block) {
        int now = Bukkit.getCurrentTick();
        machineActivationTicks.entrySet().removeIf(entry -> now - entry.getValue() > 2);
        Integer previous = machineActivationTicks.get(block);
        if (previous != null && now - previous <= 1) return false;
        machineActivationTicks.put(block, now);
        return true;
    }

    private static Inventory machineInventory(Block block) {
        BlockState state = block.getState();
        if (state instanceof Dropper dropper) return dropper.getInventory();
        return null;
    }

    /** Consume one 16-bar dropper puff, update its inventory, then vent outward. */
    private boolean smokeFromInventory(Block block, Inventory inventory, ItemStack dispensed) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) return false;
        Vector facing = directional.getFacing().getDirection().normalize();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stored = inventory.getItem(slot);
            if (!isCigarette(stored)) continue;
            if (isCigarette(dispensed) && !sameCigaretteType(stored, dispensed)) continue;
            int spent = dropperBarsToSmoke(bars(stored));
            if (spent <= 0) return false;
            int remaining = bars(stored) - spent;
            if (remaining > 0) {
                setBars(stored, remaining);
                inventory.setItem(slot, stored);
            } else if (stored.getAmount() > 1) {
                stored.setAmount(stored.getAmount() - 1);
                setBars(stored, reserve(stored));
                inventory.setItem(slot, stored);
            } else {
                inventory.clear(slot);
            }
            smoke(block, facing, spent);
            int gunpowder = gunpowderFilling(stored);
            if (gunpowder > 0) {
                Location origin = block.getLocation().add(0.5, 0.5, 0.5).add(facing.clone().multiply(0.55));
                if (gunpowder >= GUNPOWDER_OVERDOSE_THRESHOLD) {
                    block.getWorld().createExplosion(origin, 2.8F, false, true);
                } else {
                    block.getWorld().spawnParticle(Particle.EXPLOSION, origin, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
            return true;
        }
        return false;
    }

    private static boolean sameCigaretteType(ItemStack first, ItemStack second) {
        return variant(first) == variant(second)
                && isLit(first) == isLit(second)
                && gunpowderFilling(first) == gunpowderFilling(second)
                && sugarFilling(first) == sugarFilling(second)
                && prismarineFilling(first) == prismarineFilling(second);
    }

    static int dropperBarsToSmoke(int available) {
        return Math.clamp(available, 0, BARS_PER_PUFF);
    }

    private void smoke(Block block, Vector facing, int bars) {
        DeviceSmoke flow = new DeviceSmoke(block, facing, bars);
        deviceSmokes.add(flow);
        flow.start();
    }

    private final class DeviceSmoke implements Runnable {
        private final Block block;
        private final Vector facing;
        private final int total;
        private final int perTick;
        private int tick;
        private BukkitTask task;

        DeviceSmoke(Block block, Vector facing, int bars) {
            this.block = block;
            this.facing = facing.clone();
            this.total = exhaleTicks(bars);
            this.perTick = smokeParticlesPerTick(bars);
        }

        void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 0L, 1L);
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            deviceSmokes.remove(this);
        }

        @Override
        public void run() {
            if (tick >= total || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                stop();
                return;
            }
            World world = block.getWorld();
            Location origin = block.getLocation().add(0.5, 0.5, 0.5).add(facing.clone().multiply(0.55));
            for (int i = 0; i < perTick; i++) {
                double distance = 0.12D + (i % 4) * 0.16D;
                Location spot = origin.clone().add(facing.clone().multiply(distance)).add(jitter());
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, spot, 0,
                        facing.getX() * SMOKE_PUSH,
                        facing.getY() * SMOKE_PUSH + 0.01D,
                        facing.getZ() * SMOKE_PUSH,
                        1.0D);
            }
            tick++;
        }

        private Vector jitter() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            return new Vector((random.nextDouble() - 0.5D) * 0.04D,
                    (random.nextDouble() - 0.5D) * 0.04D,
                    (random.nextDouble() - 0.5D) * 0.04D);
        }
    }

    /**
     * Любой обычный ПКМ горящей сигаретой запускает тягу — и в воздух, и по
     * блоку. Сигарета забирает клик у блока, чтобы ваниль не погасила начало
     * использования предмета; огниво в другой руке зажигает её с шифт + ПКМ.
     * Поджиг сам по себе не начинает тягу: её запускает отдельное нажатие.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (smoke.containsKey(playerId) && isCigarette(handItem(player, hand))) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        int now = Bukkit.getCurrentTick();
        Integer litTick = litAtTick.get(playerId);
        if (litTick != null) {
            if (now <= litTick) {
                // Поджиг и второе событие другой руки — всё ещё один жест ПКМ.
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
            litAtTick.remove(playerId);
        }

        if (isCigarette(handItem(player, hand))) {
            // Сигареты в обеих руках: клик считаем один раз, по главной руке.
            if (hand == EquipmentSlot.OFF_HAND && isCigarette(player.getInventory().getItemInMainHand())) {
                return;
            }
            ItemStack cigarette = handItem(player, hand);
            boolean litBeforeClick = isLit(cigarette);
            if (player.isSneaking() && !litBeforeClick
                    && isFlintAndSteel(handItem(player, other(hand)))) {
                light(player, hand);
            }
            if (!litBeforeClick && isLit(handItem(player, hand))) {
                // Поджиг только меняет модель. Для начала тяги нужно новое нажатие ПКМ.
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
            if (isLit(handItem(player, hand))) {
                Block clicked = event.getClickedBlock();
                if (clicked == null && action == Action.RIGHT_CLICK_AIR) {
                    try {
                        clicked = player.getTargetBlockExact(4);
                    } catch (Throwable ignored) {
                    }
                }
                boolean functional = isFunctionalBlock(clicked);

                if (!player.isSneaking() && functional) {
                    // Игрок смотрит и достает до функционального блока: используется блок, курение не начинается
                    event.setUseInteractedBlock(Event.Result.ALLOW);
                    event.setUseItemInHand(Event.Result.DENY);
                    return;
                }

                // Зажатие ПКМ с шифтом игнорирует все функциональные блоки
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.ALLOW);
                hold(player, hand);
            } else {
                event.setUseItemInHand(Event.Result.DENY);
            }
            return;
        }

        // Огниво в этой руке, холодная сигарета в другой: тот же поджиг, только руками наоборот.
        if (player.isSneaking() && isFlintAndSteel(handItem(player, hand))) {
            if (hand == EquipmentSlot.OFF_HAND && isFlintAndSteel(player.getInventory().getItemInMainHand())) {
                return;
            }
            EquipmentSlot cigaretteHand = other(hand);
            ItemStack cigarette = handItem(player, cigaretteHand);
            if (isCigarette(cigarette) && !isLit(cigarette)) {
                light(player, cigaretteHand);
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
            }
        }
    }

    /** Отпустили ПКМ: сколько набилось затяжек — столько дыма. */
    @EventHandler
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        if (!isCigarette(event.getItem())) {
            return;
        }
        Puff puff = puffs.remove(event.getPlayer().getUniqueId());
        if (puff == null) {
            return;
        }
        finish(event.getPlayer(), puff);
    }

    /** Сигарету нельзя съесть: ваниль закончила «использование» — закрываем тягу. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        if (!isCigarette(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Puff puff = puffs.remove(event.getPlayer().getUniqueId());
        if (puff != null) {
            finish(event.getPlayer(), puff);
        }
    }

    /** Смена слота в хотбаре немедленно закрывает тягу. */
    @EventHandler
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Puff puff = puffs.remove(event.getPlayer().getUniqueId());
        if (puff != null) {
            finish(event.getPlayer(), puff);
        }
    }

    /**
     * Помещение сигареты в слот шлема: чисто косметический предмет на голове,
     * не дающий брони или других параметров.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Клик по слоту шлема (raw slot 5)
        if (event.getSlotType() == InventoryType.SlotType.ARMOR && event.getRawSlot() == 5) {
            ItemStack cursor = event.getCursor();
            if (isCigarette(cursor)) {
                ItemStack currentHelmet = event.getCurrentItem();
                event.setCancelled(true);
                event.setCurrentItem(cursor);
                event.getWhoClicked().setItemOnCursor(currentHelmet);
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 1.0F, 1.0F);
                return;
            }
        }

        // Shift + клик по сигарете надевает её в слот шлема, если он пуст
        if (event.isShiftClick()) {
            ItemStack clicked = event.getCurrentItem();
            if (isCigarette(clicked)) {
                ItemStack helmet = player.getInventory().getHelmet();
                if (helmet == null || helmet.getType() == Material.AIR) {
                    event.setCancelled(true);
                    event.setCurrentItem(null);
                    player.getInventory().setHelmet(clicked);
                    player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 1.0F, 1.0F);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        sugarSessions.remove(id);
        prismarineSessions.remove(id);
        hallucinations.removeIf(h -> {
            if (h.player.getUniqueId().equals(id)) {
                removeHallucination(h);
                return true;
            }
            return false;
        });
        cardiacArrestVictims.remove(id);
        puffs.remove(id);
        stopInhaleSound(player);
        litAtTick.remove(id);
        smokingWindows.remove(id);
        AddictionState addiction = addictions.get(id);
        if (addiction != null && addiction.stage > 0) {
            clearWithdrawalEffects(player, addiction);
            player.sendActionBar(Component.empty());
            warningPlayers.remove(id);
            stopWithdrawalMessagesIfIdle();
        }
        Smoke flow = smoke.remove(id);
        if (flow != null) {
            flow.stop();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (HallucinationEntity h : hallucinations) {
            if (h.entity != null && h.entity.isValid() && !h.player.getUniqueId().equals(id)) {
                try {
                    event.getPlayer().hideEntity(plugin, h.entity);
                } catch (Throwable ignored) {
                }
            }
        }
        AddictionState addiction = addictions.get(id);
        if (addiction == null) {
            return;
        }
        if (addiction.stage >= 1 && addiction.stage <= 3) {
            applyWithdrawalStage(event.getPlayer(), addiction);
            startWithdrawalMessages(event.getPlayer(), addiction);
        }
    }

    /** Запускает звук затяжки и повторяет короткую звуковую петлю, пока держат ПКМ. */
    private void startInhaleSound(Player player) {
        stopInhaleSound(player);
        UUID id = player.getUniqueId();
        playInhaleSound(player);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !puffs.containsKey(id)) {
                stopInhaleSound(player);
                return;
            }
            playInhaleSound(player);
        }, INHALE_SOUND_REPEAT_TICKS, INHALE_SOUND_REPEAT_TICKS);
        inhaleSounds.put(id, task);
    }

    private static void playInhaleSound(Player player) {
        player.playSound(player.getLocation(), INHALE_SOUND, SoundCategory.BLOCKS, 0.16F, 1.25F);
    }

    private void stopInhaleSound(Player player) {
        BukkitTask task = inhaleSounds.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        player.stopSound(INHALE_SOUND, SoundCategory.BLOCKS);
    }

    /**
     * Каждые два тика обновляем шкалу. Запас списывается только при отпускании
     * ПКМ, чтобы полная тяга малой сигареты тоже выдыхалась именно после отпускания.
     */
    private void tick() {
        int tick = Bukkit.getCurrentTick();
        litAtTick.entrySet().removeIf(entry -> entry.getValue() < tick);
        for (Iterator<Map.Entry<UUID, Puff>> it = puffs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Puff> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Puff puff = entry.getValue();
            if (player == null) {
                it.remove();
                BukkitTask soundTask = inhaleSounds.remove(entry.getKey());
                if (soundTask != null) {
                    soundTask.cancel();
                }
                continue;
            }
            ItemStack item = handItem(player, puff.hand());
            if (!isCigarette(item) || !isLit(item)) {
                // Сигарета ушла из руки или погасла: тяга закрывается сама.
                it.remove();
                finish(player, puff);
                continue;
            }
            int left = bars(item);
            int capacity = puff.capacity();
            int heldTicks = tick - puff.since();

            // Если игрок отпустил ПКМ (или кликнул без удержания), тяга завершается
            if (heldTicks >= 4) {
                try {
                    if (!player.isHandRaised()) {
                        it.remove();
                        finish(player, puff);
                        continue;
                    }
                } catch (Throwable ignored) {
                }
            }

            int filled = barsFor(heldTicks, left, capacity);

            int gunpowder = gunpowderFilling(item);
            if (gunpowder > 0 && heldTicks >= 4) {
                // Шанс неожиданного срыва тяги при наличии пороха: першит/срывается и сразу начинается выдох/взрыв
                double interruptChance = Math.min(0.20, 0.03 + gunpowder * 0.003);
                if (ThreadLocalRandom.current().nextDouble() < interruptChance) {
                    it.remove();
                    finish(player, puff);
                    continue;
                }
            }

            showGauge(player, filled, capacity);
        }
    }

    /** Тяга началась: сигарета поднята ко рту, шкала сразу на месте. */
    private void hold(Player player, EquipmentSlot hand) {
        // Новый клик закрывает прошлую тягу: иначе две тяги слились бы в одну.
        Puff old = puffs.remove(player.getUniqueId());
        if (old != null) {
            finish(player, old);
        }
        ItemStack item = handItem(player, hand);
        if (!isCigarette(item) || !isLit(item)) {
            return;
        }
        int capacity = barsPerPuff(item);
        puffs.put(player.getUniqueId(), new Puff(hand, Bukkit.getCurrentTick(), capacity));
        startInhaleSound(player);
        showGauge(player, 0, capacity);
    }

    /** Тяга закончилась отпусканием: дым и расход запаса. */
    private void finish(Player player, Puff puff) {
        stopInhaleSound(player);
        ItemStack item = handItem(player, puff.hand());
        if (!isCigarette(item) || !isLit(item)) {
            player.sendActionBar(Component.empty());
            return;
        }
        int held = Bukkit.getCurrentTick() - puff.since();
        spend(player, puff.hand(), barsForRelease(held, bars(item), puff.capacity()));
    }

    /**
     * Дым по числу палочек и списание запаса. Пустая затяжка тишиной проходит,
     * пустой запас — сигарета кончилась.
     */
    private void spend(Player player, EquipmentSlot hand, int filled) {
        player.sendActionBar(Component.empty());
        if (filled <= 0) {
            return;
        }
        ItemStack item = handItem(player, hand);
        if (!isCigarette(item) || !isLit(item)) {
            return;
        }
        int spent = Math.min(filled, bars(item));
        if (spent <= 0) {
            return;
        }
        int gunpowder = gunpowderFilling(item);
        if (gunpowder > 0) {
            if (gunpowder >= GUNPOWDER_OVERDOSE_THRESHOLD) {
                player.getWorld().createExplosion(player.getLocation(), 2.8F, false, true);
            } else {
                Location where = mouth(player);
                player.getWorld().spawnParticle(Particle.EXPLOSION, where, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                player.playSound(where, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.6F, 1.4F);
                player.damage(gunpowder * 1.0D);
            }
        }
        int sugar = sugarFilling(item);
        if (sugar > 0) {
            applySugarEffects(player, sugar, spent);
        }
        int prismarine = prismarineFilling(item);
        if (prismarine > 0) {
            applyPrismarineEffects(player, prismarine, spent);
        }
        // Любая реальная затяжка начинает зависимость, если её ещё не было,
        // либо сбрасывает цикл после повторного курения.
        startOrResetAddiction(player);
        smoke(player, spent);
        countSmokingBars(player, spent);
        int rest = bars(item) - spent;
        if (rest > 0) {
            setBars(item, rest);
            return;
        }
        breakUp(player, hand);
    }

    void applySugarEffects(Player player, int sugar, int spent) {
        SugarTier tier = sugarTier(sugar);
        if (tier == null || spent <= 0) {
            return;
        }
        int durationTicks = spent * tier.secondsPerBar() * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationTicks, tier.speedAmplifier()), true);
        if (tier.haste()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, durationTicks, 0), true);
        }
        if (tier.nausea()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, durationTicks, 0), true);
        }
        UUID id = player.getUniqueId();
        SugarSession existing = sugarSessions.get(id);
        int totalRemaining = (existing != null) ? Math.max(existing.remainingTicks, durationTicks) : durationTicks;
        boolean lmb = tier.involuntaryLmb() || (existing != null && existing.hasLmb);
        boolean walk = tier.involuntaryWalk() || (existing != null && existing.hasWalk);
        int walkDuration = Math.max(tier.walkDurationTicks(), (existing != null) ? existing.walkDurationTicks : 0);
        double deathChance = Math.max(tier.deathChance(), (existing != null) ? existing.deathChance : 0.0);
        boolean nausea = tier.nausea() || (existing != null && existing.hasNausea);
        boolean wax = tier.waxParticles() || (existing != null && existing.hasWaxParticles);
        int jerkTier = Math.max(tier.cameraJerkTier(), (existing != null) ? existing.cameraJerkTier : 0);

        sugarSessions.put(id, new SugarSession(totalRemaining, lmb, walk, walkDuration, deathChance, nausea, wax, jerkTier));
    }

    void applyPrismarineEffects(Player player, int prismarine, int spent) {
        if (prismarine <= 0 || spent <= 0) {
            return;
        }
        int durationTicks = spent * PRISMARINE_SECONDS_PER_BAR * 20; // 4 секунды за каждую палочку тяги
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, durationTicks, 0, false, false, false), true);
        if (prismarine >= PRISMARINE_NAUSEA_THRESHOLD) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, durationTicks, 0, false, false, false), true);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, SoundCategory.PLAYERS, 0.45F, 1.25F);

        UUID id = player.getUniqueId();
        PrismarineSession existing = prismarineSessions.get(id);
        int totalRemaining = (existing != null) ? Math.max(existing.remainingTicks, durationTicks) : durationTicks;
        int totalCrystals = (existing != null) ? Math.max(existing.crystalCount, prismarine) : prismarine;
        prismarineSessions.put(id, new PrismarineSession(totalRemaining, totalCrystals));
    }

    static int prismarineEffectDurationTicks(int spent) {
        return spent * PRISMARINE_SECONDS_PER_BAR * 20;
    }

    static boolean hasPrismarineNausea(int crystals) {
        return crystals >= PRISMARINE_NAUSEA_THRESHOLD;
    }

    private void tickEffects() {
        tickSugar();
        tickPrismarine();
        tickHallucinations();
    }

    private void tickPrismarine() {
        if (prismarineSessions.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, PrismarineSession>> it = prismarineSessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PrismarineSession> entry = it.next();
            UUID id = entry.getKey();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            PrismarineSession session = entry.getValue();
            session.remainingTicks--;
            if (session.remainingTicks <= 0) {
                it.remove();
                continue;
            }

            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            int crystals = session.crystalCount;

            // Шанс появления группы плывущих дельфинов (от 1 до 4)
            session.creatureCooldown--;
            if (session.creatureCooldown <= 0) {
                int minCool = Math.max(40, 180 - Math.min(130, crystals * 5));
                int maxCool = Math.max(minCool + 20, 260 - Math.min(180, crystals * 6));
                session.creatureCooldown = ThreadLocalRandom.current().nextInt(minCool, maxCool + 1);

                double spawnChance = Math.min(0.90, 0.30 + crystals * 0.03);
                if (ThreadLocalRandom.current().nextDouble() < spawnChance) {
                    spawnHallucinationPod(player, crystals);
                }
            }
        }
    }

    private String assignDolphinColor(Entity entity, NamedTextColor color) {
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = dolphinTeamName(color);
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
                team.color(color);
            }
            team.addEntry(entity.getUniqueId().toString());
            entity.setGlowing(true);
            return teamName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void spawnHallucinationPod(Player player, int crystals) {
        long countForPlayer = hallucinations.stream()
                .filter(h -> h.player.getUniqueId().equals(player.getUniqueId()))
                .count();
        if (countForPlayer >= 10) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int maxGroup = Math.min(4, 1 + crystals / 2);
        int groupSize = rnd.nextInt(1, maxGroup + 1);
        groupSize = Math.min(groupSize, (int) (10 - countForPlayer));
        if (groupSize <= 0) {
            return;
        }

        // Общее направление течения для всей стаи
        double currentAngle = rnd.nextDouble(0, 2 * Math.PI);
        double baseSpeed = rnd.nextDouble(0.008, 0.015); // очень медленно: ~0.2 блока в секунду

        // Центр спавна стаи в мировых координатах перед/вокруг игрока
        Location eye = player.getEyeLocation();
        double spawnDist = rnd.nextDouble(4.5, 7.5);
        double spawnAngle = rnd.nextDouble(0, 2 * Math.PI);
        double centerX = eye.getX() + Math.cos(spawnAngle) * spawnDist;
        double centerY = eye.getY() + rnd.nextDouble(-0.3, 1.4);
        double centerZ = eye.getZ() + Math.sin(spawnAngle) * spawnDist;

        for (int i = 0; i < groupSize; i++) {
            double offX = (rnd.nextDouble() - 0.5) * 3.0;
            double offY = (rnd.nextDouble() - 0.5) * 1.0;
            double offZ = (rnd.nextDouble() - 0.5) * 3.0;
            double posX = centerX + offX;
            double posY = centerY + offY;
            double posZ = centerZ + offZ;

            double dolphinAngle = currentAngle + (rnd.nextDouble() - 0.5) * 0.25;
            double speed = baseSpeed + (rnd.nextDouble() - 0.5) * 0.003;
            double vx = Math.cos(dolphinAngle) * speed;
            double vz = Math.sin(dolphinAngle) * speed;
            float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));

            Location spawnLoc = new Location(player.getWorld(), posX, posY, posZ, yaw, 0.0F);
            Entity entity;
            try {
                entity = player.getWorld().spawn(spawnLoc, Dolphin.class, d -> {
                    d.setAI(false);
                    d.setGravity(false);
                    d.setInvulnerable(true);
                    d.setSilent(true);
                    d.setCollidable(false);
                    d.setPersistent(false);
                    d.setRemoveWhenFarAway(true);
                });
            } catch (Throwable t) {
                return;
            }

            if (entity == null) {
                continue;
            }

            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(player.getUniqueId())) {
                    try {
                        other.hideEntity(plugin, entity);
                    } catch (Throwable ignored) {
                    }
                }
            }

            NamedTextColor color = DOLPHIN_COLORS[rnd.nextInt(DOLPHIN_COLORS.length)];
            String teamName = assignDolphinColor(entity, color);
            int lifeTicks = rnd.nextInt(500, 901); // 25 - 45 секунд
            HallucinationEntity hallucination = new HallucinationEntity(
                    entity, player, teamName, posX, posY, posZ, vx, vz, yaw, lifeTicks
            );
            hallucinations.add(hallucination);
        }

        Location soundLoc = new Location(player.getWorld(), centerX, centerY, centerZ);
        player.playSound(soundLoc, Sound.ENTITY_DOLPHIN_AMBIENT_WATER, SoundCategory.AMBIENT, 0.25F, 1.4F);
    }

    private void tickHallucinations() {
        if (hallucinations.isEmpty()) {
            return;
        }
        for (Iterator<HallucinationEntity> it = hallucinations.iterator(); it.hasNext(); ) {
            HallucinationEntity h = it.next();
            if (!h.player.isOnline() || h.player.isDead() || h.entity == null || !h.entity.isValid()) {
                removeHallucination(h);
                it.remove();
                continue;
            }

            h.lifeTicks--;
            if (h.lifeTicks <= 0) {
                removeHallucination(h);
                it.remove();
                continue;
            }

            // Очень медленный дрейф "по течению" в мировых координатах (не зависит от перемещения игрока)
            h.posX += h.velocityX;
            h.posZ += h.velocityZ;
            h.pitchPhase += h.pitchSpeed;
            double currentY = h.posY + Math.sin(h.pitchPhase) * 0.18;
            float currentPitch = (float) (-Math.cos(h.pitchPhase) * 5.0);

            Location nextLoc = new Location(h.player.getWorld(), h.posX, currentY, h.posZ, h.yaw, currentPitch);
            try {
                h.entity.teleport(nextLoc);
            } catch (Throwable ignored) {
            }
        }
    }

    private void tickSugar() {
        if (sugarSessions.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, SugarSession>> it = sugarSessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, SugarSession> entry = it.next();
            UUID id = entry.getKey();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            SugarSession session = entry.getValue();
            session.remainingTicks--;
            if (session.remainingTicks <= 0) {
                it.remove();
                continue;
            }

            // Шанс мгновенной смерти от остановки сердца за тик (1% при 17-23, 5% при 24+)
            if (session.deathChance > 0 && ThreadLocalRandom.current().nextDouble() < session.deathChance) {
                it.remove();
                triggerCardiacArrest(player);
                continue;
            }

            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            // Частицы снятия воска с медного блока вокруг игрока на его клиенте (для последних 3 стадий)
            if (session.hasWaxParticles) {
                Location center = player.getLocation().add(0, 1.0, 0);
                player.spawnParticle(Particle.WAX_OFF, center, 3, 0.45, 0.45, 0.45, 0.0);
            }

            // Резкие рывки камеры (для последних 3 стадий: чем выше стадия, тем резче и чаще)
            if (session.cameraJerkTier > 0) {
                session.cameraJerkCooldown--;
                if (session.cameraJerkCooldown <= 0) {
                    session.cameraJerkCooldown = SugarSession.nextCameraJerkCooldown(session.cameraJerkTier);
                    performCameraJerk(player, session.cameraJerkTier);
                }
            }

            // Непроизвольные клики ЛКМ / удары
            if (session.hasLmb) {
                session.lmbCooldown--;
                if (session.lmbCooldown <= 0) {
                    session.lmbCooldown = ThreadLocalRandom.current().nextInt(15, 40);
                    performInvoluntaryPunch(player);
                }
            }

            // Непроизвольная ходьба (не рывками, а непрерывно: ~0.5с на 13-16, ~1.0с на 17+)
            if (session.hasWalk) {
                if (session.walkTicksRemaining > 0) {
                    session.walkTicksRemaining--;
                    performContinuousWalk(player, session.walkDirection);
                } else {
                    session.walkCooldown--;
                    if (session.walkCooldown <= 0) {
                        session.walkCooldown = ThreadLocalRandom.current().nextInt(30, 60);
                        int dur = session.walkDurationTicks;
                        int minTicks = Math.max(1, (int) Math.round(dur * 0.9));
                        int maxTicks = Math.max(minTicks, (int) Math.round(dur * 1.1));
                        session.walkTicksRemaining = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
                        double angle = ThreadLocalRandom.current().nextDouble(0, 2 * Math.PI);
                        double speed = 0.24;
                        session.walkDirection = new Vector(Math.cos(angle) * speed, 0, Math.sin(angle) * speed);
                        performContinuousWalk(player, session.walkDirection);
                    }
                }
            }
        }
    }

    private void performInvoluntaryPunch(Player player) {
        player.swingMainHand();
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.PLAYERS, 0.7F, 1.2F);
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getLocation().getDirection(),
                3.0,
                0.5,
                entity -> entity != player && entity instanceof LivingEntity
        );
        if (result != null && result.getHitEntity() instanceof LivingEntity target) {
            player.attack(target);
        }
    }

    private void performContinuousWalk(Player player, Vector walkDirection) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Vector currentVel = player.getVelocity();
        player.setVelocity(new Vector(walkDirection.getX(), currentVel.getY(), walkDirection.getZ()));
    }

    private void performCameraJerk(Player player, int jerkTier) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float yawDelta;
        float pitchDelta;
        if (jerkTier == 1) {
            yawDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(15.0F, 30.0F);
            pitchDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(10.0F, 18.0F);
        } else if (jerkTier == 2) {
            yawDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(30.0F, 60.0F);
            pitchDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(15.0F, 30.0F);
        } else {
            yawDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(55.0F, 95.0F);
            pitchDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat(25.0F, 45.0F);
        }

        Vector vel = player.getVelocity();
        Location loc = player.getLocation();
        loc.setYaw(loc.getYaw() + yawDelta);
        loc.setPitch(Math.clamp(loc.getPitch() + pitchDelta, -90.0F, 90.0F));
        player.teleport(loc);
        player.setVelocity(vel);
    }

    private void triggerCardiacArrest(Player player) {
        cardiacArrestVictims.add(player.getUniqueId());
        player.setHealth(0.0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (cardiacArrestVictims.remove(player.getUniqueId())) {
            Component msg = Component.text(player.getName() + " умер от остановки сердца");
            event.deathMessage(msg);
        }
        sugarSessions.remove(player.getUniqueId());
        prismarineSessions.remove(player.getUniqueId());
        hallucinations.removeIf(h -> {
            if (h.player.getUniqueId().equals(player.getUniqueId())) {
                removeHallucination(h);
                return true;
            }
            return false;
        });
        Puff puff = puffs.remove(player.getUniqueId());
        if (puff != null) {
            stopInhaleSound(player);
            player.sendActionBar(Component.empty());
        }
    }

    /** Считает только реально потраченные палочки; нет фонового опроса или задачи на игрока. */
    private void countSmokingBars(Player player, int spent) {
        UUID id = player.getUniqueId();
        SmokingWindow window = smokingWindows.computeIfAbsent(id, ignored -> new SmokingWindow());
        if (window.add(Bukkit.getCurrentTick(), spent)) {
            smokingWindows.remove(id);
            // amplifier 0 = тошнота I; флаги выключают частицы и значок эффекта.
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NAUSEA,
                    NAUSEA_DURATION_TICKS,
                    0,
                    false,
                    false,
                    false
            ), true);
        } else if (window.barsWithinWindow() == 0) {
            smokingWindows.remove(id);
        }
    }

    /** O(1) амортизированно: очищает старые затяжки только при новой затяжке. */
    static final class SmokingWindow {
        private record Consumption(int tick, int bars) { }

        private final Deque<Consumption> consumptions = new ArrayDeque<>();
        private int barsWithinWindow;

        boolean add(int currentTick, int bars) {
            if (bars <= 0) {
                return false;
            }
            while (!consumptions.isEmpty()
                    && currentTick - consumptions.peekFirst().tick() >= SMOKING_WINDOW_TICKS) {
                barsWithinWindow -= consumptions.removeFirst().bars();
            }
            consumptions.addLast(new Consumption(currentTick, bars));
            barsWithinWindow += bars;
            if (barsWithinWindow < NAUSEA_THRESHOLD_BARS) {
                return false;
            }
            consumptions.clear();
            barsWithinWindow = 0;
            return true;
        }

        int barsWithinWindow() {
            return barsWithinWindow;
        }
    }

    /** Первая реальная затяжка запускает таймер; любая следующая сбрасывает цикл зависимости. */
    private void startOrResetAddiction(Player player) {
        UUID id = player.getUniqueId();
        AddictionState state = addictions.computeIfAbsent(id, ignored -> new AddictionState());
        cancelTransition(state);
        if (state.stage > 0) {
            clearWithdrawalEffects(player, state);
            player.sendActionBar(Component.empty());
            warningPlayers.remove(id);
            stopWithdrawalMessagesIfIdle();
        }
        state.stage = 0;
        state.lastSmokeTick = Bukkit.getCurrentTick();
        scheduleAddictionTransition(id, state, firstWithdrawalTicks);
    }

    private void scheduleAddictionTransition(UUID id, AddictionState state, long delay) {
        state.transitionTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> advanceAddiction(id),
                Math.max(1L, delay)
        );
    }

    private void advanceAddiction(UUID id) {
        AddictionState state = addictions.get(id);
        if (state == null) {
            return;
        }
        state.transitionTask = null;
        int elapsed = Bukkit.getCurrentTick() - state.lastSmokeTick;
        int nextStage = withdrawalStageForTicks(elapsed, firstWithdrawalTicks, withdrawalStageTicks);
        Player player = Bukkit.getPlayer(id);
        if (nextStage >= 4) {
            if (player != null) {
                clearWithdrawalEffects(player, state);
                player.sendActionBar(Component.empty());
            }
            warningPlayers.remove(id);
            addictions.remove(id);
            stopWithdrawalMessagesIfIdle();
            return;
        }
        if (nextStage == 0) {
            scheduleAddictionTransition(id, state, firstWithdrawalTicks - elapsed);
            return;
        }
        state.stage = nextStage;
        if (player != null) {
            applyWithdrawalStage(player, state);
            startWithdrawalMessages(player, state);
        }
        long nextBoundary = (long) firstWithdrawalTicks
                + (long) nextStage * withdrawalStageTicks;
        scheduleAddictionTransition(id, state, nextBoundary - elapsed);
    }

    /** 0=нет ломки, 1=слабая, 2=сильная с тошнотой, 3=сильная без тошноты, 4=зависимость прошла. */
    static int withdrawalStageForTicks(int elapsedTicks) {
        return withdrawalStageForTicks(elapsedTicks, FIRST_WITHDRAWAL_TICKS, WITHDRAWAL_STAGE_TICKS);
    }

    static int withdrawalStageForTicks(int elapsedTicks, int firstStageTicks, int intervalTicks) {
        long elapsed = elapsedTicks;
        long first = firstStageTicks;
        long interval = intervalTicks;
        if (elapsed < first) {
            return 0;
        }
        if (elapsed < first + interval) {
            return 1;
        }
        if (elapsed < first + 2L * interval) {
            return 2;
        }
        if (elapsed < first + 3L * interval) {
            return 3;
        }
        return 4;
    }

    private static int configuredTicks(JavaPlugin plugin, String path, int fallback) {
        long value = plugin.getConfig().getLong(path, fallback);
        if (value < 1 || value > Integer.MAX_VALUE) {
            plugin.getLogger().warning("[Сигарета] " + path + " вне диапазона, используется " + fallback);
            return fallback;
        }
        return (int) value;
    }

    private void startWithdrawalMessages(Player player, AddictionState state) {
        UUID id = player.getUniqueId();
        warningPlayers.add(id);
        player.sendActionBar(withdrawalMessage(state.stage));
        if (withdrawalMessagesTicker == null) {
            withdrawalMessagesTicker = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::refreshWithdrawalMessages,
                    WITHDRAWAL_MESSAGE_REPEAT_TICKS,
                    WITHDRAWAL_MESSAGE_REPEAT_TICKS
            );
        }
    }

    private void refreshWithdrawalMessages() {
        for (Iterator<UUID> it = warningPlayers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            AddictionState state = addictions.get(id);
            Player player = Bukkit.getPlayer(id);
            if (state == null || state.stage < 1 || state.stage > 3 || player == null) {
                it.remove();
                continue;
            }
            applyWithdrawalStage(player, state);
            player.sendActionBar(withdrawalMessage(state.stage));
        }
        stopWithdrawalMessagesIfIdle();
    }

    static Component withdrawalMessage(int stage) {
        return stage == 1 ? EARLY_CRAVING_MESSAGE : SEVERE_CRAVING_MESSAGE;
    }

    static int withdrawalAmplifierForStage(int stage) {
        return stage == 2 ? 1 : 0;
    }

    private void applyWithdrawalStage(Player player, AddictionState state) {
        if (state.stage < 1 || state.stage > 3) {
            return;
        }
        int amplifier = withdrawalAmplifierForStage(state.stage);
        setManagedEffect(player, state, PotionEffectType.WEAKNESS, amplifier);
        setManagedEffect(player, state, PotionEffectType.MINING_FATIGUE, amplifier);
        if (state.stage == 2) {
            setManagedEffect(player, state, PotionEffectType.NAUSEA, 0);
        } else {
            restoreManagedEffect(player, state, PotionEffectType.NAUSEA);
        }
    }

    private static void setManagedEffect(
            Player player,
            AddictionState state,
            PotionEffectType type,
            int amplifier
    ) {
        PotionEffect current = player.getPotionEffect(type);
        PotionEffect managed = state.managedEffects.get(type);
        PotionEffect expected = infiniteWithdrawalEffect(type, amplifier);
        if (samePotionEffect(current, expected)) {
            state.managedEffects.put(type, expected);
            if (!state.previousEffects.containsKey(type)) {
                state.previousEffects.put(type, saveCurrentEffect(current));
            }
            return;
        }
        if (!state.previousEffects.containsKey(type)) {
            state.previousEffects.put(type, saveCurrentEffect(current));
        } else if (managed != null && current != null && !samePotionEffect(current, managed)) {
            // Если другой плагин заменил эффект, сохраним его как новый исходный, чтобы вернуть при отмене.
            state.previousEffects.put(type, saveCurrentEffect(current));
        }
        player.addPotionEffect(expected, true);
        state.managedEffects.put(type, expected);
    }

    private static PotionEffect infiniteWithdrawalEffect(PotionEffectType type, int amplifier) {
        return new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, false, false, false);
    }

    private static SavedPotionEffect saveCurrentEffect(PotionEffect effect) {
        return new SavedPotionEffect(effect, Bukkit.getCurrentTick());
    }

    private static boolean samePotionEffect(PotionEffect actual, PotionEffect expected) {
        return actual != null
                && expected != null
                && actual.getType().equals(expected.getType())
                && actual.getAmplifier() == expected.getAmplifier()
                && actual.isInfinite() == expected.isInfinite()
                && actual.isAmbient() == expected.isAmbient()
                && actual.hasParticles() == expected.hasParticles()
                && actual.hasIcon() == expected.hasIcon();
    }

    private static void restoreManagedEffect(Player player, AddictionState state, PotionEffectType type) {
        PotionEffect managed = state.managedEffects.remove(type);
        SavedPotionEffect saved = state.previousEffects.remove(type);
        if (!samePotionEffect(player.getPotionEffect(type), managed)) {
            return;
        }
        player.removePotionEffect(type);
        if (saved == null || saved.effect() == null) {
            return;
        }
        PotionEffect original = saved.effect();
        if (!original.isInfinite()) {
            int remaining = original.getDuration() - Math.max(0, Bukkit.getCurrentTick() - saved.capturedTick());
            if (remaining <= 0) {
                return;
            }
            original = new PotionEffect(original.getType(), remaining, original.getAmplifier(),
                    original.isAmbient(), original.hasParticles(), original.hasIcon());
        }
        player.addPotionEffect(original, true);
    }

    private static void clearWithdrawalEffects(Player player, AddictionState state) {
        restoreManagedEffect(player, state, PotionEffectType.WEAKNESS);
        restoreManagedEffect(player, state, PotionEffectType.MINING_FATIGUE);
        restoreManagedEffect(player, state, PotionEffectType.NAUSEA);
        state.managedEffects.clear();
        state.previousEffects.clear();
    }

    private void cancelTransition(AddictionState state) {
        if (state.transitionTask != null) {
            state.transitionTask.cancel();
            state.transitionTask = null;
        }
    }

    private void stopWithdrawalMessagesIfIdle() {
        if (warningPlayers.isEmpty() && withdrawalMessagesTicker != null) {
            withdrawalMessagesTicker.cancel();
            withdrawalMessagesTicker = null;
        }
    }

    /** Сигарета догорела: звук поломки, дымок — и предмета нет. */
    private void breakUp(Player player, EquipmentSlot hand) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.7F, 1.0F);
        Location where = mouth(player);
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, where, 4, 0.04D, 0.04D, 0.04D, 0.01D);
        ItemStack item = handItem(player, hand);
        if (item != null && item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            setBars(item, reserve(item));
        } else {
            setHandItem(player, hand, new ItemStack(Material.AIR));
        }
        player.updateInventory();
    }

    /** Поджиг: огниво щёлкнуло, модель сменилась на горящую, имя то же. */
    private void light(Player player, EquipmentSlot hand) {
        ItemStack item = handItem(player, hand);
        if (!isCigarette(item) || isLit(item)) {
            return;
        }
        int remaining = bars(item);
        int gunpowder = gunpowderFilling(item);
        int sugar = sugarFilling(item);
        int prismarine = prismarineFilling(item);
        ItemStack unlitRemainder = null;
        if (item.getAmount() > 1) {
            unlitRemainder = item.clone();
            unlitRemainder.setAmount(item.getAmount() - 1);
        }
        ItemStack fired = create(true, variant(item), gunpowder, sugar, prismarine);
        fired.setAmount(1);
        setBars(fired, remaining);
        setHandItem(player, hand, fired);
        if (unlitRemainder != null) {
            for (ItemStack overflow : player.getInventory().addItem(unlitRemainder).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
        EquipmentSlot flintHand = other(hand);
        ItemStack flintAndSteel = handItem(player, flintHand);
        if (isFlintAndSteel(flintAndSteel)) {
            // ItemStack.damage uses vanilla damage rules, including Unbreaking and break events.
            setHandItem(player, flintHand, flintAndSteel.damage(1, player));
        }
        player.updateInventory();
        litAtTick.put(player.getUniqueId(), Bukkit.getCurrentTick() + 1);
        player.playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.5F, 1.2F);
        player.playSound(mouth(player), LIGHT_HISS_SOUND, SoundCategory.BLOCKS, 0.18F, 1.35F);
        Location where = mouth(player);
        player.getWorld().spawnParticle(Particle.FLAME, where, 5, 0.04D, 0.04D, 0.04D, 0.01D);
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, where, 2, 0.02D, 0.02D, 0.02D, 0.01D);
    }

    /**
     * Дым непрерывно выходит изо рта в течение 0,2 секунды на каждую
     * набранную палочку; во время выдоха направление следует за взглядом.
     */
    private void smoke(Player player, int filled) {
        Smoke old = smoke.remove(player.getUniqueId());
        if (old != null) {
            old.stop();
        }
        player.playSound(mouth(player), EXHALE_SOUND, SoundCategory.BLOCKS, 0.24F, 1.35F);
        Smoke flow = new Smoke(player, filled);
        smoke.put(player.getUniqueId(), flow);
        flow.start();
    }

    /** Шкала тяги нужной модели, набитые палочки слева направо желтеют. */
    private static void showGauge(Player player, int filled, int capacity) {
        player.sendActionBar(gauge(filled, capacity));
    }

    /** Большая сигарета — вариант по умолчанию — показывает шкалу на 32 палочки. */
    static Component gauge(int filled) {
        return gauge(filled, BIG_BARS_PER_PUFF);
    }

    /** Шкала с динамической ёмкостью: 16 палочек у обычных и 32 у большой. */
    static Component gauge(int filled, int capacity) {
        int size = Math.clamp(capacity, 1, BIG_BARS_PER_PUFF);
        Component gauge = Component.text("[ ", NamedTextColor.DARK_GRAY);
        for (int i = 0; i < size; i++) {
            gauge = gauge.append(Component.text("|", i < filled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        }
        return gauge.append(Component.text(" ]", NamedTextColor.DARK_GRAY));
    }

    /**
     * Сколько палочек набилось за тики тяги: одна за 0,2 секунды. При отпускании
     * раньше первой палочки finish() всё равно даёт минимальную тягу на выдох.
     */
    static int barsFor(int ticks, int left) {
        return barsFor(ticks, left, BIG_BARS_PER_PUFF);
    }

    static int barsFor(int ticks, int left, int capacity) {
        if (ticks <= 0 || left <= 0) {
            return 0;
        }
        return Math.clamp(Math.min(Math.clamp(capacity, 1, BIG_BARS_PER_PUFF), ticks / TICKS_PER_BAR), 0, left);
    }

    /** При отпускании любая ненулевая тяга даёт минимум одну палочку выдоха. */
    static int barsForRelease(int ticks, int left) {
        return barsForRelease(ticks, left, BIG_BARS_PER_PUFF);
    }

    static int barsForRelease(int ticks, int left, int capacity) {
        int filled = barsFor(ticks, left, capacity);
        return ticks > 0 && filled == 0 ? Math.min(1, Math.max(0, left)) : filled;
    }

    /**
     * Сколько тиков выдыхается дым: ровно 0,2 секунды на каждую набранную
     * палочку. Одна палочка — 4 тика; 32 палочки у большой — 6,4 секунды.
     */
    static int exhaleTicks(int bars) {
        return Math.clamp(bars, 0, BIG_BARS_PER_PUFF) * TICKS_PER_BAR;
    }

    /** Количество частиц уменьшено примерно вдвое; большие тяги остаются гуще. */
    static int smokeParticlesPerTick(int bars) {
        int original = 2 + Math.clamp(bars, 0, BIG_BARS_PER_PUFF) / 4;
        return Math.max(1, original / 2);
    }

    /** Рот: чуть впереди и ниже глаз, чтобы дым шёл из лица, а не из центра головы. */
    private static Location mouth(Player player) {
        Location eye = player.getEyeLocation();
        return eye.clone()
                .add(eye.getDirection().multiply(MOUTH_AHEAD))
                .subtract(0, MOUTH_DOWN, 0);
    }

    private static ItemStack handItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private static void setHandItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private static EquipmentSlot other(EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
    }

    private static boolean isFlintAndSteel(ItemStack item) {
        return item != null && item.getType() == Material.FLINT_AND_STEEL;
    }

    /**
     * Проверка, является ли блок функциональным/интерактивным при ПКМ
     * (контейнеры, двери, кнопки, рычаги, верстаки, печи, наковальни, АПВШ и т.д.).
     */
    static boolean isFunctionalBlock(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat == Material.AIR) return false;

        // Кастомные блоки CraftEngine (АПВШ, нотный блок и др.)
        try {
            if (CraftEngineSupport.available() && CraftEngineSupport.idAt(block) != null) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Контейнеры и блоки с GUI / состояниями
        try {
            if (block.getState() instanceof Container
                    || block.getState() instanceof Lectern
                    || block.getState() instanceof Jukebox
                    || block.getState() instanceof Bell
                    || block.getState() instanceof CommandBlock) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Проверка по тегам
        try {
            if (Tag.DOORS.isTagged(mat)
                    || Tag.TRAPDOORS.isTagged(mat)
                    || Tag.FENCE_GATES.isTagged(mat)
                    || Tag.BUTTONS.isTagged(mat)
                    || Tag.BEDS.isTagged(mat)
                    || Tag.SHULKER_BOXES.isTagged(mat)
                    || Tag.ANVIL.isTagged(mat)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Материалы отдельных функциональных блоков
        String name = mat.name();
        if (name.endsWith("_BUTTON") || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_GATE") || name.endsWith("_BED") || name.endsWith("_SHULKER_BOX")
                || name.endsWith("_ANVIL") || name.endsWith("_CAKE")) {
            return true;
        }

        return mat == Material.LEVER
                || mat == Material.CRAFTING_TABLE
                || mat == Material.ENCHANTING_TABLE
                || mat == Material.ENDER_CHEST
                || mat == Material.BEACON
                || mat == Material.RESPAWN_ANCHOR
                || mat == Material.LODESTONE
                || mat == Material.COMPOSTER
                || mat == Material.NOTE_BLOCK
                || mat == Material.JUKEBOX
                || mat == Material.BELL
                || mat == Material.REPEATER
                || mat == Material.COMPARATOR
                || mat == Material.DAYLIGHT_DETECTOR
                || mat == Material.CARTOGRAPHY_TABLE
                || mat == Material.SMITHING_TABLE
                || mat == Material.GRINDSTONE
                || mat == Material.LOOM
                || mat == Material.STONECUTTER
                || mat == Material.SWEET_BERRY_BUSH
                || mat == Material.CAKE
                || mat == Material.CAULDRON
                || mat == Material.WATER_CAULDRON
                || mat == Material.LAVA_CAULDRON
                || mat == Material.POWDER_SNOW_CAULDRON;
    }

    private static void setBars(ItemStack item, int bars) {
        if (!isCigarette(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BARS, PersistentDataType.INTEGER,
                Math.clamp(bars, 0, reserve(item)));
        item.setItemMeta(meta);
    }

    /**
     * Один непрерывный выдох. Каждый тик берёт новое положение рта и новое
     * направление взгляда, поэтому поток всё время поворачивает вместе с игроком.
     */
    private final class Smoke implements Runnable {

        private final Player player;
        private final int total;
        private final int perTick;
        private int tick;
        private BukkitTask task;

        Smoke(Player player, int bars) {
            this.player = player;
            this.total = exhaleTicks(bars);
            this.perTick = smokeParticlesPerTick(bars);
        }

        void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 0L, 1L);
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }

        @Override
        public void run() {
            if (tick >= total || !player.isOnline()) {
                stop();
                if (smoke.get(player.getUniqueId()) == this) {
                    smoke.remove(player.getUniqueId());
                }
                return;
            }
            Location origin = mouth(player);
            Vector dir = player.getEyeLocation().getDirection().normalize();
            // Новые частицы рождаются непрерывно вдоль текущего взгляда, а не
            // по направлению взгляда в момент отпускания ПКМ.
            for (int i = 0; i < perTick; i++) {
                double distance = 0.18D + (i % 4) * 0.16D;
                Location spot = origin.clone().add(dir.clone().multiply(distance)).add(jitter());
                player.getWorld().spawnParticle(
                        Particle.CAMPFIRE_COSY_SMOKE,
                        spot,
                        0,
                        dir.getX() * SMOKE_PUSH,
                        dir.getY() * SMOKE_PUSH + 0.01D,
                        dir.getZ() * SMOKE_PUSH,
                        1.0D
                );
            }
            tick++;
        }

        private Vector jitter() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            return new Vector(
                    (random.nextDouble() - 0.5D) * 0.04D,
                    (random.nextDouble() - 0.5D) * 0.04D,
                    (random.nextDouble() - 0.5D) * 0.04D
            );
        }
    }

}
