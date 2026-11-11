package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ступени окисления медного нотного блока: подписи, воск, расстройка и паузы. */
class CopperNoteStagesTest {

    private static final double BASE_PITCH = 0.79D;

    @Test
    void stagesAreLabelledLikeTheModels() {
        assertEquals("1s", CopperNoteStages.label(1));
        assertEquals("2s", CopperNoteStages.label(2));
        assertEquals("3s", CopperNoteStages.label(3));
        assertEquals("4s", CopperNoteStages.label(4));
        assertEquals("обычный медный", CopperNoteStages.title(1));
        assertEquals("потемневший", CopperNoteStages.title(2));
        assertEquals("состаренный", CopperNoteStages.title(3));
        assertEquals("окисленный", CopperNoteStages.title(4));
        // Обе функции обязаны пережить мусор на входе, а не упасть на индексе.
        assertEquals("1s", CopperNoteStages.label(-5));
        assertEquals("4s", CopperNoteStages.label(99));
    }

    @Test
    void stagesStepOneAtATimeAndStopAtTheEdges() {
        assertEquals(2, CopperNoteStages.next(1));
        assertEquals(4, CopperNoteStages.next(4), "дальше окисленного медь не идёт");
        assertEquals(1, CopperNoteStages.previous(1), "обычная медь уже не темнеет");
        assertEquals(3, CopperNoteStages.previous(4));
    }

    @Test
    void waxStopsOxidationAndTheLastStageHasNowhereToGo() {
        assertTrue(CopperNoteStages.canOxidize(1, false));
        assertTrue(CopperNoteStages.canOxidize(3, false));
        assertFalse(CopperNoteStages.canOxidize(4, false), "окисленная медь — последняя ступень");
        assertFalse(CopperNoteStages.canOxidize(1, true), "воск останавливает окисление");
        assertFalse(CopperNoteStages.canOxidize(2, true));
    }

    @Test
    void theCleanerTheCopperTheTighterTheTuning() {
        Random random = new Random(42L);
        double plain = maxDeviation(1, random);
        double exposed = maxDeviation(2, random);
        double weathered = maxDeviation(3, random);

        // Ровно ноль не получить: полутона считаются через логарифм, и на
        // одинаковых float остаётся пыль порядка 1e-7 полутона.
        assertEquals(0.0D, plain, 1e-4, "обычная медь держит строй точно");
        assertTrue(exposed > 0.0D, "потемневшая медь уже расстроена");
        assertTrue(weathered > exposed, "состаренная расстроена сильнее потемневшей");
        assertTrue(weathered <= CopperNoteStages.SPREAD_SEMITONES[2] + 1e-9,
                "разброс не должен выходить за свой предел");
    }

    @Test
    void theOxidizedBlockHasNoTuningAtAll() {
        Random random = new Random(7L);
        Set<Float> pitches = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            float pitch = CopperNoteStages.detunedPitch(4, (float) BASE_PITCH, random);
            assertTrue(pitch >= CopperNoteStages.MIN_PITCH && pitch <= CopperNoteStages.MAX_PITCH,
                    "высота обязана оставаться в пределах нотного блока: " + pitch);
            pitches.add(pitch);
        }

        assertTrue(pitches.size() > 400, "каждая нота — новая случайная высота");
        assertNotEquals(Set.of((float) BASE_PITCH), pitches, "строгого строя у окисленного нет");
    }

    @Test
    void onlyTheOxidizedBlockDelaysItsNotes() {
        Random random = new Random(3L);
        Set<Integer> delays = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            assertEquals(0, CopperNoteStages.noteDelayTicks(1, random));
            assertEquals(0, CopperNoteStages.noteDelayTicks(2, random));
            assertEquals(0, CopperNoteStages.noteDelayTicks(3, random));
            delays.add(CopperNoteStages.noteDelayTicks(4, random));
        }

        assertEquals(Set.of(CopperNoteStages.LOW_DELAY_TICKS, CopperNoteStages.HIGH_DELAY_TICKS), delays,
                "у окисленного блока задержка 0.1 или 0.2 секунды");
        assertEquals(2, CopperNoteStages.LOW_DELAY_TICKS);
        assertEquals(4, CopperNoteStages.HIGH_DELAY_TICKS);
    }

    @Test
    void pitchNeverLeavesTheNoteBlockRange() {
        Random random = new Random(11L);

        for (int stage = 1; stage <= CopperNoteStages.LAST; stage++) {
            for (float base : new float[]{0.01f, 0.5f, 1.0f, 2.0f, 12.0f}) {
                for (int i = 0; i < 50; i++) {
                    float pitch = CopperNoteStages.detunedPitch(stage, base, random);
                    assertTrue(pitch >= CopperNoteStages.MIN_PITCH
                                    && pitch <= CopperNoteStages.MAX_PITCH,
                            "ступень " + stage + ", база " + base + " -> " + pitch);
                }
            }
        }

        assertEquals(CopperNoteStages.MIN_PITCH, CopperNoteStages.clampPitch(Float.NaN));
        assertEquals(CopperNoteStages.MIN_PITCH, CopperNoteStages.clampPitch(0.0f));
    }

    /** Наибольший уход от базовой высоты в полутонах за 2000 нот. */
    private static double maxDeviation(int stage, Random random) {
        double max = 0.0D;
        for (int i = 0; i < 2000; i++) {
            float pitch = CopperNoteStages.detunedPitch(stage, (float) BASE_PITCH, random);
            double semitones = 12.0D * (Math.log(pitch / BASE_PITCH) / Math.log(2.0D));
            max = Math.max(max, Math.abs(semitones));
        }
        return max;
    }
}
