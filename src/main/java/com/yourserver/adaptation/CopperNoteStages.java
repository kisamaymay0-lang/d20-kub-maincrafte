package com.yourserver.adaptation;

import java.util.Random;

/**
 * Как ступень окисления медного нотного блока слышна и как называется.
 *
 * <h2>Высота</h2>
 *
 * <p>Чем сильнее окислена медь, тем чаще она «фальшивит»: блок держит ноту, но
 * иногда выдаёт случайную высоту.
 *
 * <ul>
 *   <li>{@code 1s} — обычная медь: строй точный;</li>
 *   <li>{@code 2s} — потемневшая: высоты те же, но небольшая часть нот
 *       ({@value #EXPOSED_FALSE_CHANCE}) звучит случайно;</li>
 *   <li>{@code 3s} — состаренная: фальшивых нот больше
 *       ({@value #WEATHERED_FALSE_CHANCE}), плюс иногда нота приходит с
 *       задержкой в 0.1 секунды;</li>
 *   <li>{@code 4s} — окисленная: строя нет вовсе, каждая нота — случайная
 *       высота, и перед каждой нотой случайная задержка 0.1 или 0.2 секунды.</li>
 * </ul>
 *
 * <p>Случайная высота всегда выбирается в пределах нотного блока
 * ({@value #MIN_PITCH}…{@value #MAX_PITCH}, ноты 0…24), иначе клиент не поймёт
 * ноту и частица получит чужой цвет.
 */
final class CopperNoteStages {

    /** Первая ступень: обычная медь. */
    static final int FIRST = 1;

    /** Последняя ступень: окисленная медь. */
    static final int LAST = CraftEngineCopper.STAGES;

    /** Состаренная ступень: у неё свои шансы фальши и задержки. */
    static final int WEATHERED = 3;

    /** Пределы высоты нотного блока: ноты 0…24. */
    static final float MIN_PITCH = 0.5f;
    static final float MAX_PITCH = 2.0f;

    /** Шанс фальшивой ноты у потемневшего блока: редко, но слышно. */
    static final double EXPOSED_FALSE_CHANCE = 0.15D;

    /** Шанс фальшивой ноты у состаренного блока: вдвое чаще. */
    static final double WEATHERED_FALSE_CHANCE = 0.35D;

    /** Шанс, что состаренный блок задержит ноту на 0.1 секунды. */
    static final double WEATHERED_DELAY_CHANCE = 0.30D;

    /** Добавочная задержка перед нотой у окисленного блока, тики. */
    static final int LOW_DELAY_TICKS = 2;   // 0.1 секунды
    static final int HIGH_DELAY_TICKS = 4;  // 0.2 секунды

    private static final String[] LABELS = {"1s", "2s", "3s", "4s"};

    /** Названия предметов: ступень видна в инвентаре без скобок и лишних строк. */
    private static final String[] NAMES = {
            "Медный нотный блок",
            "Потемневший медный нотный блок",
            "Состаренный медный нотный блок",
            "Окисленный медный нотный блок",
    };

    /** Приставка к названию вощёного блока: воск виден уже в инвентаре. */
    static final String WAXED_PREFIX = "Вощёный ";

    private CopperNoteStages() { }

    /** Ступень в допустимых пределах 1…4. */
    static int clamp(int stage) {
        return Math.max(FIRST, Math.min(LAST, stage));
    }

    /** «1s»…«4s». */
    static String label(int stage) {
        return LABELS[clamp(stage) - 1];
    }

    /** Название предмета этой ступени. */
    static String itemName(int stage) {
        return itemName(stage, false);
    }

    /**
     * Название предмета с учётом воска: вощёный блок ломается вощёным, поэтому
     * в инвентаре это видно — «Вощёный окисленный медный нотный блок».
     */
    static String itemName(int stage, boolean waxed) {
        String name = NAMES[clamp(stage) - 1];

        if (!waxed) {
            return name;
        }

        // «Вощёный» + «медный…»: первая буква ступени становится строчной.
        return WAXED_PREFIX + Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Ключ модели предмета: «1s_copper_note_block»…«4s_copper_note_block». */
    static String itemModel(int stage) {
        return label(stage) + "_copper_note_block";
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
     * Высота ноты с учётом ступени. {@code base} — высота из раскладки слота.
     * Точная высота у первых ступеней, иногда случайная — у подгнивших.
     */
    static float detunedPitch(int stage, float base, Random random) {
        int step = clamp(stage);

        if (step >= LAST) {
            // Строя нет: каждая нота — случайная высота.
            return randomPitch(random);
        }

        if (step == FIRST) {
            return clampPitch(base);
        }

        return random.nextDouble() < falseChance(step) ? randomPitch(random) : clampPitch(base);
    }

    /** Шанс фальшивой ноты у ступени: у 1s и 4s он не используется. */
    static double falseChance(int stage) {
        int step = clamp(stage);
        if (step == 2) return EXPOSED_FALSE_CHANCE;
        if (step >= WEATHERED && step < LAST) return WEATHERED_FALSE_CHANCE;
        return 0.0D;
    }

    /** Добавочная задержка перед нотой, тики. */
    static int noteDelayTicks(int stage, Random random) {
        int step = clamp(stage);

        if (step >= LAST) {
            return random.nextBoolean() ? LOW_DELAY_TICKS : HIGH_DELAY_TICKS;
        }

        if (step == WEATHERED && random.nextDouble() < WEATHERED_DELAY_CHANCE) {
            return LOW_DELAY_TICKS;
        }

        return 0;
    }

    /** Случайная высота в пределах нотного блока. */
    static float randomPitch(Random random) {
        return MIN_PITCH + random.nextFloat() * (MAX_PITCH - MIN_PITCH);
    }

    /** Высота всегда остаётся в пределах нотного блока. */
    static float clampPitch(float pitch) {
        if (!Float.isFinite(pitch) || pitch <= 0.0f) return MIN_PITCH;
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }
}
