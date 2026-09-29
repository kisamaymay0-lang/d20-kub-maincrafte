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
 * непрерывно вылетают частицы уютного дыма костра в текущем направлении
 * взгляда; каждая набранная палочка даёт ровно 0,2 секунды выдоха. Запас
 * зависит от модели: 64, 16 или 32 палочки; когда запас кончается, сигарета
 * ломается и исчезает. Стак — до восьми сигарет.
 */
final class Cigarette implements Listener {

    /** Название обоих видов: обычное, белое, без описаний. */
    private static final String TITLE = "Сигарета";

    /** Палочек в одной тяге и скрытый запас вариантов сигареты. */
    static final int BARS_PER_PUFF = 16;
    static final int FULL_PUFFS = 4;
    static final int RESERVE = BARS_PER_PUFF * FULL_PUFFS;
    static final int SMALL_RESERVE = 16;
    static final int REGULAR_RESERVE = 32;

    /** Метки состояния и варианта модели в самом предмете. */
    private static final NamespacedKey LIT = new NamespacedKey("f8-plugin", "cigarette_lit");
    private static final NamespacedKey VARIANT = new NamespacedKey("f8-plugin", "cigarette_variant");
    /** Невидимый запас: сколько палочек тяги осталось. */
    private static final NamespacedKey BARS = new NamespacedKey("f8-plugin", "cigarette_bars");

    private enum Variant {
        BIG("big", "sigareta-big", "sigareta-big-fire", RESERVE),
        SMALL("small", "sigareta-small", "sigareta-small-fire", SMALL_RESERVE),
        REGULAR("regular", "sigareta", "sigareta-fire", REGULAR_RESERVE);

        private final String id;
        private final String coldModel;
        private final String litModel;
        private final int reserve;

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
    /** Долгое использование: выдох происходит по отпусканию ПКМ, не по таймеру. */
    private static final float USE_SECONDS = 3600.0F;
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

    /** Кто сейчас тянет сигарету: рука и тик начала. */
    private final Map<UUID, Puff> puffs = new HashMap<>();

    /** Летящий дым: по одному потоку на игрока, новый обрывает старый. */
    private final Map<UUID, Smoke> smoke = new HashMap<>();

    /** Тик поджига: второе событие руки в том же нажатии не начинает тягу. */
    private final Map<UUID, Integer> litAtTick = new HashMap<>();

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
        litAtTick.clear();
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        puffs.remove(event.getPlayer().getUniqueId());
        litAtTick.remove(event.getPlayer().getUniqueId());
        Smoke flow = smoke.remove(event.getPlayer().getUniqueId());
        if (flow != null) {
            flow.stop();
        }
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
        ItemStack fired = create(true, variant(item));
        fired.setAmount(item.getAmount());
        setBars(fired, remaining);
        setHandItem(player, hand, fired);
        player.updateInventory();
        litAtTick.put(player.getUniqueId(), Bukkit.getCurrentTick() + 1);
        player.playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.5F, 1.2F);
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
     * Сколько тиков выдыхается дым: ровно 0,2 секунды на каждую набранную
     * палочку. Одна палочка — 4 тика; 16 палочек — 3,2 секунды.
     */
    static int exhaleTicks(int bars) {
        return Math.clamp(bars, 0, BARS_PER_PUFF) * TICKS_PER_BAR;
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
