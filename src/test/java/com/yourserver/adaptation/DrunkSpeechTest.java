package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пьяная речь: на ранних стадиях текст не трогаем, на поздних он портится, а на
 * «отключке» остаётся одно многоточие.
 */
final class DrunkSpeechTest {

    private static final String MESSAGE = "Пойдём со мной на корабль, там ром";

    @Test
    void трезвыйИНавеселеГоворятКакОбычноВсегда() {
        assertEquals(MESSAGE, DrunkSpeech.slur(MESSAGE, DrunkStage.SOBER, 1L));
        assertEquals(MESSAGE, DrunkSpeech.slur(MESSAGE, DrunkStage.TIPSY, 1L));
    }

    @Test
    void отключкаОставляетТолькоМноготочие() {
        assertEquals("…", DrunkSpeech.slur(MESSAGE, DrunkStage.BLACKOUT, 1L));
    }

    @Test
    void разговорилоПортитОдноСловоИногдаСИк() {
        String text = DrunkSpeech.slur(MESSAGE, DrunkStage.TALKATIVE, new Random(7));
        assertNotNull(text);
        assertFalse(text.isBlank());
        // Либо слово испорчено, либо в конце «ик!» — иначе стадия ничего не значит.
        assertTrue(!text.equals(MESSAGE) || text.endsWith("ик!"), text);
        // Количество слов не уменьшается: добавляется только «ик!».
        assertTrue(text.split("\\s+").length >= MESSAGE.split("\\s+").length, text);
    }

    @Test
    void вСтелькуРечьСильноПортится() {
        boolean broken = false;
        for (long seed = 1; seed <= 20; seed++) {
            String text = DrunkSpeech.slur(MESSAGE, DrunkStage.WASTED, seed);
            if (!text.equals(MESSAGE)) broken = true;
            assertFalse(text.isBlank(), "даже сильно пьяный не молчит");
        }
        assertTrue(broken, "хоть одно сообщение из двадцати должно испортиться");
    }

    @Test
    void укачалоИногдаОставляетМноготочие() {
        boolean silence = false;
        for (long seed = 1; seed <= 50; seed++) {
            if ("…".equals(DrunkSpeech.slur(MESSAGE, DrunkStage.QUEASY, seed))) silence = true;
        }
        assertTrue(silence, "на «укачало» игрок иногда мычит вместо слов");
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
}
