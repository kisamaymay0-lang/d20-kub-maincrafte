package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шаблоны биомов: одна строка конфига должна закрывать все пустынные биомы
 * (кувшин) и все зимние/ледяные (заледеневшая изморозь).
 */
class BiomePatternsTest {

    /** Пустынные биомы: пустыня и все пустоши. */
    private static final String[] DESERT_BIOMES = {
            "minecraft:desert", "minecraft:badlands",
            "minecraft:eroded_badlands", "minecraft:wooded_badlands"
    };
    /** Строки из config.yml → fishing.special-items. */
    private static final String[] DESERT_PATTERNS = {"*:desert", "*:badlands*"};
    private static final String[] COLD_PATTERNS = {
            "*:snowy*", "*:*frozen*", "*:ice_spikes", "*:grove", "*:jagged_peaks"
    };

    private static boolean any(String[] patterns, String biome) {
        for (String pattern : patterns) {
            if (BiomePatterns.matches(pattern, biome)) return true;
        }
        return false;
    }

    @Test
    void everyWinterBiomeGetsTheWinterItem() {
        for (String biome : WinterRules.BIOMES) {
            assertTrue(any(COLD_PATTERNS, biome), "без особого предмета: " + biome);
        }
    }

    @Test
    void everyDesertBiomeGetsTheJug() {
        for (String biome : DESERT_BIOMES) {
            assertTrue(any(DESERT_PATTERNS, biome), "без особого предмета: " + biome);
        }
    }

    @Test
    void otherBiomesStayWithoutAnItem() {
        for (String biome : new String[]{"minecraft:plains", "minecraft:taiga", "minecraft:cold_ocean",
                "minecraft:deep_cold_ocean", "minecraft:stony_peaks", "minecraft:jungle", "minecraft:savanna"}) {
            assertFalse(any(COLD_PATTERNS, biome), "лишний предмет: " + biome);
        }
        assertFalse(any(DESERT_PATTERNS, "minecraft:jungle"));
        assertFalse(any(DESERT_PATTERNS, "minecraft:savanna"));
        assertFalse(any(COLD_PATTERNS, "minecraft:desert"));
    }

    @Test
    void exactKeyIsNotAPattern() {
        assertTrue(BiomePatterns.matches("minecraft:desert", "minecraft:desert"));
        assertFalse(BiomePatterns.isPattern("minecraft:desert"));
        assertTrue(BiomePatterns.isPattern("*:desert"));
        assertFalse(BiomePatterns.isPattern(null));
    }

    @Test
    void modNamespacesWork() {
        assertTrue(BiomePatterns.matches("*:snowy*", "minecraft:snowy_taiga"));
        assertTrue(BiomePatterns.matches("*:snowy*", "biomesoplenty:snowy_coniferous_forest"));
        // «*:desert» — это ровно пустыня, а не «desert_oasis»: для таких биомов
        // в конфиг добавляется своя строка.
        assertFalse(BiomePatterns.matches("*:desert", "terralith:desert_oasis"));
        assertTrue(BiomePatterns.matches("*:desert*", "terralith:desert_oasis"));
    }
}
