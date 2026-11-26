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
        assertEquals(600, settings.decayTicks(), "1 единица за 30 секунд");
        assertTrue(settings.hangover());
        assertTrue(settings.slurredChat());
        assertTrue(settings.wobble());
        assertTrue(settings.phantomBlocks());
        assertTrue(settings.puddles());
        assertEquals(60, settings.puddleSeconds());
        assertEquals(16, settings.phantomMax());
        assertEquals(1.0, settings.pukeRate(), "обычная частота приступов");
    }

    @Test
    void поставляемыйФайлСовпадаетСТаблицей() throws Exception {
        Path path = Path.of("src/main/resources/settings/drunk.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        DrunkSettings settings = DrunkSettings.parse(yaml.getConfigurationSection("drunk"));

        assertEquals(50, settings.scale());
        assertEquals(600, settings.decayTicks());
        assertEquals(60, settings.puddleSeconds());
        assertEquals(16, settings.phantomMax());
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
        assertEquals(600, settings.decayTicks());
        assertTrue(settings.puddles());
    }

    @Test
    void поведениеЧитаетсяИзФайла() {
        DrunkSettings standard = DrunkSettings.parse(null);
        assertEquals(60, standard.blackoutHoldSeconds());
        assertEquals(16, standard.blackoutLieMinSeconds());
        assertEquals(28, standard.blackoutLieMaxSeconds());
        assertEquals(50, standard.blackoutWakePercent());
        assertEquals(60, standard.hangoverSeconds());
        assertEquals(1, standard.foodUnits());
        assertEquals(1, standard.milkUnits());
        assertEquals(3, standard.waterUnits());

        DrunkSettings custom = DrunkSettings.parse(section("""
                drunk:
                  blackout-hold-seconds: 5
                  blackout-lie-min-seconds: 3
                  blackout-lie-max-seconds: 9
                  blackout-wake-percent: 25
                  hangover-seconds: 10
                  food-units: 0
                  milk-units: 2
                  water-units: 5
                """));
        assertEquals(5, custom.blackoutHoldSeconds());
        assertEquals(3, custom.blackoutLieMinSeconds());
        assertEquals(9, custom.blackoutLieMaxSeconds());
        assertEquals(25, custom.blackoutWakePercent());
        assertEquals(10, custom.hangoverSeconds());
        assertEquals(0, custom.foodUnits(), "0 — еда не снимает опьянение");
        assertEquals(2, custom.milkUnits());
        assertEquals(5, custom.waterUnits());
    }

    @Test
    void кривыеЧислаПоведенияПодрезаются() {
        DrunkSettings broken = DrunkSettings.parse(section("""
                drunk:
                  blackout-hold-seconds: -5
                  blackout-lie-min-seconds: 0
                  blackout-lie-max-seconds: 9999
                  blackout-wake-percent: 300
                  hangover-seconds: 0
                  food-units: -3
                  water-units: 999
                """));
        assertEquals(0, broken.blackoutHoldSeconds(), "отрицательная пауза — сразу падение");
        assertEquals(1, broken.blackoutLieMinSeconds());
        assertEquals(300, broken.blackoutLieMaxSeconds());
        assertEquals(100, broken.blackoutWakePercent(), "просыпается с полной шкалой — не с пустой");
        assertEquals(1, broken.hangoverSeconds());
        assertEquals(0, broken.foodUnits());
        assertEquals(50, broken.waterUnits());
    }

    @Test
    void своиЧислаЧитаютсяАКривыеПодрезаются() {
        DrunkSettings settings = DrunkSettings.parse(section("""
                drunk:
                  scale: 100
                  decay-ticks: 400
                  hangover: false
                  puddles: false
                  puddles-seconds: 30
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
        assertTrue(broken.puddleMaterials().contains(Material.SLIME_BLOCK), "слизь тоже по умолчанию");
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
