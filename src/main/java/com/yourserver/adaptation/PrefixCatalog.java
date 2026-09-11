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

    List<Prefix> list() { return ordered; }
    Prefix get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

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
