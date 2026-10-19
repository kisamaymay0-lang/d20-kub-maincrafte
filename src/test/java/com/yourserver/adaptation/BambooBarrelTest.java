package com.yourserver.adaptation;

<<<<<<< HEAD
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты функционала бамбуковой бочки:
 * - Модель пиратского рома skull_poilo
 * - Время брожения (10 секунд = 200 тиков)
 * - Рецепт: 1 уголь, 1 тростник, 3 горячей воды
 * - Проверка блоков мультиблочной структуры
 * - Проверка логики состояния бочки, забора предметов через Shift + ПКМ и сброса ингредиентов
 */
final class BambooBarrelTest {

    @Test
    void модельПиратскогоРома() {
        assertEquals("skull_poilo", BambooBarrel.MODEL_PIRATE_RUM_KEY.getKey());
        assertEquals("f8resurs:skull_poilo", BambooBarrel.MODEL_PIRATE_RUM_KEY.asString());
        assertEquals("adaptation:pirate_rum", BambooBarrel.PIRATE_RUM_KEY.asString());
    }

    @Test
    void времяБроженияДесятьСекунд() {
        assertEquals(200, BambooBarrel.BREWING_TIME_TICKS);
    }

    @Test
    void проверкаБлоковМультиблока() {
        // Стенки
        assertTrue(BambooBarrel.isBarrelWall(Material.STRIPPED_BAMBOO_BLOCK));
        assertTrue(BambooBarrel.isBarrelWall(Material.BAMBOO_BLOCK));
        assertFalse(BambooBarrel.isBarrelWall(Material.OAK_PLANKS));

        // Ступени
        assertTrue(BambooBarrel.isBarrelStairs(Material.BAMBOO_STAIRS));
        assertTrue(BambooBarrel.isBarrelStairs(Material.BAMBOO_MOSAIC_STAIRS));
        assertFalse(BambooBarrel.isBarrelStairs(Material.OAK_STAIRS));

        // Полость строго пустая
        assertTrue(BambooBarrel.isCavity(Material.AIR));
        assertTrue(BambooBarrel.isCavity(Material.CAVE_AIR));
        assertTrue(BambooBarrel.isCavity(Material.VOID_AIR));
        assertFalse(BambooBarrel.isCavity(Material.WATER));
        assertFalse(BambooBarrel.isCavity(Material.STONE));
        assertFalse(BambooBarrel.isCavity(Material.BARREL));

        // Проверка допустимых блоков структуры бочки
        assertFalse(BambooBarrel.isBambooBarrelBlock(null));
        assertFalse(BambooBarrel.isBarrelWall(Material.BARREL));
    }

    @Test
    void проверкаСостоянияБочки() {
        BambooBarrel.BarrelState state = new BambooBarrel.BarrelState();
        assertTrue(state.isEmpty());

        state.coalCount = 1;
        state.caneCount = 1;
        state.hotWaterCount = 3;
        assertFalse(state.isEmpty());

        // Рецепт: ровно 1 уголь, 1 сахарный тростник, 3 бутылки горячей воды
        boolean canBrew = state.coalCount >= 1 && state.caneCount >= 1 && state.hotWaterCount >= 3;
        assertTrue(canBrew);

        // Начало брожения
        state.coalCount = 0;
        state.caneCount = 0;
        state.hotWaterCount = 0;
        state.brewing = true;
        state.brewingTicks = BambooBarrel.BREWING_TIME_TICKS;
        assertFalse(state.isEmpty());
        assertTrue(state.brewing);
        assertEquals(200, state.brewingTicks);

        // Завершение брожения
        state.brewing = false;
        state.brewingTicks = 0;
        state.ready = true;
        assertFalse(state.isEmpty());
        assertTrue(state.ready);

        // Забор напитка
        state.ready = false;
        assertTrue(state.isEmpty());
    }

    @Test
    void проверкаИзвлеченияИнгредиентовShiftRMB() {
        BambooBarrel.BarrelState state = new BambooBarrel.BarrelState();
        state.coalCount = 1;
        state.caneCount = 1;
        state.hotWaterCount = 2;

        // 1. Извлечение сахарного тростника
        state.caneCount--;
        assertEquals(0, state.caneCount);
        assertFalse(state.isEmpty());

        // 2. Извлечение угля
        state.coalCount--;
        assertEquals(0, state.coalCount);
        assertFalse(state.isEmpty());

        // 3. Извлечение горячей воды пустой бутылочкой
        state.hotWaterCount--;
        assertEquals(1, state.hotWaterCount);
        state.hotWaterCount--;
        assertEquals(0, state.hotWaterCount);

        assertTrue(state.isEmpty());
=======
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
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
    @DisplayName("Создание предмета рома с хранилищем (3 глотка) и PDC")
    void testPirateRumCreation() {
        ItemStack rum = BambooBarrel.createPirateRum(5, 2, 3);
        assertTrue(BambooBarrel.isPirateRum(rum));
        assertEquals(5, BambooBarrel.getRumYears(rum));
        assertEquals(2, BambooBarrel.getRumTransfers(rum));
        assertEquals(3, BambooBarrel.getRumCharges(rum));
>>>>>>> d95ee00 (feat(rum): add pirate rum qualities, barrel aging, decanting penalties, 3 charges and fix item frame name reset)
    }
}
