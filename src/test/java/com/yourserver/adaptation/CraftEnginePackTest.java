package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Пак CraftEngine с кувшином — против формата, который читает сам CraftEngine.
 *
 * Списки ключей и значений взяты не «на глаз», а из исходников плагина:
 * группы {@code auto_state} — из {@code AutoStateGroup}, ключи {@code settings}
 * — из {@code BlockSettingsModifiers}, ключи {@code sounds} — из
 * {@code BlockSounds.fromConfig}. Раскладка файлов — из вики плагина: конфиги
 * читаются только из пака {@code resources/<имя>/{configuration,resourcepack}}.
 */
class CraftEnginePackTest {

    /** Папка плагина в репозитории. */
    private static final Path ROOT = repoRoot();
    private static final Path PACK = ROOT.resolve("craftengine/resources/f8_jug");
    private static final Path PACK_RESOURCES = PACK.resolve("resourcepack");

    /** Значения state.auto_state, которые знает CraftEngine (enum AutoStateGroup). */
    private static final Set<String> AUTO_STATES = Set.of(
            "solid", "note_block", "mushroom_stem", "red_mushroom_block", "brown_mushroom_block",
            "mushroom", "tintable_leaves", "waterlogged_tintable_leaves", "non_tintable_leaves",
            "waterlogged_non_tintable_leaves", "leaves", "waterlogged_leaves", "lower_tripwire",
            "higher_tripwire", "tripwire", "sapling", "pressure_plate", "cactus", "sugar_cane",
            "weeping_vines", "weeping_vine", "twisting_vines", "twisting_vine",
            "cave_vines", "cave_vine", "kelp", "chorus");

    /** Ключи settings, которые разбирает BlockSettingsModifiers. */
    private static final Set<String> SETTINGS_KEYS = Set.of(
            "item", "name", "support_shape", "luminance", "map_color", "burn_chance",
            "fire_spread_chance", "block_light", "light_block", "light_dampening", "hardness",
            "friction", "speed_factor", "jump_factor", "resistance", "incorrect_tool_dig_speed",
            "replaceable", "is_redstone_conductor", "is_suffocating", "is_randomly_ticking",
            "is_view_blocking", "propagate_skylight", "burnable", "push_reaction", "instrument",
            "sounds", "fluid_state", "can_occlude", "require_correct_tools",
            "respect_tool_component", "required_break_power", "use_shape_for_light_occlusion",
            "tags", "correct_tools", "block_raytrace", "bounce_restitution", "destroy_stages");

    /** Ключи внутри settings.sounds (BlockSounds.fromConfig). */
    private static final Set<String> SOUND_KEYS = Set.of("break", "step", "place", "hit", "fall");

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("craftengine"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Не найден корень репозитория с папкой craftengine");
    }

    private static ConfigurationSection blocks() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(
                PACK.resolve("configuration/blocks/ancient_jug.yml")));
        ConfigurationSection section = yaml.getConfigurationSection("blocks");
        assertNotNull(section, "В конфиге кувшина должен быть корневой раздел blocks");
        return section;
    }

    @Test
    void packHasTheLayoutCraftEngineReads() {
        assertTrue(Files.isDirectory(PACK), "Пак должен лежать в craftengine/resources/<имя пака>");
        assertTrue(Files.isRegularFile(PACK.resolve("pack.yml")),
                "Без pack.yml CraftEngine не считает папку паком и конфиги не читает");
        assertTrue(Files.isDirectory(PACK.resolve("configuration")),
                "Конфиги читаются только из configuration/ внутри пака");
        assertTrue(Files.isDirectory(PACK_RESOURCES),
                "Модели и текстуры читаются только из resourcepack/ внутри пака");
    }

    @Test
    void packNamespaceMatchesTheBlockIds() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(PACK.resolve("pack.yml")));
        String namespace = yaml.getString("namespace");
        assertNotNull(namespace, "В pack.yml нужен namespace");
        for (String id : blocks().getKeys(false)) {
            assertEquals(namespace, id.substring(0, id.indexOf(':')),
                    "Пространство имён блока совпадает с namespace пака: " + id);
        }
    }

    @Test
    void blocksAreExactlyTheTwoJugIdsThePluginUses() throws Exception {
        Set<String> ids = blocks().getKeys(false);
        assertEquals(Set.of(CraftEngineJug.EMPTY_ID, CraftEngineJug.FILLED_ID), ids,
                "id блоков в конфиге и в плагине обязаны совпадать");
    }

    @Test
    void everyBlockHasAStateCraftEngineCanUse() throws Exception {
        for (String id : blocks().getKeys(false)) {
            ConfigurationSection block = blocks().getConfigurationSection(id);
            ConfigurationSection state = block.getConfigurationSection("state");
            assertNotNull(state, id + ": секция state обязательна");

            String autoState = state.getString("auto_state");
            assertNotNull(autoState, id + ": нужен state.auto_state");
            assertTrue(AUTO_STATES.contains(autoState.toLowerCase(Locale.ROOT)),
                    id + ": неизвестная группа auto_state «" + autoState + "»");

            ConfigurationSection model = state.getConfigurationSection("model");
            assertNotNull(model, id + ": нужен state.model");
            String path = model.getString("path");
            assertNotNull(path, id + ": нужен state.model.path");
            assertTrue(path.contains(":"), id + ": путь к модели должен быть с пространством имён");
            // По одному только path CraftEngine модель не генерирует — она
            // обязана уже лежать в паке, иначе на месте кувшина будет носитель.
            assertTrue(model.getKeys(false).containsAll(Set.of("path")),
                    id + ": model.path обязателен");
        }
    }

    @Test
    void settingsUseOnlyKeysCraftEngineParses() throws Exception {
        for (String id : blocks().getKeys(false)) {
            ConfigurationSection settings = blocks().getConfigurationSection(id)
                    .getConfigurationSection("settings");
            if (settings == null) continue;
            for (String key : settings.getKeys(false)) {
                assertTrue(SETTINGS_KEYS.contains(key),
                        id + ": CraftEngine не разбирает settings." + key);
            }
            ConfigurationSection sounds = settings.getConfigurationSection("sounds");
            if (sounds == null) continue;
            for (String key : sounds.getKeys(false)) {
                assertTrue(SOUND_KEYS.contains(key),
                        id + ": CraftEngine не разбирает settings.sounds." + key);
                assertTrue(sounds.getString(key, "").startsWith("minecraft:"),
                        id + ": звук " + key + " пишется полным id, minecraft:…");
            }
        }
    }

    @Test
    void jugIsNotASolidCubeForTheClient() throws Exception {
        // Носитель mushroom_stem — глухой куб. Без этих ключей соседние блоки не
        // рисуют грани рядом с кувшином и проводят редстоун: так кувшин 10.13
        // оставлял дыры в постройке.
        for (String id : blocks().getKeys(false)) {
            ConfigurationSection settings = blocks().getConfigurationSection(id)
                    .getConfigurationSection("settings");
            assertNotNull(settings, id + ": нужны settings");
            assertFalse(settings.getBoolean("is_view_blocking", true),
                    id + ": кувшин не должен перекрывать обзор соседям");
            assertFalse(settings.getBoolean("can_occlude", true),
                    id + ": кувшин не должен скрывать грани соседей");
            assertFalse(settings.getBoolean("is_redstone_conductor", true),
                    id + ": кувшин не должен проводить редстоун");
        }
    }

    @Test
    void noLootTableBecauseThePluginDropsTheJugItself() throws Exception {
        for (String id : blocks().getKeys(false)) {
            assertNull(blocks().getConfigurationSection(id).get("loot"),
                    id + ": loot не нужен — предмет со всем содержимым роняет AncientJug, "
                            + "иначе дроп получится двойным");
        }
    }

    @Test
    void everyModelPathPointsToAFileInThePack() throws Exception {
        for (String id : blocks().getKeys(false)) {
            String path = blocks().getConfigurationSection(id)
                    .getConfigurationSection("state").getConfigurationSection("model")
                    .getString("path");
            String[] split = path.split(":", 2);
            Path model = PACK_RESOURCES.resolve("assets").resolve(split[0])
                    .resolve("models").resolve(split[1] + ".json");
            assertTrue(Files.isRegularFile(model),
                    id + ": модели " + path + " нет в паке (" + ROOT.relativize(model) + ")");
        }
    }

    @Test
    void everyTextureTheModelsUseIsInThePack() throws Exception {
        Pattern texture = Pattern.compile("\"(?:[0-9]+|particle|all|side|top|bottom|end|north|south|east|west)\""
                + "\\s*:\\s*\"([a-z0-9_.\\-/]+:[a-z0-9_.\\-/]+)\"");
        Set<String> referenced = new LinkedHashSet<>();
        try (var models = Files.walk(PACK_RESOURCES.resolve("assets"))) {
            for (Path model : models.filter(p -> p.toString().endsWith(".json")).toList()) {
                Matcher matcher = texture.matcher(Files.readString(model));
                while (matcher.find()) referenced.add(matcher.group(1));
            }
        }
        assertFalse(referenced.isEmpty(), "Модели кувшина должны ссылаться на текстуры");
        for (String id : referenced) {
            String[] split = id.split(":", 2);
            Path png = PACK_RESOURCES.resolve("assets").resolve(split[0])
                    .resolve("textures").resolve(split[1] + ".png");
            assertTrue(Files.isRegularFile(png),
                    "Текстуры " + id + " нет в паке (" + ROOT.relativize(png) + ")");
        }
    }

    @Test
    void pluginDeclaresCraftEngineAsSoftDependency() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(
                ROOT.resolve("src/main/resources/plugin.yml")));
        List<String> softDepend = yaml.getStringList("softdepend");
        assertTrue(softDepend.contains("CraftEngine"),
                "Без softdepend: [CraftEngine] плагин загружается раньше CraftEngine "
                        + "и не находит его блоки");
    }

    @Test
    void thereIsNoStrayBlockConfigOutsideAPack() {
        // Ошибка 10.13: конфиг положили в docs/craftengine/blocks/ и велели
        // копировать его в plugins/CraftEngine/blocks/. CraftEngine читает
        // только паки из resources/, такой файл не загружается никогда.
        assertFalse(Files.exists(ROOT.resolve("docs/craftengine")),
                "docs/craftengine с конфигом вне пака больше не нужен");
        assertFalse(Files.exists(ROOT.resolve("craftengine/blocks")),
                "Конфиг блоков вне пака CraftEngine не читает");
        List<String> stray = new ArrayList<>();
        try (var walk = Files.walk(ROOT.resolve("craftengine"))) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".yml")).toList()) {
                Path relative = PACK.relativize(path);
                if (relative.startsWith("..")) stray.add(ROOT.relativize(path).toString());
            }
        } catch (Exception ex) {
            fail("Не обошли папку craftengine: " + ex);
        }
        assertEquals(List.of(), stray, "Все yml пака должны лежать внутри resources/f8_jug");
    }

    @Test
    void jugIdsAreTheOnesTheConfigDefines() {
        // Обратная связь от плагина к тесту: если id в CraftEngineJug поменяют,
        // тест blocksAreExactlyTheTwoJugIdsThePluginUses это поймает.
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.EMPTY_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.FILLED_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.keyName(1));
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.keyName(0));
        assertTrue(CraftEngineJug.PACK_CONFIG.contains("resources/f8_jug/configuration/blocks/ancient_jug.yml"),
                "Путь к конфигу в сообщениях должен вести в пак");
    }
}
