package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Вода старит медь ровно на одну ступень и не трогает то, что старить нельзя. */
final class CopperWeatheringTest {

    @Test
    void обычнаяМедьСтановитсяПотускневшей() {
        assertEquals("exposed_copper_block", CopperWeathering.next("copper_block"));
        assertEquals("exposed_cut_copper", CopperWeathering.next("cut_copper"));
        assertEquals("exposed_copper_grate", CopperWeathering.next("copper_grate"));
        assertEquals("exposed_copper_bulb", CopperWeathering.next("copper_bulb"));
    }

    @Test
    void потускневшаяИдётДальшеПоКругуСтупеней() {
        assertEquals("weathered_copper_block", CopperWeathering.next("exposed_copper_block"));
        assertEquals("oxidized_copper_block", CopperWeathering.next("weathered_copper_block"));
        assertNull(CopperWeathering.next("oxidized_copper_block"), "Последняя ступень — тупик");
    }

    @Test
    void воскЗащищает() {
        assertNull(CopperWeathering.next("waxed_copper_block"));
        assertNull(CopperWeathering.next("waxed_weathered_cut_copper"));
        assertNull(CopperWeathering.next("waxed_exposed_copper_bulb"));
    }

    @Test
    void воСнимаетсяВодой() {
        assertEquals("copper_block", CopperWeathering.unwax("waxed_copper_block"));
        assertEquals("exposed_cut_copper", CopperWeathering.unwax("waxed_exposed_cut_copper"));
        assertEquals("weathered_copper_grate", CopperWeathering.unwax("waxed_weathered_copper_grate"));
        assertEquals("oxidized_copper_door", CopperWeathering.unwax("WAXED_OXIDIZED_COPPER_DOOR"));
        assertNull(CopperWeathering.unwax("copper_block"), "без воска снимать нечего");
        assertNull(CopperWeathering.unwax("waxed_"));
        assertNull(CopperWeathering.unwax(""));
        assertNull(CopperWeathering.unwax((String) null));
    }

    @Test
    void состояниеБлокаПослеСнятияВоска() {
        // Ступень окисления та же, уходит только защита.
        assertEquals("minecraft:copper_block", CopperWeathering.unwaxBlockData("minecraft:waxed_copper_block"));
        assertEquals("minecraft:exposed_cut_copper_stairs[facing=east,half=bottom]",
                CopperWeathering.unwaxBlockData("minecraft:waxed_exposed_cut_copper_stairs[facing=east,half=bottom]"));
        assertEquals("minecraft:oxidized_copper_grate[waterlogged=true]",
                CopperWeathering.unwaxBlockData("minecraft:waxed_oxidized_copper_grate[waterlogged=true]"));
        assertNull(CopperWeathering.unwaxBlockData("minecraft:copper_block"), "без воска не трогаем");
        assertNull(CopperWeathering.unwaxBlockData("minecraft:weathered_copper_grate"));
        assertNull(CopperWeathering.unwaxBlockData(null));
    }

    @Test
    void регистрНеВажен() {
        assertEquals("exposed_copper_block", CopperWeathering.next("COPPER_BLOCK"));
        assertEquals("weathered_copper_block", CopperWeathering.next("Exposed_Copper_Block"));
    }

    @Test
    void чужоеИмяНеОкисляется() {
        assertNull(CopperWeathering.next(""));
        assertNull(CopperWeathering.next((String) null));
    }

    @Test
    void состояниеБлокаПереписываетсяЦеликом() {
        // Поворот лестницы и водность должны уехать в новую ступень как есть.
        assertEquals("minecraft:exposed_cut_copper_stairs[facing=east,half=bottom]",
                CopperWeathering.nextBlockData("minecraft:cut_copper_stairs[facing=east,half=bottom]"));
        assertEquals("minecraft:oxidized_copper_grate[waterlogged=true]",
                CopperWeathering.nextBlockData("minecraft:weathered_copper_grate[waterlogged=true]"));
    }

    @Test
    void состояниеБезСвойствТожеРаботает() {
        assertEquals("minecraft:exposed_copper_block", CopperWeathering.nextBlockData("minecraft:copper_block"));
        assertNull(CopperWeathering.nextBlockData("minecraft:oxidized_copper_block"));
        assertNull(CopperWeathering.nextBlockData(null));
    }

    @Test
    void четыреШагаСтарятБлокДоКонца() {
        // Ровно то, что увидит игрок, поливая один и тот же блок четыре раза.
        String state = "minecraft:copper_block";
        StringBuilder path = new StringBuilder(state);
        for (int i = 0; i < 3; i++) {
            state = CopperWeathering.nextBlockData(state);
            path.append(" -> ").append(state);
        }
        assertEquals("minecraft:copper_block -> minecraft:exposed_copper_block"
                        + " -> minecraft:weathered_copper_block -> minecraft:oxidized_copper_block",
                path.toString());
        assertNull(CopperWeathering.nextBlockData(state), "Пятый шаг ничего не меняет");
    }
}
