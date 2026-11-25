package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Паки косметики: по файлу на пак в {@code plugins/f8-plugin/content/cosmeticpacks/}.
 *
 * <p>Устроено ровно как {@link PrefixPackCatalog}, только вместо префиксов —
 * косметика: пак виден сундучком в меню «Косметика», на нём название и сколько
 * таких кейсов у игрока. Вскрытие достаёт одну косметику из набора пака — ту,
 * которой у игрока ещё нет.
 *
 * <h2>Файл пака</h2>
 *
 * <pre>
 * name: 'Летний набор'        # название на сундучке
 * time-hours: 24              # срок кейса в часах с момента выдачи; -1 — бессрочный
 * cosmetics:                  # файлы моделей из settings/cosmetics.yml
 *   - kosmetika1              # имя и цвет возьмутся из settings/cosmetics.yml
 *   - file: kosmetika2        # тот же вид, но полем
 *   - file: kosmetika3        # имя и цвет можно переопределить на месте
 *     name: 'Своё имя'
 *     color: '#7FF0D2'
 * </pre>
 *
 * <p>Файл читает {@link PackFile} — тот же, что и у паков префиксов, поэтому
 * формат у них один и тот же.
 *
 * <h2>Откуда берутся id</h2>
 *
 * Если файл модели найден в {@code settings/cosmetics.yml}, косметика пака — это
 * та же косметика: тот же id, имя, цвет и номер. Если файла в настройках нет,
 * пак заводит свою запись: id собирается из имени пака и файла
 * ({@code letniy_nabor_kosmetika9}) или берётся из поля {@code id}, имя — из поля
 * {@code name} или из самого файла, а в журнал уходит предупреждение: такую
 * модель надо добавить в {@code settings/cosmetics.yml}.
 *
 * <h2>Срок кейса</h2>
 *
 * {@code time-hours} — это срок жизни кейса у игрока, а не срок выбитой
 * косметики: кейс сгорел — и не открыть. Косметика, которая из него выпала,
 * остаётся у игрока навсегда.
 */
final class CosmeticPackCatalog {

    /** Срок, который означает «бессрочно». */
    static final int INFINITE = PackFile.INFINITE;

    /** Название списка записей в файле пака косметики. */
    private static final String ENTRIES_KEY = "cosmetics";

    private final Map<String, Pack> byId;
    private final List<Pack> ordered;

    private CosmeticPackCatalog(Map<String, Pack> byId) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
    }

    /** Пак: название, срок кейса и его косметика. Номер для команды выдачи раздаёт {@link CosmeticCatalog}. */
    record Pack(String id, String name, int timeHours, List<CosmeticCatalog.Cosmetic> cosmetics)
            implements ItemPack {

        @Override
        public int size() {
            return cosmetics.size();
        }

        /** Бессрочный ли кейс ({@code time-hours: -1}). */
        boolean infinite() {
            return timeHours < 0;
        }

        /** id предметов пака — косметики. */
        @Override
        public List<String> itemIds() {
            return cosmetics.stream().map(CosmeticCatalog.Cosmetic::id).toList();
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

    /** Прочитать все {@code *.yml} из папки. */
    static CosmeticPackCatalog load(Path folder) {
        return load(folder, null, null);
    }

    /**
     * Прочитать все {@code *.yml} из папки. Имена и цвета косметики паков
     * берутся из {@code base} ({@code settings/cosmetics.yml}) по файлу модели; всё,
     * чего в нём нет, сообщается в {@code warn} (обычно — журнал плагина).
     */
    static CosmeticPackCatalog load(Path folder, CosmeticCatalog base, Consumer<String> warn) {
        Map<String, Pack> byId = new LinkedHashMap<>();
        for (PackFile.Header header : PackFile.readAll(folder, ENTRIES_KEY)) {
            Pack pack = build(header, base, warn);
            if (pack != null) byId.put(pack.id(), pack);
        }
        return new CosmeticPackCatalog(byId);
    }

    private static Pack build(PackFile.Header header, CosmeticCatalog base, Consumer<String> warn) {
        List<CosmeticCatalog.Cosmetic> cosmetics = new ArrayList<>();
        for (PackFile.Entry entry : header.entries()) {
            String entryFile = entry.file();
            String entryName = entry.name();
            String entryId = entry.id();
            String entryColor = entry.color();

            CosmeticCatalog.Cosmetic known = base == null ? null : base.byFile(entryFile);
            String finalId;
            String finalName;
            String finalColor;
            if (known != null && entryId.isEmpty() && entryName.isEmpty() && entryColor.isEmpty()) {
                // Ничего не переопределено — это ровно та косметика из settings/cosmetics.yml:
                // берём её вместе с номером, чтобы в меню пака номер совпадал.
                cosmetics.add(known);
                continue;
            }
            if (known != null) {
                finalId = entryId.isEmpty() ? known.id() : entryId;
                finalName = entryName.isEmpty() ? known.name() : entryName;
                finalColor = entryColor.isEmpty() ? known.color().asHexString() : entryColor;
            } else {
                if (warn != null) {
                    warn.accept("Пак " + header.id() + ": модель косметики «" + entryFile
                            + "» не найдена в settings/cosmetics.yml — имя взято из файла, добавьте косметику в settings/cosmetics.yml");
                }
                finalId = entryId.isEmpty() ? PackFile.sanitize(header.id() + "_" + entryFile) : entryId;
                finalName = entryName.isEmpty() ? entryFile : entryName;
                finalColor = entryColor.isEmpty() ? "#E6B94B" : entryColor;
            }
            TextColor color = TextColor.fromHexString(finalColor);
            if (color == null) throw new IllegalArgumentException("Неверный цвет косметики " + finalId);
            cosmetics.add(new CosmeticCatalog.Cosmetic(PackFile.sanitize(finalId), finalName, color, entryFile,
                    known == null ? 0 : known.number()));
        }
        if (cosmetics.isEmpty()) return null;
        return new Pack(header.id(), header.name(), header.timeHours(), List.copyOf(cosmetics));
    }

    /** id в безопасном виде — как у паков префиксов. */
    static String sanitize(String value) {
        return PackFile.sanitize(value);
    }

    /** Когда кейс сгорит: {@code now + часы}; бессрочный — {@link Long#MAX_VALUE}. */
    static long expiresAt(long now, int timeHours) {
        return PackFile.expiresAt(now, timeHours);
    }

    /** Сколько осталось жить кейсу: коротко, для подписи сундучка. */
    static String leftText(long expiresAt, long now) {
        return PackFile.leftText(expiresAt, now);
    }
}
