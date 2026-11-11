package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ступени окисления медного нотного блока: названия, воск, фальшь и паузы. */
class CopperNoteStagesTest {

    private static final float BASE_PITCH = 0.79f;
    private static final int SAMPLES = 4000;

    @Test
    void stagesAreLabelledLikeTheModels() {
        assertEquals("1s", CopperNoteStages.label(1));
        assertEquals("2s", CopperNoteStages.label(2));
        assertEquals("3s", CopperNoteStages.label(3));
        assertEquals("4s", CopperNoteStages.label(4));
        assertEquals("1s_copper_note_block", CopperNoteStages.itemModel(1));
        assertEquals("4s_copper_note_block", CopperNoteStages.itemModel(4));
        // Обе функции обязаны пережить мусор на входе, а не упасть на индексе.
        assertEquals("1s", CopperNoteStages.label(-5));
        assertEquals("4s", CopperNoteStages.label(99));
    }

    @Test
    void itemNamesSayTheStageWithoutBrackets() {
        assertEquals("Медный нотный блок", CopperNoteStages.itemName(1));
        assertEquals("Потемневший медный нотный блок", CopperNoteStages.itemName(2));
        assertEquals("Состаренный медный нотный блок", CopperNoteStages.itemName(3));
        assertEquals("Окисленный медный нотный блок", CopperNoteStages.itemName(4));
        for (int stage = 1; stage <= CopperNoteStages.LAST; stage++) {
            assertFalse(CopperNoteStages.itemName(stage).contains("("),
                    "скобок в названии быть не должно");
        }
    }

    @Test
    void waxedNamesSayTheyAreWaxed() {
        assertEquals("Вощёный медный нотный блок", CopperNoteStages.itemName(1, true));
        assertEquals("Вощёный потемневший медный нотный блок", CopperNoteStages.itemName(2, true));
        assertEquals("Вощёный состаренный медный нотный блок", CopperNoteStages.itemName(3, true));
        assertEquals("Вощёный окисленный медный нотный блок", CopperNoteStages.itemName(4, true));
        // Без воска названия прежние.
        assertEquals(CopperNoteStages.itemName(4), CopperNoteStages.itemName(4, false));
        assertEquals("Окисленный медный нотный блок", CopperNoteStages.itemName(4, false));
        // Мусор на входе не ломает приставку.
        assertEquals("Вощёный медный нотный блок", CopperNoteStages.itemName(-3, true));
        assertEquals("Вощёный окисленный медный нотный блок", CopperNoteStages.itemName(42, true));
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
    void plainCopperNeverFalsifies() {
        Random random = new Random(1L);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(BASE_PITCH, CopperNoteStages.detunedPitch(1, BASE_PITCH, random), 0.0f,
                    "обычная медь держит строй точно");
        }
        assertEquals(0.0D, CopperNoteStages.falseChance(1), 0.0D);
    }

    @Test
    void exposedCopperKeepsTheTuningButSometimesMisses() {
        Random random = new Random(2L);
        Set<Float> distinct = new HashSet<>();
        int falseNotes = 0;

        for (int i = 0; i < SAMPLES; i++) {
            float pitch = CopperNoteStages.detunedPitch(2, BASE_PITCH, random);
            distinct.add(pitch);
            if (pitch != BASE_PITCH) falseNotes++;
        }

        double rate = falseNotes / (double) SAMPLES;
        assertEquals(CopperNoteStages.EXPOSED_FALSE_CHANCE, rate, 0.03D,
                "потемневший блок фальшивит редко, но слышно");
        assertTrue(distinct.size() > 100, "фальшивая нота — случайная высота, а не одна и та же");
        assertTrue(distinct.contains(BASE_PITCH), "остальные ноты звучат точно");
    }

    @Test
    void weatheredCopperFalsifiesMoreOftenThanExposed() {
        Random exposed = new Random(3L);
        Random weathered = new Random(4L);
        double exposedRate = falseRate(2, exposed);
        double weatheredRate = falseRate(3, weathered);

        assertEquals(CopperNoteStages.WEATHERED_FALSE_CHANCE, weatheredRate, 0.03D);
        assertTrue(weatheredRate > exposedRate + 0.1D,
                "состаренный блок фальшивит заметно чаще: " + weatheredRate + " против " + exposedRate);
    }

    @Test
    void theOxidizedBlockHasNoTuningAtAll() {
        Random random = new Random(7L);
        Set<Float> pitches = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            float pitch = CopperNoteStages.detunedPitch(4, BASE_PITCH, random);
            assertTrue(pitch >= CopperNoteStages.MIN_PITCH && pitch <= CopperNoteStages.MAX_PITCH,
                    "высота обязана оставаться в пределах нотного блока: " + pitch);
            pitches.add(pitch);
        }

        assertTrue(pitches.size() > 400, "каждая нота — новая случайная высота");
        assertFalse(pitches.contains(BASE_PITCH), "строгого строя у окисленного нет");
    }

    @Test
    void delaysGrowWithOxidation() {
        Random random = new Random(5L);

        for (int i = 0; i < 2000; i++) {
            assertEquals(0, CopperNoteStages.noteDelayTicks(1, random), "обычная медь не тормозит");
            assertEquals(0, CopperNoteStages.noteDelayTicks(2, random), "потемневший не тормозит");
        }

        Set<Integer> weathered = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            weathered.add(CopperNoteStages.noteDelayTicks(3, random));
        }
        assertEquals(Set.of(0, CopperNoteStages.LOW_DELAY_TICKS), weathered,
                "состаренный иногда задерживает ноту на 0.1 секунды");

        Set<Integer> oxidized = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            oxidized.add(CopperNoteStages.noteDelayTicks(4, random));
        }
        assertEquals(Set.of(CopperNoteStages.LOW_DELAY_TICKS, CopperNoteStages.HIGH_DELAY_TICKS),
                oxidized, "у окисленного задержка 0.1 или 0.2 секунды");
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

    private static double falseRate(int stage, Random random) {
        int falseNotes = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (CopperNoteStages.detunedPitch(stage, BASE_PITCH, random) != BASE_PITCH) {
                falseNotes++;
            }
        }
        return falseNotes / (double) SAMPLES;
    }
}
