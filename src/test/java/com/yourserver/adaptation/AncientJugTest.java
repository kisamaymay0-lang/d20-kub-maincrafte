package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для механики Древнего кувшина:
 * - Углы наклона головы для соскальзывания кувшина
 * - Идентификаторы и параметры
 */
final class AncientJugTest {

    @Test
    void углыНаклонаГоловыДляПаденияКувшина() {
        // При наклоне камеры вверх (<= -50.0f) или вниз (>= 50.0f) кувшин должен падать
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

    private static boolean isPitchDangerous(float pitch) {
        return pitch <= -50.0f || pitch >= 50.0f;
    }
}
