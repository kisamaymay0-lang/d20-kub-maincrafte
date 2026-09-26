package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Уровень блока света идёт вниз, а с нуля круг начинается заново. */
final class LightBlocksTest {

    @Test
    void каждыйШагНижеНаОдин() {
        assertEquals(14, LightBlocks.nextLevel(15));
        assertEquals(13, LightBlocks.nextLevel(14));
        assertEquals(1, LightBlocks.nextLevel(2));
        assertEquals(0, LightBlocks.nextLevel(1));
    }

    @Test
    void сНуляСветВозвращаетсяКПолному() {
        assertEquals(LightBlocks.MAX_LEVEL, LightBlocks.nextLevel(0));
    }

    @Test
    void кругЗамкнут() {
        int level = LightBlocks.MAX_LEVEL;
        for (int step = 0; step < 15; step++) level = LightBlocks.nextLevel(level);
        assertEquals(0, level, "Пятнадцать шагов — и свет погашен");
        assertEquals(15, LightBlocks.nextLevel(level), "Следующий шаг зажигает снова");
    }

    @Test
    void чужойУровеньПриводитсяККраю() {
        assertEquals(14, LightBlocks.nextLevel(999));
        assertEquals(LightBlocks.MAX_LEVEL, LightBlocks.nextLevel(-5));
    }
}
