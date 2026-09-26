package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полить можно только живой коралл: мёртвый уже не оживёт. */
final class CoralCareTest {

    @Test
    void живыеКораллыПоливаются() {
        assertTrue(CoralCare.isCoral("brain_coral"));
        assertTrue(CoralCare.isCoral("bubble_coral_block"));
        assertTrue(CoralCare.isCoral("fire_coral_fan"));
        assertTrue(CoralCare.isCoral("tube_coral_wall_fan"));
        assertTrue(CoralCare.isCoral("horn_coral_block"));
    }

    @Test
    void мёртвыеКораллыНеПоливаются() {
        assertFalse(CoralCare.isCoral("dead_brain_coral"));
        assertFalse(CoralCare.isCoral("dead_bubble_coral_block"));
        assertFalse(CoralCare.isCoral("dead_fire_coral_fan"));
    }

    @Test
    void всёОстальноеНеКоралл() {
        assertFalse(CoralCare.isCoral("stone"));
        assertFalse(CoralCare.isCoral("water"));
        assertFalse(CoralCare.isCoral(""));
        assertFalse(CoralCare.isCoral((String) null));
    }

    @Test
    void регистрНеВажен() {
        assertTrue(CoralCare.isCoral("BRAIN_CORAL_BLOCK"));
        assertFalse(CoralCare.isCoral("DEAD_BRAIN_CORAL_BLOCK"));
    }
}
