package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сигарета — тестовый админский предмет из меню {@code /f8}.
 *
 * <p>Модели из ресурспака игрока: {@code sigareta-big} — холодная сигарета,
 * {@code sigareta-big-fire} — горящая. Обе называются одинаково, просто
 * «Сигарета»: имя не меняется от поджига, описания у предмета нет.
 *
 * <p>Поджиг: огниво во второй руке, сигарета в главной, шифт + ПКМ — модель
 * меняется на горящую. Любой ПКМ горящей сигаретой начинает тягу, и в воздух,
 * и по блоку. Тягу держат, отпускают — идёт выдох. Анимация трубения в козий
 * рог видна и самому игроку от первого лица; само «съедение» запрещено в
 * {@link #onEat}. Сигарета перехватывает ПКМ у блока, чтобы действие работало
 * в обоих случаях.
 *
 * <p>Шкала пополняется на палочку каждые 0,2 секунды. После отпускания изо рта
 * непрерывно вылетают частицы уютного дыма костра в текущем направлении
 * взгляда, выдох длится от 0,5 до 8 секунд. Запас — 64 палочки, четыре полных
 * тяги; когда запас кончается, сигарета ломается и исчезает. Стак — до восьми
 * сигарет.
 */
final class Cigarette implements Listener {

    /** Название обоих видов: обычное, белое, без описаний. */
    private static final String TITLE = "Сигарета";

    /** Модели из ресурспака: холодная сигарета и горящая. */
    private static final NamespacedKey MODEL_COLD = new NamespacedKey("f8resurs", "sigareta-big");
    private static final NamespacedKey MODEL_LIT = new NamespacedKey("f8resurs", "sigareta-big-fire");

    /** Метка вида в самом предмете: 0 — холодная, 1 — горящая. */
    private static final NamespacedKey LIT = new NamespacedKey("f8-plugin", "cigarette_lit");
    /** Невидимый запас: сколько палочек тяги осталось. */
    private static final NamespacedKey BARS = new NamespacedKey("f8-plugin", "cigarette_bars");

    /** Палочек в одной тяге и полных тяг в запасе: 16 × 4 = 64. */
    static final int BARS_PER_PUFF = 16;
    static final int FULL_PUFFS = 4;
    static final int RESERVE = BARS_PER_PUFF * FULL_PUFFS;

    /** Одна палочка — 0,2 секунды тяги. */
    private static final int TICKS_PER_BAR = 4;
    /** Сигареты объединяются максимум по восемь штук в стаке. */
    static final int MAX_STACK_SIZE = 8;
    /** Время «использования»: тянуть можно сколько угодно, обрыва нет. */
    private static final float USE_SECONDS = 3600.0F;
    /** Звука во время тяги быть не должно: специально «пустой» ванильный звук. */
    private static final Key SILENT_SOUND = Key.key("intentionally_empty");
    /** Как часто перерисовываем шкалу и проверяем, не кончился ли запас. */
    private static final long PERIOD = 2L;
    /** Выдох после тяги: от половины секунды до восьми, полсекунды на палочку. */
    private static final double EXHALE_MIN_SECONDS = 0.5D;
    private static final double EXHALE_MAX_SECONDS = 8.0D;
    /** Начало струи дыма: чуть впереди и ниже глаз, чтобы шёл изо рта. */
    private static final double MOUTH_AHEAD = 0.28D;
    private static final double MOUTH_DOWN = 0.12D;
    /** Расстояние выброса дыма перед ртом. */
    private static final double SMOKE_PUSH = 0.04D;

    private final JavaPlugin plugin;

    /** Кто сейчас тянет сигарету: рука и тик начала. */
    private final Map<UUID, Puff> puffs = new HashMap<>();

    /** Летящий дым: по одному потоку на игрока, новый обрывает старый. */
    private final Map<UUID, Smoke> smoke = new HashMap<>();

    private BukkitTask ticker;

    /** Тяга: рука, в которой сигарета, и тик, с которого игрок держит ПКМ. */
    private record Puff(EquipmentSlot hand, int since) { }

    Cigarette(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
    }

    /** Выключение: гасим шкалу, дым и текущие тяги. */
    void disable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Smoke flow : smoke.values()) {
            flow.stop();
        }
        smoke.clear();
        puffs.clear();
    }

    /** Холодная сигарета: модель sigareta-big, полный запас. */
    static ItemStack cold() {
        return create(false);
    }

    /** Горящая сигарета: модель sigareta-big-fire, полный запас. */
    static ItemStack lit() {
        return create(true);
    }

    /**
     * Сама сигарета: бумага, белое имя без описаний, модель из ресурспака и
     * съедобный компонент — он и даёт анимацию трубения в козий рог с держанием
     * ПКМ. Звука нет, частиц нет, «съесть» предмет нельзя: {@link #onEat}
     * запрещает завершение. На старом ядре без компонента сигарета просто
     * останется бумажкой, как и кувшин без своего компонента.
     */
    static ItemStack create(boolean lit) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ProfileItems.text(TITLE, NamedTextColor.WHITE));
        meta.setItemModel(lit ? MODEL_LIT : MODEL_COLD);
        meta.getPersistentDataContainer().set(LIT, PersistentDataType.INTEGER, lit ? 1 : 0);
        meta.getPersistentDataContainer().set(BARS, PersistentDataType.INTEGER, RESERVE);
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
        // Огромное время «съедения»: тянуть можно сколько угодно, обрыва нет.
        // Компонент и даёт анимацию трубения в козий рог с держанием ПКМ.
        apply(item, DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(USE_SECONDS)
                .animation(ItemUseAnimation.TOOT_HORN)
                .sound(SILENT_SOUND)
                .hasConsumeParticles(false)
                .build(), "consumable");
        apply(item, DataComponentTypes.MAX_STACK_SIZE, MAX_STACK_SIZE, "max_stack_size");
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
        return value == null ? RESERVE : Math.clamp(value, 0, RESERVE);
    }

    /**
     * Любой обычный ПКМ горящей сигаретой запускает тягу — и в воздух, и по
     * блоку. Сигарета забирает клик у блока, чтобы ваниль не погасила начало
     * использования предмета; огниво в другой руке по-прежнему зажигает её
     * при шифт + ПКМ.
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

        if (isCigarette(handItem(player, hand))) {
            // Сигареты в обеих руках: клик считаем один раз, по главной руке.
            if (hand == EquipmentSlot.OFF_HAND && isCigarette(player.getInventory().getItemInMainHand())) {
                return;
            }
            ItemStack cigarette = handItem(player, hand);
            if (player.isSneaking() && !isLit(cigarette)
                    && isFlintAndSteel(handItem(player, other(hand)))) {
                light(player, hand);
            }
            if (isLit(handItem(player, hand))) {
                // Не даём блоку перехватить клик; предмет используется и в воздухе, и по блоку.
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
                event.setUseItemInHand(Event.Result.ALLOW);
                hold(player, cigaretteHand);
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        puffs.remove(event.getPlayer().getUniqueId());
        Smoke flow = smoke.remove(event.getPlayer().getUniqueId());
        if (flow != null) {
            flow.stop();
        }
    }

    /**
     * Каждые два тика: шкала тяги и проверка, не кончился ли запас прямо в
     * руке. Отпускание ПКМ ловит {@link #onStopUsing}; сигарета, унесённая из
     * руки или погасшая, закрывает тягу сама, а набранная шкала — выдох.
     */
    private void tick() {
        int tick = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<UUID, Puff>> it = puffs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Puff> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Puff puff = entry.getValue();
            if (player == null) {
                it.remove();
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
            int filled = barsFor(tick - puff.since(), left);
            showGauge(player, filled);
            if (filled >= left) {
                // Запас кончился прямо в тяге: сигарета догорела.
                it.remove();
                spend(player, puff.hand(), filled);
            }
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
        puffs.put(player.getUniqueId(), new Puff(hand, Bukkit.getCurrentTick()));
        showGauge(player, 0);
    }

    /** Тяга закончилась отпусканием: дым и расход запаса. */
    private void finish(Player player, Puff puff) {
        ItemStack item = handItem(player, puff.hand());
        if (!isCigarette(item) || !isLit(item)) {
            player.sendActionBar(Component.empty());
            return;
        }
        int held = Bukkit.getCurrentTick() - puff.since();
        spend(player, puff.hand(), barsForRelease(held, bars(item)));
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
        smoke(player, filled);
        ItemStack item = handItem(player, hand);
        if (!isCigarette(item)) {
            return;
        }
        int rest = bars(item) - filled;
        if (rest > 0) {
            setBars(item, rest);
            return;
        }
        breakUp(player, hand);
    }

    /** Сигарета догорела: звук поломки, дымок — и предмета нет. */
    private void breakUp(Player player, EquipmentSlot hand) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.7F, 1.0F);
        Location where = mouth(player);
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, where, 4, 0.04D, 0.04D, 0.04D, 0.01D);
        ItemStack item = handItem(player, hand);
        if (item != null && item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            setBars(item, RESERVE);
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
        ItemStack fired = create(true);
        fired.setAmount(item.getAmount());
        setHandItem(player, hand, fired);
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.5F, 1.2F);
        Location where = mouth(player);
        player.getWorld().spawnParticle(Particle.FLAME, where, 5, 0.04D, 0.04D, 0.04D, 0.01D);
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, where, 2, 0.02D, 0.02D, 0.02D, 0.01D);
    }

    /**
     * Дым изо рта летит туда, куда игрок смотрит. Чем больше затяжка, тем
     * длиннее струя и тем гуще дым; предыдущий поток тотчас обрывается.
     */
    private void smoke(Player player, int filled) {
        Smoke old = smoke.remove(player.getUniqueId());
        if (old != null) {
            old.stop();
        }
        Smoke flow = new Smoke(player, filled);
        smoke.put(player.getUniqueId(), flow);
        flow.start();
    }

    /** Шкала тяги: "[ |||||||||||||||| ]", палочки слева направо желтеют. */
    private static void showGauge(Player player, int filled) {
        player.sendActionBar(gauge(filled));
    }

    /** Та же шкала одной строкой: 16 палочек, набитые — жёлтые. */
    static Component gauge(int filled) {
        Component gauge = Component.text("[ ", NamedTextColor.DARK_GRAY);
        for (int i = 0; i < BARS_PER_PUFF; i++) {
            gauge = gauge.append(Component.text("|", i < filled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        }
        return gauge.append(Component.text(" ]", NamedTextColor.DARK_GRAY));
    }

    /**
     * Сколько палочек набилось за тики тяги: одна за 0,2 секунды. При отпускании
     * раньше первой палочки finish() всё равно даёт минимальную тягу на выдох.
     */
    static int barsFor(int ticks, int left) {
        if (ticks <= 0 || left <= 0) {
            return 0;
        }
        return Math.clamp(Math.min(BARS_PER_PUFF, ticks / TICKS_PER_BAR), 0, left);
    }

    /** При отпускании любая ненулевая тяга даёт минимум одну палочку выдоха. */
    static int barsForRelease(int ticks, int left) {
        int filled = barsFor(ticks, left);
        return ticks > 0 && filled == 0 ? Math.min(1, Math.max(0, left)) : filled;
    }

    /**
     * Сколько тиков игрок непрерывно выдыхает дым после тяги: полсекунды на
     * палочку, минимум полсекунды, максимум восемь секунд.
     */
    static int exhaleTicks(int bars) {
        double seconds = Math.max(EXHALE_MIN_SECONDS, Math.min(EXHALE_MAX_SECONDS, bars * 0.5D));
        return (int) Math.round(seconds * 20.0D);
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

    private static void setBars(ItemStack item, int bars) {
        if (!isCigarette(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BARS, PersistentDataType.INTEGER, Math.clamp(bars, 0, RESERVE));
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
            this.perTick = 2 + bars / 4;
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
