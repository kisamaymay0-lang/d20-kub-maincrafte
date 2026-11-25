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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
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

    /** Сколько тиков идёт «отключка». */
    private static final int BLACKOUT_TICKS = 80;
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
    private final Map<UUID, Integer> blackout = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextPuke = new ConcurrentHashMap<>();
    private final Map<UUID, DrunkStage> lastStage = new ConcurrentHashMap<>();
    private final Path storePath;

    private DrunkSettings settings = DrunkSettings.defaults();
    private BukkitTask playersTask;
    private BukkitTask pukeTask;
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

    /** Строка для профиля: «Опьянение: 12/50 — Шатает» или «Опьянение: трезв». */
    static Component statusLine(int points) {
        int scale = scaleOf();
        DrunkStage stage = DrunkStage.of(points, scale);
        if (stage == DrunkStage.SOBER) {
            return Component.text("Опьянение: трезв", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
        return Component.text("Опьянение: " + points + "/" + scale + " — " + stage.title(), colorOf(stage))
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
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 20L * 60L, 20L * 60L);
    }

    /** Остановить всё: убрать лужи и сохранить показатели. */
    void stop() {
        if (playersTask != null) playersTask.cancel();
        if (pukeTask != null) pukeTask.cancel();
        if (saveTask != null) saveTask.cancel();
        playersTask = null;
        pukeTask = null;
        saveTask = null;
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

    /** Добавить единицы: выпитая бутылочка, команда, отладка. */
    void add(Player player, int units) {
        if (player == null || units == 0) return;
        set(player, points(player.getUniqueId()) + units);
    }

    /** Поставить значение: с пересчётом стадии, отключкой и сообщением о смене. */
    void set(Player player, int value) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        int scale = settings.scale();
        int next = Math.max(0, Math.min(scale, value));
        int previous = points.getOrDefault(id, 0);
        points.put(id, next);

        DrunkStage oldStage = DrunkStage.of(previous, scale);
        DrunkStage newStage = DrunkStage.of(next, scale);
        if (newStage != oldStage) {
            onStageChanged(player, oldStage, newStage);
        }
        if (next >= scale && previous < scale) {
            blackout(player);
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
        blackout.remove(id);
        lastStage.remove(id);
        clearEffects(player);
    }

    /** Начать похмелье. */
    void startHangover(Player player, int ticks) {
        if (player == null || !settings.hangover()) return;
        hangover.put(player.getUniqueId(), Math.max(20, ticks));
        player.sendActionBar(Component.text("Голова гудит…", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
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

            int value = points.getOrDefault(id, 0);
            if (value > 0) {
                int progress = decayProgress.merge(id, 20, Integer::sum);
                if (progress >= settings.decayTicks()) {
                    int left = progress - settings.decayTicks();
                    value = Math.max(0, value - 1);
                    if (left > 0) decayProgress.put(id, left);
                    else decayProgress.remove(id);
                    points.put(id, value);
                    DrunkStage stage = DrunkStage.of(value, scale);
                    if (stage != DrunkStage.of(value + 1, scale)) onStageChanged(player,
                            DrunkStage.of(value + 1, scale), stage);
                }
            }

            DrunkStage stage = DrunkStage.of(value, scale);
            if (stage == DrunkStage.SOBER) {
                applyHangover(player, id, stage);
                continue;
            }
            if (settings.ignoreCreative() && (player.getGameMode() == GameMode.CREATIVE)) {
                applyHangover(player, id, stage);
                continue;
            }
            applyStage(player, value, stage);
            applyHangover(player, id, stage);
            tickBlackout(player, id, value, scale);
        }
    }

    private void tickBlackout(Player player, UUID id, int value, int scale) {
        Integer left = blackout.get(id);
        if (left == null) return;
        if (left <= 0) {
            blackout.remove(id);
            if (value >= scale) set(player, scale / 2);
            startHangover(player, HANGOVER_TICKS);
            clearEffects(player);
            return;
        }
        blackout.put(id, left - 20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 200, true, false, false));
        if (value >= scale) points.put(id, scale);
    }

    /** Приступ за секунду до отключки — так игрок хотя бы понимает, что произошло. */
    private void blackout(Player player) {
        UUID id = player.getUniqueId();
        if (blackout.containsKey(id)) return;
        blackout.put(id, BLACKOUT_TICKS);
        lastStage.put(id, DrunkStage.BLACKOUT);
        player.sendActionBar(Component.text("Земля ушла из-под ног…", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP,
                SoundCategory.PLAYERS, 1.0F, 0.5F);
        vomit.burst(player, settings, true);
    }

    // ===== ЭФФЕКТЫ СТАДИЙ =====

    private void applyStage(Player player, int value, DrunkStage stage) {
        // Баффы: сначала «навеселе» — теплее и смелее, дальше только хуже.
        if (stage.atLeast(DrunkStage.TIPSY)) {
            effect(player, PotionEffectType.ABSORPTION, 0);
        }
        if (stage.atLeast(DrunkStage.TALKATIVE)) {
            effect(player, PotionEffectType.STRENGTH, 0);
            effect(player, PotionEffectType.ABSORPTION, 1);
        }
        if (stage.atLeast(DrunkStage.SWAYING)) {
            effect(player, PotionEffectType.RESISTANCE, 0);
            effect(player, PotionEffectType.SLOWNESS, 0);
        }
        if (stage.atLeast(DrunkStage.QUEASY)) {
            effect(player, PotionEffectType.NAUSEA, 0);
            effect(player, PotionEffectType.SLOWNESS, 1);
            schedulePuke(player, stage);
        }
        if (stage.atLeast(DrunkStage.WASTED)) {
            effect(player, PotionEffectType.STRENGTH, 1);
            effect(player, PotionEffectType.NAUSEA, 1);
            effect(player, PotionEffectType.WEAKNESS, 0);
            maybeDropItem(player);
        }
        if (stage.atLeast(DrunkStage.EDGE)) {
            edgePulse(player);
        }

        wobble(player, stage);
        hiccup(player, stage);
        vision(player, stage);
        if (stage.atLeast(DrunkStage.SWAYING)) heartbeat(player);
    }

    private void effect(Player player, PotionEffectType type, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, Math.max(0, amplifier),
                true, false, false));
    }

    /** Снимаем всё, что навесили мы сами (и что не должно висеть после протрезвления). */
    private void clearEffects(Player player) {
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.ABSORPTION);
    }

    /**
     * Шатание: раз в пару секунд подталкивает в случайную сторону. Только на земле,
     * не в транспорте и не в полёте — иначе это уже не «пьяный», а «сломанный».
     */
    private void wobble(Player player, DrunkStage stage) {
        if (!settings.wobble() || !stage.atLeast(DrunkStage.SWAYING)) return;
        if (random.nextDouble() > (stage.atLeast(DrunkStage.WASTED) ? 0.5 : 0.25)) return;
        if (!player.isOnGround() || player.isInsideVehicle() || player.isGliding()
                || player.isSwimming() || player.isSleeping()) return;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        double strength = stage.atLeast(DrunkStage.WASTED) ? 0.16 : 0.09;
        Vector push = new Vector((random.nextDouble() - 0.5) * 2 * strength, 0.03,
                (random.nextDouble() - 0.5) * 2 * strength);
        player.setVelocity(player.getVelocity().add(push));
    }

    /** «Ик»: тихий звук и иногда строчка над хотбаром. */
    private void hiccup(Player player, DrunkStage stage) {
        if (stage == DrunkStage.TIPSY || random.nextDouble() > 0.06) return;
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

    /** Приступ тошноты по расписанию (раз в 60–90 секунд на «Укачало»). */
    private void schedulePuke(Player player, DrunkStage stage) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long next = nextPuke.getOrDefault(id, 0L);
        if (next == 0L) {
            nextPuke.put(id, now + randomBetween(settings.pukeMinTicks(), settings.pukeMaxTicks()) * 50L);
            return;
        }
        if (now < next) return;

        if (player.isInsideVehicle() || player.isSleeping() || player.isDead()) {
            nextPuke.put(id, now + 10_000L);
            return;
        }
        player.sendActionBar(Component.text("Тошнит…", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        vomit.burst(player, settings, stage.atLeast(DrunkStage.WASTED));
        effect(player, PotionEffectType.WEAKNESS, 0);
        nextPuke.put(id, now + randomBetween(settings.pukeMinTicks(), settings.pukeMaxTicks()) * 50L);
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    /** Похмелье: тошнота, слабость, замедление и «голова гудит». */
    private void applyHangover(Player player, UUID id, DrunkStage stage) {
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
        if (left % 100 == 0) {
            player.sendActionBar(Component.text("Голова гудит…", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
    }

    /** Смена стадии: короткая строчка и звук, чтобы игрок понял, что происходит. */
    private void onStageChanged(Player player, DrunkStage from, DrunkStage to) {
        lastStage.put(player.getUniqueId(), to);
        if (from == to || to == DrunkStage.SOBER) {
            if (to == DrunkStage.SOBER) {
                player.sendActionBar(Component.text("В голове проясняется", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            return;
        }
        player.sendActionBar(Component.text(to.title(), colorOf(to))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, to.atLeast(DrunkStage.QUEASY)));
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
            return;
        }
        if (item.getType() == Material.MILK_BUCKET) {
            hangover.remove(player.getUniqueId());
            add(player, -15);
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
        nextPuke.remove(id);
        lastStage.remove(id);
        hangover.remove(id);
        blackout.remove(id);
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
