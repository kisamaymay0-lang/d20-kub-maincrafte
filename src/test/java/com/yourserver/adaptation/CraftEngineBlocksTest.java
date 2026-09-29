package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Конфиги кастомных блоков — против формата, который читает сам CraftEngine.
 *
 * <p>Проверяются сами файлы из {@code docs/craftengine-blocks/}: их копируют в
 * пак {@code plugins/CraftEngine/resources/<имя>/configuration/blocks/}, так что
 * тест читает ровно то, что окажется на сервере. Ресурспак с моделями на сервер
 * кладут отдельно, поэтому здесь сверяется только конфиг и пути моделей.
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
    private static final Path CONFIGS = ROOT.resolve("docs/craftengine-blocks");
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

    /** Все конфиги блоков: id -> секция блока, по одному на файл. */
    private static Map<String, ConfigurationSection> blocks() throws Exception {
        assertTrue(Files.isDirectory(CONFIGS),
                "Нет папки с конфигами блоков: " + ROOT.relativize(CONFIGS));
        Map<String, ConfigurationSection> blocks = new LinkedHashMap<>();
        try (var files = Files.list(CONFIGS)) {
            List<Path> yaml = files.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
            assertFalse(yaml.isEmpty(), "В " + ROOT.relativize(CONFIGS) + " нет ни одного .yml");
            for (Path file : yaml) {
                YamlConfiguration loaded = new YamlConfiguration();
                loaded.loadFromString(Files.readString(file));
                ConfigurationSection section = loaded.getConfigurationSection("blocks");
                assertNotNull(section, ROOT.relativize(file)
                        + ": нужен корневой раздел blocks — иначе файл ничего не описывает");
                for (String id : section.getKeys(false)) {
                    assertNull(blocks.put(id, section.getConfigurationSection(id)),
                            id + " описан в двух файлах: один и тот же id в двух паках — конфликт");
                }
            }
        }
        return blocks;
    }

    @Test
    void configFilesDescribeExactlyTheBlocksThePluginUses() throws Exception {
        // Обратная связь в обе стороны: добавят блок в плагин — тест потребует
        // описать его в конфиге, и наоборот.
        assertEquals(WANTED_IDS, blocks().keySet(),
                "id блоков в конфигах и в плагине обязаны совпадать");
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.EMPTY_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.FILLED_ID);
        assertEquals("f8resurs:ancient_jug_filled", CraftEngineJug.keyName(1));
        assertEquals("f8resurs:ancient_jug", CraftEngineJug.keyName(0));
        assertEquals("f8resurs:copper_note_block", CraftEngineCopper.ID);
    }

    @Test
    void copperBlockHasItsOwnFileToDropIntoThePack() throws Exception {
        Path file = CONFIGS.resolve("copper_note_block.yml");
        assertTrue(Files.isRegularFile(file),
                "Нужен отдельный файл для медного нотного блока: " + ROOT.relativize(file));
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(Files.readString(file));
        assertEquals(Set.of(CraftEngineCopper.ID),
                loaded.getConfigurationSection("blocks").getKeys(false),
                "в файле медного блока должен быть только он: кувшин может быть уже "
                        + "прописан в паке, и дубль id дал бы конфликт");
        assertTrue(Files.readString(DOC).contains("copper_note_block.yml"),
                "документ должен вести к файлу с конфигом");
    }

    @Test
    void pluginMessagesPointAtThisDocument() {
        assertEquals("docs/craftengine-blocks.md", CraftEngineSupport.CONFIG_DOC);
        assertTrue(Files.isRegularFile(ROOT.resolve(CraftEngineSupport.CONFIG_DOC)),
                "Путь к документу в сообщениях должен вести в существующий файл");
    }

    @Test
    void documentedPackNamespaceMatchesTheBlockIds() throws Exception {
        // Образец pack.yml живёт в документе: namespace пака обязан совпадать с
        // началом id блоков, иначе CraftEngine зарегистрирует их под другим
        // именем и плагин их не найдёт.
        String text = Files.readString(DOC);
        Matcher matcher = Pattern.compile("```yaml\\R(.*?)```", Pattern.DOTALL).matcher(text);
        assertTrue(matcher.find(), "В документе нет образца pack.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(matcher.group(1));
        String namespace = yaml.getString("namespace");
        assertNotNull(namespace, "В образце pack.yml нужен namespace");
        for (String id : blocks().keySet()) {
            assertEquals(namespace, id.substring(0, id.indexOf(':')),
                    "Пространство имён блока совпадает с namespace пака: " + id);
        }
    }

    @Test
    void everyBlockHasAStateCraftEngineCanUse() throws Exception {
        for (var entry : blocks().entrySet()) {
            String id = entry.getKey();
            ConfigurationSection state = entry.getValue().getConfigurationSection("state");
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
        // Моделей в репозитории нет: они в том ресурспаке, который сервер и так
        // ставит в CraftEngine. Если сослаться на модель, которой там нет, на
        // месте блока будет носитель — и понять это можно только в игре.
        assertTrue(Files.isRegularFile(SHIPPED_PACK), "Нет собранного ресурспака для сверки");
        try (ZipFile zip = new ZipFile(SHIPPED_PACK.toFile())) {
            for (var entry : blocks().entrySet()) {
                String path = entry.getValue().getConfigurationSection("state")
                        .getConfigurationSection("model").getString("path");
                String[] split = path.split(":", 2);
                String name = "assets/" + split[0] + "/models/" + split[1] + ".json";
                ZipEntry model = zip.getEntry(name);
                assertNotNull(model, entry.getKey() + ": модели " + path
                        + " нет в ресурспаке (" + name + ")");
            }
        }
    }

    @Test
    void settingsUseOnlyKeysCraftEngineParses() throws Exception {
        for (var entry : blocks().entrySet()) {
            String id = entry.getKey();
            ConfigurationSection settings = entry.getValue().getConfigurationSection("settings");
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
        List<String> keys = List.of("is_view_blocking", "can_occlude", "is_suffocating",
                "is_redstone_conductor", "propagate_skylight");
        for (var entry : blocks().entrySet()) {
            ConfigurationSection settings = entry.getValue().getConfigurationSection("settings");
            for (String key : keys) {
                assertTrue(settings.contains(key),
                        entry.getKey() + ": settings." + key + " не задан явно");
            }
        }
    }

    @Test
    void jugIsNotASolidCubeForTheClient() throws Exception {
        // Носитель mushroom_stem — глухой куб. Без этих ключей соседние блоки
        // не рисуют грани рядом с кувшином и проводят редстоун: так кувшин
        // 10.13 оставлял дыры в постройке.
        for (String id : List.of(CraftEngineJug.EMPTY_ID, CraftEngineJug.FILLED_ID)) {
            ConfigurationSection settings = blocks().get(id).getConfigurationSection("settings");
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
        ConfigurationSection settings = blocks().get(CraftEngineCopper.ID)
                .getConfigurationSection("settings");
        assertTrue(settings.getBoolean("is_view_blocking", false), "медный блок — глухой куб");
        assertTrue(settings.getBoolean("can_occlude", false), "медный блок — глухой куб");
        assertTrue(settings.getBoolean("is_suffocating", false), "медный блок — глухой куб");
        assertTrue(settings.getBoolean("is_redstone_conductor", false),
                "медный блок должен проводить редстоун, иначе он не сработает от провода");
        assertFalse(settings.getBoolean("propagate_skylight", true),
                "глухой куб свет не пропускает");
    }

    @Test
    void copperBlockMinesLikeTheNoteBlockItReplaces() throws Exception {
        ConfigurationSection settings = blocks().get(CraftEngineCopper.ID)
                .getConfigurationSection("settings");
        assertEquals(0.8, settings.getDouble("hardness"), 1e-9,
                "у ванильного NOTE_BLOCK hardness 0.8");
        assertEquals(0.8, settings.getDouble("resistance"), 1e-9,
                "у ванильного NOTE_BLOCK resistance 0.8");
        assertTrue(settings.getStringList("tags").contains("minecraft:mineable/axe"),
                "топор должен ускорять копание: нотный блок в ванили в этом теге");
        assertNull(settings.get("correct_tools"),
                "correct_tools включил бы require_correct_tools, и без топора блок "
                        + "не дропал бы вовсе, а нотный блок ломается чем угодно");
        assertNull(settings.get("require_correct_tools"),
                "нотный блок ломается чем угодно");
    }

    @Test
    void jugBreaksInstantlyWithAnything() throws Exception {
        for (String id : List.of(CraftEngineJug.EMPTY_ID, CraftEngineJug.FILLED_ID)) {
            ConfigurationSection settings = blocks().get(id).getConfigurationSection("settings");
            assertEquals(0.0, settings.getDouble("hardness"), 1e-9,
                    id + ": при нулевой прочности блок ломается за один тик");
            assertNull(settings.get("correct_tools"),
                    id + ": кувшин ломается любым инструментом");
            assertNull(settings.get("require_correct_tools"),
                    id + ": кувшин ломается любым инструментом");
        }
    }

    @Test
    void tunedNoteBlockNoLongerLooksLikeTheCopperBlock() throws Exception {
        // Нота 24 снова рисует обычный нотный блок. Модель
        // f8resurs:block/copper_note_block остаётся в паке — на неё ссылается
        // state.model.path блока CraftEngine, — но блок-стейт нотного блока к
        // ней больше не привязан.
        String model = "f8resurs:block/copper_note_block";
        assertFalse(Files.readString(ROOT.resolve(
                        "resourcepack/assets/minecraft/blockstates/note_block.json")).contains(model),
                "настроенный до ноты 24 нотный блок не должен рисоваться медным");
        try (ZipFile zip = new ZipFile(SHIPPED_PACK.toFile())) {
            ZipEntry entry = zip.getEntry("assets/minecraft/blockstates/note_block.json");
            assertNotNull(entry, "в ресурспаке нет блок-стейта нотного блока");
            try (var in = zip.getInputStream(entry)) {
                assertFalse(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(model),
                        "в собранном ресурспаке нота 24 всё ещё рисуется медным блоком");
            }
        }
    }

    @Test
    void noTunedNoteBlockPathRemainsInTheListener() {
        // Медный нотный блок получается только крафтом. До 10.21 признаком была
        // нота 24, и любой настроенный нотный блок считался медным — открывал
        // меню и дропал предмет медного блока. Если этот путь вернут, тест
        // это поймает.
        assertThrows(NoSuchFieldException.class,
                () -> CopperBlockListener.class.getDeclaredField("MARKER_NOTE"),
                "признак «нота 24» у медного блока больше не используется");
        for (var method : CopperBlockListener.class.getDeclaredMethods()) {
            assertNotEquals("isLegacyCopperBlock", method.getName(),
                    "обычный нотный блок медным больше не считается");
            assertNotEquals("migrate", method.getName(),
                    "переноса нотных блоков в медные больше нет");
        }
    }

    @Test
    void noLootTableBecauseThePluginDropsTheItemsItself() throws Exception {
        for (var entry : blocks().entrySet()) {
            assertNull(entry.getValue().get("loot"), entry.getKey()
                    + ": loot не нужен — предмет роняет плагин, иначе дроп получится двойным");
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
    void theRepoNoLongerShipsACraftEnginePack() throws Exception {
        // Папки с паком (pack.yml + копии моделей) в пул-реквесте носить
        // перестали: ресурспак в CraftEngine кладёт сам сервер, а дубликат
        // моделей только устаревал. Остались конфиги — их и копируют в пак.
        assertFalse(Files.exists(ROOT.resolve("craftengine")),
                "Папку craftengine/ в репозитории быть не должно");
        assertFalse(Files.exists(ROOT.resolve("docs/craftengine")),
                "docs/craftengine с конфигом вне пака больше не нужен");
        List<String> names;
        try (var files = Files.list(CONFIGS)) {
            names = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("ancient_jug.yml", "copper_note_block.yml"), names,
                "в папке только конфиги блоков: pack.yml у вас свой, а копии моделей "
                        + "берутся из вашего ресурспака");
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
