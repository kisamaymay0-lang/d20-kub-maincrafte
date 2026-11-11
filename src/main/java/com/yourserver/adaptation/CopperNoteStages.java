package com.yourserver.adaptation;

import java.util.Random;

/**
 * Как ступень окисления медного нотного блока слышна: высота нот и паузы.
 *
 * <h2>Высота</h2>
 *
 * <p>Чем сильнее окислена медь, тем хуже она держит строй: каждая нота
 * «плывёт» вокруг своей высоты тем сильнее, чем выше ступень —
 * {@code 1s} звучит точно, {@code 2s} расстраивается на десятые доли
 * полутона, {@code 3s} — почти на полутон. У {@code 4s} строя нет вовсе:
 * высота выбирается случайно в пределах нотного блока ({@value #MIN_PITCH}…
 * {@value #MAX_PITCH}, то есть ноты 0…24) каждый раз, когда нота играет.
 *
 * <h2>Паузы</h2>
 *
 * <p>Окисленный блок ещё и «заедает»: у {@code 4s} перед каждой нотой
 * добавляется случайная задержка {@value #LOW_DELAY_TICKS} или
 * {@value #HIGH_DELAY_TICKS} тика — 0.1 или 0.2 секунды. Первые три ступени
 * играют без задержек.
 *
 * <p>Сам номер ноты не хранится: плагин играет ноты сам и берёт высоту из
 * раскладки слота, поэтому «расстройка» — это множитель к ней.
 */
final class CopperNoteStages {

    /** Первая ступень: обычная медь. */
    static final int FIRST = 1;

    /** Последняя ступень: окисленная медь. */
    static final int LAST = CraftEngineCopper.STAGES;

    /** Пределы высоты нотного блока: ноты 0…24. */
    static final float MIN_PITCH = 0.5f;
    static final float MAX_PITCH = 2.0f;

    /**
     * Разброс высоты по ступеням 1s…3s в полутонах. У {@code 4s} не
     * используется: там высота выбирается целиком случайно.
     */
    static final double[] SPREAD_SEMITONES = {0.0D, 0.35D, 0.85D};

    /** Добавочная задержка перед нотой у окисленного блока, тики. */
    static final int LOW_DELAY_TICKS = 2;   // 0.1 секунды
    static final int HIGH_DELAY_TICKS = 4;  // 0.2 секунды

    private static final String[] LABELS = {"1s", "2s", "3s", "4s"};
    private static final String[] TITLES = {"обычный медный", "потемневший", "состаренный", "окисленный"};

    private CopperNoteStages() { }

    /** Ступень в допустимых пределах 1…4. */
    static int clamp(int stage) {
        return Math.max(FIRST, Math.min(LAST, stage));
    }

    /** «1s»…«4s». */
    static String label(int stage) {
        return LABELS[clamp(stage) - 1];
    }

    /** «обычный медный»…«окисленный». */
    static String title(int stage) {
        return TITLES[clamp(stage) - 1];
    }

    /** Следующая ступень окисления; у последней ступени — она же. */
    static int next(int stage) {
        return clamp(stage + 1);
    }

    /** Предыдущая ступень: топор снимает окисление на одну. */
    static int previous(int stage) {
        return clamp(stage - 1);
    }

    /** Стареет ли блок сам: воск останавливает окисление, дальше 4s идти некуда. */
    static boolean canOxidize(int stage, boolean waxed) {
        return !waxed && clamp(stage) < LAST;
    }

    /**
     * Высота ноты с учётом ступени. {@code base} — высота из раскладки слота,
     * результат всегда в пределах нотного блока, иначе клиент не поймёт ноту.
     */
    static float detunedPitch(int stage, float base, Random random) {
        int step = clamp(stage);
        if (step >= LAST) {
            // Строя нет: каждая нота — случайная высота.
            return MIN_PITCH + random.nextFloat() * (MAX_PITCH - MIN_PITCH);
        }
        double spread = SPREAD_SEMITONES[step - 1];
        if (spread <= 0.0D || !Float.isFinite(base)) {
            return clampPitch(base);
        }
        // Равномерно в пределах ±spread полутонов вокруг ноты.
        double semitones = (random.nextDouble() * 2.0D - 1.0D) * spread;
        return clampPitch((float) (base * Math.pow(2.0D, semitones / 12.0D)));
    }

    /** Добавочная задержка перед нотой, тики: есть только у окисленного блока. */
    static int noteDelayTicks(int stage, Random random) {
        if (clamp(stage) < LAST) return 0;
        return random.nextBoolean() ? LOW_DELAY_TICKS : HIGH_DELAY_TICKS;
    }

    /** Высота всегда остаётся в пределах нотного блока. */
    static float clampPitch(float pitch) {
        if (!Float.isFinite(pitch) || pitch <= 0.0f) return MIN_PITCH;
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }
}
