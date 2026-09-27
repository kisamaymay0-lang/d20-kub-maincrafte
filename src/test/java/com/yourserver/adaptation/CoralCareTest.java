package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Полить можно и живой, и мёртвый коралл; мёртвый оживает без таймера. */
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
    void мёртвыеКораллыОживают() {
        assertTrue(CoralCare.isDeadCoral("dead_brain_coral"));
        assertTrue(CoralCare.isDeadCoral("dead_bubble_coral_block"));
        assertTrue(CoralCare.isDeadCoral("dead_fire_coral_fan"));
        assertEquals("brain_coral", CoralCare.reviveName("dead_brain_coral"));
        assertEquals("minecraft:tube_coral_wall_fan[facing=east,waterlogged=false]",
                CoralCare.reviveBlockData("minecraft:dead_tube_coral_wall_fan[facing=east,waterlogged=false]"));
        assertNull(CoralCare.reviveName("brain_coral"));
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
