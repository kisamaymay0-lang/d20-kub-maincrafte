package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Настройки бочковых напитков из {@code data/napitki/napitki.yml}.
 *
 * <h2>Зачем</h2>
 *
 * Раньше «сколько крафтится», «сколько лет до какого качества» и «что написано в
 * подсказке бутылочки» были зашиты в {@link BambooBarrel}. Теперь это файл: у каждого
 * бочкового напитка свои сроки, свои ступени качества и свои описания.
 *
 * <h2>Что где</h2>
 *
 * <ul>
 *   <li><b>default</b> — общие настройки: их берёт любой напиток, у которого нет своих;</li>
 *   <li><b>brew-ticks</b> — сколько тиков бродит закладка (20 тиков = 1 секунда);</li>
 *   <li><b>bottles</b> — сколько бутылочек выходит из одной закладки;</li>
 *   <li><b>ticks-per-year</b> — сколько тиков идёт один год выдержки (24000 = сутки в Minecraft);</li>
 *   <li><b>quality</b> — ступени качества: {@code tier} (номер ступени), {@code name},
 *       {@code color}, необязательные {@code gradient} (цвет в конце надписи),
 *       {@code bold}, {@code underline} и {@code description};</li>
 *   <li><b>years</b> — до какого года какая ступень: {@code up-to-years} (включительно;
 *       {@code -1} — «и дальше»), {@code tier} и необязательное своё {@code description};</li>
 *   <li><b>quality-description</b> — одно описание на все ступени сразу. Так у вин пока
 *       стоит прочерк «—» вместо настоящих описаний.</li>
 * </ul>
 *
 * <p>Своё описание у года важнее описания ступени, а {@code quality-description} важнее обоих:
 * он и задуман как «поставить заглушку на весь напиток».
 *
 * <p>Ступени нумеруются от 1 и понижаются штрафом за переливания (та же арифметика, что
 * была у рома): 2–3 переливания — минус ступень, 4–5 — минус две, 6 и больше — не выше
 * второй ступени.
 */
final class BarrelDrinks {

    /** Ступень качества: как называется и что о ней написано. */
    record Level(int tier, String name, TextColor color, TextColor gradientTo, boolean bold, boolean underline,
                 String description) {

        /** Готовая надпись ступени: цвет или градиент плюс жирный/подчёркнутый. */
        Component format() {
            Component text = gradientTo() != null
                    ? BambooBarrel.gradientText(name(), color(), gradientTo())
                    : Component.text(name(), color());
            text = text.decoration(TextDecoration.ITALIC, false);
            if (bold) text = text.decorate(TextDecoration.BOLD);
            if (underline) text = text.decorate(TextDecoration.UNDERLINED);
            return text;
        }
    }

    /** «До какого года» — ступень и, если нужно, своё описание. */
    record Years(int upToYears, int tier, String description) {

        /** Подходит ли год под эту запись. {@code -1} — «и дальше», без верхней границы. */
        boolean covers(int years) {
            return upToYears < 0 || years <= upToYears;
        }
    }

    /** Настройки одного напитка: сроки, ступени, годы и сила опьянения. */
    record Profile(int brewTicks, int bottles, long ticksPerYear, List<Level> levels, List<Years> years,
                   String descriptionOverride, Map<Integer, Integer> drunk) {

        Profile {
            levels = List.copyOf(levels);
            years = List.copyOf(years);
            drunk = Map.copyOf(drunk);
        }

        /** Сколько единиц опьянения даёт глоток этого качества; 0 — не пьянит. */
        int drunkFor(int tier) {
            Integer value = drunk.get(level(tier).tier());
            return value == null ? 0 : Math.max(0, value);
        }

        /** Копия с другой силой опьянения — для комплекта по умолчанию. */
        Profile withDrunk(Map<Integer, Integer> values) {
            return new Profile(brewTicks, bottles, ticksPerYear, levels, years, descriptionOverride, values);
        }

        /** Ступень, которая действует в этот год выдержки. */
        int tierFor(int ageYears) {
            int year = Math.max(0, ageYears);
            for (Years entry : years) {
                if (entry.covers(year)) return entry.tier();
            }
            return years.isEmpty() ? 1 : years.get(years.size() - 1).tier();
        }

        /** Запись «до какого года» для этого года; null — таблица годов пуста. */
        Years yearsEntry(int ageYears) {
            int year = Math.max(0, ageYears);
            for (Years entry : years) {
                if (entry.covers(year)) return entry;
            }
            return years.isEmpty() ? null : years.get(years.size() - 1);
        }

        /** Ступень по номеру; если такой нет — ближайшая снизу, иначе первая. */
        Level level(int tier) {
            if (levels.isEmpty()) throw new IllegalStateException("В napitki.yml нет ни одной ступени качества");
            Level fallback = levels.get(0);
            for (Level level : levels) {
                if (level.tier() == tier) return level;
                if (level.tier() < tier) fallback = level;
            }
            return fallback;
        }

        /** Ступень после штрафа за переливания. */
        int tierAfterTransfers(int ageYears, int transfers) {
            return BarrelDrinks.tierAfterTransfers(tierFor(ageYears), transfers);
        }

        /** Описание для подсказки: заглушка напитка, своё описание года или описание ступени. */
        String description(int ageYears, int tier) {
            if (descriptionOverride != null) return descriptionOverride;
            Years entry = yearsEntry(ageYears);
            if (entry != null && entry.description() != null) return entry.description();
            return level(tier).description();
        }
    }

    /** Имя файла с настройками (внутри папки data). */
    static final String FILE = "napitki/napitki.yml";

    static final int DEFAULT_BREW_TICKS = BambooBarrel.BREWING_TIME_TICKS;
    static final int DEFAULT_BOTTLES = 3;
    static final long DEFAULT_TICKS_PER_YEAR = BambooBarrel.DEFAULT_TICKS_PER_YEAR;

    /** Сколько даёт глоток рома: 40 % крепости против 12 % у вина. */
    static final Map<Integer, Integer> DEFAULT_RUM_DRUNK = Map.of(1, 4, 2, 5, 3, 6, 4, 7, 5, 8);

    /** Вино: те же ступени, но единиц меньше. */
    static final Map<Integer, Integer> DEFAULT_WINE_DRUNK = Map.of(1, 2, 2, 3, 3, 3, 4, 4, 5, 5);

    /** Пиво: слабее всех, целая бутылка — это «навеселе», а не отключка. */
    static final Map<Integer, Integer> DEFAULT_BEER_DRUNK = Map.of(1, 1, 2, 1, 3, 2, 4, 2, 5, 3);

    /** Что читать, пока файл не загружен. */
    private static volatile BarrelDrinks current = defaults();

    private final Profile base;
    private final Map<String, Profile> byDrink;

    /** Какие ключи владелец написал у напитка сам — для /f8 drinks. */
    private final Map<String, Set<String>> ownKeys;

    private BarrelDrinks(Profile base, Map<String, Profile> byDrink) {
        this(base, byDrink, Map.of());
    }

    private BarrelDrinks(Profile base, Map<String, Profile> byDrink, Map<String, Set<String>> ownKeys) {
        this.base = base;
        this.byDrink = Map.copyOf(byDrink);
        this.ownKeys = Map.copyOf(ownKeys);
    }

    /** Id напитков, у которых в файле есть своя запись. */
    Set<String> ids() {
        return byDrink.keySet();
    }

    /** Какие ключи напитка заданы в файле (quality, years, drunk, brew-ticks…). */
    Set<String> ownKeys(String id) {
        if (id == null) return Set.of();
        Set<String> keys = ownKeys.get(id);
        if (keys != null) return keys;
        String normalized = DrinkRecipes.normalizeId(id);
        for (Map.Entry<String, Set<String>> entry : ownKeys.entrySet()) {
            if (DrinkRecipes.normalizeId(entry.getKey()).equals(normalized)) return entry.getValue();
        }
        return Set.of();
    }

    /** Текущие настройки: их читает {@link BambooBarrel}. */
    static BarrelDrinks current() {
        return current;
    }

    /** Поставить новые настройки (после чтения файла или /f8 reload). */
    static void install(BarrelDrinks next) {
        if (next != null) current = next;
    }

    /** Настройки напитка: свои, а чего нет — из {@code default}. */
    Profile of(String drinkId) {
        Profile own = own(drinkId);
        return own != null ? own : base;
    }

    /** Есть ли у напитка своя запись в файле (регистр и дефисы не важны). */
    boolean has(String drinkId) {
        return own(drinkId) != null;
    }

    /** Своя запись напитка; null — берём {@code default}. */
    private Profile own(String drinkId) {
        if (drinkId == null) return null;
        Profile own = byDrink.get(drinkId);
        if (own != null) return own;
        String normalized = DrinkRecipes.normalizeId(drinkId);
        for (Map.Entry<String, Profile> entry : byDrink.entrySet()) {
            if (DrinkRecipes.normalizeId(entry.getKey()).equals(normalized)) return entry.getValue();
        }
        return null;
    }

    /** Общие настройки — то, что записано в разделе {@code default}. */
    Profile base() {
        return base;
    }

    // ===== АРИФМЕТИКА ШТРАФОВ =====

    /** Штраф за переливания: та же лестница, что была у рома. */
    static int tierAfterTransfers(int baseTier, int transfers) {
        if (transfers >= 6) return Math.min(baseTier, 2);
        if (transfers >= 4) return baseTier == 1 ? 1 : Math.max(2, baseTier - 2);
        if (transfers >= 2) return baseTier == 1 ? 1 : Math.max(2, baseTier - 1);
        return baseTier;
    }

    // ===== ЗАГРУЗКА =====

    /** Настройки из комплекта — ровно те, что были зашиты в код: ром со своей лестницей. */
    static BarrelDrinks defaults() {
        Profile base = new Profile(DEFAULT_BREW_TICKS, DEFAULT_BOTTLES, DEFAULT_TICKS_PER_YEAR,
                defaultLevels(), defaultYears(), null, DEFAULT_RUM_DRUNK);
        // Вина слабее рома: свои значения на каждое качество.
        Map<String, Profile> drinks = new LinkedHashMap<>();
        // Ром — и есть base, но запись нужна: /f8 drinks и проверка «есть ли блок
        // в napitki.yml» смотрят именно на список id.
        drinks.put("pirate_rum", base);
        drinks.put("wine", base.withDrunk(DEFAULT_WINE_DRUNK));
        drinks.put("sweet_wine", base.withDrunk(DEFAULT_WINE_DRUNK));
        drinks.put("white_wine", base.withDrunk(DEFAULT_WINE_DRUNK));
        drinks.put("bitter_wine", base.withDrunk(DEFAULT_WINE_DRUNK));
        // Даже если в файле про пиво ничего нет — оно всё равно слабое, а не как ром.
        drinks.put("beer", base.withDrunk(DEFAULT_BEER_DRUNK));
        return new BarrelDrinks(base, drinks);
    }

    /** Пять ступеней рома: те же имена, цвета и описания, что были в коде. */
    static List<Level> defaultLevels() {
        List<Level> levels = new ArrayList<>(5);
        levels.add(new Level(1, "Ужасное! ☠", NamedTextColor.RED, null, false, false,
                "Молодой сивушный спирт. Обжигает горло."));
        levels.add(new Level(2, "Странное...", TextColor.color(0xFFA500), null, false, false,
                "Вкус еще только формируется..."));
        levels.add(new Level(3, "Хорошее ☺", NamedTextColor.YELLOW, null, true, false,
                "Добротный пиратский ром, пик для будней на корабле!"));
        levels.add(new Level(4, "Идеальное! ☀", NamedTextColor.GREEN, null, true, true,
                "Ром идеален, вкус приятно чешет горло."));
        levels.add(new Level(5, "БЕЗУПРЕЧНОЕ!!1", NamedTextColor.WHITE, TextColor.color(0xFFFF55), true, true,
                "Теперь ты можешь называть себя настоящим пиратом!"));
        return List.copyOf(levels);
    }

    /** Годы рома: до 1 — ужасное, 2–3 странное, 4–5 хорошее, 6–9 идеальное, 10 безупречное,
     *  11–13 хорошее («передержали»), 14 и больше — странное («старая доска»). */
    static List<Years> defaultYears() {
        return List.of(
                new Years(1, 1, null),
                new Years(3, 2, null),
                new Years(5, 3, null),
                new Years(9, 4, null),
                new Years(10, 5, null),
                new Years(13, 3, "Передержали. Ром становится горьким и древесным"),
                new Years(-1, 2, "Уже не ром, а жидкость со вкусом старой доски."));
    }

    /**
     * Прочитать {@code data/napitki/napitki.yml}. Ошибка в файле — исключение: лучше
     * оставить прежние настройки, чем молча получить напиток без качества.
     */
    static BarrelDrinks load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        Profile base = readProfile(yaml.getConfigurationSection("default"), defaults().base(), "default");
        Map<String, Profile> byDrink = new LinkedHashMap<>();
        Map<String, Set<String>> ownKeys = new LinkedHashMap<>();
        ConfigurationSection drinks = yaml.getConfigurationSection("drinks");
        if (drinks != null) {
            for (String rawId : drinks.getKeys(false)) {
                String id = ProfileText.clean(rawId);
                if (id.isEmpty()) continue;
                ConfigurationSection section = drinks.getConfigurationSection(rawId);
                byDrink.put(id, readProfile(section, base, id));
                if (section != null) {
                    Set<String> keys = new LinkedHashSet<>();
                    for (String key : section.getKeys(false)) keys.add(key.toLowerCase(Locale.ROOT));
                    ownKeys.put(id, Set.copyOf(keys));
                }
            }
        }
        return new BarrelDrinks(base, byDrink, ownKeys);
    }

    /** Раздел напитка поверх общих настроек: чего в нём нет — берётся из базы. */
    private static Profile readProfile(ConfigurationSection section, Profile base, String id) {
        if (section == null) return base;
        int brewTicks = section.contains("brew-ticks")
                ? positive(section.getInt("brew-ticks", base.brewTicks()), "brew-ticks", id)
                : base.brewTicks();
        int bottles = section.contains("bottles")
                ? positive(section.getInt("bottles", base.bottles()), "bottles", id)
                : base.bottles();
        long ticksPerYear = section.contains("ticks-per-year")
                ? Math.max(1L, section.getLong("ticks-per-year", base.ticksPerYear()))
                : base.ticksPerYear();
        List<Level> levels = section.isList("quality") ? readLevels(section, id) : base.levels();
        List<Years> years = section.isList("years") ? readYears(section, levels, id) : base.years();
        String description = section.contains("quality-description")
                ? ProfileText.clean(section.getString("quality-description", ""))
                : base.descriptionOverride();
        if (description != null && description.isEmpty()) description = null;
        // Сила опьянения складывается с общими: чего у напитка нет — берём из default.
        Map<Integer, Integer> drunk = new LinkedHashMap<>(base.drunk());
        if (section.isConfigurationSection("drunk")) {
            drunk.putAll(readDrunk(section.getConfigurationSection("drunk"), id));
        }
        return new Profile(brewTicks, Math.min(bottles, BambooBarrel.MAX_BOTTLES), ticksPerYear, levels, years,
                description, drunk);
    }

    /** Сила опьянения: «ступень: единиц за глоток». */
    private static Map<Integer, Integer> readDrunk(ConfigurationSection section, String id) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (String raw : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Напиток " + id + ": в drunk ступень «" + raw + "» — не число");
            }
            int units = section.getInt(raw, 0);
            if (units < 0) {
                throw new IllegalArgumentException("Напиток " + id + ": в drunk у ступени " + tier
                        + " отрицательное число единиц");
            }
            values.put(tier, units);
        }
        return Map.copyOf(values);
    }

    private static int positive(int value, String key, String id) {
        if (value > 0) return value;
        throw new IllegalArgumentException("Напиток " + id + ": " + key + " должен быть больше нуля, а стоит " + value);
    }

    private static List<Level> readLevels(ConfigurationSection section, String id) {
        List<Map<?, ?>> raw = section.getMapList("quality");
        List<Level> levels = new ArrayList<>(raw.size());
        int auto = 1;
        for (Map<?, ?> entry : raw) {
            int tier = intOf(entry.get("tier"), auto, id, "tier");
            String name = ProfileText.clean(textOf(entry, "name"));
            if (name.isEmpty()) throw new IllegalArgumentException("Напиток " + id + ": у ступени " + tier + " нет name");
            TextColor color = colorOf(entry.get("color"), NamedTextColor.WHITE, id, tier);
            TextColor gradient = entry.get("gradient") == null
                    ? null
                    : colorOf(entry.get("gradient"), null, id, tier);
            boolean bold = flag(entry.get("bold"));
            boolean underline = flag(entry.get("underline"));
            Object description = entry.get("description");
            String text = description == null ? null : ProfileText.clean(String.valueOf(description));
            if (text != null && text.isEmpty()) text = null;
            levels.add(new Level(tier, name, color, gradient, bold, underline, text));
            auto++;
        }
        if (levels.isEmpty()) throw new IllegalArgumentException("Напиток " + id + ": список quality пуст");
        return List.copyOf(levels);
    }

    private static List<Years> readYears(ConfigurationSection section, List<Level> levels, String id) {
        List<Map<?, ?>> raw = section.getMapList("years");
        List<Years> years = new ArrayList<>(raw.size());
        for (Map<?, ?> entry : raw) {
            Object rawYears = entry.get("up-to-years");
            int upTo = isForever(rawYears) ? -1 : intOf(rawYears, -1, id, "up-to-years");
            int tier = intOf(entry.get("tier"), 0, id, "tier");
            if (tier <= 0) throw new IllegalArgumentException("Напиток " + id + ": у года нет tier");
            boolean known = false;
            for (Level level : levels) {
                if (level.tier() == tier) known = true;
            }
            if (!known) {
                throw new IllegalArgumentException("Напиток " + id + ": в years ступень " + tier
                        + ", а такой нет в quality");
            }
            Object description = entry.get("description");
            String text = description == null ? null : ProfileText.clean(String.valueOf(description));
            if (text != null && text.isEmpty()) text = null;
            years.add(new Years(upTo, tier, text));
        }
        if (years.isEmpty()) throw new IllegalArgumentException("Напиток " + id + ": список years пуст");
        return List.copyOf(years);
    }

    /** {@code '*'} и {@code -1} значат «и дальше, без верхней границы». */
    private static boolean isForever(Object raw) {
        if (raw == null) return false;
        String text = ProfileText.clean(String.valueOf(raw)).toLowerCase(Locale.ROOT);
        return text.equals("*") || text.equals("inf") || text.equals("infinity");
    }

    private static String textOf(Map<?, ?> entry, String key) {
        Object raw = entry.get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    private static int intOf(Object raw, int fallback, String id, String key) {
        if (raw == null) return fallback;
        if (raw instanceof Number number) return number.intValue();
        String text = ProfileText.clean(String.valueOf(raw));
        if (text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Напиток " + id + ": " + key + " — не число («" + text + "»)");
        }
    }

    private static boolean flag(Object raw) {
        if (raw instanceof Boolean value) return value;
        return raw != null && ProfileText.clean(String.valueOf(raw)).equalsIgnoreCase("true");
    }

    private static TextColor colorOf(Object raw, TextColor fallback, String id, int tier) {
        if (raw == null) return fallback;
        String text = ProfileText.clean(String.valueOf(raw));
        if (text.isEmpty()) return fallback;
        TextColor hex = TextColor.fromHexString(text.startsWith("#") ? text : "#" + text);
        if (hex != null) return hex;
        TextColor named = NamedTextColor.NAMES.value(text.toLowerCase(Locale.ROOT).replace(' ', '_'));
        if (named != null) return named;
        throw new IllegalArgumentException("Напиток " + id + ", ступень " + tier + ": неверный цвет «" + text
                + "». Пишите #RRGGBB или название цвета (red, yellow, green…)");
    }
}
