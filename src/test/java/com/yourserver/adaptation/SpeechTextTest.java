package com.yourserver.adaptation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Общая надпись над питомцем, короной и игроком: посимвольное появление,
 * мягкое проявление/затухание и безопасная нарезка строки.
 */
class SpeechTextTest {

    /** Клиент читает непрозрачность как беззнаковый байт, поэтому сравниваем так же. */
    private static int opacity(int age, int lifetime) {
        return Byte.toUnsignedInt(SpeechText.opacityFor(age, lifetime));
    }

    @Test
    void speechFadesInAndOutInsteadOfPopping() {
        int lifetime = 80;
        assertEquals(4, opacity(0, lifetime), "первый тик почти прозрачный");
        assertTrue(opacity(10, lifetime) > opacity(0, lifetime), "текст плавно появляется");
        assertTrue(opacity(15, lifetime) > opacity(10, lifetime));
        assertEquals(255, opacity(lifetime / 2, lifetime), "в середине текст полностью виден");
        assertTrue(opacity(lifetime - 1, lifetime) < 255, "перед исчезновением текст тускнеет");
        assertTrue(opacity(lifetime - 1, lifetime) >= 4, "минимум 4, иначе клиент считает текст непрозрачным");
    }

    @Test
    void shortRepliesStillFadeOutBeforeTheyDisappear() {
        int lifetime = 40;
        assertEquals(255, opacity(lifetime / 2, lifetime));
        assertTrue(opacity(lifetime - 1, lifetime) < 40, "короткий ответ успевает погаснуть");
    }

    @Test
    void shortPhrasesAreTypedLetterByLetter() {
        assertEquals(1, SpeechText.charsPerTick(24, 80), "короткая реплика печатается по букве за тик");
        assertEquals(1, SpeechText.charsPerTick(3, 40), "«...» появляется мгновенно, но по буквам");
        assertEquals(1, SpeechText.charsPerTick(0, 60));
    }

    @Test
    void longChatMessagesTypeFasterSoTheyDoNotStayEmpty() {
        int length = 160;
        int lifetime = 60;
        int perTick = SpeechText.charsPerTick(length, lifetime);
        assertTrue(perTick > 1, "длинное сообщение печатается пачками");
        int typingTicks = (int) Math.ceil(length / (double) perTick);
        assertTrue(typingTicks <= lifetime / 2,
                "буквы успевают появиться за половину времени жизни: " + typingTicks);
    }

    /** Размеры и высоты слов должны быть в поставляемом config.yml. */
    @Test
    void speechSizesAndHeightsAreDocumentedInConfig() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream stream = SpeechTextTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml должен лежать в комплекте");
            config.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        assertEquals(1.0D, config.getDouble("pets.speech.player-scale"), 0.0001D,
                "слова игроков вдвое крупнее прежних 0.5");
        assertTrue(config.contains("pets.speech.player-height"), "высота слов игрока");
        assertTrue(config.contains("pets.speech.player-height-with-crown"),
                "отдельная высота слов игрока, когда надета корона");
        assertEquals(0.5D, config.getDouble("pets.speech.pet-1-scale"), 0.0001D);
        assertEquals(0.5D, config.getDouble("pets.speech.pet-2-scale"), 0.0001D);
        assertTrue(config.contains("pets.speech.pet-1-height"), "своя высота у первого питомца");
        assertTrue(config.contains("pets.speech.pet-2-height"), "своя высота у короны");
    }

    @Test
    void scaleIsClampedSoATypoDoesNotBreakTheText() {
        assertEquals(SpeechText.DEFAULT_SCALE, SpeechText.clampScale(Float.NaN));
        assertEquals(SpeechText.MIN_SCALE, SpeechText.clampScale(0.0f), 0.0001f);
        assertEquals(SpeechText.MAX_SCALE, SpeechText.clampScale(100.0f), 0.0001f);
        assertEquals(1.0f, SpeechText.clampScale(1.0f), 0.0001f);
    }

    @Test
    void visiblePartGrowsByPrefixAndNeverSplitsAnEmoji() {
        String text = "Привет, мир!";
        assertEquals("", SpeechText.visiblePart(text, 0));
        assertEquals("П", SpeechText.visiblePart(text, 1));
        assertEquals("Привет", SpeechText.visiblePart(text, 6));
        assertEquals(text, SpeechText.visiblePart(text, text.length() + 5));

        String emoji = "а😀б";
        // Суррогатная пара занимает два char: на границе нельзя показать
        // половину эмодзи, поэтому показывается целый символ.
        assertEquals("а", SpeechText.visiblePart(emoji, 1));
        assertEquals("а😀", SpeechText.visiblePart(emoji, 2), "эмодзи не рвётся пополам");
        assertEquals("а😀", SpeechText.visiblePart(emoji, 3));
        assertEquals(emoji, SpeechText.visiblePart(emoji, emoji.length()));
    }
}
