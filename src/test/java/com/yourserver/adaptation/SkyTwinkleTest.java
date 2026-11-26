package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мерцание звёзд: своё у каждой, медленное, и одинаковое от запуска к запуску. */
final class SkyTwinkleTest {

    @Test
    void характерЗвездыПовторяетсяОтЗапускаКЗапуску() {
        assertEquals(SkyTwinkle.sizeFactor("alpha:1"), SkyTwinkle.sizeFactor("alpha:1"));
        assertEquals(SkyTwinkle.phase("alpha:1"), SkyTwinkle.phase("alpha:1"));
        assertEquals(SkyTwinkle.brightness("alpha:1"), SkyTwinkle.brightness("alpha:1"));
        // Разные звёзды — разный характер.
        assertNotEquals(SkyTwinkle.phase("alpha:1"), SkyTwinkle.phase("alpha:2"));
    }

    @Test
    void размерИЯркостьВРазумныхПределах() {
        for (String key : new String[]{"a:1", "b:2", "constellation:star-3", "x", "testpack:star"}) {
            float size = SkyTwinkle.sizeFactor(key);
            assertTrue(size >= SkyTwinkle.MIN_SIZE - 1e-6 && size <= SkyTwinkle.MAX_SIZE + 1e-6,
                    key + ": размер " + size);
            int brightness = SkyTwinkle.brightness(key);
            assertTrue(brightness >= 13 && brightness <= 15, key + ": яркость " + brightness);
            assertTrue(SkyTwinkle.periodSeconds(key) >= 5.0 && SkyTwinkle.periodSeconds(key) <= 14.0);
            assertTrue(SkyTwinkle.amplitude(key) > 0.05f && SkyTwinkle.amplitude(key) < 0.12f);
        }
    }

    @Test
    void мерцаниеНеМеняетРазмерБольшеЧемНаАмплитуду() {
        String key = "alpha:1";
        float phase = SkyTwinkle.phase(key);
        double period = SkyTwinkle.periodSeconds(key);
        float amplitude = SkyTwinkle.amplitude(key);

        for (double seconds = 0; seconds < 60; seconds += 0.25) {
            float pulse = SkyTwinkle.pulse(phase, period, amplitude, seconds);
            assertTrue(Math.abs(pulse - 1f) <= amplitude + 1e-5, "пульс " + pulse);
        }
    }

    @Test
    void мерцаниеМедленноеАНеМигание() {
        String key = "alpha:7";
        float phase = SkyTwinkle.phase(key);
        double period = SkyTwinkle.periodSeconds(key);
        float amplitude = SkyTwinkle.amplitude(key);

        // За один серверный тик размер меняется меньше чем на процент.
        float before = SkyTwinkle.pulse(phase, period, amplitude, 10.0);
        float after = SkyTwinkle.pulse(phase, period, amplitude, 10.05);
        assertTrue(Math.abs(after - before) < 0.01f, "скачок " + Math.abs(after - before));
    }
}
