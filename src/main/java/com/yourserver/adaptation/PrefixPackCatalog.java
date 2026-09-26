package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Паки префиксов: по файлу на пак в {@code plugins/f8-plugin/prefixpacks/}.
 *
 * <h2>Что такое пак</h2>
 *
 * Пак — это кейс со своим набором префиксов. В меню префиксов пак виден
 * сундучком: на нём название пака и сколько таких кейсов лежит у игрока.
 * Вскрытие достаёт один префикс из набора пака — тот, которого у игрока ещё нет.
 *
 * <h2>Файл пака</h2>
 *
 * <pre>
 * name: 'Тестовый пак'        # название на сундучке
 * time-hours: 24              # срок кейса в часах с момента выдачи; -1 — бессрочный
 * prefixes:                   # свои префиксы пака
 *   - name: 'Морозный'        # название префикса
 *     file: pref1             # иконка из ресурспака: items/pref1.json + textures/item/pref1.png
 *     color: '#8FE3F5'        # необязательно: цвет названия
 * </pre>
 *
 * <h2>Откуда берутся id</h2>
 *
 * Префиксы пака — свои, а не ссылки на {@code prefixes.yml}: id собирается из
 * имени пака и иконки ({@code pref-pack1_pref1}) или берётся из поля {@code id},
 * если оно написано руками. Так один и тот же id не прыгает между перезапусками
 * и выданные префиксы не теряются.
 *
 * <h2>Срок кейса</h2>
 *
 * {@code time-hours} — это срок жизни кейса у игрока, а не срок выбитого
 * префикса: кейс сгорел — и не открыть. Префикс, который из него выпал,
 * остаётся у игрока навсегда (снять его можно командой администратора).
 */
final class PrefixPackCatalog {

    /** Срок, который означает «бессрочно». */
    static final int INFINITE = -1;

    private final Map<String, Pack> byId;
    private final List<Pack> ordered;

    private PrefixPackCatalog(Map<String, Pack> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    /**
     * Пак: название, срок кейса и его префиксы. Префиксы уже с id и цветом,
     * номер для команды выдачи им раздаёт {@link PrefixCatalog}.
     */
    record Pack(String id, String name, int timeHours, List<PrefixCatalog.Prefix> prefixes) {

        int size() {
            return prefixes.size();
        }

        /** Бессрочный ли кейс ({@code time-hours: -1}). */
        boolean infinite() {
            return timeHours < 0;
        }
    }

    List<Pack> packs() {
        return ordered;
    }

    Pack get(String id) {
        return id == null ? null : byId.get(id);
    }

    int size() {
        return ordered.size();
    }

    /** Пак по записи: точный id или название. */
    Pack resolve(String text) {
        if (text == null) return null;
        String key = ProfileText.clean(text).toLowerCase(Locale.ROOT);
        Pack direct = byId.get(key);
        if (direct != null) return direct;
        for (Pack pack : ordered) {
            if (pack.name().equalsIgnoreCase(ProfileText.clean(text))) return pack;
        }
        return null;
    }

    // ===== ЗАГРУЗКА =====

    /** Прочитать все {@code *.yml} из папки; битые файлы пропускаются. */
    static PrefixPackCatalog load(Path folder) {
        Map<String, Pack> byId = new LinkedHashMap<>();
        if (folder == null || !Files.isDirectory(folder)) return new PrefixPackCatalog(byId);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        } catch (Exception ignored) {
            return new PrefixPackCatalog(byId);
        }
        for (Path file : files) {
            try {
                Pack pack = read(file);
                if (pack != null) byId.put(pack.id(), pack);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Пак " + file.getFileName() + ": " + ex.getMessage(), ex);
            }
        }
        return new PrefixPackCatalog(byId);
    }

    private static Pack read(Path file) throws Exception {
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".yml".length());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));

        String name = ProfileText.clean(yaml.getString("name", ""));
        if (name.isEmpty()) name = id;
        int timeHours = yaml.getInt("time-hours", yaml.getInt("time", INFINITE));

        List<PrefixCatalog.Prefix> prefixes = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("prefixes")) {
            String entryName = ProfileText.clean(text(raw.get("name")));
            String entryFile = ProfileText.clean(text(raw.get("file")));
            if (entryName.isEmpty() || entryFile.isEmpty()) continue;
            String entryId = ProfileText.clean(text(raw.get("id")));
            if (entryId.isEmpty()) entryId = sanitize(id + "_" + entryFile);
            TextColor color = TextColor.fromHexString(text(raw.get("color")).isEmpty()
                    ? "#E6B94B" : text(raw.get("color")));
            prefixes.add(new PrefixCatalog.Prefix(sanitize(entryId), entryName,
                    color == null ? TextColor.color(0xE6B94B) : color, entryFile, 0));
        }
        if (prefixes.isEmpty()) return null;
        return new Pack(sanitize(id), name, timeHours, List.copyOf(prefixes));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** id в безопасном виде: строчные буквы, цифры, точка, дефис и подчёркивание. */
    static String sanitize(String value) {
        if (value == null) return "";
        String cleaned = ProfileText.clean(value).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(cleaned.length());
        for (char c : cleaned.toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(allowed ? c : '_');
        }
        String result = out.toString().replace("_", "_");
        while (result.contains("__")) result = result.replace("__", "_");
        return result;
    }

    // ===== СРОК КЕЙСА =====

    /**
     * Когда кейс сгорит: {@code now + часы}. Для бессрочного ({@code -1}) —
     * {@link Long#MAX_VALUE}, то есть никогда.
     */
    static long expiresAt(long now, int timeHours) {
        if (timeHours < 0) return Long.MAX_VALUE;
        return now + timeHours * 3_600_000L;
    }

    /** Сколько осталось жить кейсу: коротко, для подписи сундучка. */
    static String leftText(long expiresAt, long now) {
        if (expiresAt == Long.MAX_VALUE) return "бессрочный";
        long millis = expiresAt - now;
        if (millis <= 0) return "сгорел";
        long minutes = millis / 60_000L;
        if (minutes < 1) return "меньше минуты";
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60L;
        if (hours < 24) return hours + " ч";
        long days = hours / 24L;
        return days + " д";
    }
}
