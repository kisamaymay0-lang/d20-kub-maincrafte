package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Пьяная речь: плагин правит сообщение игрока по стадии опьянения.
 *
 * <h2>Что делаем</h2>
 *
 * <p>Чем сильнее игрок напился, тем больше «портится» текст — и тем веселее:
 *
 * <ul>
 *   <li>переставляем и мешаем буквы внутри слов («првиет», «каробль»);</li>
 *   <li>выкидываем буквы («привт») и вставляем чёрточки прямо в слова («пр-ивет»);</li>
 *   <li>тыкаем точки с пробелами между словами («идём . домой»);</li>
 *   <li>повторяем слова («идём идём домой»);</li>
 *   <li>тянем гласные («идёёём»), добавляем «ик!» и «ик…»;</li>
 *   <li>на «Краю» и «Отключке» — полный абсурд: слова перемешаны, регистр скачет,
 *       в конце висят лишние многоточия.</li>
 * </ul>
 *
 * <p>Молчания нет ни на одной стадии: даже в отключке игрок продолжает мычать что-то
 * невнятное. Единственный случай, когда сообщение не меняется, — «Трезв» и «Навеселе».
 *
 * <p>Правим только чат: команды и служебные сообщения сюда не попадают, потому что
 * {@code AsyncChatEvent} их не получает. Оригинальный текст нигде не теряется — он
 * уходит в журнал сервера как обычно.
 *
 * <p>Всё здесь — чистая математика со {@link Random}: тесты передают свой {@code Random}
 * и проверяют результат без сервера.
 */
final class DrunkSpeech {

    /** Что дописываем в конце: «ик!» или «ик…». */
    private static final String HICCUP_LOUD = "ик!";
    private static final String HICCUP_SOFT = "ик…";

    /** Гласные, которые тянем. */
    private static final String VOWELS = "аеёиоуыэюя";

    /** Многоточия и «э-э», которыми заканчивается абсурд. */
    private static final String[] CHAOS_PREFIX = {"Э-э… ", "Э… ", "Ы-ы… ", "А?.. "};
    private static final String[] CHAOS_TAIL = {" …  …", " . . .", " … … …", " ыыы…"};

    /**
     * Насколько сильно портим текст на стадии.
     *
     * @param broken  доля слов, которые вообще трогаем
     * @param dash    шанс вставить чёрточку внутрь слова
     * @param dot     шанс поставить точку с пробелами между словами
     * @param drop    шанс потерять букву
     * @param shuffle шанс перемешать буквы в середине слова
     * @param stretch шанс растянуть гласные во всём сообщении
     * @param repeat  шанс повторить слово
     * @param hiccup  шанс добавить «ик»
     * @param word    само «ик»
     * @param chaos   общий уровень абсурда (регистр, порядок слов, лишние многоточия)
     */
    private record Style(double broken,
                         double dash,
                         double dot,
                         double drop,
                         double shuffle,
                         double stretch,
                         double repeat,
                         double hiccup,
                         String word,
                         double chaos) {
    }

    private static final Style TALKATIVE =
            new Style(0.30, 0.10, 0.06, 0.10, 0.30, 0.10, 0.05, 0.30, HICCUP_LOUD, 0.0);
    private static final Style SWAYING =
            new Style(0.55, 0.30, 0.18, 0.25, 0.55, 0.25, 0.10, 0.45, HICCUP_LOUD, 0.05);
    private static final Style QUEASY =
            new Style(0.75, 0.45, 0.30, 0.40, 0.75, 0.40, 0.18, 0.55, HICCUP_SOFT, 0.15);
    private static final Style WASTED =
            new Style(0.90, 0.60, 0.45, 0.50, 0.85, 0.55, 0.30, 0.70, HICCUP_SOFT, 0.35);
    private static final Style EDGE =
            new Style(1.00, 0.75, 0.60, 0.60, 0.95, 0.70, 0.45, 0.85, HICCUP_SOFT, 0.60);
    private static final Style BLACKOUT =
            new Style(1.00, 0.85, 0.75, 0.70, 1.00, 0.85, 0.60, 1.00, HICCUP_SOFT, 0.90);

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

        Style style = switch (stage) {
            case SOBER, TIPSY -> null;
            case TALKATIVE -> TALKATIVE;
            case SWAYING -> SWAYING;
            case QUEASY -> QUEASY;
            case WASTED -> WASTED;
            case EDGE -> EDGE;
            case BLACKOUT -> BLACKOUT;
        };
        if (style == null) return message;

        String text = paint(message, dice, style);
        return text == null || text.isBlank() ? message : text;
    }

    /** То же, но с собственным зерном: удобно для демонстрации и тестов. */
    static String slur(String message, DrunkStage stage, long seed) {
        return slur(message, stage, new Random(seed));
    }

    /** Главный конвейер: слово за словом портим текст по «стилю» стадии. */
    private static String paint(String message, Random dice, Style style) {
        String[] words = message.trim().split("\\s+");
        List<String> out = new ArrayList<>(words.length * 2 + 2);
        double hiccupPerWord = style.hiccup() / Math.max(1, words.length);

        for (String word : words) {
            if (word.isEmpty()) continue;
            String broken = dice.nextDouble() < style.broken() && word.length() > 2
                    ? breakWord(word, dice, style)
                    : word;
            out.add(broken);
            if (word.length() > 2 && dice.nextDouble() < style.repeat()) {
                // С перепугу сказал дважды.
                out.add(breakWord(word, dice, style));
            }
            if (dice.nextDouble() < style.dot()) {
                out.add(".");
            }
            if (dice.nextDouble() < hiccupPerWord) {
                out.add(style.word());
            }
        }

        String text = String.join(" ", out);
        if (dice.nextDouble() < style.stretch()) {
            text = stretch(text, dice, 0.35);
        }
        return style.chaos() > 0 ? chaos(text, dice, style.chaos()) : text;
    }

    /** Одно слово: перемешать, потерять букву, вставить чёрточку, растянуть. */
    private static String breakWord(String word, Random dice, Style style) {
        String text = word;
        if (dice.nextDouble() < style.shuffle()) text = shuffleInner(text, dice);
        if (text.length() > 3 && dice.nextDouble() < style.drop()) text = dropLetter(text, dice);
        if (text.length() > 2 && dice.nextDouble() < style.dash()) text = dashInside(text, dice);
        if (dice.nextDouble() < style.stretch() * 0.5) text = stretch(text, dice, 0.5);
        return text;
    }

    /** Первая и последняя буквы на месте, середина перемешана — «каробль». */
    private static String shuffleInner(String word, Random dice) {
        if (word.length() < 4) return word;
        List<Character> middle = new ArrayList<>(word.length() - 2);
        for (int i = 1; i < word.length() - 1; i++) middle.add(word.charAt(i));
        Collections.shuffle(middle, dice);
        StringBuilder out = new StringBuilder(word.length());
        out.append(word.charAt(0));
        for (char c : middle) out.append(c);
        out.append(word.charAt(word.length() - 1));
        return out.toString();
    }

    /** Пропала буква: «привт». Первую не трогаем, иначе слово не узнать. */
    private static String dropLetter(String word, Random dice) {
        int at = 1 + dice.nextInt(word.length() - 2);
        return word.substring(0, at) + word.substring(at + 1);
    }

    /** Чёрточка прямо в слове: «пр-ивет», «ко-рабль». */
    private static String dashInside(String word, Random dice) {
        int at = 1 + dice.nextInt(word.length() - 1);
        String separator = dice.nextDouble() < 0.25 ? " — " : "-";
        return word.substring(0, at) + separator + word.substring(at);
    }

    /** Тянем гласные: «нееет», «дааа». */
    private static String stretch(String text, Random dice, double chance) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            boolean vowel = VOWELS.indexOf(Character.toLowerCase(c)) >= 0;
            if (vowel && dice.nextDouble() < chance) {
                out.append(c);
                if (dice.nextDouble() < 0.3) out.append(c);
            }
        }
        return out.toString();
    }

    /** Абсурд поздних стадий: порядок слов, регистр, лишние многоточия. */
    private static String chaos(String text, Random dice, double power) {
        String result = text;
        if (dice.nextDouble() < power * 0.7) {
            List<String> words = new ArrayList<>(Arrays.asList(result.split("\\s+")));
            Collections.shuffle(words, dice);
            result = String.join(" ", words);
        }
        if (dice.nextDouble() < power * 0.5) {
            result = mixedCase(result, dice);
        }
        if (dice.nextDouble() < power) {
            result = result + CHAOS_TAIL[dice.nextInt(CHAOS_TAIL.length)];
        }
        if (dice.nextDouble() < power * 0.35) {
            result = CHAOS_PREFIX[dice.nextInt(CHAOS_PREFIX.length)] + result;
        }
        return result;
    }

    /** Скачет регистр — как будто человек уже не следит за руками. */
    private static String mixedCase(String text, Random dice) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(dice.nextDouble() < 0.25 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
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
