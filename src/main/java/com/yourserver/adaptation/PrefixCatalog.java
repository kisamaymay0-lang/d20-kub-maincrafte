package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Неизменяемый список префиксов из prefixes.yml. Порядок записей в конфиге задаёт
 * «номер» префикса (1, 2, 3, …): по нему выдаются и забираются префиксы командой.
 * Иконка перед ником — файл префикса из ресурспака (file: pref1 → глиф pref1.png и т.д.).
 */
final class PrefixCatalog {
    record Prefix(String id, String name, TextColor color, String file, int number) { }

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
     * Префиксы из {@code prefixes.yml} плюс префиксы паков
     * ({@code plugins/f8-plugin/prefixpacks/*.yml}).
     *
     * Паки добавляются вторыми, поэтому их номера идут после обычных: выдача
     * «по номеру» из {@code prefixes.yml} не съезжает. Если id из пака уже есть
     * в {@code prefixes.yml}, запись пака пропускается — файл настроек главнее.
     */
    static PrefixCatalog load(Path path, PrefixPackCatalog packs) throws Exception {
        return load(path).withPacks(packs);
    }

    /**
     * Прочитать {@code prefixes.yml}: только свои префиксы, без паков. По этому
     * каталогу паки находят имена и цвета: пак перечисляет файлы префиксов, а
     * всё остальное берётся отсюда.
     */
    static PrefixCatalog load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        ConfigurationSection section = yaml.getConfigurationSection("prefixes");
        if (section == null) throw new IllegalArgumentException("В prefixes.yml нет раздела prefixes");
        Map<String, Prefix> byId = new LinkedHashMap<>();
        int number = 1;
        for (String id : section.getKeys(false)) {
            String cleanId = ProfileText.clean(id);
            if (cleanId.isEmpty()) continue;
            String name = ProfileText.clean(section.getString(id + ".name", ""));
            String file = ProfileText.clean(section.getString(id + ".file", cleanId));
            if (name.isEmpty() || file.isEmpty()) throw new IllegalArgumentException("Не заполнен префикс " + cleanId);
            TextColor color = TextColor.fromHexString(section.getString(id + ".color", "#E6B94B"));
            if (color == null) throw new IllegalArgumentException("Неверный цвет префикса " + cleanId);
            byId.put(cleanId, new Prefix(cleanId, name, color, file, number++));
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список префиксов пуст");
        return new PrefixCatalog(byId);
    }

    /**
     * Тот же каталог, но с префиксами паков. Префикс пака, чей id уже есть
     * (обычно так и бывает: пак ссылается на префикс из {@code prefixes.yml} по
     * его файлу), не добавляется второй раз — имя, цвет и номер остаются от
     * файла настроек.
     */
    PrefixCatalog withPacks(PrefixPackCatalog packs) {
        if (packs == null || packs.size() == 0) return this;
        Map<String, Prefix> merged = new LinkedHashMap<>(byId);
        int number = ordered.size() + 1;
        for (PrefixPackCatalog.Pack pack : packs.packs()) {
            for (Prefix entry : pack.prefixes()) {
                if (merged.containsKey(entry.id())) continue;
                merged.put(entry.id(), new Prefix(entry.id(), entry.name(), entry.color(), entry.file(), number++));
            }
        }
        return new PrefixCatalog(merged);
    }

    List<Prefix> list() { return ordered; }
    Prefix get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

    /** Префикс по файлу иконки (file: pref1) или null. По этому файлу паки префиксов находят имя и цвет. */
    Prefix byFile(String file) {
        if (file == null || file.isEmpty()) return null;
        for (Prefix prefix : ordered) {
            if (prefix.file().equalsIgnoreCase(file)) return prefix;
        }
        return null;
    }

    /** Префикс по номеру из конфига (1, 2, 3, …) или null. */
    Prefix byNumber(int number) {
        if (number < 1 || number > ordered.size()) return null;
        return ordered.get(number - 1);
    }

    /** Какой-либо префикс по записи «номер», «prefN» или точному id/имени. */
    Prefix resolve(String text) {
        if (text == null) return null;
        try {
            int number = Integer.parseInt(text.trim());
            Prefix byNumber = byNumber(number);
            if (byNumber != null) return byNumber;
        } catch (NumberFormatException ignored) { }
        String key = ProfileText.clean(text).toLowerCase(java.util.Locale.ROOT);
        Prefix direct = byId.get(key);
        if (direct != null) return direct;
        for (Prefix prefix : ordered) {
            if (prefix.name().equalsIgnoreCase(ProfileText.clean(text))) return prefix;
        }
        return null;
    }
}
