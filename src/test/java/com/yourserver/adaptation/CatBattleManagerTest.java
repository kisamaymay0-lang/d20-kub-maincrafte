package com.yourserver.adaptation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatBattleManagerTest {
    private YamlConfiguration config(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void chanceIsReadAsPerTickProbabilityAndClamped() throws Exception {
        assertEquals(0.025, CatBattleManager.readChance(config("""
                cat-battles:
                  chance-per-tick: 0.025
                """)), 0.0000001);
        assertEquals(0.0, CatBattleManager.readChance(config("""
                cat-battles:
                  chance-per-tick: -1
                """)), 0.0000001);
        assertEquals(1.0, CatBattleManager.readChance(config("""
                cat-battles:
                  chance-per-tick: 4
                """)), 0.0000001);
    }

    @Test
    void percentAliasIsAvailableForExistingConfigs() throws Exception {
        assertEquals(0.002, CatBattleManager.readChance(config("""
                cat-battles:
                  chance-percent-per-tick: 0.2
                """)), 0.0000001);
    }

    @Test
    void missingChanceUsesDocumentedDefault() throws Exception {
        assertEquals(0.001, CatBattleManager.readChance(config("cat-battles:\n  enabled: true\n")), 0.0000001);
    }
}
