package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Конфиг кастомных блоков — против формата, который читает сам CraftEngine.
 *
 * <p>Папки с паком в репозитории больше нет: ресурспак в CraftEngine кладёт сам
 * сервер, а от плагина нужен только список id и конфиг. Проверять нечего было
 * бы, если бы конфиг лежал в README «для вида» — поэтому он лежит в
 * {@link CraftEngineSupport#CONFIG_DOC} целиком, и тест читает именно его:
 * проверяется то, что сервер-админ копирует себе в пак.
 *
 * <p>Списки ключей и значений взяты не «на глаз», а из исходников плагина:
 * группы {@code auto_state} — из {@code AutoStateGroup}, ключи {@code settings}
 * — из {@code BlockSettingsModifiers}, ключи {@code sounds} — из
 * {@code BlockSounds.fromConfig}. Раскладка файлов — из вики плагина: конфиги
 * читаются только из пака {@code resources/<имя пака>/configuration/}.
 */
class CraftEngineBlocksTest {

    private static final Path ROOT = repoRoot();
    private static final Path DOC = ROOT.resolve(CraftEngineSupport.CONFIG_DOC);
    private static final Path SHIPPED_PACK = ROOT.resolve("docs/f8resurspack-fixed.zip");

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

    /** Три блока, которые плагин ждёт от CraftEngine. */
    private static final Set<String> WANTED_IDS =
            Set.of(CraftEngineJug.EMPTY_ID, CraftEngineJug.FILLED_ID, CraftEngineCopper.ID);

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Не найден корень репозитория");
    }

    /** Все блоки ```yaml``` из документа — именно их копируют себе в пак. */
    private static List<YamlConfiguration> yamlBlocks() throws Exception {
        assertTrue(Files.isRegularFile(DOC),
                "Нет документа с конфигами блоков: " + ROOT.relativize(DOC));
        String text = Files.readString(DOC);
        Matcher matcher = Pattern.compile("```yaml\\R(.*?)```", Pattern.DOTALL).matcher(text);
        List<YamlConfiguration> blocks = new ArrayList<>();
        while (matcher.find()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(matcher.group(1));
            blocks.add(yaml);
        }
        assertFalse(blocks.isEmpty(), "В документе нет ни одного блока ```yaml``` с конфигом");
        return blocks;
    }

    /** Тот блок ```yaml```, где описаны сами блоки. */
    private static ConfigurationSection blocks() throws Exception {
        for (YamlConfiguration yaml : yamlBlocks()) {
            ConfigurationSection section = yaml.getConfigurationSection("blocks");
            if (section != null) {
                return section;
            }
        }
        fail("В документе нет блока ```yaml``` с корневым разделом blocks — копировать нечего");
        throw new IllegalStateException();
    }

    /** Тот блок ```yaml```, где описан pack.yml. */
    private static YamlConfiguration packYaml() throws Exception {
        for (YamlConfiguration yaml : yamlBlocks()) {
            if (yaml.getString("namespace") != null) {
                return yaml;
            }
        }
        fail("В документе нет образца pack.yml с namespace");
        throw new IllegalStateException();
    }

    @Test
    void docListsExactlyTheBlocksThePluginUses() throws Exception {
        // Обратная связь в обе стороны: добавят блок в плагин — тест потребует
        // описать его здесь, и наоборот.
        assertEquals(WANTED_IDS, blocks().getKeys(false),
                "id блоков в конфиге и в плагине обязаны совпадать");
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.EMPTY_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.FILLED_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.keyName(1));
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.keyName(0));
        assertEquals("f8resurs:copper_note_block", CraftEngineCopper.ID);
    }

    @Test
    void pluginMessagesPointAtThisDocument() {
        assertEquals("docs/craftengine-blocks.md", CraftEngineSupport.CONFIG_DOC);
        assertTrue(Files.isRegularFile(ROOT.resolve(CraftEngineSupport.CONFIG_DOC)),
                "Путь к документу в сообщениях должен вести в существующий файл");
    }

    @Test
    void packNamespaceMatchesTheBlockIds() throws Exception {
        String namespace = packYaml().getString("namespace");
        assertNotNull(namespace, "В pack.yml нужен namespace");
        for (String id : blocks().getKeys(false)) {
            assertEquals(namespace, id.substring(0, id.indexOf(':')),
                    "Пространство имён блока совпадает с namespace пака: " + id);
        }
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
        }
    }

    @Test
    void everyModelPathExistsInTheShippedResourcePack() throws Exception {
        // Пака с моделями в репозитории больше нет: модель берётся из того
        // ресурспака, который сервер и так ставит в CraftEngine. Если сослаться
        // на модель, которой там нет, на месте блока будет носитель — и понять
        // это можно только в игре.
        assertTrue(Files.isRegularFile(SHIPPED_PACK), "Нет собранного ресурспака для сверки");
        try (ZipFile zip = new ZipFile(SHIPPED_PACK.toFile())) {
            for (String id : blocks().getKeys(false)) {
                String path = blocks().getConfigurationSection(id)
                        .getConfigurationSection("state").getConfigurationSection("model")
                        .getString("path");
                String[] split = path.split(":", 2);
                String entry = "assets/" + split[0] + "/models/" + split[1] + ".json";
                ZipEntry model = zip.getEntry(entry);
                assertNotNull(model, id + ": модели " + path + " нет в ресурспаке (" + entry + ")");
            }
        }
    }

    @Test
    void settingsUseOnlyKeysCraftEngineParses() throws Exception {
        for (String id : blocks().getKeys(false)) {
            ConfigurationSection settings = blocks().getConfigurationSection(id)
                    .getConfigurationSection("settings");
            assertNotNull(settings, id + ": без settings блок ведёт себя как носитель");
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
    void solidityFlagsAreSetExplicitlyForEveryBlock() throws Exception {
        // По умолчанию эти признаки «не определены» и наследуются от носителя.
        // У кувшина носитель — глухой mushroom_stem, у медного блока — сплошной
        // куб; молча наследовать значит получить поведение носителя.
        for (String id : blocks().getKeys(false)) {
            ConfigurationSection settings = blocks().getConfigurationSection(id)
                    .getConfigurationSection("settings");
            for (String key : List.of("is_view_blocking", "can_occlude", "is_suffocating",
                    "is_redstone_conductor", "propagate_skylight")) {
                assertTrue(settings.contains(key), id + ": settings." + key + " не задан явно");
            }
        }
    }

    @Test
    void jugIsNotASolidCubeForTheClient() throws Exception {
        // Носитель mushroom_stem — глухой куб. Без этих ключей соседние блоки
        // не рисуют грани рядом с кувшином и проводят редстоун: так кувшин
        // 10.13 оставлял дыры в постройке.
        for (String id : List.of(CraftEngineJug.EMPTY_ID, CraftEngineJug.FILLED_ID)) {
            ConfigurationSection settings = blocks().getConfigurationSection(id)
                    .getConfigurationSection("settings");
            assertFalse(settings.getBoolean("is_view_blocking", true),
                    id + ": кувшин не должен перекрывать обзор соседям");
            assertFalse(settings.getBoolean("can_occlude", true),
                    id + ": кувшин не должен скрывать грани соседей");
            assertFalse(settings.getBoolean("is_redstone_conductor", true),
                    id + ": кувшин не должен проводить редстоун");
            assertTrue(settings.getBoolean("propagate_skylight", false),
                    id + ": кувшин не должен гасить свет");
        }
    }

    @Test
    void copperBlockIsASolidCubeLikeTheNoteBlockItReplaces() throws Exception {
        // Модель медного блока — cube_all, а питание он читает обычным
        // block.isBlockPowered(): если куб не проводит редстоун, блок перестанет
        // срабатывать от провода.
        ConfigurationSection settings = blocks().getConfigurationSection(CraftEngineCopper.ID)
                .getConfigurationSection("settings");
        assertTrue(settings.getBoolean("is_view_blocking", false),
                "медный блок — глухой куб");
        assertTrue(settings.getBoolean("can_occlude", false),
                "медный блок — глухой куб");
        assertTrue(settings.getBoolean("is_suffocating", false),
                "медный блок — глухой куб");
        assertTrue(settings.getBoolean("is_redstone_conductor", false),
                "медный блок должен проводить редстоун, иначе он не сработает от провода");
        assertFalse(settings.getBoolean("propagate_skylight", true),
                "глухой куб свет не пропускает");
    }

    @Test
    void noLootTableBecauseThePluginDropsTheItemsItself() throws Exception {
        for (String id : blocks().getKeys(false)) {
            assertNull(blocks().getConfigurationSection(id).get("loot"),
                    id + ": loot не нужен — предмет роняет плагин, иначе дроп получится двойным");
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
    void theRepoNoLongerShipsACraftEnginePack() {
        // Папку с паком в пул-реквесте носить перестали: ресурспак в CraftEngine
        // кладёт сам сервер, а дубликат моделей в репозитории только устаревал.
        assertFalse(Files.exists(ROOT.resolve("craftengine")),
                "Папку craftengine/ в репозитории быть не должно");
        assertFalse(Files.exists(ROOT.resolve("docs/craftengine")),
                "docs/craftengine с конфигом вне пака больше не нужен");
    }

    @Test
    void jugModelsInTheWorkingPackMatchTheShippedOne() throws Exception {
        // Ресурспак в рабочем каталоге собирается скриптом. Если модель кувшина
        // здесь разойдётся с той, что уходит игрокам, кувшин встанет, но будет
        // выглядеть прежним — и понять это можно только в игре.
        assertTrue(Files.isRegularFile(SHIPPED_PACK), "Нет собранного ресурспака для сверки");
        try (ZipFile zip = new ZipFile(SHIPPED_PACK.toFile())) {
            for (String name : List.of("ancient_jug", "ancient_jug_filled", "copper_note_block")) {
                ZipEntry entry = zip.getEntry("assets/f8resurs/models/block/" + name + ".json");
                assertNotNull(entry, name + ": модели нет в ресурспаке");
                byte[] expected;
                try (var in = zip.getInputStream(entry)) {
                    expected = in.readAllBytes();
                }
                assertArrayEquals(expected,
                        Files.readAllBytes(ROOT.resolve("resourcepack/assets/f8resurs/models/block/"
                                + name + ".json")),
                        name + ": рабочая копия в resourcepack/ разошлась с ресурспаком");
            }
        }
    }

    @Test
    void documentedIdsAreTheOnlyOnesTheHelpersAccept() {
        // «Блок кастомный» — ещё не «это наш блок»: на сервере могут стоять
        // чужие блоки CraftEngine, и их нельзя ни переставлять, ни принимать за
        // свои.
        assertTrue(CraftEngineJug.isJugId(CraftEngineJug.EMPTY_ID));
        assertTrue(CraftEngineJug.isJugId(CraftEngineJug.FILLED_ID));
        assertFalse(CraftEngineJug.isJugId(CraftEngineCopper.ID),
                "медный нотный блок кувшином не считается");
        assertTrue(CraftEngineCopper.isCopperId(CraftEngineCopper.ID));
        assertFalse(CraftEngineCopper.isCopperId(CraftEngineJug.EMPTY_ID),
                "кувшин медным блоком не считается");
        assertFalse(CraftEngineCopper.isCopperId("default:topaz_ore"),
                "чужой блок CraftEngine нашим не считается");
        assertFalse(CraftEngineCopper.isCopperId(null), "ванильный блок нашим не считается");
        assertFalse(CraftEngineJug.isJugId(null), "ванильный блок кувшином не считается");
    }
}
