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

/** Неизменяемый список префиксов из prefixes.yml: id, название, цвет и файл иконки. */
final class PrefixCatalog {
    record Prefix(String id, String name, TextColor color, String file) { }

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
                    TextColor.color(0xE6B94B), "pref" + i));
        }
        return new PrefixCatalog(byId);
    }

    static PrefixCatalog load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        ConfigurationSection section = yaml.getConfigurationSection("prefixes");
        if (section == null) throw new IllegalArgumentException("В prefixes.yml нет раздела prefixes");
        Map<String, Prefix> byId = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            String cleanId = ProfileText.clean(id);
            if (cleanId.isEmpty()) continue;
            String name = ProfileText.clean(section.getString(id + ".name", ""));
            String file = ProfileText.clean(section.getString(id + ".file", cleanId));
            if (name.isEmpty() || file.isEmpty()) throw new IllegalArgumentException("Не заполнен префикс " + cleanId);
            TextColor color = TextColor.fromHexString(section.getString(id + ".color", "#E6B94B"));
            if (color == null) throw new IllegalArgumentException("Неверный цвет префикса " + cleanId);
            byId.put(cleanId, new Prefix(cleanId, name, color, file));
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список префиксов пуст");
        return new PrefixCatalog(byId);
    }

    List<Prefix> list() { return ordered; }
    Prefix get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }
}
