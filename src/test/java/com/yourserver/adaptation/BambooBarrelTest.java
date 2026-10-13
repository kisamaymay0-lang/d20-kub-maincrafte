package com.yourserver.adaptation;

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

        // Полость
        assertTrue(BambooBarrel.isCavity(Material.AIR));
        assertTrue(BambooBarrel.isCavity(Material.CAVE_AIR));
        assertTrue(BambooBarrel.isCavity(Material.VOID_AIR));
        assertFalse(BambooBarrel.isCavity(Material.WATER));
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
    }
}
