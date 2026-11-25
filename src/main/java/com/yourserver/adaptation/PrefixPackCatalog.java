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
 * Паки префиксов: по файлу на пак в {@code plugins/f8-plugin/content/prefixpacks/}.
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
 * {@code settings/prefixes.yml} — по полю {@code file}. Сам формат файла
 * читает {@link PackFile} (он же читает и паки косметики):
 *
 * <pre>
 * name: 'Тестовый пак'        # название на сундучке
 * time-hours: 24              # срок кейса в часах с момента выдачи; -1 — бессрочный
 * prefixes:                   # файлы префиксов из settings/prefixes.yml
 *   - pref1                   # имя и цвет возьмутся из settings/prefixes.yml
 *   - file: pref9             # тот же вид, но полем
 *   - file: pref3             # имя и цвет можно переопределить на месте
 *     name: 'Своё имя'
 *     color: '#7FF0D2'
 * </pre>
 *
 * <h2>Откуда берутся id</h2>
 *
 * Если файл найден в {@code settings/prefixes.yml}, префикс пака — это тот же префикс:
 * тот же id, имя, цвет и номер. Так один и тот же префикс не появляется дважды
 * (своим и из пака), а выданный префикс не теряется. Если файла в
 * {@code settings/prefixes.yml} нет, пак заводит свой префикс: id собирается из имени
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
    static final int INFINITE = PackFile.INFINITE;

    /** Название списка записей в файле пака префиксов. */
    private static final String ENTRIES_KEY = "prefixes";

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
    record Pack(String id, String name, int timeHours, List<PrefixCatalog.Prefix> prefixes)
            implements ItemPack {

        @Override
        public int size() {
            return prefixes.size();
        }

        /** Бессрочный ли кейс ({@code time-hours: -1}). */
        boolean infinite() {
            return timeHours < 0;
        }

        /** id предметов пака — префиксов. */
        @Override
        public List<String> itemIds() {
            return prefixes.stream().map(PrefixCatalog.Prefix::id).toList();
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
     * берутся из {@code base} ({@code settings/prefixes.yml}) по файлу иконки; всё, чего
     * в нём нет, сообщается в {@code warn} (обычно — журнал плагина).
     */
    static PrefixPackCatalog load(Path folder, PrefixCatalog base, Consumer<String> warn) {
        Map<String, Pack> byId = new LinkedHashMap<>();
        for (PackFile.Header header : PackFile.readAll(folder, ENTRIES_KEY)) {
            Pack pack = build(header, base, warn);
            if (pack != null) byId.put(pack.id(), pack);
        }
        return new PrefixPackCatalog(byId);
    }

    private static Pack build(PackFile.Header header, PrefixCatalog base, Consumer<String> warn) {
        List<PrefixCatalog.Prefix> prefixes = new ArrayList<>();
        for (PackFile.Entry entry : header.entries()) {
            String entryFile = entry.file();
            String entryName = entry.name();
            String entryId = entry.id();
            String entryColor = entry.color();

            PrefixCatalog.Prefix known = base == null ? null : base.byFile(entryFile);
            String finalId;
            String finalName;
            String finalColor;
            if (known != null && entryId.isEmpty() && entryName.isEmpty() && entryColor.isEmpty()) {
                // Ничего не переопределено — это ровно тот префикс из settings/prefixes.yml:
                // берём его вместе с номером и анимацией, чтобы в меню пака номер совпадал.
                prefixes.add(known);
                continue;
            }
            if (known != null) {
                // Файл есть в settings/prefixes.yml: префикс пака — тот же префикс.
                finalId = entryId.isEmpty() ? known.id() : entryId;
                finalName = entryName.isEmpty() ? known.name() : entryName;
                finalColor = entryColor.isEmpty() ? known.color().asHexString() : entryColor;
            } else {
                if (warn != null) {
                    warn.accept("Пак " + header.id() + ": файл префикса «" + entryFile
                            + "» не найден в settings/prefixes.yml — имя взято из файла, добавьте префикс в settings/prefixes.yml");
                }
                finalId = entryId.isEmpty() ? PackFile.sanitize(header.id() + "_" + entryFile) : entryId;
                finalName = entryName.isEmpty() ? entryFile : entryName;
                finalColor = entryColor.isEmpty() ? "#E6B94B" : entryColor;
            }
            TextColor color = TextColor.fromHexString(finalColor);
            if (color == null) throw new IllegalArgumentException("Неверный цвет префикса " + finalId);
            prefixes.add(known == null
                    ? new PrefixCatalog.Prefix(PackFile.sanitize(finalId), finalName, color, entryFile, 0)
                    : new PrefixCatalog.Prefix(PackFile.sanitize(finalId), finalName, color, entryFile, known.number(),
                            known.frames(), known.intervalHundredths()));
        }
        if (prefixes.isEmpty()) return null;
        return new Pack(header.id(), header.name(), header.timeHours(), List.copyOf(prefixes));
    }

    /** id в безопасном виде: строчные буквы, цифры, точка, дефис и подчёркивание. */
    static String sanitize(String value) {
        return PackFile.sanitize(value);
    }

    // ===== СРОК КЕЙСА =====

    /**
     * Когда кейс сгорит: {@code now + часы}. Для бессрочного ({@code -1}) —
     * {@link Long#MAX_VALUE}, то есть никогда.
     */
    static long expiresAt(long now, int timeHours) {
        return PackFile.expiresAt(now, timeHours);
    }

    /** Сколько осталось жить кейсу: коротко, для подписи сундучка. */
    static String leftText(long expiresAt, long now) {
        return PackFile.leftText(expiresAt, now);
    }
}
