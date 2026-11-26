package com.yourserver.adaptation;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Чтение файлов паков — общая часть паков префиксов ({@code content/prefixpacks/})
 * и паков косметики ({@code content/cosmeticpacks/}).
 *
 * <p>Формат у обоих паков один и тот же, различается только название списка:
 * у пака префиксов {@code prefixes:}, у пака косметики {@code cosmetics:}.
 *
 * <pre>
 * name: 'Тестовый набор'      # название на сундучке
 * time-hours: 24              # срок кейса в часах с момента выдачи; -1 — бессрочный
 * cosmetics:                  # файлы моделей из settings/cosmetics.yml
 *   - kosmetika1              # имя и цвет возьмутся из settings/cosmetics.yml
 *   - file: kosmetika2        # тот же вид, но полем
 *   - file: kosmetika3        # имя и цвет можно переопределить на месте
 *     name: 'Своё имя'
 *     color: '#7FF0D2'
 * </pre>
 *
 * <p>Здесь читается только сам файл пака — имена и цвета в {@code Header} ещё
 * «сырые», их подставляет каталог ({@link PrefixCatalog} или {@link CosmeticCatalog}).
 */
final class PackFile {

    /** Срок, который означает «бессрочно». */
    static final int INFINITE = -1;

    /** Запись пака: файл предмета и необязательные переопределения. */
    record Entry(String file, String id, String name, String color) {
    }

    /** Разобранный файл пака: id из имени файла, название, срок и записи. */
    record Header(String id, String name, int timeHours, List<Entry> entries) {
    }

    private PackFile() {
    }

    /**
     * Прочитать все {@code *.yml} из папки по порядку имён.
     * Ошибка в файле — исключение с его именем: каталог не подменяется наполовину.
     *
     * @param entriesKey название списка записей ({@code prefixes} или {@code cosmetics})
     */
    static List<Header> readAll(Path folder, String entriesKey) {
        List<Header> headers = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) return headers;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .filter(PackFile::isYaml)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        } catch (Exception ignored) {
            return headers;
        }
        for (Path file : files) {
            try {
                headers.add(read(file, entriesKey));
            } catch (Exception ex) {
                throw new IllegalArgumentException("Пак " + file.getFileName() + ": " + ex.getMessage(), ex);
            }
        }
        return headers;
    }

    static Header read(Path file, String entriesKey) throws Exception {
        if (!isYaml(file)) throw new IllegalArgumentException("не .yml");
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.lastIndexOf('.'));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));

        String name = ProfileText.clean(yaml.getString("name", ""));
        if (name.isEmpty()) name = id;
        int timeHours = yaml.getInt("time-hours", yaml.getInt("time", INFINITE));

        List<Entry> entries = new ArrayList<>();
        for (Object raw : yaml.getList(entriesKey, List.of())) {
            if (raw instanceof Map<?, ?> map) {
                // '- file: pref9' вместе с необязательными name/color/id.
                entries.add(new Entry(ProfileText.clean(text(map.get("file"))),
                        ProfileText.clean(text(map.get("id"))),
                        ProfileText.clean(text(map.get("name"))),
                        ProfileText.clean(text(map.get("color")))));
            } else {
                // Запись целиком — это и есть файл модели: '- pref1'.
                entries.add(new Entry(ProfileText.clean(text(raw)), "", "", ""));
            }
        }
        // Запись без файла ничего не значит — её просто нет.
        entries.removeIf(entry -> entry.file().isEmpty());
        return new Header(sanitize(id), name, timeHours, List.copyOf(entries));
    }

    static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
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
