package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Тесты для моделей картин при переименовании:
 * - «Не курить 1» -> f8resurs:dont_smoke
 * - «Не курить 2» -> f8resurs:dont_smoke2
 */
final class PaintingListenerTest {

    @Test
    void идентификаторыМоделейКартинНеКурить() {
        assertEquals("f8resurs:dont_smoke", PaintingListener.DONT_SMOKE_KEY.asString());
        assertEquals("f8resurs:dont_smoke2", PaintingListener.DONT_SMOKE_2_KEY.asString());
    }

    @Test
    void определениеМоделиКартиныПоНазванию() {
        assertEquals(PaintingListener.DONT_SMOKE_KEY, PaintingListener.paintingModelForName("Не курить 1"));
        assertEquals(PaintingListener.DONT_SMOKE_KEY, PaintingListener.paintingModelForName("не курить 1"));
        assertEquals(PaintingListener.DONT_SMOKE_2_KEY, PaintingListener.paintingModelForName("Не курить 2"));
        assertEquals(PaintingListener.DONT_SMOKE_2_KEY, PaintingListener.paintingModelForName("  Не курить 2  "));

        assertNull(PaintingListener.paintingModelForName("Обычная картина"));
        assertNull(PaintingListener.paintingModelForName(""));
        assertNull(PaintingListener.paintingModelForName(null));
    }

    @Test
    void nullПредметНеОбновляется() {
        assertFalse(PaintingListener.updatePaintingModel(null));
    }
}
