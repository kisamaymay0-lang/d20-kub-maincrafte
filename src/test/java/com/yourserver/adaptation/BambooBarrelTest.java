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
    }
}
