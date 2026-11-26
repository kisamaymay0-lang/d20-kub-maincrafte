package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Тесты для моделей плакатов при переименовании бумаги:
 * - «Не курить 1» -> f8resurs:dont_smoke
 * - «Не курить 2» -> f8resurs:dont_smoke2
 */
final class PaintingListenerTest {

    @Test
    void идентификаторыМоделейПлакатовНеКурить() {
        assertEquals("f8resurs:dont_smoke", PaintingListener.DONT_SMOKE_KEY.asString());
        assertEquals("f8resurs:dont_smoke2", PaintingListener.DONT_SMOKE_2_KEY.asString());
    }

    @Test
    void определениеМоделиПлакатаПоНазванию() {
        assertEquals(PaintingListener.DONT_SMOKE_KEY, PaintingListener.paintingModelForName("Не курить 1"));
        assertEquals(PaintingListener.DONT_SMOKE_KEY, PaintingListener.paintingModelForName("не курить 1"));
        assertEquals(PaintingListener.DONT_SMOKE_2_KEY, PaintingListener.paintingModelForName("Не курить 2"));
        assertEquals(PaintingListener.DONT_SMOKE_2_KEY, PaintingListener.paintingModelForName("  Не курить 2  "));

        assertNull(PaintingListener.paintingModelForName("Обычная бумага"));
        assertNull(PaintingListener.paintingModelForName(""));
        assertNull(PaintingListener.paintingModelForName(null));
    }

    @Test
    void nullПредметНеОбновляется() {
        assertFalse(PaintingListener.updatePaintingModel(null));
    }
}
