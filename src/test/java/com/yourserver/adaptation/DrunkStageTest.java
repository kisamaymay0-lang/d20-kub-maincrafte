package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Таблица стадий опьянения из {@code docs/OPJANENIE.md}: пороги, пересчёт под другой
 * размер шкалы и подписи. Никакого сервера — только числа.
 */
final class DrunkStageTest {

    @Test
    void порогиСтадийНаЭталоннойШкале() {
        assertEquals(DrunkStage.SOBER, DrunkStage.of(0));
        assertEquals(DrunkStage.TIPSY, DrunkStage.of(1), "первая единица — уже навеселе");
        assertEquals(DrunkStage.TIPSY, DrunkStage.of(7));
        assertEquals(DrunkStage.TALKATIVE, DrunkStage.of(8));
        assertEquals(DrunkStage.TALKATIVE, DrunkStage.of(16));
        assertEquals(DrunkStage.SWAYING, DrunkStage.of(17));
        assertEquals(DrunkStage.SWAYING, DrunkStage.of(26));
        assertEquals(DrunkStage.QUEASY, DrunkStage.of(27));
        assertEquals(DrunkStage.QUEASY, DrunkStage.of(36));
        assertEquals(DrunkStage.WASTED, DrunkStage.of(37));
        assertEquals(DrunkStage.WASTED, DrunkStage.of(45));
        assertEquals(DrunkStage.EDGE, DrunkStage.of(46));
        assertEquals(DrunkStage.EDGE, DrunkStage.of(49));
        assertEquals(DrunkStage.BLACKOUT, DrunkStage.of(50));
        assertEquals(DrunkStage.BLACKOUT, DrunkStage.of(999), "выше шкалы — всё равно отключка");
        assertEquals(DrunkStage.SOBER, DrunkStage.of(-5), "минус — это трезв");
    }

    @Test
    void блеватьТянетВсёЧащеСКаждойСтадией() {
        assertEquals(0.0, DrunkStage.SOBER.pukeChance());
        double previous = DrunkStage.SOBER.pukeChance();
        for (DrunkStage stage : DrunkStage.values()) {
            if (stage == DrunkStage.SOBER) continue;
            assertTrue(stage.pukeChance() > previous, "частота растёт: " + stage);
            previous = stage.pukeChance();
        }
        assertTrue(DrunkStage.TIPSY.pukeChance() > 0, "уже на первой стадии может стошнить");
        assertTrue(DrunkStage.EDGE.pukeChance() >= 1.0, "на «Краю» — каждую секунду");
        assertTrue(DrunkStage.BLACKOUT.pukeChance() > 1.0, "в отключке — даже чаще секунды");
    }

    @Test
    void другаяШкалаПересчитываетПороги() {
        // 30 из 60 — это те же 25 из 50, то есть «Шатает».
        assertEquals(DrunkStage.SWAYING, DrunkStage.of(30, 60));
        assertEquals(DrunkStage.SWAYING, DrunkStage.of(15, 30));
        assertEquals(DrunkStage.BLACKOUT, DrunkStage.of(60, 60));
        assertEquals(DrunkStage.SOBER, DrunkStage.of(0, 60));
        // 8 из 100 — это 4 из 50: ещё «навеселе».
        assertEquals(DrunkStage.TIPSY, DrunkStage.of(8, 100));
        // Нулевая шкала не должна ронять расчёт.
        assertEquals(DrunkStage.BLACKOUT, DrunkStage.of(50, 0));
    }

    @Test
    void стадииСравниваютсяПоСиле() {
        assertTrue(DrunkStage.WASTED.atLeast(DrunkStage.QUEASY));
        assertTrue(DrunkStage.BLACKOUT.atLeast(DrunkStage.SOBER));
        assertFalse(DrunkStage.SOBER.atLeast(DrunkStage.TIPSY));
        assertFalse(DrunkStage.SWAYING.atLeast(DrunkStage.WASTED));
    }

    @Test
    void подписиИИдентификаторы() {
        assertEquals("Трезв", DrunkStage.SOBER.title());
        assertEquals("Навеселе", DrunkStage.TIPSY.title());
        assertEquals("Разговорило", DrunkStage.TALKATIVE.title());
        assertEquals("Шатает", DrunkStage.SWAYING.title());
        assertEquals("Укачало", DrunkStage.QUEASY.title());
        assertEquals("В стельку", DrunkStage.WASTED.title());
        assertEquals("Край", DrunkStage.EDGE.title());
        assertEquals("Отключка", DrunkStage.BLACKOUT.title());

        assertEquals("wasted", DrunkStage.WASTED.id());
        assertEquals(45, DrunkStage.WASTED.to());
        assertEquals(37, DrunkStage.WASTED.from());
        assertEquals(50, DrunkStage.MAX_POINTS);
    }
}
