package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
