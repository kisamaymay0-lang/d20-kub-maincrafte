package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Частица ноты обязана совпадать со звуком: цвет клиент берёт из смещения,
 * а смещение — из номера ноты, посчитанного по высоте.
 */
final class NoteTonesTest {

    private static final double EPS = 1e-9;

    @Test
    void ровныйPitch_этоНотаДвенадцать() {
        assertEquals(12, NoteTones.noteId(1.0f));
        assertEquals(0.5, NoteTones.offset(1.0f), EPS);
    }

    @Test
    void полутонВверхИВниз_даётСоседниеНоты() {
        // Ровно полутон вверх: 2^(1/12) ≈ 1.059463.
        assertEquals(13, NoteTones.noteId((float) Math.pow(2.0, 1.0 / 12.0)));
        assertEquals(11, NoteTones.noteId((float) Math.pow(2.0, -1.0 / 12.0)));
    }

    @Test
    void голосаПоследовательности_попадаютВСвоиНоты() {
        // Голоса медного блока: 1.2 / 1.0 / 0.79 / 0.63 — те же, что играет плагин.
        assertEquals(15, NoteTones.noteId(1.2f));
        assertEquals(12, NoteTones.noteId(1.0f));
        assertEquals(8, NoteTones.noteId(0.79f));
        assertEquals(4, NoteTones.noteId(0.63f));
    }

    @Test
    void голосаРазныхЦветов() {
        double high = NoteTones.offset(1.2f);
        double mid = NoteTones.offset(1.0f);
        double low = NoteTones.offset(0.79f);
        double sub = NoteTones.offset(0.63f);
        assertTrue(high > mid && mid > low && low > sub, "У каждого голоса свой цвет");
        assertTrue(sub >= 0.0 && high <= 1.0, "Смещение всегда внутри 0…1");
    }

    @Test
    void краяДиапазона_обрезаются() {
        assertEquals(NoteTones.MAX_NOTE, NoteTones.noteId(100.0f));
        assertEquals(NoteTones.MIN_NOTE, NoteTones.noteId(0.01f));
        assertEquals(1.0, NoteTones.offset(999), EPS);
        assertEquals(0.0, NoteTones.offset(-999), EPS);
    }

    @Test
    void немузыкальныйPitch_неЛомаетЧастицу() {
        assertEquals(NoteTones.TUNING_NOTE, NoteTones.noteId(0.0f));
        assertEquals(NoteTones.TUNING_NOTE, NoteTones.noteId(-1.0f));
        assertEquals(NoteTones.TUNING_NOTE, NoteTones.noteId(Float.NaN));
        assertEquals(NoteTones.TUNING_NOTE, NoteTones.noteId(Float.POSITIVE_INFINITY));
        assertEquals(0.5, NoteTones.offset(0.0f), EPS);
    }
}
