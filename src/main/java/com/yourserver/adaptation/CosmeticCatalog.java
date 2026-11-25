package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Неизменяемый список косметики из {@code settings/cosmetics.yml}. Устройство
 * ровно как у {@link PrefixCatalog}: «номер» косметики берётся из имени модели
 * (kosmetika1 → №1), по нему косметику выдают и забирают командой. Модель без
 * цифр получает первый свободный номер.
 *
 * <p>Дополнительных файлов у косметики нет: всё описано в
 * {@code settings/cosmetics.yml}. Наборы (кейсы) описываются отдельно — файлами
 * в {@code content/cosmeticpacks/}, см. {@link CosmeticPackCatalog}: пак только
 * перечисляет файлы моделей, а имя и цвет берёт отсюда.
 *
 * <p>Отличие от префикса только в том, КАК косметика показывается: префикс — это
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

    /**
     * Прочитать {@code settings/cosmetics.yml}: только свои записи, без паков.
     * По этому каталогу паки косметики находят имена и цвета: пак перечисляет
     * файлы моделей, а всё остальное берётся отсюда.
     */
    static CosmeticCatalog load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        ConfigurationSection section = yaml.getConfigurationSection("cosmetics");
        if (section == null) throw new IllegalArgumentException("В settings/cosmetics.yml нет раздела cosmetics");
        Map<String, Cosmetic> byId = new LinkedHashMap<>();
        Set<Integer> used = new LinkedHashSet<>();
        int fallback = 1;
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
        if (byId.isEmpty()) throw new IllegalArgumentException("Список косметики пуст");
        return new CosmeticCatalog(byId);
    }

    /**
     * Тот же каталог, но с косметикой паков. Косметика пака, чей id уже есть
     * (обычно так и бывает: пак ссылается на модель из {@code settings/cosmetics.yml}
     * по её файлу), не добавляется второй раз — имя, цвет и номер остаются от
     * файла настроек.
     */
    CosmeticCatalog withPacks(CosmeticPackCatalog packs) {
        if (packs == null || packs.size() == 0) return this;
        Map<String, Cosmetic> merged = new LinkedHashMap<>(byId);
        Set<Integer> used = new LinkedHashSet<>();
        for (Cosmetic cosmetic : ordered) {
            used.add(cosmetic.number());
        }
        int fallback = 1;
        for (CosmeticPackCatalog.Pack pack : packs.packs()) {
            for (Cosmetic entry : pack.cosmetics()) {
                if (merged.containsKey(entry.id())) continue;
                int number = PrefixCatalog.allocateNumber(entry.file(), used, fallback);
                fallback = Math.max(fallback, number + 1);
                merged.put(entry.id(), new Cosmetic(entry.id(), entry.name(), entry.color(), entry.file(), number));
            }
        }
        return new CosmeticCatalog(merged);
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

    /** Косметика по файлу модели (file: kosmetika1) или null. По этому файлу паки косметики находят имя и цвет. */
    Cosmetic byFile(String file) {
        if (file == null || file.isEmpty()) return null;
        for (Cosmetic cosmetic : ordered) {
            if (cosmetic.file().equalsIgnoreCase(file)) return cosmetic;
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
