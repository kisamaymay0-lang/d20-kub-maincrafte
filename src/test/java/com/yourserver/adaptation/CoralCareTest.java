package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полить можно и живой коралл (он не высохнет), и мёртвый — он снова станет цветным. */
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

    @Test
    void мёртвыеКораллыУзнаются() {
        assertTrue(CoralCare.isDeadCoral("dead_brain_coral"));
        assertTrue(CoralCare.isDeadCoral("dead_brain_coral_block"));
        assertTrue(CoralCare.isDeadCoral("dead_fire_coral_wall_fan"));
        assertTrue(CoralCare.isDeadCoral("DEAD_TUBE_CORAL"));
        assertFalse(CoralCare.isDeadCoral("brain_coral"), "живой коралл не мёртвый");
        assertFalse(CoralCare.isDeadCoral("dead_stone"), "мёртвый, но не коралл");
        assertFalse(CoralCare.isDeadCoral(""));
        assertFalse(CoralCare.isDeadCoral((String) null));
    }

    @Test
    void мёртвыйКораллПревращаетсяВЖивой() {
        assertEquals("brain_coral", CoralCare.liveName("dead_brain_coral"));
        assertEquals("brain_coral_block", CoralCare.liveName("dead_brain_coral_block"));
        assertEquals("fire_coral_wall_fan", CoralCare.liveName("dead_fire_coral_wall_fan"));
        assertEquals("horn_coral", CoralCare.liveName("DEAD_HORN_CORAL"));
        assertNull(CoralCare.liveName("brain_coral"));
        assertNull(CoralCare.liveName("dead_stone"));
        assertNull(CoralCare.liveName((String) null));
    }

    @Test
    void состояниеМёртногоКораллаПереписываетсяЦеликом() {
        // Поворот настенного веера и водность должны уехать в живой вариант как есть.
        assertEquals("minecraft:brain_coral_wall_fan[facing=north,waterlogged=true]",
                CoralCare.reviveBlockData("minecraft:dead_brain_coral_wall_fan[facing=north,waterlogged=true]"));
        assertEquals("minecraft:bubble_coral", CoralCare.reviveBlockData("minecraft:dead_bubble_coral"));
        assertNull(CoralCare.reviveBlockData("minecraft:brain_coral"), "живой коралл оживлять не нужно");
        assertNull(CoralCare.reviveBlockData(null));
    }
}
