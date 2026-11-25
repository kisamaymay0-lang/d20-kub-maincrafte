package com.yourserver.adaptation;

import java.util.Locale;

/**
 * Стадии опьянения: от «трезв» до «отключка».
 *
 * <h2>Зачем отдельный класс</h2>
 *
 * Пороги и названия стадий — это таблица из {@code docs/OPJANENIE.md}, и её хочется
 * проверять тестами без запуска сервера. Поэтому здесь только числа и подписи:
 * никаких зелий, частиц и Bukkit-классов.
 *
 * <h2>Эталонная шкала</h2>
 *
 * Пороги записаны для шкалы 0…{@link #MAX_POINTS}. Если владелец поменяет размер шкалы
 * в {@code settings/drunk.yml}, {@link #of(int, int)} пересчитает границы сам:
 * 30 из 60 — это те же 25 из 50, то есть «Шатает».
 */
enum DrunkStage {

    /** Совсем трезв. */
    SOBER("Трезв", 0, 0),
    /** «Шумно в голове»: первые баффы. */
    TIPSY("Навеселе", 1, 7),
    /** Тянет разговаривать, речь чуть плывёт. */
    TALKATIVE("Разговорило", 8, 16),
    /** Ведёт в стороны, двоится в глазах. */
    SWAYING("Шатает", 17, 26),
    /** Мутит, накатывают приступы тошноты. */
    QUEASY("Укачало", 27, 36),
    /** Не стоит на месте, руки не слушаются. */
    WASTED("В стельку", 37, 45),
    /** Слепота пульсами, звуки приглушены. */
    EDGE("Край", 46, 49),
    /** Отключка: темнота на несколько секунд и похмелье. */
    BLACKOUT("Отключка", 50, 50);

    /** Шкала, для которой записаны пороги. */
    static final int MAX_POINTS = 50;

    private final String title;
    private final int from;
    private final int to;

    DrunkStage(String title, int from, int to) {
        this.title = title;
        this.from = from;
        this.to = to;
    }

    /** Подпись стадии: «Шатает», «В стельку». */
    String title() {
        return title;
    }

    /** Первое значение шкалы (для эталонных 50). */
    int from() {
        return from;
    }

    /** Последнее значение шкалы (для эталонных 50). */
    int to() {
        return to;
    }

    /** Стадия по очкам и размеру шкалы. Всё, что выше шкалы, — «отключка». */
    static DrunkStage of(int points, int scale) {
        int size = scale > 0 ? scale : MAX_POINTS;
        int safe = Math.max(0, points);
        long normalized = Math.round(safe * (double) MAX_POINTS / size);
        int value = (int) Math.min(MAX_POINTS, normalized);
        for (DrunkStage stage : values()) {
            if (value >= stage.from && value <= stage.to) return stage;
        }
        return BLACKOUT;
    }

    /** Эта же стадия для шкалы по умолчанию. */
    static DrunkStage of(int points) {
        return of(points, MAX_POINTS);
    }

    /** Не ниже ли эта стадия указанной (удобно для «с такого-то очка включаем…»). */
    boolean atLeast(DrunkStage other) {
        return ordinal() >= other.ordinal();
    }

    /** Маленькое имя для файлов, команд и тестов: {@code wasted}. */
    String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
