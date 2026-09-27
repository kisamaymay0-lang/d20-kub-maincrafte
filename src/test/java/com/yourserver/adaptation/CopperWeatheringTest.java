package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Вода старит медь ровно на одну ступень и не трогает то, что старить нельзя. */
final class CopperWeatheringTest {

    @Test
    void обычнаяМедьСтановитсяПотускневшей() {
        assertEquals("exposed_copper", CopperWeathering.next("copper_block"));
        assertEquals("exposed_cut_copper", CopperWeathering.next("cut_copper"));
        assertEquals("exposed_copper_grate", CopperWeathering.next("copper_grate"));
        assertEquals("exposed_copper_bulb", CopperWeathering.next("copper_bulb"));
    }

    @Test
    void потускневшаяИдётДальшеПоКругуСтупеней() {
        assertEquals("weathered_copper", CopperWeathering.next("exposed_copper"));
        assertEquals("oxidized_copper", CopperWeathering.next("weathered_copper"));
        assertNull(CopperWeathering.next("oxidized_copper"), "Последняя ступень — тупик");
    }

    @Test
    void водаСнимаетВоск() {
        assertEquals("copper_block", CopperWeathering.next("waxed_copper_block"));
        assertEquals("weathered_cut_copper", CopperWeathering.next("waxed_weathered_cut_copper"));
        assertEquals("exposed_copper_bulb", CopperWeathering.next("waxed_exposed_copper_bulb"));
        assertEquals("oxidized_copper", CopperWeathering.next("waxed_oxidized_copper"));
    }

    @Test
    void регистрНеВажен() {
        assertEquals("exposed_copper", CopperWeathering.next("COPPER_BLOCK"));
        assertEquals("weathered_copper", CopperWeathering.next("Exposed_Copper"));
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
        assertEquals("minecraft:exposed_copper", CopperWeathering.nextBlockData("minecraft:copper_block"));
        assertEquals("minecraft:copper_block", CopperWeathering.nextBlockData("minecraft:waxed_copper_block"));
        assertEquals("minecraft:exposed_copper", CopperWeathering.nextBlockData("minecraft:waxed_exposed_copper"));
        assertNull(CopperWeathering.nextBlockData("minecraft:oxidized_copper"));
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
        assertEquals("minecraft:copper_block -> minecraft:exposed_copper"
                        + " -> minecraft:weathered_copper -> minecraft:oxidized_copper",
                path.toString());
        assertNull(CopperWeathering.nextBlockData(state), "Пятый шаг ничего не меняет");
    }
}
