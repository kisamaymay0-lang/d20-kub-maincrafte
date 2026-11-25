package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Неизменяемый список косметики из settings/cosmetics.yml и дополнительных
 * {@code content/cosmetics/*.yml}. Устройство ровно как у {@link PrefixCatalog}:
 * «номер» косметики берётся из имени модели (kosmetika1 → №1), по нему косметику
 * выдают и забирают командой. Модель без цифр получает первый свободный номер.
 *
 * Отличие от префикса только в том, КАК косметика показывается: префикс — это
 * картинка перед ником, а косметика — предмет, который визуально надет на
 * голову игрока (модель из ресурспака: {@code file: kosmetika1} → предмет
 * {@code f8resurs:kosmetika1}).
 */
final class CosmeticCatalog {
    record Cosmetic(String id, String name, TextColor color, String file, int number) { }

    /** Сколько косметик идёт в комплекте по умолчанию. */
    static final int DEFAULT_COUNT = 3;

    private final Map<String, Cosmetic> byId;
    private final List<Cosmetic> ordered;

    private CosmeticCatalog(Map<String, Cosmetic> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    static CosmeticCatalog defaults() {
        Map<String, Cosmetic> byId = new LinkedHashMap<>();
        // Цвет нужен для названия в меню и для салюта при вскрытии кейса.
        TextColor[] colors = { TextColor.color(0xE6B94B), TextColor.color(0x8FE3F5), TextColor.color(0xC79BF0) };
        for (int i = 1; i <= DEFAULT_COUNT; i++) {
            byId.put("kosmetika" + i, new Cosmetic("kosmetika" + i, "Косметика kosmetika" + i,
                    colors[(i - 1) % colors.length], "kosmetika" + i, i));
        }
        return new CosmeticCatalog(byId);
    }

    static CosmeticCatalog load(Path path) throws Exception {
        return loadFiles(List.of(path));
    }

    /**
     * Загрузить основной файл и все дополнительные {@code cosmetics/*.yml}.
     * Папка перечисляется заново при каждом вызове, поэтому новый файл не
     * требует перезапуска сервера. Основной файл всегда идёт первым — его
     * старые номера не сдвигаются при добавлении дополнительного файла.
     */
    static CosmeticCatalog load(Path primary, Path additionalFolder) throws Exception {
        List<Path> files = new ArrayList<>();
        files.add(primary);
        if (additionalFolder != null && Files.isDirectory(additionalFolder)) {
            try (Stream<Path> stream = Files.list(additionalFolder)) {
                stream.filter(Files::isRegularFile)
                        .filter(CosmeticCatalog::isYaml)
                        .sorted(Comparator.comparing((Path path) -> path.getFileName().toString(),
                                String.CASE_INSENSITIVE_ORDER).thenComparing(path -> path.getFileName().toString()))
                        .forEach(files::add);
            }
        }
        return loadFiles(files);
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static CosmeticCatalog loadFiles(List<Path> paths) throws Exception {
        Map<String, Cosmetic> byId = new LinkedHashMap<>();
        Set<Integer> used = new LinkedHashSet<>();
        int fallback = 1;
        boolean primary = true;
        for (Path path : paths) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(path));
            ConfigurationSection section = yaml.getConfigurationSection("cosmetics");
            if (section == null) {
                // Основной файл без раздела cosmetics — ошибка. Дополнительный —
                // просто чужой/пустой файл в папке: пропускаем, остальные читаются.
                if (primary) throw new IllegalArgumentException("В " + path + " нет раздела cosmetics");
                primary = false;
                continue;
            }
            primary = false;
            for (String id : section.getKeys(false)) {
                String cleanId = ProfileText.clean(id);
                if (cleanId.isEmpty()) continue;
                String name = ProfileText.clean(section.getString(id + ".name", ""));
                String file = ProfileText.clean(section.getString(id + ".file", cleanId));
                if (name.isEmpty() || file.isEmpty()) {
                    throw new IllegalArgumentException("Не заполнена косметика " + cleanId + " в " + path);
                }
                TextColor color = TextColor.fromHexString(section.getString(id + ".color", "#E6B94B"));
                if (color == null) throw new IllegalArgumentException("Неверный цвет косметики " + cleanId + " в " + path);
                // Номер — из имени модели (kosmetika1 → №1), как у префиксов: перестановка
                // записей в файле не сдвигает номера выдачи. Нет цифр — первый свободный.
                int number = PrefixCatalog.allocateNumber(file, used, fallback);
                fallback = Math.max(fallback, number + 1);
                Cosmetic cosmetic = new Cosmetic(cleanId, name, color, file, number);
                if (byId.putIfAbsent(cleanId, cosmetic) != null) {
                    throw new IllegalArgumentException("Повторяется id косметики " + cleanId + " в " + path);
                }
            }
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список косметики пуст");
        return new CosmeticCatalog(byId);
    }

    List<Cosmetic> list() { return ordered; }
    Cosmetic get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

    /** Косметика по номеру из имени модели (kosmetika1 → №1) или null. */
    Cosmetic byNumber(int number) {
        for (Cosmetic cosmetic : ordered) {
            if (cosmetic.number() == number) return cosmetic;
        }
        return null;
    }

    /** Диапазон номеров для сообщений: «1–3» или «1»; пустой каталог — «0». */
    String numbersText() {
        return numbersText(ordered);
    }

    static String numbersText(List<Cosmetic> cosmetics) {
        if (cosmetics == null || cosmetics.isEmpty()) return "0";
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (Cosmetic cosmetic : cosmetics) {
            min = Math.min(min, cosmetic.number());
            max = Math.max(max, cosmetic.number());
        }
        return min == max ? Integer.toString(min) : min + "–" + max;
    }

    /** Какая-либо косметика по записи «номер», «kosmetikaN» или точному id/имени. */
    Cosmetic resolve(String text) {
        if (text == null) return null;
        try {
            Cosmetic byNumber = byNumber(Integer.parseInt(text.trim()));
            if (byNumber != null) return byNumber;
        } catch (NumberFormatException ignored) { }
        String key = ProfileText.clean(text).toLowerCase(Locale.ROOT);
        Cosmetic direct = byId.get(key);
        if (direct != null) return direct;
        for (Cosmetic cosmetic : ordered) {
            if (cosmetic.name().equalsIgnoreCase(ProfileText.clean(text))) return cosmetic;
        }
        return null;
    }
}
