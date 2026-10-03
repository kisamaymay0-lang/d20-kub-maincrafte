package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Неизменяемый список косметики из cosmetics.yml. Устройство ровно как у
 * {@link PrefixCatalog}: порядок записей в конфиге задаёт «номер» косметики
 * (1, 2, 3, …), по нему косметику выдают и забирают командой.
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
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        ConfigurationSection section = yaml.getConfigurationSection("cosmetics");
        if (section == null) throw new IllegalArgumentException("В cosmetics.yml нет раздела cosmetics");
        Map<String, Cosmetic> byId = new LinkedHashMap<>();
        int number = 1;
        for (String id : section.getKeys(false)) {
            String cleanId = ProfileText.clean(id);
            if (cleanId.isEmpty()) continue;
            String name = ProfileText.clean(section.getString(id + ".name", ""));
            String file = ProfileText.clean(section.getString(id + ".file", cleanId));
            if (name.isEmpty() || file.isEmpty()) throw new IllegalArgumentException("Не заполнена косметика " + cleanId);
            TextColor color = TextColor.fromHexString(section.getString(id + ".color", "#E6B94B"));
            if (color == null) throw new IllegalArgumentException("Неверный цвет косметики " + cleanId);
            byId.put(cleanId, new Cosmetic(cleanId, name, color, file, number++));
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Список косметики пуст");
        return new CosmeticCatalog(byId);
    }

    List<Cosmetic> list() { return ordered; }
    Cosmetic get(String id) { return id == null ? null : byId.get(id); }
    int size() { return ordered.size(); }

    /** Косметика по номеру из конфига (1, 2, 3, …) или null. */
    Cosmetic byNumber(int number) {
        if (number < 1 || number > ordered.size()) return null;
        return ordered.get(number - 1);
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
