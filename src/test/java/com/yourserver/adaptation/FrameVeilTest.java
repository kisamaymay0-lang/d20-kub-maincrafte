package com.yourserver.adaptation;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для механик рамок и картин FrameVeil:
 * - Модели картин «Не курить 1» и «Не курить 2»
 * - Исключение картин из правила отображения названий кастомных предметов
 */
final class FrameVeilTest {

    @Test
    void идентификаторыМоделейКартинНеКурить() {
        assertEquals("f8resurs:dont_smoke", FrameVeil.DONT_SMOKE_KEY.asString());
        assertEquals("f8resurs:dont_smoke2", FrameVeil.DONT_SMOKE_2_KEY.asString());
    }

    @Test
    void определениеМоделиКартиныПоНазванию() {
        assertEquals(FrameVeil.DONT_SMOKE_KEY, FrameVeil.paintingModelForName("Не курить 1"));
        assertEquals(FrameVeil.DONT_SMOKE_KEY, FrameVeil.paintingModelForName("не курить 1"));
        assertEquals(FrameVeil.DONT_SMOKE_2_KEY, FrameVeil.paintingModelForName("Не курить 2"));
        assertEquals(FrameVeil.DONT_SMOKE_2_KEY, FrameVeil.paintingModelForName("  Не курить 2  "));

        assertNull(FrameVeil.paintingModelForName("Обычная картина"));
        assertNull(FrameVeil.paintingModelForName(""));
        assertNull(FrameVeil.paintingModelForName(null));
    }

    @Test
    void картиныИОбычныеБлокиИсключеныИзОтображенияНазванийВРамке() {
        assertTrue(FrameVeil.isExcludedFromFrameHover(Material.PAINTING));
        assertTrue(FrameVeil.isExcludedFromFrameHover(Material.AIR));
        assertTrue(FrameVeil.isExcludedFromFrameHover(null));

        assertFalse(FrameVeil.isExcludedFromFrameHover(Material.DIRT));
        assertFalse(FrameVeil.isExcludedFromFrameHover(Material.STONE));
        assertFalse(FrameVeil.isExcludedFromFrameHover(Material.IRON_NUGGET));
    }

    @Test
    void nullПредметНеКастомный() {
        assertFalse(FrameVeil.isPluginCustomItem(null));
        assertFalse(FrameVeil.updatePaintingModel(null));
    }
}
