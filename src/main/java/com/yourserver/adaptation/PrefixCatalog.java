package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Неизменяемый список префиксов из settings/prefixes.yml. Порядок записей в конфиге задаёт
 * порядок в меню, а «номер» префикса берётся из имени картинки: файл {@code pref1} — №1,
 * {@code pref12} — №12. Поэтому номер не сдвигается, если запись переставили или добавили
 * новую выше, и всегда совпадает с картинкой, которую видит игрок.
 *
 * <p>Если в имени файла цифр нет ({@code file: snowflake}), номер берётся по порядку —
 * первый свободный. Иконка перед ником — файл префикса из ресурспака
 * ({@code file: pref1} → глиф {@code pref1.png}).
 *
 * <p>Анимация: у префикса может быть список картинок, которые сменяют друг друга с
 * заданным интервалом в сотых секунды. Смотри {@link Prefix#frames()} и
 * {@link Prefix#intervalHundredths()}.
 */
final class PrefixCatalog {

    /** Глиф в шрифте есть только у картинок pref1 … pref99. */
    private static final Pattern ICON_NUMBER = Pattern.compile("(?i)^pref(\\d{1,3})$");
    /** Номер берётся из цифр в конце имени картинки: pref12 → 12, kosmetika3 → 3. */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d{1,3})$");
    /** Больше кадров в анимации не нужно: это уже не префикс, а бегущая строка. */
    private static final int MAX_FRAMES = 16;
    /** По умолчанию кадр держится 10 сотых секунды — 0,1 с. */
    static final int DEFAULT_INTERVAL_HUNDREDTHS = 10;
    /** Границы интервала: от 1 сотой (0,01 с) до 60 секунд. */
    static final int MIN_INTERVAL_HUNDREDTHS = 1;
    static final int MAX_INTERVAL_HUNDREDTHS = 6000;

    /**
     * Префикс.
     *
     * @param number             номер для выдачи командой; 0 — «ещё не выдан», такой
     *                           префикс получает номер при склейке с каталогом
     * @param frames             картинки анимации по кругу; пусто — статичная иконка {@code file}
     * @param intervalHundredths сколько держится один кадр, в сотых секунды (10 = 0,1 с)
     */
    record Prefix(String id, String name, TextColor color, String file, int number,
                  List<String> frames, int intervalHundredths) {

        Prefix {
            frames = frames == null ? List.of() : List.copyOf(frames);
        }

        /** Статичный префикс: одна картинка, без анимации. */
        Prefix(String id, String name, TextColor color, String file, int number) {
            this(id, name, color, file, number, List.of(), DEFAULT_INTERVAL_HUNDREDTHS);
        }

        /** Сменяются ли картинки. */
        boolean animated() {
            return frames.size() > 1;
        }

        /** Картинки по кругу: анимация, а без неё — единственный {@code file}. */
        List<String> iconFrames() {
            return frames.isEmpty() ? List.of(file) : frames;
        }

        /** Сколько держится кадр, в миллисекундах (минимум один тик сервера). */
        long frameMillis() {
            int hundredths = Math.clamp(intervalHundredths, MIN_INTERVAL_HUNDREDTHS, MAX_INTERVAL_HUNDREDTHS);

            return Math.max(50L, hundredths * 10L);
        }
    }

    private final Map<String, Prefix> byId;
    private final List<Prefix> ordered;

    private PrefixCatalog(Map<String, Prefix> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    static PrefixCatalog defaults() {
        Map<String, Prefix> byId = new LinkedHashMap<>();
        for (int i = 1; i <= 10; i++) {
            byId.put("pref" + i, new Prefix("pref" + i, "Префикс pref" + i,
                    TextColor.color(0xE6B94B), "pref" + i, i));
        }
        return new PrefixCatalog(byId);
    }

    /**
     * Префиксы из {@code settings/prefixes.yml} плюс префиксы паков
     * ({@code plugins/f8-plugin/content/prefixpacks/*.yml}).
     *
     * Паки добавляются вторыми, поэтому их номера идут после обычных: выдача
     * «по номеру» из {@code settings/prefixes.yml} не съезжает. Если id из пака уже есть
     * в {@code settings/prefixes.yml}, запись пака пропускается — файл настроек главнее.
     */
    static PrefixCatalog load(Path path, PrefixPackCatalog packs) throws Exception {
        return load(path).withPacks(packs);
    }

    /**
     * Прочитать {@code settings/prefixes.yml}: только свои префиксы, без паков. По этому
     * каталогу паки находят имена и цвета: пак перечисляет файлы префиксов, а
     * всё остальное берётся отсюда.
     */
    static PrefixCatalog load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        ConfigurationSection section = yaml.getConfigurationSection("prefixes");
        if (section == null) throw new IllegalArgumentException("В settings/prefixes.yml нет раздела prefixes");
        Map<String, Prefix> byId = new LinkedHashMap<>();
        Set<Integer> used = new LinkedHashSet<>();
        int fallback = 1;
        for (String id : section.getKeys(false)) {
            String cleanId = ProfileText.clean(id);
            if (cleanId.isEmpty()) continue;
            String name = ProfileText.clean(section.getString(id + ".name", ""));
            String file = ProfileText.clean(section.getString(id + ".file", cleanId));
            if (name.isEmpty() || file.isEmpty()) throw new IllegalArgumentException("Не заполнен префикс " + cleanId);
            TextColor color = TextColor.fromHexString(section.getString(id + ".color", "#E6B94B"));
            if (color == null) throw new IllegalArgumentException("Неверный цвет префикса " + cleanId);
            List<String> frames = readFrames(section.get(id + ".animation"));
            int interval = readInterval(section.get(id + ".animation"));
            int number = allocateNumber(file, used, fallback++);
            byId.put(cleanId, new Prefix(cleanId, name, color, file, number, frames, interval));
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список префиксов пуст");
        return new PrefixCatalog(byId);
    }

    /**
     * Номер из имени картинки: {@code pref1} → 1, {@code pref12} → 12,
     * {@code kosmetika3} → 3. Для картинок без цифр ({@code snowflake}) — 0,
     * то есть «номер ещё не назначен».
     */
    static int numberFromFile(String file) {
        return trailingNumber(file, TRAILING_NUMBER);
    }

    /**
     * Номер глифа префикса: только {@code prefN} — у этих картинок в
     * font/profile_ui.json есть символ. У остальных имён глифа нет, вернётся 0.
     */
    static int glyphNumber(String file) {
        return trailingNumber(file, ICON_NUMBER);
    }

    private static int trailingNumber(String file, Pattern pattern) {
        if (file == null) return 0;
        Matcher matcher = pattern.matcher(file.trim());
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Номер из картинки, а если он занят или цифр нет — первый свободный. */
    static int allocateNumber(String file, Set<Integer> used, int fallbackStart) {
        int fromFile = numberFromFile(file);

        if (fromFile > 0 && used.add(fromFile)) {
            return fromFile;
        }

        int number = Math.max(1, fallbackStart);

        while (!used.add(number)) {
            number++;
        }

        return number;
    }

    /**
     * Картинки анимации из раздела {@code animation}.
     *
     * <p>Две записи равнозначны:
     * <pre>
     * animation:
     *   frames: [pref1, pref2, pref3]
     *   interval-hundredths: 10
     * </pre>
     * и короткая строка {@code animation: [pref1, pref2, pref3]} — интервал тогда
     * стандартный (10 сотых = 0,1 с).
     */
    static List<String> readFrames(Object animation) {
        List<?> raw;

        if (animation instanceof ConfigurationSection section) {
            raw = section.getList("frames", List.of());
        } else if (animation instanceof List<?> list) {
            raw = list;
        } else if (animation instanceof String text) {
            raw = List.of(text.split("[,\\s]+"));
        } else {
            return List.of();
        }

        List<String> frames = new ArrayList<>();

        for (Object entry : raw) {
            if (frames.size() >= MAX_FRAMES) break;
            String frame = ProfileText.clean(entry == null ? "" : String.valueOf(entry));

            if (frame.isEmpty()) continue;
            // Два одинаковых кадра подряд — это пауза, а не кадр: картинка та же.
            if (!frames.isEmpty() && frames.get(frames.size() - 1).equalsIgnoreCase(frame)) continue;
            frames.add(frame);
        }

        // Один кадр — это не анимация: показываем обычную статичную иконку.
        if (frames.size() < 2) return List.of();

        // Зацикленная анимация не должна повторять первый кадр дважды подряд в конце.
        if (frames.size() > 2 && frames.get(0).equalsIgnoreCase(frames.get(frames.size() - 1))) {
            frames.remove(frames.size() - 1);
        }

        return List.copyOf(frames);
    }

    /** Интервал кадра из {@code animation.interval-hundredths}; вне границ — приведён к ним. */
    static int readInterval(Object animation) {
        int raw = DEFAULT_INTERVAL_HUNDREDTHS;

        if (animation instanceof ConfigurationSection section) {
            raw = section.getInt("interval-hundredths", section.getInt("interval", DEFAULT_INTERVAL_HUNDREDTHS));
        }

        return Math.clamp(raw, MIN_INTERVAL_HUNDREDTHS, MAX_INTERVAL_HUNDREDTHS);
    }

    /** Интервал кадра по-человечески: 8 → «0,08 с», 10 → «0,1 с», 250 → «2,5 с». */
    static String intervalText(int hundredths) {
        int clamped = Math.clamp(hundredths, MIN_INTERVAL_HUNDREDTHS, MAX_INTERVAL_HUNDREDTHS);

        if (clamped % 100 == 0) return (clamped / 100) + " с";

        String fraction = Integer.toString(clamped % 100);

        if (fraction.length() == 1) fraction = "0" + fraction;
        fraction = fraction.replaceAll("0+$", "");

        return (clamped / 100) + "," + fraction + " с";
    }

    /**
     * Тот же каталог, но с префиксами паков. Префикс пака, чей id уже есть
     * (обычно так и бывает: пак ссылается на префикс из {@code settings/prefixes.yml} по
     * его файлу), не добавляется второй раз — имя, цвет, анимация и номер остаются
     * от файла настроек.
     */
    PrefixCatalog withPacks(PrefixPackCatalog packs) {
        if (packs == null || packs.size() == 0) return this;
        Map<String, Prefix> merged = new LinkedHashMap<>(byId);
        Set<Integer> used = new LinkedHashSet<>();

        for (Prefix prefix : ordered) {
            used.add(prefix.number());
        }

        int fallback = 1;
        for (PrefixPackCatalog.Pack pack : packs.packs()) {
            for (Prefix entry : pack.prefixes()) {
                if (merged.containsKey(entry.id())) continue;
                int number = allocateNumber(entry.file(), used, fallback);
                fallback = Math.max(fallback, number + 1);
                merged.put(entry.id(), new Prefix(entry.id(), entry.name(), entry.color(), entry.file(),
                        number, entry.frames(), entry.intervalHundredths()));
            }
        }
        return new PrefixCatalog(merged);
    }

    List<Prefix> list() { return ordered; }
    Prefix get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

    /** Сколько префиксов меняют картинку — для строки запуска и /profile prefix reload. */
    int animatedCount() {
        return (int) ordered.stream().filter(Prefix::animated).count();
    }

    /** Диапазон номеров для сообщений: «1–12» или «12»; пустой каталог — «0». */
    String numbersText() {
        if (ordered.isEmpty()) return "0";
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (Prefix prefix : ordered) {
            min = Math.min(min, prefix.number());
            max = Math.max(max, prefix.number());
        }
        return min == max ? Integer.toString(min) : min + "–" + max;
    }

    /** Префикс по файлу иконки (file: pref1) или null. По этому файлу паки префиксов находят имя и цвет. */
    Prefix byFile(String file) {
        if (file == null || file.isEmpty()) return null;
        for (Prefix prefix : ordered) {
            if (prefix.file().equalsIgnoreCase(file)) return prefix;
        }
        return null;
    }

    /** Префикс по номеру из картинки (pref1 → №1) или null. */
    Prefix byNumber(int number) {
        for (Prefix prefix : ordered) {
            if (prefix.number() == number) return prefix;
        }
        return null;
    }

    /** Какой-либо префикс по записи «номер», «prefN» или точному id/имени. */
    Prefix resolve(String text) {
        if (text == null) return null;
        try {
            int number = Integer.parseInt(text.trim());
            Prefix byNumber = byNumber(number);
            if (byNumber != null) return byNumber;
        } catch (NumberFormatException ignored) { }
        String key = ProfileText.clean(text).toLowerCase(Locale.ROOT);
        Prefix direct = byId.get(key);
        if (direct != null) return direct;
        for (Prefix prefix : ordered) {
            if (prefix.name().equalsIgnoreCase(ProfileText.clean(text))) return prefix;
        }
        return null;
    }
}
