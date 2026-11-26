package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** «Тотем медали»: кривая размера картинки и границы кадров. */
class MedalAnimationTest {

    @Test
    void картинкаВсплываетИРастворяется() {
        assertEquals(0f, MedalAnimation.scaleAt(0), 1e-6, "в первом кадре картинки ещё нет");
        assertEquals(0f, MedalAnimation.scaleAt(MedalAnimation.TICKS), 1e-6, "в последнем — уже нет");
        assertEquals(0f, MedalAnimation.scaleAt(-1), 1e-6);
        assertEquals(0f, MedalAnimation.scaleAt(MedalAnimation.TICKS + 5), 1e-6);
    }

    @Test
    void вСерединеКартинкаСамаяКрупная() {
        float middle = MedalAnimation.scaleAt(MedalAnimation.TICKS / 2);
        assertTrue(middle > 1.3f, "в середине медаль крупная: " + middle);
        assertTrue(MedalAnimation.scaleAt(3) < middle);
        assertTrue(MedalAnimation.scaleAt(MedalAnimation.TICKS - 3) < middle);
        assertTrue(MedalAnimation.scaleAt(1) > 0f, "уже во втором кадре что-то видно");
    }
}
