package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Бамбуковая бочка: в ней варят и выдерживают напитки (3x3x3 мультблок по NBT-структуре bamboo-bo4ka):
 * <ul>
 *   <li><b>Структура 3x3x3:</b> Состоит из обтёсанных бамбуковых блоков (stripped_bamboo_block) и бамбуковых ступеней (bamboo_stairs) с полой сердцевиной (air).</li>
 *   <li><b>Ориентация:</b> Поддерживается горизонтальное расположение вдоль оси X и вдоль оси Z.</li>
 *   <li><b>Что варится:</b> любой напиток с {@code method: barrel} из settings/recipe-poilo.yml.
 *       Рецепт — это «предмет: сколько»: у рома тростник, уголь и три горячей воды; у вин —
 *       помятые (или продавленные) ягоды и вода, а сладкому ещё сахар, белому — костная мука.</li>
 *   <li><b>Вместимость:</b> Вмещает до 9 бутылочек напитка для выдержки.</li>
 *   <li><b>Добавление:</b> При добавлении предметов из бочки вылетают частицы WAX_OFF.</li>
 *   <li><b>Извлечение:</b> На Shift + ПКМ можно забрать из бочки один ингредиент обратно.</li>
 *   <li><b>Брожение:</b> сколько тиков и сколько бутылочек — в data/napitki/napitki.yml
 *       (у рома и вин по умолчанию 200 тиков и 3 бутылочки).</li>
 *   <li><b>Готовность:</b> После завершения брожения вся 3x3 поверхность бочки покрывается стекающими каплями воды (dripping_water / falling_water).</li>
 *   <li><b>Выдержка и качества:</b> Годы, ступени качества и их описания — тоже в
 *       data/napitki/napitki.yml (по умолчанию 24000 тиков = 1 год), штрафы от переливаний, 3 глотка.</li>
 *   <li><b>Разрушение бочки:</b> Твёрдые ресурсы выпадают в центре, а жидкость разливается и не выпадает.</li>
 *   <li><b>Обратная связь:</b> Исключительно внутриигровая (звуки, партиклы, предметы) без лишнего чата.</li>
 * </ul>
 */
public class BambooBarrel implements Listener {

    public static final NamespacedKey MODEL_PIRATE_RUM_KEY = new NamespacedKey("f8resurs", "skull_poilo");
    public static final NamespacedKey PIRATE_RUM_KEY = new NamespacedKey("adaptation", "pirate_rum");
    /** Какой бочковый напиток в бутылочке: id из settings/recipe-poilo.yml. */
    public static final NamespacedKey BARREL_DRINK_KEY = new NamespacedKey("adaptation", "barrel_drink");
    public static final NamespacedKey RUM_YEARS_KEY = new NamespacedKey("adaptation", "pirate_rum_years");
    public static final NamespacedKey RUM_TRANSFERS_KEY = new NamespacedKey("adaptation", "pirate_rum_transfers");
    public static final NamespacedKey RUM_CHARGES_KEY = new NamespacedKey("adaptation", "pirate_rum_charges");

    public static final int BREWING_TIME_TICKS = 200; // 10 секунд
    public static final int MAX_BOTTLES = 9;          // Вместимость бочки: до 9 бутылок рома
    public static final long DEFAULT_TICKS_PER_YEAR = 24000L; // 1 день майнкрафта = 1 год
    private static long ticksPerYear = DEFAULT_TICKS_PER_YEAR;

    private static final Random RANDOM = new Random();

    private final Plugin plugin;
    private final Map<Location, BarrelState> barrels = new HashMap<>();

    public BambooBarrel(Plugin plugin) {
        this.plugin = plugin;
        reloadSettings();
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        }
    }

    public void reloadSettings() {
        if (plugin != null) {
            long configured = plugin.getConfig().getLong("bamboo_barrel.ticks_per_year", DEFAULT_TICKS_PER_YEAR);
            ticksPerYear = configured > 0L ? configured : DEFAULT_TICKS_PER_YEAR;
        }
    }

    public static long getTicksPerYear() {
        return ticksPerYear;
    }

    public static void setTicksPerYear(long ticks) {
        ticksPerYear = ticks > 0L ? ticks : DEFAULT_TICKS_PER_YEAR;
    }

    public enum QualityTier {
        TERRIBLE(1),
        STRANGE(2),
        GOOD(3),
        PERFECT(4),
        FLAWLESS(5);

        private final int tier;

        QualityTier(int tier) {
            this.tier = tier;
        }

        public int tier() {
            return tier;
        }

        public static QualityTier fromTier(int tier) {
            for (QualityTier q : values()) {
                if (q.tier == tier) return q;
            }
            return STRANGE;
        }
    }

    /** Что сейчас в бочке: какие ингредиенты лежат и что из них варится. */
    public static final class BarrelState {
        /** Ингредиенты в бочке: чего сколько лежит. */
        public final Map<DrinkRecipes.BarrelIngredient, Integer> items =
                new java.util.EnumMap<>(DrinkRecipes.BarrelIngredient.class);
        /** id напитка из recipe-poilo.yml, когда закладка уже варится или настоялась. */
        public String drink = null;
        public boolean brewing = false;
        public int brewingTicks = 0;
        public boolean ready = false;
        public int bottlesCount = 0;
        public long ageTicks = 0L;
        public int transfers = 0;

        public boolean isEmpty() {
            return items.isEmpty() && !brewing && !ready && bottlesCount == 0;
        }
    }

    // ===== КАЧЕСТВА И ОФОРМЛЕНИЕ =====
    //
    // Лестница качества живёт в data/napitki/napitki.yml: у каждого бочкового напитка свои
    // сроки, свои ступени и свои описания. Здесь только читаем её и считаем штрафы.

    /** Ступени качества рома — для старых вызовов и тестов. */
    public static QualityTier getBaseQuality(int years) {
        return QualityTier.fromTier(BarrelDrinks.current().of(Shaker.RECIPE_PIRATE_RUM).tierFor(years));
    }

    public static QualityTier getQuality(int years, int transfers) {
        int tier = BarrelDrinks.tierAfterTransfers(getBaseQuality(years).tier(), transfers);
        return QualityTier.fromTier(tier);
    }

    public static Component gradientText(String text, TextColor from, TextColor to) {
        Component comp = Component.empty();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            float ratio = len > 1 ? (float) i / (len - 1) : 0f;
            int r = Math.round(from.red() + ratio * (to.red() - from.red()));
            int g = Math.round(from.green() + ratio * (to.green() - from.green()));
            int b = Math.round(from.blue() + ratio * (to.blue() - from.blue()));
            comp = comp.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, b)));
        }
        return comp;
    }

    /** Надпись ступени рома (цвет, жирность, подчёркивание) — из настроек напитка. */
    public static Component formatQualityName(QualityTier quality) {
        return BarrelDrinks.current().of(Shaker.RECIPE_PIRATE_RUM).level(quality.tier()).format();
    }

    public static TextColor getQualityColor(QualityTier quality) {
        return BarrelDrinks.current().of(Shaker.RECIPE_PIRATE_RUM).level(quality.tier()).color();
    }

    public static String getQualityDescription(QualityTier quality, int years) {
        return BarrelDrinks.current().of(Shaker.RECIPE_PIRATE_RUM).description(years, quality.tier());
    }

    /**
     * Описание напитка в подсказке бутылочки: качество, годы, переливания и текст.
     *
     * <p>Текст берётся из {@code data/napitki/napitki.yml} (описание ступени или года), а если
     * у напитка в {@code settings/recipe-poilo.yml} заполнено {@code description} — оно
     * добавляется отдельной строкой в конце. Так описания бочковых напитков правятся в
     * recipe-poilo.yml, не трогая годы и ступени.
     */
    public static List<Component> buildDrinkLore(String drinkId, int years, int transfers) {
        BarrelDrinks.Profile profile = BarrelDrinks.current().of(drinkId);
        int tier = profile.tierAfterTransfers(years, transfers);
        BarrelDrinks.Level level = profile.level(tier);
        TextColor qColor = level.color();

        List<Component> lore = new ArrayList<>(7);
        lore.add(Component.text("----------------------", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Качество: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(level.format()));
        lore.add(Component.text("----------------------", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Лет: ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(years, qColor).decoration(TextDecoration.ITALIC, false)));
        lore.add(Component.text("Переливаний: ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(transfers, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        // Пьянит или нет — без цифр: шкала опьянения игроку не показывается.
        if (profile.drunkFor(tier) > 0) {
            lore.add(Component.text("Алькогольное", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        String description = profile.description(years, tier);
        if (description != null && !description.isEmpty()) {
            lore.add(Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        DrinkRecipes.Drink recipe = DrinkRecipes.current().get(drinkId);
        if (recipe != null && recipe.description() != null && !recipe.description().isEmpty()) {
            lore.add(Component.text(recipe.description(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lore;
    }

    public static List<Component> buildRumLore(int years, int transfers) {
        return buildDrinkLore(Shaker.RECIPE_PIRATE_RUM, years, transfers);
    }

    // ===== ПРОВЕРКА МУЛЬТИБЛОКА (3x3x3 NBT СТРУКТУРА) =====

    public static boolean isBarrelWall(Material mat) {
        return mat == Material.STRIPPED_BAMBOO_BLOCK || mat == Material.BAMBOO_BLOCK;
    }

    public static boolean isBarrelStairs(Material mat) {
        return mat == Material.BAMBOO_STAIRS || mat == Material.BAMBOO_MOSAIC_STAIRS;
    }

    public static boolean isCavity(Material mat) {
        return mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR;
    }

    public static boolean isBambooBarrelBlock(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        return isBarrelWall(mat) || isBarrelStairs(mat);
    }

    private static boolean checkStairs(Block block, BlockFace expectedFacing, Bisected.Half expectedHalf) {
        if (block == null) return false;
        if (!isBarrelStairs(block.getType())) return false;
        if (block.getBlockData() instanceof Stairs stairs) {
            if (expectedFacing != null && stairs.getFacing() != expectedFacing) {
                return false;
            }
            if (expectedHalf != null && stairs.getHalf() != expectedHalf) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesXAligned(World world, int cx, int cy, int cz) {
        if (world == null) return false;

        // dy = -1 (низ бочки)
        for (int dx = -1; dx <= 1; dx++) {
            if (!checkStairs(world.getBlockAt(cx + dx, cy - 1, cz - 1), BlockFace.SOUTH, Bisected.Half.TOP)) return false;
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy - 1, cz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + dx, cy - 1, cz + 1), BlockFace.NORTH, Bisected.Half.TOP)) return false;
        }

        // dy = 0 (средний слой)
        for (int dx = -1; dx <= 1; dx++) {
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz - 1).getType())) return false;
            if (dx == 0) {
                if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) return false;
            } else {
                if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz).getType())) return false;
            }
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy, cz + 1).getType())) return false;
        }

        // dy = +1 (верх бочки)
        for (int dx = -1; dx <= 1; dx++) {
            if (!checkStairs(world.getBlockAt(cx + dx, cy + 1, cz - 1), BlockFace.SOUTH, Bisected.Half.BOTTOM)) return false;
            if (!isBarrelWall(world.getBlockAt(cx + dx, cy + 1, cz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + dx, cy + 1, cz + 1), BlockFace.NORTH, Bisected.Half.BOTTOM)) return false;
        }

        return true;
    }

    public static boolean matchesZAligned(World world, int cx, int cy, int cz) {
        if (world == null) return false;

        // dy = -1 (низ бочки)
        for (int dz = -1; dz <= 1; dz++) {
            if (!checkStairs(world.getBlockAt(cx - 1, cy - 1, cz + dz), BlockFace.EAST, Bisected.Half.TOP)) return false;
            if (!isBarrelWall(world.getBlockAt(cx, cy - 1, cz + dz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + 1, cy - 1, cz + dz), BlockFace.WEST, Bisected.Half.TOP)) return false;
        }

        // dy = 0 (средний слой)
        for (int dz = -1; dz <= 1; dz++) {
            if (!isBarrelWall(world.getBlockAt(cx - 1, cy, cz + dz).getType())) return false;
            if (dz == 0) {
                if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) return false;
            } else {
                if (!isBarrelWall(world.getBlockAt(cx + dz, cy, cz + dz).getType())) return false;
            }
            if (!isBarrelWall(world.getBlockAt(cx + 1, cy, cz + dz).getType())) return false;
        }

        // dy = +1 (верх бочки)
        for (int dz = -1; dz <= 1; dz++) {
            if (!checkStairs(world.getBlockAt(cx - 1, cy + 1, cz + dz), BlockFace.EAST, Bisected.Half.BOTTOM)) return false;
            if (!isBarrelWall(world.getBlockAt(cx, cy + 1, cz + dz).getType())) return false;
            if (!checkStairs(world.getBlockAt(cx + 1, cy + 1, cz + dz), BlockFace.WEST, Bisected.Half.BOTTOM)) return false;
        }

        return true;
    }

    public static boolean isValidBarrelCenter(World world, int cx, int cy, int cz) {
        if (world == null) return false;
        if (!isCavity(world.getBlockAt(cx, cy, cz).getType())) {
            return false;
        }
        return matchesXAligned(world, cx, cy, cz) || matchesZAligned(world, cx, cy, cz);
    }

    public static Location findBarrelCenter(Block clickedBlock) {
        if (clickedBlock == null) return null;
        World world = clickedBlock.getWorld();
        if (world == null) return null;

        int bx = clickedBlock.getX();
        int by = clickedBlock.getY();
        int bz = clickedBlock.getZ();

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    int cx = bx + ox;
                    int cy = by + oy;
                    int cz = bz + oz;
                    if (isValidBarrelCenter(world, cx, cy, cz)) {
                        return new Location(world, cx, cy, cz);
                    }
                }
            }
        }
        return null;
    }

    // ===== СОЗДАНИЕ И ПРОВЕРКА БУТЫЛОЧКИ ИЗ БОЧКИ =====

    /** Пустая бутылочка рома: 0 лет, 1 переливание, 3 глотка (как раньше). */
    public static ItemStack createPirateRum() {
        return createBarrelDrink(Shaker.RECIPE_PIRATE_RUM, 0, 1, 3);
    }

    public static ItemStack createPirateRum(int years, int transfers, int charges) {
        return createBarrelDrink(Shaker.RECIPE_PIRATE_RUM, years, transfers, charges);
    }

    /**
     * Бутылочка напитка из бочки: название, модель, цвет и описание — из
     * {@code settings/recipe-poilo.yml}, годы и качество — из {@code data/napitki/napitki.yml}.
     *
     * <p>Модели у нового напитка может не быть — тогда бутылочка остаётся обычным зельем
     * своего цвета, и модель появится сама, как только её пропишут в файле.
     */
    public static ItemStack createBarrelDrink(String drinkId, int years, int transfers, int charges) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(PotionType.WATER);
            DrinkRecipes.Drink recipe = DrinkRecipes.current().get(drinkId);
            String name = recipe != null ? recipe.name()
                    : (Shaker.RECIPE_PIRATE_RUM.equals(drinkId) ? Shaker.NAME_PIRATE_RUM : drinkId);
            Component title = Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false);
            meta.itemName(title);
            meta.displayName(title);
            if (recipe != null && recipe.model() != null && !recipe.model().isEmpty()) {
                meta.setItemModel(new NamespacedKey("f8resurs", recipe.model()));
            } else if (Shaker.RECIPE_PIRATE_RUM.equals(drinkId)) {
                meta.setItemModel(MODEL_PIRATE_RUM_KEY);
            }
            if (recipe != null) meta.setColor(Color.fromRGB(recipe.color().value()));
            else meta.setColor(Color.fromRGB(0x8B, 0x3A, 0x14));
            var pdc = meta.getPersistentDataContainer();
            pdc.set(BARREL_DRINK_KEY, PersistentDataType.STRING, drinkId);
            if (Shaker.RECIPE_PIRATE_RUM.equals(drinkId)) {
                pdc.set(PIRATE_RUM_KEY, PersistentDataType.BYTE, (byte) 1);
            }
            pdc.set(RUM_YEARS_KEY, PersistentDataType.INTEGER, years);
            pdc.set(RUM_TRANSFERS_KEY, PersistentDataType.INTEGER, transfers);
            pdc.set(RUM_CHARGES_KEY, PersistentDataType.INTEGER, Math.max(1, Math.min(3, charges)));
            pdc.set(Shaker.DRINK_PDC_KEY, PersistentDataType.STRING, drinkId);
            meta.lore(buildDrinkLore(drinkId, years, transfers));
            potion.setItemMeta(meta);
        }
        return potion;
    }

    /** Это бутылочка из бочки (ром, вино и всё, что там настаивают). */
    public static boolean isBarrelDrink(ItemStack item) {
        return getBarrelDrinkId(item) != null;
    }

    /** Что за напиток в бутылочке; null — это не напиток из бочки. */
    public static String getBarrelDrinkId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(BARREL_DRINK_KEY, PersistentDataType.STRING);
        if (id != null && !id.isEmpty()) return id;
        Byte legacy = pdc.get(PIRATE_RUM_KEY, PersistentDataType.BYTE);
        return legacy != null && legacy == (byte) 1 ? Shaker.RECIPE_PIRATE_RUM : null;
    }

    public static boolean isPirateRum(ItemStack item) {
        return Shaker.RECIPE_PIRATE_RUM.equals(getBarrelDrinkId(item));
    }

    public static int getRumYears(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer y = item.getItemMeta().getPersistentDataContainer().get(RUM_YEARS_KEY, PersistentDataType.INTEGER);
        return y != null ? y : 0;
    }

    public static int getRumTransfers(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 1;
        Integer t = item.getItemMeta().getPersistentDataContainer().get(RUM_TRANSFERS_KEY, PersistentDataType.INTEGER);
        return t != null ? t : 1;
    }

    public static int getRumCharges(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 3;
        Integer c = item.getItemMeta().getPersistentDataContainer().get(RUM_CHARGES_KEY, PersistentDataType.INTEGER);
        return c != null ? c : 3;
    }

    // ===== ТИК И ВИЗУАЛЬНЫЕ ЭФФЕКТЫ (ПО ВСЕЙ СТРУКТУРЕ 3x3) =====

    private void tick() {
        Iterator<Map.Entry<Location, BarrelState>> it = barrels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, BarrelState> entry = it.next();
            Location centerLoc = entry.getKey();
            BarrelState state = entry.getValue();

            World world = centerLoc.getWorld();
            if (world == null || !world.isChunkLoaded(centerLoc.getBlockX() >> 4, centerLoc.getBlockZ() >> 4)) {
                continue;
            }

            int cx = centerLoc.getBlockX();
            int cy = centerLoc.getBlockY();
            int cz = centerLoc.getBlockZ();

            // Проверка целостности бочки (мультиблок 3x3x3 с пустой полостью внутри)
            if (!isValidBarrelCenter(world, cx, cy, cz)) {
                dropIngredients(world, centerLoc, state);
                it.remove();
                continue;
            }

            if (state.brewing) {
                state.brewingTicks -= 2;

                // Партиклы пара и кипения по верхней плоскости 3x3 структуры бочки
                for (int i = 0; i < 3; i++) {
                    double px = (cx - 0.8) + RANDOM.nextDouble() * 2.6;
                    double pz = (cz - 0.8) + RANDOM.nextDouble() * 2.6;
                    world.spawnParticle(Particle.SPLASH, px, cy + 1.2, pz, 2, 0.15, 0.1, 0.15, 0.05);
                }
                if (RANDOM.nextFloat() < 0.4f) {
                    double px = (cx - 0.5) + RANDOM.nextDouble() * 2.0;
                    double pz = (cz - 0.5) + RANDOM.nextDouble() * 2.0;
                    world.spawnParticle(Particle.BUBBLE_POP, px, cy + 1.4, pz, 1, 0.0, 0.05, 0.0, 0.01);
                }

                if (state.brewingTicks <= 0) {
                    state.brewing = false;
                    state.ready = true;
                    state.bottlesCount = Math.min(MAX_BOTTLES,
                            state.bottlesCount + BarrelDrinks.current().of(state.drink).bottles());
                    state.ageTicks = 0L;
                    world.playSound(centerLoc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.8F, 1.2F);
                    world.playSound(centerLoc, Sound.BLOCK_CONDUIT_ACTIVATE, SoundCategory.BLOCKS, 0.5F, 1.5F);
                }
            } else if (state.ready) {
                // Настаивание: время выдержки тикает
                state.ageTicks += 2L;

                // Покрывается каплями воды по всей внешней поверхности структуры 3x3
                for (int i = 0; i < 6; i++) {
                    double px = (cx - 1.0) + RANDOM.nextDouble() * 3.0;
                    double pz = (cz - 1.0) + RANDOM.nextDouble() * 3.0;
                    double py = (cy - 1.0) + RANDOM.nextDouble() * 3.0;

                    if (RANDOM.nextBoolean()) {
                        // Капли сверху и скатывающиеся вниз
                        world.spawnParticle(Particle.DRIPPING_WATER, px, cy + 2.02, pz, 1, 0.0, 0.0, 0.0, 0.0);
                        world.spawnParticle(Particle.FALLING_WATER, px, cy + 1.8, pz, 1, 0.0, 0.0, 0.0, 0.0);
                    } else {
                        // Капли по бокам корпуса
                        world.spawnParticle(Particle.DRIPPING_WATER, px, py, pz, 1, 0.02, 0.02, 0.02, 0.0);
                    }
                }
            }
        }
    }

    // ===== ВЗАИМОДЕЙСТВИЕ ИГРОКА =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isBambooBarrelBlock(block)) return;

        Location centerLoc = findBarrelCenter(block);
        if (centerLoc == null) return;

        Player player = event.getPlayer();
        World world = block.getWorld();
        EquipmentSlot hand = event.getHand();
        if (hand == null) hand = EquipmentSlot.HAND;

        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        BarrelState state = barrels.computeIfAbsent(centerLoc, k -> new BarrelState());

        // 1. Shift + ПКМ: забрать из бочки один ингредиент. Готовый напиток забирают
        //    пустой бутылочкой (пункт 3): пока он настаивается, трогать нечего.
        if (player.isSneaking()) {
            if (!state.brewing && !state.ready) {
                takeIngredient(player, state, centerLoc, block, world);
                if (state.isEmpty()) {
                    barrels.remove(centerLoc);
                }
            }
            return;
        }

        // 2. Перелить готовый напиток обратно в бочку (только полная бутылочка, 3 глотка).
        if (isBarrelDrink(held)) {
            int charges = getRumCharges(held);
            if (charges < 3) {
                return;
            }
            if (state.isEmpty() || (state.ready && state.bottlesCount < MAX_BOTTLES)) {
                event.setCancelled(true);
                String drinkId = getBarrelDrinkId(held);
                int years = getRumYears(held);
                int transfers = getRumTransfers(held);

                ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
                if (held.getAmount() > 1) {
                    held.setAmount(held.getAmount() - 1);
                    setHandItem(player, hand, held);
                    giveOrDrop(player, emptyBottle);
                } else {
                    setHandItem(player, hand, emptyBottle);
                }

                if (state.isEmpty()) {
                    state.items.clear();
                    state.drink = drinkId;
                    state.ready = true;
                    state.brewing = false;
                    state.bottlesCount = 1;
                    state.ageTicks = years * BarrelDrinks.current().of(drinkId).ticksPerYear();
                    state.transfers = transfers;
                } else {
                    state.bottlesCount++;
                }

                spawnAddParticles(world, block, centerLoc);
                player.playSound(centerLoc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.8F, 1.0F);
            }
            return;
        }

        // 3. Забор готового напитка пустой бутылочкой (обычный ПКМ).
        if (state.ready && state.bottlesCount > 0) {
            if (held != null && held.getType() == Material.GLASS_BOTTLE) {
                event.setCancelled(true);

                long ticksPerYear = BarrelDrinks.current().of(state.drink).ticksPerYear();
                int years = (int) (state.ageTicks / Math.max(1L, ticksPerYear));
                int transfers = state.transfers + 1;
                ItemStack bottle = createBarrelDrink(state.drink, years, transfers, 3);

                if (held.getAmount() > 1) {
                    held.setAmount(held.getAmount() - 1);
                    setHandItem(player, hand, held);
                    giveOrDrop(player, bottle);
                } else {
                    setHandItem(player, hand, bottle);
                }

                state.bottlesCount--;
                if (state.bottlesCount <= 0) {
                    state.ready = false;
                    state.drink = null;
                    state.ageTicks = 0L;
                    state.transfers = 0;
                    if (state.isEmpty()) {
                        barrels.remove(centerLoc);
                    }
                }

                player.playSound(centerLoc, Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8F, 1.0F);
            }
            return;
        }

        if (state.brewing) {
            return;
        }

        if (held == null || held.getType() == Material.AIR) return;

        // 4. Положить ингредиент: уголь, тростник, вода, ягоды, сахар, костная мука.
        //    Сколько чего бочка принимает, решают рецепты в settings/recipe-poilo.yml.
        DrinkRecipes.BarrelIngredient ingredient = DrinkRecipes.BarrelIngredient.of(held);
        if (ingredient == null) return;

        int max = DrinkRecipes.current().maxBarrelCount(ingredient);
        if (state.items.getOrDefault(ingredient, 0) >= max) return;

        event.setCancelled(true);
        if (ingredient == DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE
                || ingredient == DrinkRecipes.BarrelIngredient.WATER_BOTTLE) {
            ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
            if (held.getAmount() > 1) {
                held.setAmount(held.getAmount() - 1);
                setHandItem(player, hand, held);
                giveOrDrop(player, emptyBottle);
            } else {
                setHandItem(player, hand, emptyBottle);
            }
            player.playSound(centerLoc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.7F, 1.0F);
        } else {
            consumeOne(player, hand, held);
            player.playSound(centerLoc, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.7F, 1.0F);
        }
        state.items.merge(ingredient, 1, Integer::sum);
        spawnAddParticles(world, block, centerLoc);
        checkStartBrewing(centerLoc, state);
    }

    /** Забрать из бочки один ингредиент — тот, что лежит первым по списку. */
    private void takeIngredient(Player player, BarrelState state, Location centerLoc,
                                Block block, World world) {
        DrinkRecipes.BarrelIngredient ingredient = null;
        for (Map.Entry<DrinkRecipes.BarrelIngredient, Integer> entry : state.items.entrySet()) {
            if (entry.getValue() > 0) {
                ingredient = entry.getKey();
                break;
            }
        }
        if (ingredient == null) {
            // В бочке только жидкость — пусть игрок услышит, что там что-то есть.
            if (state.items.isEmpty()) {
                player.playSound(centerLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.4F, 1.5F);
            }
            return;
        }

        int left = state.items.get(ingredient) - 1;
        if (left > 0) {
            state.items.put(ingredient, left);
        } else {
            state.items.remove(ingredient);
        }
        giveOrDrop(player, ingredient.create());

        Location clickPos = block.getLocation().add(0.5, 0.8, 0.5);
        if (ingredient == DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE
                || ingredient == DrinkRecipes.BarrelIngredient.WATER_BOTTLE) {
            player.playSound(centerLoc, Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8F, 1.0F);
            world.spawnParticle(Particle.SPLASH, clickPos, 4, 0.1, 0.1, 0.1, 0.02);
        } else {
            player.playSound(centerLoc, Sound.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.BLOCKS, 0.7F, 1.1F);
            world.spawnParticle(Particle.WAX_OFF, clickPos, 3, 0.1, 0.1, 0.1, 0.02);
        }
    }

    private static void spawnAddParticles(World world, Block clickedBlock, Location centerLoc) {
        if (world == null) return;
        Location clickPos = clickedBlock != null ? clickedBlock.getLocation().add(0.5, 0.8, 0.5) : centerLoc.clone().add(0.5, 1.2, 0.5);
        world.spawnParticle(Particle.WAX_OFF, clickPos, 7, 0.25, 0.25, 0.25, 0.05);
        Location topCenter = centerLoc.clone().add(0.5, 1.5, 0.5);
        world.spawnParticle(Particle.WAX_OFF, topCenter, 5, 0.3, 0.1, 0.3, 0.05);
    }

    /**
     * Начать варку, если в бочке лежит ровно то, что просит один из напитков.
     *
     * <p>Совпадение точное: лишний предмет останавливает варку. Поэтому сладкое вино
     * получится и тогда, когда сахар положили раньше ягод, — но не превратится в обычное
     * вино, если сахар лежит рядом.
     */
    private void checkStartBrewing(Location centerLoc, BarrelState state) {
        if (state.brewing || state.ready) return;

        DrinkRecipes recipes = DrinkRecipes.current();
        for (DrinkRecipes.Drink drink : recipes.barrelDrinks()) {
            DrinkRecipes.BarrelRecipe recipe = recipes.barrelRecipe(drink.id());
            if (recipe == null || !recipe.matches(state.items)) continue;

            BarrelDrinks.Profile profile = BarrelDrinks.current().of(drink.id());
            state.items.clear();
            state.drink = drink.id();
            state.brewing = true;
            state.brewingTicks = profile.brewTicks();
            state.ageTicks = 0L;
            state.transfers = 0;

            World world = centerLoc.getWorld();
            if (world != null) {
                world.playSound(centerLoc, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.8F, 0.9F);
            }
            return;
        }
    }

    // ===== УПОТРЕБЛЕНИЕ НАПИТКА (3 ГЛОТКА / ХРАНИЛИЩА) =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!isBarrelDrink(item)) return;

        String drinkId = getBarrelDrinkId(item);
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) hand = EquipmentSlot.HAND;
        EquipmentSlot finalHand = hand;

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.8F, 0.9F);

        int charges = getRumCharges(item);
        int years = getRumYears(item);
        int transfers = getRumTransfers(item);

        if (charges > 1 && plugin != null) {
            ItemStack remaining = createBarrelDrink(drinkId, years, transfers, charges - 1);
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack inHand = finalHand == EquipmentSlot.OFF_HAND
                        ? player.getInventory().getItemInOffHand()
                        : player.getInventory().getItemInMainHand();
                if (inHand != null && inHand.getType() == Material.GLASS_BOTTLE) {
                    if (inHand.getAmount() > 1) {
                        inHand.setAmount(inHand.getAmount() - 1);
                        if (finalHand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(inHand);
                        else player.getInventory().setItemInMainHand(inHand);
                        giveOrDrop(player, remaining);
                    } else {
                        if (finalHand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(remaining);
                        else player.getInventory().setItemInMainHand(remaining);
                    }
                } else {
                    giveOrDrop(player, remaining);
                }
            });
        }
    }

    // ===== РАЗРУШЕНИЕ БЛОКОВ И ДРОП ИНГРЕДИЕНТОВ =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockDestroyed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            handleBlockDestroyed(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            handleBlockDestroyed(b);
        }
    }

    private void handleBlockDestroyed(Block block) {
        if (block == null) return;
        World world = block.getWorld();
        int bx = block.getX();
        int by = block.getY();
        int bz = block.getZ();

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Location center = new Location(world, bx + ox, by + oy, bz + oz);
                    BarrelState state = barrels.remove(center);
                    if (state != null) {
                        dropIngredients(world, center, state);
                    }
                }
            }
        }
    }

    private void dropIngredients(World world, Location loc, BarrelState state) {
        if (world == null || loc == null || state == null) return;
        Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);

        if (state.ready) {
            // Готовый напиток (жидкость) разливается и не выпадает в виде предмета
            world.spawnParticle(Particle.SPLASH, dropLoc, 25, 0.6, 0.5, 0.6, 0.1);
            world.playSound(dropLoc, Sound.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.8F, 0.9F);
            return;
        }

        if (state.brewing) {
            // Твёрдые ресурсы уже ушли в закладку, а варящаяся жидкость разливается
            world.spawnParticle(Particle.SPLASH, dropLoc, 20, 0.5, 0.5, 0.5, 0.1);
            world.playSound(dropLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.8F, 1.0F);
            return;
        }

        // Твёрдые ресурсы выпадают в центре, жидкость разливается
        for (Map.Entry<DrinkRecipes.BarrelIngredient, Integer> entry : state.items.entrySet()) {
            DrinkRecipes.BarrelIngredient ingredient = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;
            if (ingredient == DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE
                    || ingredient == DrinkRecipes.BarrelIngredient.WATER_BOTTLE) {
                world.spawnParticle(Particle.SPLASH, dropLoc, 10 * count, 0.4, 0.4, 0.4, 0.1);
                world.playSound(dropLoc, Sound.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.6F, 1.2F);
                continue;
            }
            ItemStack stack = ingredient.create();
            stack.setAmount(count);
            world.dropItemNaturally(dropLoc, stack);
        }
        state.items.clear();
    }

    private static void consumeOne(Player player, EquipmentSlot hand, ItemStack item) {
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            setHandItem(player, hand, item);
        } else {
            setHandItem(player, hand, new ItemStack(Material.AIR));
        }
    }

    private static void setHandItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item);
        } else {
            player.getInventory().setItemInOffHand(item);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }
}
