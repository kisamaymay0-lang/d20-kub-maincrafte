package com.yourserver.adaptation;

/**
 * Высота звука Bukkit (pitch) и номер ноты Minecraft: по номеру красится частица
 * {@code Particle.NOTE}.
 *
 * <h2>Как ваниль считает ноту</h2>
 *
 * У нотного блока 25 состояний (0…24), и каждому отвечает своя высота:
 * {@code pitch = 2^((note − 12) / 12)}. Обратно — логарифм:
 * {@code note = 12 + 12 · log2(pitch)}. Номер округляется до целого и
 * обрезается по краям: на них звук всё равно выходит за диапазон нотного блока,
 * а частица обязана оставаться в 0…24 — иначе клиент раскрасит её не тем цветом.
 *
 * <h2>Как частица получает цвет</h2>
 *
 * {@code Particle.NOTE} among простых частиц: у неё нет отдельного типа данных,
 * цвет клиент берёт из первого смещения, {@code offsetX = note / 24} — ровно так
 * ванильный сервер шлёт частицу нотного блока. Поэтому частица всегда
 * спавнится с {@code count = 0}: при нуле смещения идут как параметры частицы,
 * а не как разброс.
 *
 * Проверено на обеих частях: звук играет плагин ({@code world.playSound}),
 * частицу — тоже он, ванильного нотного блока под медным блоком нет.
 */
final class NoteTones {

    /** Первая нота нотного блока. */
    static final int MIN_NOTE = 0;

    /** Последняя нота нотного блока. */
    static final int MAX_NOTE = 24;

    /** Сколько всего нот: делитель смещения частицы. */
    static final int NOTES = MAX_NOTE - MIN_NOTE + 1;

    /** Нота «ля» первой октавы — ей отвечает ровный pitch 1.0. */
    static final int TUNING_NOTE = 12;

    private NoteTones() { }

    /**
     * Номер ноты по высоте звука. Немузыкальному pitch (0, NaN, бесконечность)
     * отвечает ровный тон {@link #TUNING_NOTE} — частица всё равно будет
     * покрашена, а не уедет за край диапазона.
     */
    static int noteId(float pitch) {
        if (!(pitch > 0.0f) || Float.isNaN(pitch) || Float.isInfinite(pitch)) return TUNING_NOTE;
        double note = TUNING_NOTE + 12.0 * (Math.log(pitch) / Math.log(2.0));
        long rounded = Math.round(note);
        long clamped = Math.clamp(rounded, MIN_NOTE, MAX_NOTE);
        return (int) clamped;
    }

    /**
     * Смещение частицы {@code Particle.NOTE} по номеру ноты: {@code note / 24}.
     * Чужие номера обрезаются, чтобы клиент не получил цвет вне диапазона.
     */
    static double offset(int noteId) {
        long clamped = Math.clamp((long) noteId, MIN_NOTE, MAX_NOTE);
        return clamped / (double) (NOTES - 1);
    }

    /** Смещение частицы прямо по высоте звука — для мест, где нота не хранится. */
    static double offset(float pitch) {
        return offset(noteId(pitch));
    }
}
