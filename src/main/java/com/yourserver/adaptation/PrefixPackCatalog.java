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
import java.util.function.Consumer;
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
 * Пак перечисляет только файлы префиксов, а имена и цвета берёт из
 * {@code prefixes.yml} — по полю {@code file}:
 *
 * <pre>
 * name: 'Тестовый пак'        # название на сундучке
 * time-hours: 24              # срок кейса в часах с момента выдачи; -1 — бессрочный
 * prefixes:                   # файлы префиксов из prefixes.yml
 *   - pref1                   # имя и цвет возьмутся из prefixes.yml
 *   - file: pref9             # тот же вид, но полем
 *   - file: pref3             # имя и цвет можно переопределить на месте
 *     name: 'Своё имя'
 *     color: '#7FF0D2'
 * </pre>
 *
 * <h2>Откуда берутся id</h2>
 *
 * Если файл найден в {@code prefixes.yml}, префикс пака — это тот же префикс:
 * тот же id, имя, цвет и номер. Так один и тот же префикс не появляется дважды
 * (своим и из пака), а выданный префикс не теряется. Если файла в
 * {@code prefixes.yml} нет, пак заводит свой префикс: id собирается из имени
 * пака и файла ({@code pref-pack1_pref1}) или берётся из поля {@code id}, если
 * оно написано руками, а имя — из поля {@code name} или из самого файла; в
 * журнал при этом уходит предупреждение.
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
        return load(folder, null, null);
    }

    /**
     * Прочитать все {@code *.yml} из папки. Имена и цвета префиксов паков
     * берутся из {@code base} ({@code prefixes.yml}) по файлу иконки; всё, чего
     * в нём нет, сообщается в {@code warn} (обычно — журнал плагина).
     */
    static PrefixPackCatalog load(Path folder, PrefixCatalog base, Consumer<String> warn) {
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
                Pack pack = read(file, base, warn);
                if (pack != null) byId.put(pack.id(), pack);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Пак " + file.getFileName() + ": " + ex.getMessage(), ex);
            }
        }
        return new PrefixPackCatalog(byId);
    }

    private static Pack read(Path file, PrefixCatalog base, Consumer<String> warn) throws Exception {
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".yml".length());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));

        String name = ProfileText.clean(yaml.getString("name", ""));
        if (name.isEmpty()) name = id;
        int timeHours = yaml.getInt("time-hours", yaml.getInt("time", INFINITE));

        List<PrefixCatalog.Prefix> prefixes = new ArrayList<>();
        for (Object raw : yaml.getList("prefixes", List.of())) {
            String entryFile;
            String entryName = "";
            String entryId = "";
            String entryColor = "";
            if (raw instanceof Map<?, ?> map) {
                entryFile = ProfileText.clean(text(map.get("file")));
                entryName = ProfileText.clean(text(map.get("name")));
                entryId = ProfileText.clean(text(map.get("id")));
                entryColor = ProfileText.clean(text(map.get("color")));
            } else {
                // Запись целиком — это и есть файл префикса: '- pref1'.
                entryFile = ProfileText.clean(text(raw));
            }
            if (entryFile.isEmpty()) continue;

            PrefixCatalog.Prefix known = base == null ? null : base.byFile(entryFile);
            String finalId;
            String finalName;
            String finalColor;
            if (known != null) {
                // Файл есть в prefixes.yml: префикс пака — тот же префикс.
                finalId = entryId.isEmpty() ? known.id() : entryId;
                finalName = entryName.isEmpty() ? known.name() : entryName;
                finalColor = entryColor.isEmpty() ? known.color().asHexString() : entryColor;
            } else {
                if (warn != null) {
                    warn.accept("Пак " + id + ": файл префикса «" + entryFile
                            + "» не найден в prefixes.yml — имя взято из файла, добавьте префикс в prefixes.yml");
                }
                finalId = entryId.isEmpty() ? sanitize(id + "_" + entryFile) : entryId;
                finalName = entryName.isEmpty() ? entryFile : entryName;
                finalColor = entryColor.isEmpty() ? "#E6B94B" : entryColor;
            }
            TextColor color = TextColor.fromHexString(finalColor);
            if (color == null) throw new IllegalArgumentException("Неверный цвет префикса " + finalId);
            prefixes.add(new PrefixCatalog.Prefix(sanitize(finalId), finalName, color, entryFile, 0));
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
        String result = out.toString();
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
