package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пьяная походка: покачивание начинается не с первой стадии, растёт вместе с ней,
 * а поворот курса не меняет скорость — только заворачивает путь в дугу.
 */
final class DrunkWalkTest {

    @Test
    void наТрезвуюИНавеселеПокачиванияНет() {
        assertFalse(DrunkWalk.sways(DrunkStage.SOBER));
        assertFalse(DrunkWalk.sways(DrunkStage.TIPSY));
        assertEquals(0.0, DrunkWalk.sway(DrunkStage.SOBER));
        assertEquals(0.0, DrunkWalk.sway(DrunkStage.TIPSY));
        assertTrue(DrunkWalk.sways(DrunkStage.TALKATIVE), "с «Разговорило» уже ведёт");
    }

    @Test
    void чемВышеСтадияТемШиреДуги() {
        DrunkStage[] ladder = {
                DrunkStage.TALKATIVE,
                DrunkStage.SWAYING,
                DrunkStage.QUEASY,
                DrunkStage.WASTED,
                DrunkStage.EDGE
        };
        for (int i = 1; i < ladder.length; i++) {
            assertTrue(DrunkWalk.sway(ladder[i]) > DrunkWalk.sway(ladder[i - 1]),
                    "покачивание растёт: " + ladder[i]);
            assertTrue(DrunkWalk.curve(ladder[i]) > DrunkWalk.curve(ladder[i - 1]),
                    "дуга круче: " + ladder[i]);
        }
        assertEquals(DrunkWalk.sway(DrunkStage.EDGE), DrunkWalk.sway(DrunkStage.BLACKOUT),
                "в отключке на ногах качает так же сильно");
    }

    @Test
    void стоящегоВедётТолькоНаВысокихСтадиях() {
        assertEquals(0.0, DrunkWalk.standingFactor(DrunkStage.SWAYING));
        assertEquals(0.0, DrunkWalk.standingFactor(DrunkStage.QUEASY));
        assertTrue(DrunkWalk.standingFactor(DrunkStage.WASTED) > 1.0,
                "с «В стельку» на месте не устоять");
        assertTrue(DrunkWalk.standingFactor(DrunkStage.EDGE) >= DrunkWalk.standingFactor(DrunkStage.WASTED));
    }

    @Test
    void поворотКурсаНеМеняетСкорость() {
        double[] turned = DrunkWalk.rotate(0.40, 0.00, Math.PI / 2);
        assertEquals(0.0, turned[0], 1e-9);
        assertEquals(0.40, turned[1], 1e-9);

        double[] still = DrunkWalk.rotate(0.12, -0.30, 0.0);
        assertEquals(0.12, still[0], 1e-9);
        assertEquals(-0.30, still[1], 1e-9);

        for (double angle = -0.4; angle <= 0.4; angle += 0.1) {
            double[] any = DrunkWalk.rotate(0.25, 0.10, angle);
            double before = Math.hypot(0.25, 0.10);
            assertEquals(before, Math.hypot(any[0], any[1]), 1e-9, "длина скорости сохраняется");
        }
    }
}
