package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void картиныИОбычныеБлокиНеЯвляютсяКастомнымиПредметамиДляРамок() {
        assertFalse(FrameVeil.isPluginCustomItem(null));
        assertFalse(FrameVeil.isPluginCustomItem(new ItemStack(Material.AIR)));
        assertFalse(FrameVeil.isPluginCustomItem(new ItemStack(Material.STONE)));
        // Картины с особой моделью (и любые картины) исключены из правила отображения
        assertFalse(FrameVeil.isPluginCustomItem(new ItemStack(Material.PAINTING)));
    }

    @Test
    void обновлениеМоделиБезопасноДляНеКартин() {
        assertFalse(FrameVeil.updatePaintingModel(null));
        assertFalse(FrameVeil.updatePaintingModel(new ItemStack(Material.STONE)));
    }
}
