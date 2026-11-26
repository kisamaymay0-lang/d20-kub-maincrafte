package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пьяная речь: на ранних стадиях текст не трогаем, дальше он ломается всё сильнее,
 * а молчания нет ни на одной стадии — даже в отключке игрок мычит что-то невнятное.
 */
final class DrunkSpeechTest {

    private static final String MESSAGE = "Пойдём со мной на корабль, там ром";

    @Test
    void трезвыйИНавеселеГоворятКакОбычноВсегда() {
        assertEquals(MESSAGE, DrunkSpeech.slur(MESSAGE, DrunkStage.SOBER, 1L));
        assertEquals(MESSAGE, DrunkSpeech.slur(MESSAGE, DrunkStage.TIPSY, 1L));
    }

    @Test
    void никтоНеМолчит() {
        for (DrunkStage stage : DrunkStage.values()) {
            for (long seed = 1; seed <= 20; seed++) {
                String text = DrunkSpeech.slur(MESSAGE, stage, seed);
                assertNotNull(text, "стадия " + stage);
                assertFalse(text.isBlank(), "пустое сообщение на стадии " + stage);
                assertFalse("…".equals(text), "в отключке игрок больше не молчит: " + stage);
            }
        }
    }

    @Test
    void отключкаПревращаетсяВАбсурдНоОстаётсяТекстом() {
        boolean absurd = false;
        for (long seed = 1; seed <= 40; seed++) {
            String text = DrunkSpeech.slur(MESSAGE, DrunkStage.BLACKOUT, seed);
            assertFalse(text.isBlank());
            if (text.contains("…") || text.contains(" . ") || text.contains("-")
                    || !text.startsWith("П")) {
                absurd = true;
            }
        }
        assertTrue(absurd, "на отключке текст должен разваливаться на части");
    }

    @Test
    void сКаждойСтадиейВсёБольшеРазнообразия() {
        int talkative = variety(DrunkStage.TALKATIVE);
        int edge = variety(DrunkStage.EDGE);
        assertTrue(talkative >= 5, "даже «Разговорило» не повторяет одно и то же: " + talkative);
        assertTrue(edge >= talkative, "поздние стадии не беднее ранних");
        assertTrue(edge >= 15, "на «Краю» вариантов много: " + edge);
    }

    @Test
    void ранниеСтадииТрогаютМеньшеСлов() {
        int broken = 0;
        for (long seed = 1; seed <= 40; seed++) {
            if (!MESSAGE.equals(DrunkSpeech.slur(MESSAGE, DrunkStage.TALKATIVE, seed))) broken++;
        }
        assertTrue(broken > 0, "«Разговорило» уже портит речь");

        int intact = 0;
        for (long seed = 1; seed <= 40; seed++) {
            if (MESSAGE.equals(DrunkSpeech.slur(MESSAGE, DrunkStage.WASTED, seed))) intact++;
        }
        assertEquals(0, intact, "на «В стельку» исходной фразы уже не остаётся");
    }

    @Test
    void вХодуИЧёрточкиИТочки() {
        boolean dash = false;
        boolean dot = false;
        for (long seed = 1; seed <= 60; seed++) {
            String text = DrunkSpeech.slur(MESSAGE, DrunkStage.EDGE, new Random(seed));
            if (text.contains("-")) dash = true;
            if (text.contains(" . ")) dot = true;
        }
        assertTrue(dash, "чёрточки внутри слов — часть пьяной речи");
        assertTrue(dot, "точки с пробелами тоже");
    }

    @Test
    void укачалоНеСъедаетСообщениеЦеликом() {
        for (long seed = 1; seed <= 30; seed++) {
            String text = DrunkSpeech.slur(MESSAGE, DrunkStage.QUEASY, seed);
            assertFalse(text.isBlank());
            assertTrue(text.length() > 3, "от фразы остаётся хоть что-то: " + text);
        }
    }

    @Test
    void пустоеИСлишкомКороткоеНеЛомается() {
        assertEquals("", DrunkSpeech.slur("", DrunkStage.WASTED, 3L));
        assertNull(DrunkSpeech.slur(null, DrunkStage.WASTED, 3L));
        assertEquals(MESSAGE, DrunkSpeech.slur(MESSAGE, null, 3L));
        // Одно короткое слово без гласных не должно становиться пустым.
        assertFalse(DrunkSpeech.slur("да", DrunkStage.EDGE, 5L).isBlank());
    }

    @Test
    void стадиюМожноПрочитатьИзКоманды() {
        assertEquals(DrunkStage.WASTED, DrunkSpeech.stageOf("wasted"));
        assertEquals(DrunkStage.QUEASY, DrunkSpeech.stageOf(" Укачало "), "по-русски тоже можно");
        assertNull(DrunkSpeech.stageOf("ром"));
        assertNull(DrunkSpeech.stageOf(null));
    }

    /** Сколько разных вариантов фразы даёт стадия на сорока зёрнах. */
    private static int variety(DrunkStage stage) {
        Set<String> texts = new HashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            texts.add(DrunkSpeech.slur(MESSAGE, stage, seed));
        }
        return texts.size();
    }
}
