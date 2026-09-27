package com.yourserver.adaptation;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Паки префиксов: по файлу на пак в {@code plugins/f8-plugin/prefixpacks/}.
 *
 * <p>В паке записываются только {@code file} и имя самого пака. Имя, цвет,
 * номер и id префикса берутся из {@code prefixes.yml} по полю {@code file}.
 * Это <b>те же</b> префиксы, не их копии; выигрыш из пака остаётся в общей
 * коллекции и не выпадет повторно из другого пака или обычного кейса.
 *
 * <pre>
 * name: 'Тестовый пак'
 * time-hours: 24           # срок кейса от выдачи; -1 — бессрочный
 * prefixes:
 *   - pref1                # файл из prefixes.yml
 *   - pref9
 * </pre>
 *
 * <p>Старый формат списка ({@code - name: ...; file: pref1}) принимается для
 * совместимости: имя и цвет игнорируются, используется только {@code file}.
 * Ненайденный файл — ошибка: не выдаём пустые кейсы при опечатке.
 */
final class PrefixPackCatalog {

    static final int INFINITE = -1;

    private final Map<String, Pack> byId;
    private final List<Pack> ordered;

    private PrefixPackCatalog(Map<String, Pack> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    record Pack(String id, String name, int timeHours, List<PrefixCatalog.Prefix> prefixes) {
        int size() { return prefixes.size(); }
        boolean infinite() { return timeHours < 0; }
    }

    static PrefixPackCatalog empty() { return new PrefixPackCatalog(Map.of()); }

    List<Pack> packs() { return ordered; }
    Pack get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

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

    /** Прочитать все {@code *.yml} из папки, связав file с prefixes.yml. */
    static PrefixPackCatalog load(Path folder, PrefixCatalog prefixes) throws Exception {
        Map<String, Pack> byId = new LinkedHashMap<>();
        if (folder == null || !Files.isDirectory(folder)) return new PrefixPackCatalog(byId);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        }
        for (Path file : files) {
            try {
                Pack pack = read(file, prefixes);
                if (pack != null) byId.put(pack.id(), pack);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Пак " + file.getFileName() + ": " + ex.getMessage(), ex);
            }
        }
        return new PrefixPackCatalog(byId);
    }

    private static Pack read(Path file, PrefixCatalog catalog) throws Exception {
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".yml".length());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));

        String name = ProfileText.clean(yaml.getString("name", ""));
        if (name.isEmpty()) name = id;
        int timeHours = yaml.getInt("time-hours", yaml.getInt("time", INFINITE));
        List<?> entries = yaml.getList("prefixes");
        if (entries == null) throw new IllegalArgumentException("Нет списка prefixes");

        List<PrefixCatalog.Prefix> resolved = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object entry : entries) {
            String fileId = entry instanceof Map<?, ?> map ? text(map.get("file")) : text(entry);
            fileId = ProfileText.clean(fileId);
            if (fileId.isEmpty()) throw new IllegalArgumentException("Пустой file в prefixes");
            PrefixCatalog.Prefix prefix = catalog.byFile(fileId);
            if (prefix == null) throw new IllegalArgumentException("Файл «" + fileId
                    + "» не найден в prefixes.yml (добавьте туда префикс с file: " + fileId + ")");
            if (seen.add(prefix.id())) resolved.add(prefix);
        }
        if (resolved.isEmpty()) return null;
        return new Pack(sanitize(id), name, timeHours, List.copyOf(resolved));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    static String sanitize(String value) {
        if (value == null) return "";
        String cleaned = ProfileText.clean(value).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(cleaned.length());
        for (char c : cleaned.toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(allowed ? c : '_');
        }
        String result = out.toString();
        while (result.contains("__")) result = result.replace("__", "_");
        return result;
    }

    static long expiresAt(long now, int timeHours) {
        if (timeHours < 0) return Long.MAX_VALUE;
        return now + timeHours * 3_600_000L;
    }

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
