package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Пьяная речь: плагин правит сообщение игрока по стадии опьянения.
 *
 * <h2>Что делаем</h2>
 *
 * <ul>
 *   <li><b>Навеселе</b> — сообщение как есть;</li>
 *   <li><b>Разговорило</b> — переставляем буквы в одном слове, в конце иногда «ик!»;</li>
 *   <li><b>Шатает</b> — в двух словах, гласные тянутся, «ик!» чаще;</li>
 *   <li><b>Укачало</b> — половина слов, «ик…» ещё чаще, иногда многоточие вместо слов;</li>
 *   <li><b>В стельку</b> — почти каждое слово, «ик…» почти всегда;</li>
 *   <li><b>Отключка</b> — вместо сообщения «…»: сказать уже нечего.</li>
 * </ul>
 *
 * <p>Правим только то, что видят другие: команды и служебные сообщения сюда не попадают,
 * потому что {@code AsyncChatEvent} их не получает. Оригинальный текст нигде не теряется —
 * он уходит в журнал сервера как обычно.
 *
 * <p>Всё здесь — чистая математика со {@link Random}: тесты передают свой {@code Random}
 * и проверяют результат без сервера.
 */
final class DrunkSpeech {

    /** Что дописываем в конце: «ик!» или «ик…». */
    private static final String HICCUP_LOUD = "ик!";
    private static final String HICCUP_SOFT = "ик…";

    /** Гласные, которые тянем на поздних стадиях. */
    private static final String VOWELS = "аеёиоуыэюя";

    private DrunkSpeech() {
    }

    /**
     * Пьяная версия сообщения. Возвращает исходный текст, если игрок ещё внятно говорит.
     *
     * @param message исходное сообщение (без цветов)
     * @param stage   стадия опьянения
     * @param random  источник случайности (в тестах — с зерном)
     */
    static String slur(String message, DrunkStage stage, Random random) {
        if (message == null || message.isEmpty() || stage == null) return message;
        Random dice = random != null ? random : new Random();

        return switch (stage) {
            case SOBER, TIPSY -> message;
            case BLACKOUT -> "…";
            case TALKATIVE -> mangle(message, 1, dice, 0.20, HICCUP_LOUD);
            case SWAYING -> stretch(mangle(message, 2, dice, 0.40, HICCUP_LOUD), dice);
            case QUEASY -> maybeSilence(stretch(mangle(message, half(message), dice, 0.60, HICCUP_SOFT), dice),
                    dice, 0.15);
            case WASTED -> maybeSilence(stretch(mangle(message, message.split("\\s+").length, dice, 0.70,
                    HICCUP_SOFT), dice), dice, 0.30);
            case EDGE -> maybeSilence(stretch(mangle(message, message.split("\\s+").length, dice, 0.85,
                    HICCUP_SOFT), dice), dice, 0.40);
        };
    }

    /** То же, но с собственным зерном: удобно для демонстрации и тестов. */
    static String slur(String message, DrunkStage stage, long seed) {
        return slur(message, stage, new Random(seed));
    }

    /** Меняем до {@code words} слов: переставляем буквы и портим регистр. */
    private static String mangle(String message, int words, Random random, double hiccupChance,
                                 String hiccup) {
        String[] parts = message.split("\\s+");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isBlank() && parts[i].length() > 2) order.add(i);
        }
        if (!order.isEmpty()) {
            int count = Math.max(1, Math.min(words, order.size()));
            for (int i = 0; i < count; i++) {
                int pick = order.remove(random.nextInt(order.size()));
                parts[pick] = scramble(parts[pick], random);
            }
        }
        String text = String.join(" ", parts);
        if (random.nextDouble() < hiccupChance) {
            text = text + " " + hiccup;
        }
        return text;
    }

    /** Переставляем две соседние буквы в середине слова — «привте», «нормальо». */
    private static String scramble(String word, Random random) {
        if (word.length() < 4) return word;
        int at = 1 + random.nextInt(word.length() - 3);
        char[] chars = word.toCharArray();
        char swap = chars[at];
        chars[at] = chars[at + 1];
        chars[at + 1] = swap;
        return new String(chars);
    }

    /** Тянем гласные: «нееет», «дааа». */
    private static String stretch(String text, Random random) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            boolean vowel = VOWELS.indexOf(Character.toLowerCase(c)) >= 0;
            if (vowel && random.nextDouble() < 0.18) {
                out.append(c).append(c);
            }
        }
        return out.toString();
    }

    /** Иногда вместо слов остаётся только многоточие. */
    private static String maybeSilence(String text, Random random, double chance) {
        return random.nextDouble() < chance ? "…" : text;
    }

    /** Сколько слов править на «Укачало»: примерно половина. */
    private static int half(String message) {
        int words = message.split("\\s+").length;
        return Math.max(1, words / 2);
    }

    /** Стадия по строке — для команды {@code /f8 drunk}: {@code wasted} или «В стельку». */
    static DrunkStage stageOf(String raw) {
        if (raw == null) return null;
        String text = ProfileText.clean(raw).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (text.isEmpty()) return null;
        for (DrunkStage stage : DrunkStage.values()) {
            String title = ProfileText.clean(stage.title()).toLowerCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            if (stage.id().equals(text) || title.equals(text)) return stage;
        }
        return null;
    }
}
