package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Вода старит медь ровно на одну ступень и не трогает то, что старить нельзя. */
final class CopperWeatheringTest {

    @Test
    void обычнаяМедьСтановитсяПотускневшей() {
        // Медный блок целиком — единственное исключение: exposed_copper, не exposed_copper_block.
        assertEquals("exposed_copper", CopperWeathering.next("copper_block"));
        assertEquals("exposed_cut_copper", CopperWeathering.next("cut_copper"));
        assertEquals("exposed_copper_grate", CopperWeathering.next("copper_grate"));
        assertEquals("exposed_copper_bulb", CopperWeathering.next("copper_bulb"));
        assertEquals("exposed_copper_door", CopperWeathering.next("copper_door"));
        assertEquals("exposed_copper_chest", CopperWeathering.next("copper_chest"));
    }

    @Test
    void потускневшаяИдётДальшеПоКругуСтупеней() {
        assertEquals("weathered_copper", CopperWeathering.next("exposed_copper"));
        assertEquals("oxidized_copper", CopperWeathering.next("weathered_copper"));
        assertNull(CopperWeathering.next("oxidized_copper"), "Последняя ступень — тупик");
        assertEquals("weathered_cut_copper", CopperWeathering.next("exposed_cut_copper"));
        assertEquals("oxidized_copper_grate", CopperWeathering.next("weathered_copper_grate"));
    }

    @Test
    void ступениМедногоБлокаНеПолучаютПриставкуBlock() {
        // exposed_copper_block в игре нет: ваниль называет ступени блока
        // exposed_copper, weathered_copper и oxidized_copper.
        for (String name : new String[] { "copper_block", "exposed_copper", "weathered_copper" }) {
            String stepped = CopperWeathering.next(name);
            assertNotNull(stepped, name + " должен иметь следующую ступень");
            assertFalse(stepped.endsWith("_block"), name + " → " + stepped + ": такого материала нет");
        }
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
