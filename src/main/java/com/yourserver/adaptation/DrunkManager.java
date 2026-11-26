package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Опьянение: невидимая шкала у каждого игрока и всё, что с ней происходит.
 *
 * <h2>Как устроено</h2>
 *
 * <ul>
 *   <li>Каждый глоток алкоголя из бочки добавляет столько единиц, сколько написано в
 *       {@code data/napitki/napitki.yml} для качества этого напитка
 *       ({@code drunk: {1: 4, …}});</li>
 *   <li>шкала сама падает: одна единица за {@code decay-ticks} тиков
 *       ({@code settings/drunk.yml}, по умолчанию 200 тиков = 10 секунд);</li>
 *   <li>стадия ({@link DrunkStage}) решает, что с игроком: баффы, тошнота, шатание,
 *       пьяная речь, приступы тошноты и отключка;</li>
 *   <li>сильно выпившему бывает плохо и потом — похмелье.</li>
 * </ul>
 *
 * <h2>Где живёт значение</h2>
 *
 * В памяти и в {@code data/drunk.yml}: пишется при выходе игрока, раз в минуту и при
 * выключении плагина. Смерть обнуляет шкалу, сон — тоже (но с похмельем, если спал
 * сильно пьяным).
 *
 * <h2>Почему не «как в Brewery»</h2>
 *
 * Там шкала 0…100 и десять уровней, которые в основном выдают зелья. Здесь стадии
 * событийные: приступ тошноты с фантомными блоками и лужами, шатание, пьяная речь,
 * двоение в глазах, отключка и похмелье. А сила напитка зависит от качества выдержки,
 * то есть от того, сколько лет он стоял в бочке.
 */
final class DrunkManager implements Listener {

    /** Файл с показателями: {@code data/drunk.yml}. */
    static final String FILE = "drunk.yml";

    /**
     * Отключка в две фазы: сначала целую минуту игрок ещё на ногах, но шкалу нельзя
     * сбить ничем, а потом он ложится и не встаёт 16–28 секунд.
     */
    private static final int BLACKOUT_HOLD_SECONDS = 60;
    private static final int BLACKOUT_MIN_SECONDS = 16;
    private static final int BLACKOUT_MAX_SECONDS = 28;
    /** «Просто прилёг» на высоких стадиях: 2–4 секунды, встать нельзя. */
    private static final int LIE_MIN_TICKS = 40;
    private static final int LIE_MAX_TICKS = 80;
    /** Баффы, которые выдаём мы сами, — их же и снимаем при смене стадии. */
    private static final PotionEffectType[] BOONS = {
            PotionEffectType.ABSORPTION,
            PotionEffectType.SPEED,
            PotionEffectType.LUCK,
            PotionEffectType.STRENGTH,
            PotionEffectType.RESISTANCE,
            PotionEffectType.REGENERATION,
            PotionEffectType.HASTE,
            PotionEffectType.JUMP_BOOST
    };
    /** Через сколько тиков после сна наступает похмелье. */
    private static final int HANGOVER_TICKS = 60 * 20;
    /** Как часто освежаем зелья: раз в секунду, длительность — с запасом. */
    private static final int EFFECT_TICKS = 80;
    /** Прошлый менеджер — чтобы {@link #current()} пережил перезагрузку настроек. */
    private static volatile DrunkManager current;

    private final AdaptationPlugin plugin;
    private final Vomit vomit = new Vomit();
    private final Random random = new Random();
    private final Map<UUID, Integer> points = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> decayProgress = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hangover = new ConcurrentHashMap<>();
    private final Map<UUID, Lie> lie = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hold = new ConcurrentHashMap<>();
    private final Map<UUID, Sway> sway = new ConcurrentHashMap<>();
    private final Map<UUID, Set<PotionEffectType>> boons = new ConcurrentHashMap<>();
    private final Set<UUID> waking = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DrunkStage> lastStage = new ConcurrentHashMap<>();
    private final Path storePath;

    /**
     * Пьяная походка игрока: фаза покачивания плюс «куда меня сейчас несёт» —
     * направление, которое держится несколько секунд и меняется само по себе.
     */
    private static final class Sway {
        double phase;
        double speed;
        double wanderAngle;
        double wanderLeft;
        double wanderPower;

        Sway(Random random) {
            this.phase = random.nextDouble() * Math.PI * 2;
            this.speed = 0.09 + random.nextDouble() * 0.08;
            this.wanderAngle = random.nextDouble() * Math.PI * 2;
        }

        /** Выбрать новое «куда несёт»: 2–6 секунд в случайную сторону. */
        void rewind(Random random, DrunkStage stage) {
            this.wanderAngle = random.nextDouble() * Math.PI * 2;
            this.wanderLeft = 20 + random.nextInt(40);
            this.wanderPower = DrunkWalk.wander(stage) * (0.7 + random.nextDouble() * 0.6);
        }
    }

    /**
     * Временная кровать под лежачим: пока игрок в отключке, он спит по-настоящему
     * (иначе клиент рисует его стоящим), а блоки потом возвращаются как были.
     */
    private static final class FallBed {
        final Block foot;
        final Block head;
        final BlockData oldFoot;
        final BlockData oldHead;

        FallBed(Block foot, Block head, BlockData oldFoot, BlockData oldHead) {
            this.foot = foot;
            this.head = head;
            this.oldFoot = oldFoot;
            this.oldHead = oldHead;
        }
    }

    /** Игрок лежит на земле: где упал, сколько ещё лежать и отключка ли это. */
    private static final class Lie {
        final Location anchor;
        final boolean blackout;
        int ticks;
        FallBed bed;

        Lie(Location anchor, int ticks, boolean blackout) {
            this.anchor = anchor.clone();
            this.ticks = ticks;
            this.blackout = blackout;
        }
    }

    private DrunkSettings settings = DrunkSettings.defaults();
    private BukkitTask playersTask;
    private BukkitTask pukeTask;
    private BukkitTask swayTask;
    private BukkitTask saveTask;

    DrunkManager(AdaptationPlugin plugin) {
        this.plugin = plugin;
        this.storePath = Layout.data(plugin, FILE);
        loadSettings();
        loadStore();
        current = this;
    }

    /** Текущий менеджер (нужен профилю и команде). */
    static DrunkManager current() {
        return current;
    }

    // ===== ЧТЕНИЕ ДЛЯ ДРУГИХ КЛАССОВ =====

    /** Сколько единиц опьянения у игрока; 0 — трезв или менеджера ещё нет. */
    static int pointsOf(UUID id) {
        DrunkManager manager = current;
        return manager == null || id == null ? 0 : manager.points(id);
    }

    /** Размер шкалы для подсказок. */
    static int scaleOf() {
        DrunkManager manager = current;
        return manager == null ? DrunkStage.MAX_POINTS : manager.settings.scale();
    }

    /**
     * Строка для профиля: «Состояние: Шатает». Без единиц — шкала невидимая, цифры
     * видит только админ командой {@code /f8 drunk}.
     */
    static Component statusLine(int points) {
        DrunkStage stage = DrunkStage.of(points, scaleOf());
        if (stage == DrunkStage.SOBER) {
            return Component.text("Опьянение: трезв", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
        return Component.text("Состояние: " + stage.title(), colorOf(stage))
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Цвет стадии — общий для профиля, команды и подсказок. */
    static TextColor colorOf(DrunkStage stage) {
        if (stage == null) return NamedTextColor.GRAY;
        return switch (stage) {
            case SOBER -> NamedTextColor.GRAY;
            case TIPSY -> NamedTextColor.GREEN;
            case TALKATIVE -> NamedTextColor.YELLOW;
            case SWAYING -> NamedTextColor.GOLD;
            case QUEASY -> NamedTextColor.RED;
            case WASTED, EDGE, BLACKOUT -> NamedTextColor.DARK_RED;
        };
    }

    // ===== ЖИЗНЕННЫЙ ЦИКЛ =====

    /** Запустить тики: раз в секунду — игроки, каждые два тика — фантомные блоки. */
    void start() {
        if (playersTask != null) return;
        playersTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayers, 20L, 20L);
        pukeTask = Bukkit.getScheduler().runTaskTimer(plugin, vomit::tick, 2L, 2L);
        // Покачивание считаем каждые два тика: так оно плавное, а не рывками.
        swayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::swayTick, 2L, 2L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 20L * 60L, 20L * 60L);
    }

    /** Остановить всё: убрать лужи и сохранить показатели. */
    void stop() {
        if (playersTask != null) playersTask.cancel();
        if (pukeTask != null) pukeTask.cancel();
        if (swayTask != null) swayTask.cancel();
        if (saveTask != null) saveTask.cancel();
        playersTask = null;
        pukeTask = null;
        swayTask = null;
        saveTask = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Lie state = lie.remove(player.getUniqueId());
            if (state == null) continue;
            wakeFromBed(player, state);
            player.setPose(Pose.STANDING, true);
        }
        vomit.restoreAll();
        save();
    }

    /** Перечитать {@code settings/drunk.yml}. */
    void loadSettings() {
        settings = DrunkSettings.parse(plugin.getConfig().getConfigurationSection("drunk"));
    }

    // ===== ШКАЛА =====

    int points(UUID id) {
        Integer value = points.get(id);
        return value == null ? 0 : value;
    }

    /** Размер шкалы из настроек — для команд и подсказок. */
    int scale() {
        return settings.scale();
    }

    /** Стадия игрока. */
    DrunkStage stage(Player player) {
        return DrunkStage.of(points(player.getUniqueId()), settings.scale());
    }

    /**
     * Добавить единицы: выпитая бутылочка, команда, отладка. Пока идёт «отключка»,
     * шкалу нельзя сбить ничем: ни едой, ни молоком, ни водой.
     */
    void add(Player player, int units) {
        if (player == null || units == 0) return;
        if (hold.containsKey(player.getUniqueId())) return;
        set(player, points(player.getUniqueId()) + units);
    }

    /** Поставить значение: с пересчётом стадии, отключкой и сообщением о смене. */
    void set(Player player, int value) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        int scale = settings.scale();
        int next = Math.max(0, Math.min(scale, value));
        int previous = points.getOrDefault(id, 0);
        // Пока держится отключка, админ может только протрезвить (clear), но не сбить на число.
        if (hold.containsKey(id) && next < scale) return;
        points.put(id, next);

        DrunkStage oldStage = DrunkStage.of(previous, scale);
        DrunkStage newStage = DrunkStage.of(next, scale);
        if (newStage != oldStage) {
            onStageChanged(player, oldStage, newStage);
        }
        if (next >= scale && previous < scale) {
            startHold(player);
            // Предел шкалы достигнут: медаль «всему нужно знать меру» и подобные.
            ProfileManager profiles = plugin.profileManager();
            if (profiles != null) profiles.drunkMax(player);
        }
        if (next == 0) {
            decayProgress.remove(id);
        }
    }

    /** Протрезвить полностью. */
    void clear(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        points.remove(id);
        decayProgress.remove(id);
        hangover.remove(id);
        lastStage.remove(id);
        hold.remove(id);
        sway.remove(id);
        Lie state = lie.remove(id);
        if (state != null) {
            wakeFromBed(player, state);
            player.setPose(Pose.STANDING, true);
        }
        clearEffects(player);
    }

    /** Начать похмелье. */
    void startHangover(Player player, int ticks) {
        if (player == null || !settings.hangover()) return;
        hangover.put(player.getUniqueId(), Math.max(20, ticks));
    }

    /** Сколько тиков похмелья осталось (0 — нет). */
    int hangoverTicks(UUID id) {
        return hangover.getOrDefault(id, 0);
    }

    // ===== ТИК ИГРОКОВ =====

    private void tickPlayers() {
        int scale = settings.scale();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;

            tickHold(player, id);

            int value = points.getOrDefault(id, 0);
            if (value > 0 && lie.get(id) == null && hold.get(id) == null) {
                int progress = decayProgress.merge(id, 20, Integer::sum);
                if (progress >= settings.decayTicks()) {
                    int left = progress - settings.decayTicks();
                    value = Math.max(0, value - 1);
                    if (left > 0) decayProgress.put(id, left);
                    else decayProgress.remove(id);
                    points.put(id, value);
                    DrunkStage stage = DrunkStage.of(value, scale);
                    if (stage != DrunkStage.of(value + 1, scale)) {
                        onStageChanged(player, DrunkStage.of(value + 1, scale), stage);
                    }
                }
            }

            DrunkStage stage = DrunkStage.of(value, scale);
            if (settings.ignoreCreative() && player.getGameMode() == GameMode.CREATIVE) {
                dropBoons(player, Set.of());
                applyHangover(player, id);
                continue;
            }
            if (stage == DrunkStage.SOBER && lie.get(id) == null) {
                dropBoons(player, Set.of());
                applyHangover(player, id);
                continue;
            }

            applyStage(player, stage);
            applyHangover(player, id);
            tickLie(player, id);
            maybePuke(player, stage);
        }
    }

    /**
     * Первая фаза отключки: минуту игрок ещё на ногах, но шкала заперта на максимуме —
     * её не сбить ни временем, ни едой, ни молоком. Через минуту он падает.
     */
    private void tickHold(Player player, UUID id) {
        Integer left = hold.get(id);
        if (left == null) return;

        int next = left - 20;
        points.put(id, settings.scale());
        if (next > 0) {
            hold.put(id, next);
            return;
        }
        hold.remove(id);
        decayProgress.remove(id);
        int ticks = randomBetween(BLACKOUT_MIN_SECONDS, BLACKOUT_MAX_SECONDS) * 20;
        startLie(player, ticks, true);
    }

    /** Игрок лежит: держим позу, считаем секунды и не даём встать. */
    private void tickLie(Player player, UUID id) {
        Lie state = lie.get(id);
        if (state == null) return;

        state.ticks -= 20;
        if (state.ticks <= 0) {
            lie.remove(id);
            wakeFromBed(player, state);
            player.setPose(Pose.STANDING, true);
            if (state.blackout) finishBlackout(player);
            return;
        }

        // Клиент время от времени «встаёт» сам — возвращаем на землю.
        if (player.getPose() != Pose.SLEEPING) player.setPose(Pose.SLEEPING, true);
        // И держим настоящий сон: иначе игрок видит себя стоящим.
        if (state.bed == null) {
            layInBed(player, state);
        } else if (!player.isSleeping()) {
            wakeFromBed(player, state);
            layInBed(player, state);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 200, true, false, false));
        if (state.blackout) points.put(id, settings.scale());
    }

    /** Вход в отключку: минута на ногах с запертой шкалой, потом падение. */
    private void startHold(Player player) {
        UUID id = player.getUniqueId();
        if (hold.containsKey(id)) return;
        Lie current = lie.get(id);
        if (current != null && current.blackout) return;

        hold.put(id, BLACKOUT_HOLD_SECONDS * 20);
        points.put(id, settings.scale());
        dropBoons(player, Set.of());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP,
                SoundCategory.PLAYERS, 1.0F, 0.5F);
        vomit.burst(player, settings, true);
    }

    private void finishBlackout(Player player) {
        UUID id = player.getUniqueId();
        points.put(id, Math.max(1, settings.scale() / 2));
        decayProgress.remove(id);
        startHangover(player, HANGOVER_TICKS);
        vomit.burst(player, settings, false);
    }

    /** Прилёг прямо на землю: на высоких стадиях само собой, после минуты — отключка. */
    private void startLie(Player player, int ticks, boolean blackout) {
        UUID id = player.getUniqueId();
        Lie current = lie.get(id);
        if (current != null) return;
        Lie state = new Lie(player.getLocation(), ticks, blackout);
        lie.put(id, state);
        player.setVelocity(new Vector(0, 0, 0));
        player.setPose(Pose.SLEEPING, true);
        layInBed(player, state);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks + 20, 200, true, false, false));
        if (blackout) {
            vomit.burst(player, settings, true);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_MUD_BREAK,
                    SoundCategory.PLAYERS, 0.8F, 0.6F);
        }
    }

    /**
     * Уложить по-настоящему. Если просто выставить позу сна, клиент всё равно рисует
     * игрока стоящим (свою модель он считает сам), поэтому на время лежания плагин
     * ставит под ним обычную кровать, укладывает спать, а потом возвращает блоки как
     * были. В аду и в энде кровати взрываются, там остаётся только поза.
     */
    private boolean layInBed(Player player, Lie state) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return false;

        BlockFace facing = bedFacing(state.anchor.getYaw());
        Block foot = state.anchor.getBlock();
        Block head = foot.getRelative(facing);
        if (!bedSpace(foot) || !bedSpace(head)) return false;

        BlockData oldFoot = foot.getBlockData().clone();
        BlockData oldHead = head.getBlockData().clone();
        try {
            Bed footData = (Bed) Material.RED_BED.createBlockData();
            footData.setFacing(facing);
            footData.setPart(Bed.Part.FOOT);
            foot.setBlockData(footData, false);

            Bed headData = (Bed) Material.RED_BED.createBlockData();
            headData.setFacing(facing);
            headData.setPart(Bed.Part.HEAD);
            head.setBlockData(headData, false);

            if (!player.sleep(foot.getLocation(), true)) {
                restoreBed(foot, head, oldFoot, oldHead);
                return false;
            }
            state.bed = new FallBed(foot, head, oldFoot, oldHead);
            return true;
        } catch (RuntimeException ex) {
            restoreBed(foot, head, oldFoot, oldHead);
            return false;
        }
    }

    /** Проснуться и убрать временную кровать. */
    private void wakeFromBed(Player player, Lie state) {
        FallBed bed = state.bed;
        if (bed == null) return;
        state.bed = null;
        if (player.isSleeping()) {
            UUID id = player.getUniqueId();
            waking.add(id);
            try {
                player.wakeup(false);
            } catch (RuntimeException ignored) {
                // Уже проснулся — не страшно.
            } finally {
                waking.remove(id);
            }
        }
        restoreBed(bed.foot, bed.head, bed.oldFoot, bed.oldHead);
    }

    private static void restoreBed(Block foot, Block head, BlockData oldFoot, BlockData oldHead) {
        if (foot.getBlockData() instanceof Bed) foot.setBlockData(oldFoot, false);
        if (head.getBlockData() instanceof Bed) head.setBlockData(oldHead, false);
    }

    /** Куда можно поставить временную кровать: только в пустое место или траву. */
    private static boolean bedSpace(Block block) {
        Material type = block.getType();
        return type.isAir() || type == Material.SHORT_GRASS || type == Material.TALL_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN || type == Material.SNOW
                || type == Material.DEAD_BUSH;
    }

    /** Направление кровати по взгляду: 0° — юг, 90° — запад, 180° — север, 270° — восток. */
    private static BlockFace bedFacing(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;
        if (normalized < 45 || normalized >= 315) return BlockFace.SOUTH;
        if (normalized < 135) return BlockFace.WEST;
        if (normalized < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Лежачего не двигаем: держим на месте и разрешаем только вертеть головой.
     *
     * <p>Позу обновляем прямо здесь, на каждом шаге клиента: если этого не делать,
     * анимация лежания слетает, стоит игроку нажать ходьбу. И любое движение вверх
     * гасим — прыгать лёжа нельзя.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Lie state = lie.get(player.getUniqueId());
        if (state == null) return;
        Location to = event.getTo();
        Location anchor = state.anchor;
        if (to.getWorld() != anchor.getWorld()) return;

        if (player.getPose() != Pose.SLEEPING) player.setPose(Pose.SLEEPING, true);

        double dx = to.getX() - anchor.getX();
        double dz = to.getZ() - anchor.getZ();
        double dy = to.getY() - anchor.getY();

        if (dy > 0.05) {
            // Прыжок лёжа: гасим подъём и оставляем на земле.
            Vector velocity = player.getVelocity();
            player.setVelocity(new Vector(velocity.getX(), 0, velocity.getZ()));
        }
        boolean escaped = dx * dx + dz * dz > 0.16 || dy > 0.05 || dy < -0.5;
        if (!escaped) return;

        Location stay = anchor.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        event.setTo(stay);
    }

    // ===== ЭФФЕКТЫ СТАДИЙ =====

    /**
     * Что делает стадия. Баффы выдаём только на первых двух («весело»), дальше их
     * снимаем: поднялся на ступень — старые плюшки уходят, иначе к «Краю» игрок
     * оставался бы с полным набором усилений.
     */
    private void applyStage(Player player, DrunkStage stage) {
        applyBoons(player, stage);

        if (stage.atLeast(DrunkStage.SWAYING)) {
            effect(player, PotionEffectType.SLOWNESS, 0);
        }
        if (stage.atLeast(DrunkStage.QUEASY)) {
            effect(player, PotionEffectType.NAUSEA, 0);
            effect(player, PotionEffectType.SLOWNESS, 1);
        }
        if (stage.atLeast(DrunkStage.WASTED)) {
            effect(player, PotionEffectType.NAUSEA, 1);
            effect(player, PotionEffectType.SLOWNESS, 2);
            effect(player, PotionEffectType.WEAKNESS, 0);
            maybeDropItem(player);
        }
        if (stage.atLeast(DrunkStage.EDGE)) {
            effect(player, PotionEffectType.SLOWNESS, 3);
            effect(player, PotionEffectType.WEAKNESS, 1);
            edgePulse(player);
        }

        wobble(player, stage);
        hiccup(player, stage);
        vision(player, stage);
        if (stage.atLeast(DrunkStage.SWAYING)) heartbeat(player);
    }

    /**
     * Баффы стадии. Помним, что выдали именно мы ({@code boons}), поэтому чужие зелья
     * не трогаем, а свои снимаем сразу, как только стадия их больше не даёт.
     */
    private void applyBoons(Player player, DrunkStage stage) {
        Set<PotionEffectType> wanted = new HashSet<>();
        if (stage == DrunkStage.TIPSY) {
            wanted.add(PotionEffectType.ABSORPTION);
        } else if (stage == DrunkStage.TALKATIVE) {
            wanted.add(PotionEffectType.ABSORPTION);
            wanted.add(PotionEffectType.SPEED);
            wanted.add(PotionEffectType.LUCK);
        }
        dropBoons(player, wanted);
        if (wanted.isEmpty()) return;
        Set<PotionEffectType> applied = boons.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>());
        for (PotionEffectType type : wanted) {
            player.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, 0, true, false, false));
            applied.add(type);
        }
    }

    /** Снять всё, что мы выдали, кроме перечисленного. */
    private void dropBoons(Player player, Set<PotionEffectType> keep) {
        UUID id = player.getUniqueId();
        Set<PotionEffectType> applied = boons.get(id);
        if (applied == null || applied.isEmpty()) return;
        for (PotionEffectType type : BOONS) {
            if (!applied.contains(type) || keep.contains(type)) continue;
            player.removePotionEffect(type);
            applied.remove(type);
        }
        if (applied.isEmpty()) boons.remove(id);
    }

    private void effect(Player player, PotionEffectType type, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, Math.max(0, amplifier),
                true, false, false));
    }

    /** Снимаем всё, что навесили мы сами (и что не должно висеть после протрезвления). */
    private void clearEffects(Player player) {
        UUID id = player.getUniqueId();
        Set<PotionEffectType> applied = boons.remove(id);
        if (applied != null) {
            for (PotionEffectType type : applied) player.removePotionEffect(type);
        }
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (lie.remove(id) != null) player.setPose(Pose.STANDING, true);
    }

    /**
     * Самая первая стадия: короткие рывки и маленькие подскоки. Дальше рывков нет —
     * там игрока плавно ведёт в стороны ({@link #swayTick()}).
     */
    private void wobble(Player player, DrunkStage stage) {
        if (!settings.wobble() || stage != DrunkStage.TIPSY) return;
        if (lie.containsKey(player.getUniqueId())) return;
        if (!standing(player)) return;
        if (random.nextDouble() > 0.35) return;

        double strength = 0.05 + random.nextDouble() * 0.04;
        player.setVelocity(player.getVelocity().add(new Vector(
                (random.nextDouble() - 0.5) * 2 * strength,
                0.02,
                (random.nextDouble() - 0.5) * 2 * strength)));
        // Маленький подскок: ноги уже чуть заплетаются.
        if (random.nextDouble() < 0.25) {
            player.setVelocity(player.getVelocity().add(new Vector(0, 0.22, 0)));
        }
    }

    /** Можно ли сейчас качать этого игрока: живой, на земле, не в транспорте и не лежит. */
    private boolean standing(Player player) {
        if (player.isDead() || player.isInsideVehicle() || player.isGliding()
                || player.isSwimming() || player.isSleeping() || !player.isOnGround()) return false;
        if (lie.containsKey(player.getUniqueId())) return false;
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    /**
     * Плавное покачивание: каждые два тика игрока мягко тянет в сторону, причём сторона
     * меняется по синусу, а направление его же скорости мы чуть-чуть подкручиваем —
     * поэтому он идёт дугами, а не по прямой. На высоких стадиях качает даже стоящего.
     */
    private void swayTick() {
        if (!settings.wobble()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            DrunkStage stage = stage(player);
            if (!DrunkWalk.sways(stage)) continue;
            if (!standing(player)) continue;

            Sway walk = sway.computeIfAbsent(player.getUniqueId(), key -> new Sway(random));
            walk.phase += walk.speed;
            if (walk.phase > Math.PI * 2) walk.phase -= Math.PI * 2;

            Vector velocity = player.getVelocity();
            Vector flat = new Vector(velocity.getX(), 0, velocity.getZ());
            double speed = flat.length();
            boolean moving = speed > 0.06;
            // Стоять на месте на высоких стадиях не получается вовсе.
            if (!moving && !stage.atLeast(DrunkStage.WASTED)) continue;

            Vector direction = moving
                    ? flat.clone().multiply(1 / speed)
                    : player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() < 1e-6) continue;
            direction.normalize();

            double factor = moving ? 1.0 : DrunkWalk.standingFactor(stage);
            if (factor <= 0) continue;
            double amp = DrunkWalk.sway(stage) * factor;
            double wave = Math.sin(walk.phase);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(wave * amp);
            Vector next = velocity.clone().add(side);

            // «Понесло»: несколько секунд игрока тянет в одну случайную сторону,
            // причём направление само понемногу кривится — идёт неровно.
            if (DrunkWalk.wanders(stage)) {
                if (walk.wanderLeft <= 0) walk.rewind(random, stage);
                walk.wanderLeft--;
                walk.wanderAngle += (random.nextDouble() - 0.5) * 0.12;
                next = next.add(new Vector(Math.cos(walk.wanderAngle), 0,
                        Math.sin(walk.wanderAngle)).multiply(walk.wanderPower));
            }
            if (moving) {
                double[] curved = DrunkWalk.rotate(next.getX(), next.getZ(), wave * DrunkWalk.curve(stage));
                next = new Vector(curved[0], next.getY(), curved[1]);
            }
            if (Math.hypot(next.getX(), next.getZ()) > 0.62) continue;
            player.setVelocity(next);
        }
    }

    /** «Ик»: тихий звук отрыжки. Никаких надписей — только звук. */
    private void hiccup(Player player, DrunkStage stage) {
        if (stage == DrunkStage.TIPSY) return;
        double chance = stage.atLeast(DrunkStage.WASTED) ? 0.25 : 0.12;
        if (random.nextDouble() > chance) return;
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP,
                SoundCategory.PLAYERS, 0.35F, 1.7F);
    }

    /** В глазах двоится: у случайного блока рядом появляется его копия на секунду. */
    private void vision(Player player, DrunkStage stage) {
        World world = player.getWorld();
        Location eyes = player.getEyeLocation();
        if (stage.atLeast(DrunkStage.QUEASY)) {
            world.spawnParticle(Particle.ITEM_SLIME,
                    eyes.clone().add(eyes.getDirection().multiply(0.6)), 3, 0.3, 0.2, 0.3, 0.01);
        }
        if (stage.atLeast(DrunkStage.EDGE)) {
            world.spawnParticle(Particle.CLOUD, eyes.clone().add(eyes.getDirection().multiply(0.8)),
                    4, 0.5, 0.3, 0.5, 0.01);
        }
    }

    private void heartbeat(Player player) {
        if (random.nextDouble() > 0.5) return;
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,
                SoundCategory.PLAYERS, 0.25F, 0.6F);
    }

    /** «Край»: слепота пульсами и звон в ушах. */
    private void edgePulse(Player player) {
        if (random.nextDouble() < 0.25) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, true, false, false));
        }
        if (random.nextDouble() < 0.2) {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.PLAYERS, 0.2F, 0.5F);
        }
    }

    /** Руки не слушаются: иногда предмет выскальзывает из руки. */
    private void maybeDropItem(Player player) {
        if (random.nextDouble() > 0.02) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) return;
        ItemStack drop = held.clone();
        drop.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? new ItemStack(Material.AIR) : held);
        Location at = player.getLocation().add(0, 1.0, 0);
        player.getWorld().dropItem(at, drop);
        player.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.4F, 0.6F);
    }

    /**
     * Приступ тошноты. Это не расписание, а шанс: на «Навеселе» стошнить может редко,
     * с каждой стадией чаще, а в отключке — каждую секунду и даже чаще.
     * {@code puke-rate} в настройках умножает все шансы разом.
     */
    private void maybePuke(Player player, DrunkStage stage) {
        double chance = stage.pukeChance() * settings.pukeRate();
        if (chance <= 0) return;
        // Свой сон в отключке блевать не мешает: лежачего тоже тошнит.
        if (player.isInsideVehicle() || player.isDead()) return;
        if (player.isSleeping() && !lie.containsKey(player.getUniqueId())) return;

        int bursts = (int) Math.floor(chance);
        if (random.nextDouble() < chance - bursts) bursts++;
        for (int i = 0; i < bursts; i++) {
            vomit.burst(player, settings, stage.atLeast(DrunkStage.WASTED));
        }
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    /** Похмелье: тошнота, слабость и замедление. Без надписей — только состояние. */
    private void applyHangover(Player player, UUID id) {
        Integer left = hangover.get(id);
        if (left == null) return;
        if (left <= 0) {
            hangover.remove(id);
            return;
        }
        hangover.put(id, left - 20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, EFFECT_TICKS, 0, true, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, EFFECT_TICKS, 0, true, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, EFFECT_TICKS, 0, true, false, false));
    }

    /**
     * Смена стадии. Игроку про шкалу не сообщаем ничего: только звук, а баффы прошлой
     * стадии снимаем сразу, чтобы они не тянулись за игроком вниз по лестнице.
     */
    private void onStageChanged(Player player, DrunkStage from, DrunkStage to) {
        lastStage.put(player.getUniqueId(), to);
        if (from == to) return;
        if (to == DrunkStage.SOBER) {
            dropBoons(player, Set.of());
            return;
        }
        applyBoons(player, to);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                SoundCategory.PLAYERS, 0.4F, to.atLeast(DrunkStage.QUEASY) ? 0.6F : 1.2F);
    }

    // ===== СЛУШАТЕЛИ =====

    /**
     * Выпитое и съеденное: алкоголь добавляет, молоко и вода убирают, еда закусывает.
     *
     * <p>Слушаем на MONITOR, то есть после всех, кто решает, что вообще произойдёт:
     * если событие отменили, ничего не считаем.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (BambooBarrel.isBarrelDrink(item)) {
            int units = unitsOf(item);
            if (units > 0) add(player, units);
            ProfileManager profiles = plugin.profileManager();
            if (profiles != null) profiles.drinkConsumed(player, BambooBarrel.getBarrelDrinkId(item));
            return;
        }
        // Напиток из шейкера: крепость задаётся строкой alcohol в recipe-poilo.yml.
        String shakerDrink = Shaker.isDrink(item) ? Shaker.getDrinkType(item) : null;
        if (shakerDrink != null) {
            int units = DrinkRecipes.current().alcoholOf(shakerDrink);
            if (units > 0) add(player, units);
            ProfileManager profiles = plugin.profileManager();
            if (profiles != null) profiles.drinkConsumed(player, shakerDrink);
            return;
        }
        if (item.getType() == Material.MILK_BUCKET) {
            hangover.remove(player.getUniqueId());
            add(player, -1);
            return;
        }
        if (WaterBottle.isWaterBottle(item)) {
            Integer left = hangover.get(player.getUniqueId());
            if (left != null) hangover.put(player.getUniqueId(), Math.max(0, left - HANGOVER_TICKS / 2));
            add(player, -3);
            return;
        }
        if (item.getType().isEdible()) {
            add(player, -1);
        }
    }

    /** Сколько единиц даёт глоток этого напитка: качество берём из бутылочки. */
    int unitsOf(ItemStack item) {
        String drinkId = BambooBarrel.getBarrelDrinkId(item);
        if (drinkId == null) return 0;
        // Своего профиля в napitki.yml нет — работает alcohol из recipe-poilo.yml.
        if (BarrelDrinks.current().ownKeys(drinkId).isEmpty()) {
            int alcohol = DrinkRecipes.current().alcoholOf(drinkId);
            if (alcohol > 0) return alcohol;
        }
        BarrelDrinks.Profile profile = BarrelDrinks.current().of(drinkId);
        int tier = profile.tierAfterTransfers(BambooBarrel.getRumYears(item),
                BambooBarrel.getRumTransfers(item));
        return profile.drunkFor(tier);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Integer saved = points.get(id);
        if (saved != null) points.put(id, Math.min(saved, settings.scale()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        decayProgress.remove(id);
        lastStage.remove(id);
        hangover.remove(id);
        boons.remove(id);
        hold.remove(id);
        sway.remove(id);
        Lie state = lie.remove(id);
        if (state != null) {
            wakeFromBed(event.getPlayer(), state);
            event.getPlayer().setPose(Pose.STANDING, true);
        }
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity());
    }

    /** Спал пьяным — проснулся трезвым, но с похмельем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWakeUp(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // Отключку не сбить даже сном: минута заперта наглухо.
        if (hold.containsKey(id)) return;
        // И наша временная кровать — тоже не повод трезветь.
        if (lie.containsKey(id) || waking.contains(id)) return;
        int value = points.getOrDefault(id, 0);
        if (value <= 0) return;
        clear(player);
        if (value * DrunkStage.MAX_POINTS >= settings.scale() * 25) {
            startHangover(player, HANGOVER_TICKS);
        }
    }

    /** «Море по колено»: пьяному падать не так больно. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        DrunkStage stage = stage(player);
        if (!stage.atLeast(DrunkStage.TIPSY) || stage.atLeast(DrunkStage.EDGE)) return;
        event.setDamage(Math.min(event.getDamage(), 1.0));
    }

    /**
     * Игрок убирает лужу руками: ничего не выпадает, а прежний блок возвращается
     * следующим тиком (событие срабатывает до того, как мир станет воздухом).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPuddleBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockData old = vomit.takePuddle(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (old == null) return;
        event.setDropItems(false);
        event.setExpToDrop(0);
        Bukkit.getScheduler().runTask(plugin, () -> block.setBlockData(old, false));
    }

    /** Лужи в выгружаемом чанке убираем сразу: иначе они остались бы навсегда. */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        vomit.restoreChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    /**
     * Пьяная речь. Стоим на HIGH: обработчик профиля на HIGHEST, значит он получит уже
     * пьяный текст и подпишет его префиксом как обычно.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!settings.slurredChat()) return;
        Player player = event.getPlayer();
        DrunkStage stage = DrunkStage.of(points(player.getUniqueId()), settings.scale());
        if (stage == DrunkStage.SOBER || stage == DrunkStage.TIPSY) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isBlank()) return;
        String slurred = DrunkSpeech.slur(message, stage, random);
        if (slurred != null && !slurred.equals(message)) {
            event.message(Component.text(slurred));
        }
    }

    // ===== СОХРАНЕНИЕ =====

    private void loadStore() {
        if (!Files.exists(storePath)) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storePath.toFile());
            if (yaml.getConfigurationSection("players") != null) {
                for (String key : yaml.getConfigurationSection("players").getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(key);
                        int value = yaml.getInt("players." + key, 0);
                        if (value > 0) points.put(id, value);
                    } catch (IllegalArgumentException ignored) {
                        // Ключ не UUID — пропускаем, файл мог править человек.
                    }
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Не читается data/" + FILE + ": " + ex.getMessage());
        }
    }

    /** Записать всех, кто сейчас в игре, и всех, кто остался в памяти. */
    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : points.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                yaml.set("players." + entry.getKey(), entry.getValue());
            }
        }
        try {
            yaml.save(storePath.toFile());
        } catch (Exception ex) {
            plugin.getLogger().warning("Не сохраняется data/" + FILE + ": " + ex.getMessage());
        }
    }

    /** Для тестов и команды: сколько фантомных блоков и луж сейчас живо. */
    int phantomCount() {
        return vomit.phantoms();
    }

    int puddleCount() {
        return vomit.puddles();
    }
}
