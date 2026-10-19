package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BambooBarrelTest {

    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("Базовые качества по годам выдержки")
    void testBaseQualitiesByYears() {
        // 0-1 год: Ужасное! ☠
        assertEquals(BambooBarrel.QualityTier.TERRIBLE, BambooBarrel.getBaseQuality(0));
        assertEquals(BambooBarrel.QualityTier.TERRIBLE, BambooBarrel.getBaseQuality(1));

        // 2-3 года: Странное...
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getBaseQuality(2));
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getBaseQuality(3));

        // 4-5 лет: Хорошее ☺
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getBaseQuality(4));
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getBaseQuality(5));

        // 6-9 лет: Идеальное! ☀
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getBaseQuality(6));
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getBaseQuality(7));
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getBaseQuality(8));
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getBaseQuality(9));

        // 10 лет: БЕЗУПРЕЧНОЕ!!1
        assertEquals(BambooBarrel.QualityTier.FLAWLESS, BambooBarrel.getBaseQuality(10));

        // 11-13 лет: Хорошее ☺
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getBaseQuality(11));
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getBaseQuality(12));
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getBaseQuality(13));

        // 14+ лет: Странное...
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getBaseQuality(14));
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getBaseQuality(20));
    }

    @Test
    @DisplayName("Штрафы от переливаний")
    void testTransfersPenalties() {
        // 0-1 переливание: без изменений
        assertEquals(BambooBarrel.QualityTier.FLAWLESS, BambooBarrel.getQuality(10, 0));
        assertEquals(BambooBarrel.QualityTier.FLAWLESS, BambooBarrel.getQuality(10, 1));
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getQuality(7, 1));

        // 2-3 переливания: -1 ступень
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getQuality(10, 2));
        assertEquals(BambooBarrel.QualityTier.PERFECT, BambooBarrel.getQuality(10, 3));
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getQuality(7, 2));

        // 4-5 переливаний: -2 ступени
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getQuality(10, 4));
        assertEquals(BambooBarrel.QualityTier.GOOD, BambooBarrel.getQuality(10, 5));
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getQuality(7, 4));

        // 5+ (6+) переливаний: падает до Странного
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getQuality(10, 6));
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getQuality(10, 10));

        // Переливание не повышает качество у Ужасного и не опускает ниже Странного
        assertEquals(BambooBarrel.QualityTier.TERRIBLE, BambooBarrel.getQuality(0, 5));
        assertEquals(BambooBarrel.QualityTier.TERRIBLE, BambooBarrel.getQuality(0, 10));
        assertEquals(BambooBarrel.QualityTier.STRANGE, BambooBarrel.getQuality(4, 10));
    }

    @Test
    @DisplayName("Описания для качеств (строка 6)")
    void testDescriptions() {
        assertEquals("Молодой сивушный спирт. Обжигает горло.",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.TERRIBLE, 0));

        assertEquals("Вкус еще только формируется...",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.STRANGE, 2));

        assertEquals("Уже не ром, а жидкость со вкусом старой доски.",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.STRANGE, 14));

        assertEquals("Добротный пиратский ром, пик для будней на корабле!",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.GOOD, 4));

        assertEquals("Передержали. Ром становится горьким и древесным",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.GOOD, 12));

        assertEquals("Ром идеален, вкус приятно чешет горло.",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.PERFECT, 8));

        assertEquals("Теперь ты можешь называть себя настоящим пиратом!",
                BambooBarrel.getQualityDescription(BambooBarrel.QualityTier.FLAWLESS, 10));
    }

    @Test
    @DisplayName("Формирование лора рома из 6 строк")
    void testRumLoreStructure() {
        List<Component> lore = BambooBarrel.buildRumLore(10, 1);
        assertEquals(6, lore.size());

        assertEquals("----------------------", plain.serialize(lore.get(0)));
        assertTrue(plain.serialize(lore.get(1)).startsWith("Качество: "));
        assertTrue(plain.serialize(lore.get(1)).contains("БЕЗУПРЕЧНОЕ!!1"));
        assertEquals("----------------------", plain.serialize(lore.get(2)));
        assertEquals("Лет: 10", plain.serialize(lore.get(3)));
        assertEquals("Переливаний: 1", plain.serialize(lore.get(4)));
        assertEquals("Теперь ты можешь называть себя настоящим пиратом!", plain.serialize(lore.get(5)));
    }

    @Test
    @DisplayName("Цвета и стили качества")
    void testQualityNamesAndFormatting() {
        Component flawless = BambooBarrel.formatQualityName(BambooBarrel.QualityTier.FLAWLESS);
        assertTrue(plain.serialize(flawless).contains("БЕЗУПРЕЧНОЕ!!1"));

        Component perfect = BambooBarrel.formatQualityName(BambooBarrel.QualityTier.PERFECT);
        assertTrue(plain.serialize(perfect).contains("Идеальное! ☀"));

        Component good = BambooBarrel.formatQualityName(BambooBarrel.QualityTier.GOOD);
        assertTrue(plain.serialize(good).contains("Хорошее ☺"));

        Component strange = BambooBarrel.formatQualityName(BambooBarrel.QualityTier.STRANGE);
        assertTrue(plain.serialize(strange).contains("Странное..."));

        Component terrible = BambooBarrel.formatQualityName(BambooBarrel.QualityTier.TERRIBLE);
        assertTrue(plain.serialize(terrible).contains("Ужасное! ☠"));
    }

    @Test
    @DisplayName("Настройка времени 1 года выдержки и вместимость бочки")
    void testConfiguredTicksPerYearAndCapacity() {
        assertEquals(24000L, BambooBarrel.DEFAULT_TICKS_PER_YEAR);
        assertEquals(9, BambooBarrel.MAX_BOTTLES);

        BambooBarrel.setTicksPerYear(12000L);
        assertEquals(12000L, BambooBarrel.getTicksPerYear());

        // Восстанавливаем дефолтное значение
        BambooBarrel.setTicksPerYear(BambooBarrel.DEFAULT_TICKS_PER_YEAR);
        assertEquals(24000L, BambooBarrel.getTicksPerYear());
    }
}
