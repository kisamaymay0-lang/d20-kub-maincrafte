package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code settings/drunk.yml}: значения по умолчанию, поставляемый файл и то, что
 * кривые числа в файле не ломают опьянение.
 */
final class DrunkSettingsTest {

    @TempDir Path folder;

    private static ConfigurationSection section(String yamlText) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return yaml.getConfigurationSection("drunk");
    }

    @Test
    void безФайлаРаботаютЗначенияПоУмолчанию() {
        DrunkSettings settings = DrunkSettings.parse(null);

        assertEquals(50, settings.scale(), "шкала 50, как в таблице");
        assertEquals(200, settings.decayTicks(), "1 единица за 10 секунд");
        assertTrue(settings.hangover());
        assertTrue(settings.slurredChat());
        assertTrue(settings.wobble());
        assertTrue(settings.phantomBlocks());
        assertTrue(settings.puddles());
        assertEquals(60, settings.puddleSeconds());
        assertEquals(8, settings.phantomMax());
        assertEquals(1200, settings.pukeMinTicks(), "приступ раз в 60–90 секунд");
        assertEquals(1800, settings.pukeMaxTicks());
    }

    @Test
    void поставляемыйФайлСовпадаетСТаблицей() throws Exception {
        Path path = Path.of("src/main/resources/settings/drunk.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        DrunkSettings settings = DrunkSettings.parse(yaml.getConfigurationSection("drunk"));

        assertEquals(50, settings.scale());
        assertEquals(200, settings.decayTicks());
        assertEquals(60, settings.puddleSeconds());
        assertEquals(8, settings.phantomMax());
        assertTrue(settings.puddles(), "лужи включены — так решил владелец");
        assertTrue(settings.puddleMaterials().contains(Material.MUD), settings.puddleMaterials().toString());
        assertTrue(settings.puddleMaterials().contains(Material.SLIME_BLOCK));

        assertEquals(27, settings.pointsFor(DrunkStage.QUEASY), "27 из 50 — это «Укачало»");
        assertEquals(1, settings.pointsFor(DrunkStage.TIPSY));
    }

    @Test
    void лужиНеСтавятсяНаПостройки() {
        DrunkSettings settings = DrunkSettings.parse(null);
        assertTrue(settings.naturalFloor().contains(Material.DIRT));
        assertTrue(settings.naturalFloor().contains(Material.GRASS_BLOCK));
        assertTrue(settings.naturalFloor().contains(Material.STONE));
        assertFalse(settings.naturalFloor().contains(Material.OAK_PLANKS), "доскам под лужей не место");
        assertFalse(settings.naturalFloor().contains(Material.BRICKS));
        assertFalse(settings.naturalFloor().contains(Material.GLASS));
    }

    @Test
    void пустойРазделОставляетУмолчания() {
        DrunkSettings settings = DrunkSettings.parse(section("drunk: {}"));
        assertEquals(50, settings.scale());
        assertEquals(200, settings.decayTicks());
        assertTrue(settings.puddles());
    }

    @Test
    void своиЧислаЧитаютсяАКривыеПодрезаются() {
        DrunkSettings settings = DrunkSettings.parse(section("""
                drunk:
                  scale: 100
                  decay-ticks: 400
                  hangover: false
                  puddles: false
                  puddle-seconds: 30
                  phantom-max: 4
                """));
        assertEquals(100, settings.scale());
        assertEquals(400, settings.decayTicks());
        assertFalse(settings.hangover());
        assertFalse(settings.puddles());
        assertEquals(30, settings.puddleSeconds());
        assertEquals(4, settings.phantomMax());
        // Порог стадии пересчитался вместе со шкалой: 27 из 50 = 54 из 100.
        assertEquals(54, settings.pointsFor(DrunkStage.QUEASY));

        DrunkSettings broken = DrunkSettings.parse(section("""
                drunk:
                  scale: 1
                  decay-ticks: 1
                  puddles-seconds: 0
                  puddle-materials: [НЕ_БЛОК]
                """));
        assertEquals(5, broken.scale(), "шкала не может быть меньше пяти");
        assertEquals(20, broken.decayTicks(), "быстрее тика не трезвеют");
        assertEquals(5, broken.puddleSeconds());
        assertTrue(broken.puddleMaterials().contains(Material.MUD), "незнакомый блок заменён умолчанием");
    }

    @Test
    void материалПоИмени() {
        assertEquals(Material.MUD, DrunkSettings.material("mud"));
        assertEquals(Material.SOUL_SAND, DrunkSettings.material(" minecraft:Soul_Sand "));
        assertEquals(Material.SLIME_BLOCK, DrunkSettings.material("SLIME_BLOCK"));
        assertNull(DrunkSettings.material("не блок"));
        assertNull(DrunkSettings.material(null));
    }
}
