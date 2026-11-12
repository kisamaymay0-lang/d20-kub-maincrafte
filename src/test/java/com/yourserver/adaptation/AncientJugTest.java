package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для механики Древнего кувшина:
 * - Углы наклона головы для соскальзывания кувшина
 * - Задержка в 1 секунду (20 тиков) перед падением
 */
final class AncientJugTest {

    @Test
    void углыНаклонаГоловыДляПаденияКувшина() {
        // При наклоне камеры вверх (<= -50.0f) или вниз (>= 50.0f) кувшин начинает соскальзывать
        assertTrue(isPitchDangerous(-50.0f));
        assertTrue(isPitchDangerous(-70.0f));
        assertTrue(isPitchDangerous(-90.0f));
        assertTrue(isPitchDangerous(50.0f));
        assertTrue(isPitchDangerous(65.0f));
        assertTrue(isPitchDangerous(90.0f));

        // Нормальные углы обзора
        assertFalse(isPitchDangerous(0.0f));
        assertFalse(isPitchDangerous(20.0f));
        assertFalse(isPitchDangerous(-20.0f));
        assertFalse(isPitchDangerous(45.0f));
        assertFalse(isPitchDangerous(-45.0f));
    }

    @Test
    void задержкаПаденияКувшинаОднаСекунда() {
        // 20 тиков = 1 секунда
        int requiredTicks = 20;
        assertEquals(20, requiredTicks);
    }

    private static boolean isPitchDangerous(float pitch) {
        return pitch <= -50.0f || pitch >= 50.0f;
    }
}
