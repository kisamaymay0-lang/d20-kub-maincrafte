package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полный файл настроек при запуске: чего в файле владельца нет — дописывается из комплекта,
 * значения владельца и его комментарии остаются на месте, повторный проход ничего не меняет.
 *
 * Здесь же проверяются поставляемые settings/*.yml: они должны сами читаться и вместе
 * содержать все ключи, которые читает код, — иначе «полный конфиг» остался бы неполным.
 */
class ConfigSyncTest {

    private static final String DEFAULTS = """
            # Заголовок файла
            a:
              # Что-то про ключ b
              b: 1
              c: 2
            d:
              e:
                f: 3
              g: 4
            """;
    private static final String OLD_FILE = """
            # Заголовок файла
            a:
              # Что-то про ключ b
              b: 42
            """;

    private static YamlConfiguration parse(String text) {
        YamlConfiguration config = ConfigSync.parse(text);
        assertNotNull(config, "YAML должен читаться");
        return config;
    }

    private static String shipped(String name) throws IOException {
        try (InputStream stream = ConfigSyncTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name + " должен лежать в комплекте");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) > 0) out.append(buffer, 0, read);
                return out.toString();
            }
        }
    }

    @Test
    void missingFindsOnlyTheKeysTheFileLacks() {
        List<String> missing = ConfigSync.missing(parse(DEFAULTS), parse(OLD_FILE));
        assertEquals(List.of("a.c", "d"), missing);
        assertEquals(List.of(), ConfigSync.missing(parse(DEFAULTS), parse(DEFAULTS)));
    }

    @Test
    void missingNamesAWholeSectionOnceInsteadOfEveryKeyInside() {
        // Раздел «d» пропал целиком: в списке он один, а не «d», «d.e», «d.e.f», «d.g».
        List<String> missing = ConfigSync.missing(parse(DEFAULTS), parse("a:\n  b: 1\n  c: 2\n"));
        assertEquals(List.of("d"), missing);
        assertFalse(missing.contains("d.e.f"));
    }

    @Test
    void topUpPutsKeysIntoTheirSectionsAndKeepsTheOwnersValues() {
        String result = ConfigSync.topUp(OLD_FILE, DEFAULTS, ConfigSync.missing(parse(DEFAULTS), parse(OLD_FILE)));
        YamlConfiguration parsed = parse(result);          // дубликаты ключей YAML бы не простил
        assertEquals(42, parsed.getInt("a.b"));            // значение владельца на месте
        assertEquals(2, parsed.getInt("a.c"));             // ключ дописан в свой раздел
        assertEquals(3, parsed.getInt("d.e.f"));           // целый раздел дописан вместе с детьми
        assertEquals(4, parsed.getInt("d.g"));
        assertTrue(result.contains("# Что-то про ключ b"));  // комментарий владельца не потерялся
        assertTrue(result.contains(ConfigSync.MARKER));      // видно, что дописано при запуске
        assertTrue(result.startsWith("# Заголовок файла"));  // файл начинается как начинался
        assertEquals(parse(DEFAULTS).getKeys(true), parsed.getKeys(true));
    }

    @Test
    void topUpIsIdempotentAndDoesNotTouchACompleteFile() {
        String once = ConfigSync.topUp(OLD_FILE, DEFAULTS, ConfigSync.missing(parse(DEFAULTS), parse(OLD_FILE)));
        assertEquals(List.of(), ConfigSync.missing(parse(DEFAULTS), parse(once)));
        assertEquals(once, ConfigSync.topUp(once, DEFAULTS, List.of()));
        String twice = ConfigSync.topUp(once, DEFAULTS, ConfigSync.missing(parse(DEFAULTS), parse(once)));
        assertEquals(once, twice);
    }

    @Test
    void topUpDoesNotBreakTheHeaderOfTheNextSection() {
        // У раздела «a» не хватает ключа, а за ним сразу идёт шапка раздела «b». Новый ключ должен
        // встать в конец «a», а не между шапкой «b» и самим «b».
        String defaults = """
                a:
                  x: 1
                  y: 2

                # Шапка раздела b
                b:
                  z: 3
                """;
        String actual = "a:\n  x: 1\n\n# Шапка раздела b\nb:\n  z: 3\n";
        String result = ConfigSync.topUp(actual, defaults, ConfigSync.missing(parse(defaults), parse(actual)));
        int marker = result.indexOf(ConfigSync.MARKER);
        int header = result.indexOf("# Шапка раздела b");
        assertTrue(marker > 0, "вставка помечена");
        assertTrue(header > marker, "шапка следующего раздела остаётся при нём");
        assertTrue(result.contains("  y: 2"), "ключ встал в свой раздел");
        YamlConfiguration parsed = parse(result);
        assertEquals(1, parsed.getInt("a.x"));
        assertEquals(2, parsed.getInt("a.y"));
        assertEquals(3, parsed.getInt("b.z"));
        assertEquals(parse(defaults).getKeys(true), parsed.getKeys(true));
    }

    @Test
    void aNewSectionAtTheEndDoesNotSwallowTheLastOne() {
        // Последний раздел файла неполон, и сразу после него дописывается новый раздел. Куски
        // встают по глубине: строчки нового раздела не должны уехать внутрь прежнего.
        String defaults = """
                gouge:
                  reach: 2.5
                  slide:
                    entry: 1.5
                fishing:
                  chance: 5
                """;
        String actual = "gouge:\n  reach: 2.5\n";
        String result = ConfigSync.topUp(actual, defaults, ConfigSync.missing(parse(defaults), parse(actual)));
        YamlConfiguration parsed = parse(result);
        assertEquals(2.5, parsed.getDouble("gouge.reach"), 1e-9);
        assertEquals(1.5, parsed.getDouble("gouge.slide.entry"), 1e-9);
        assertEquals(5, parsed.getInt("fishing.chance"));
        assertEquals(parse(defaults).getKeys(true), parsed.getKeys(true));
    }

    @Test
    void topUpUnderstandsQuotedKeysWithColonsInside() {
        String defaults = """
                special-items:
                  # Болото
                  "*:swamp": CRAB_CLAW
                  "*:*swamp*": CRAB_CLAW
                """;
        String actual = "settings:\n  a: 1\n";
        String result = ConfigSync.topUp(actual, defaults, ConfigSync.missing(parse(defaults), parse(actual)));
        YamlConfiguration parsed = parse(result);
        // Bukkit хранит такие ключи без кавычек — как их достаёт и сам плагин.
        assertEquals("CRAB_CLAW", parsed.getString("special-items.*:swamp"));
        assertEquals("CRAB_CLAW", parsed.getString("special-items.*:*swamp*"));
        assertEquals(1, parsed.getInt("settings.a"));
        assertEquals(Set.of("*:swamp", "*:*swamp*"), parsed.getConfigurationSection("special-items").getKeys(false));
    }

    @Test
    void subtreesComeOutWithTheirCommentsAndIndentation() {
        String subtree = ConfigSync.subtree(DEFAULTS, "a.b");
        assertTrue(subtree.contains("# Что-то про ключ b"));
        assertTrue(subtree.contains("b: 1"));
        assertFalse(subtree.contains("c: 2"));            // только свой ключ, без соседей
        assertEquals("g: 4\n", ConfigSync.subtree(DEFAULTS, "d.g").replaceAll("^ +", ""));
        assertEquals("", ConfigSync.subtree(DEFAULTS, "нет.такого"));
    }

    @Test
    void keyParsingIgnoresValuesAndReadsQuotedNames() {
        assertEquals("b", ConfigSync.key("b: 1"));
        assertEquals("b", ConfigSync.key("  b:"));
        assertEquals("*:*swamp*", ConfigSync.key("\"*:*swamp*\": CRAB_CLAW"));
        assertNull(ConfigSync.key("- 1"));
        assertNull(ConfigSync.key("- \"строка: с двоеточием\""));
        assertNull(ConfigSync.key("# комментарий"));
        assertNull(ConfigSync.key("просто строка без ключа"));
    }

    @Test
    void blocksSeeSectionsAndTheirEnds() {
        List<String> lines = ConfigSync.linesOf(DEFAULTS);
        assertEquals(9, lines.size());
        var blocks = ConfigSync.blocks(lines);
        assertEquals(1, blocks.get("a").start());                 // строка «a:»
        assertEquals(5, blocks.get("d").start());                 // строка «d:»
        assertEquals(5, blocks.get("a").end());                   // «a» кончается перед «d»
        assertEquals(lines.size(), blocks.get("d").end());        // «d» тянется до конца файла
        assertEquals(0, blocks.get("a").indent());                // ключ верхнего уровня
        assertEquals(2, blocks.get("d.e").indent());              // вложенный раздел
        assertNull(blocks.get("нет.такого"));
    }

    @Test
    void shippedSettingsAreCompleteAndAnOldFileSurvives() throws IOException {
        // Раскладка: настройки лежат по темам, но вместе обязаны покрыть все ключи,
        // которые читает код, — иначе /f8 reload подставит значения по умолчанию.
        YamlConfiguration full = parse(shippedMerged());
        Set<String> everyKey = full.getKeys(true);

        for (String key : List.of("settings.required-hits.lvl1", "settings.damage-interval-normal-ms",
                "rollback.history-seconds", "rollback.effects.slowness-duration",
                "caviar.chance-percent", "constellations.sky-rotation-degrees-per-second",
                "constellations.render-distance", "constellations.depth-fill",
                "constellations.follow-interpolation-ticks", "profiles.date-time-zone",
                "profiles.overhead-tags",
                "gouge.config-version", "gouge.hold-grace-ticks", "gouge.reach",
                "gouge.slide.hard-friction", "gouge.slide.drift-deceleration",
                "gouge.slide-durability.per-block", "gouge.wall-jump.upward-boost",
                "fishing.special-item-chance-percent", "fishing.special-minigame.hit-radius",
                "fishing.special-minigame.ping-compensation", "fishing.crab-claw.reach-blocks",
                "fishing.crab-claw.wear-per-block",
                "pets.speech.player-scale", "pets.crown-gap", "cat-battles.chance-per-tick",
                "copper-note-block.oxidation-minutes", "shaker.glass-models",
                "bamboo_barrel.ticks_per_year", "cigarette.addiction.first-stage-ticks")) {
            assertTrue(everyKey.contains(key), "в settings/ нет ключа " + key);
        }

        // Старый файл: в fishing.yml нет подраздела special-minigame, значение владельца другое.
        String defaults = shipped("settings/fishing.yml");
        List<String> lines = ConfigSync.linesOf(defaults);
        var blocks = ConfigSync.blocks(lines);
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i >= blocks.get("fishing.special-minigame").start()
                    && i < blocks.get("fishing.special-minigame").end()) continue;
            old.append(lines.get(i)).append('\n');
        }
        String oldText = old.toString().replace("special-item-chance-percent: 5", "special-item-chance-percent: 42");
        assertTrue(oldText.contains("42"));

        String result = ConfigSync.topUp(oldText, defaults, ConfigSync.missing(parse(defaults), parse(oldText)));
        YamlConfiguration parsed = parse(result);                 // дубликатов ключей быть не должно
        assertEquals(42, parsed.getInt("fishing.special-item-chance-percent"));   // своё значение сохранилось
        assertEquals(0.8, parsed.getDouble("fishing.special-minigame.hit-radius"), 1e-9);
        assertEquals(true, parsed.getBoolean("fishing.special-minigame.ping-compensation"));
        assertEquals(parse(defaults).getKeys(true), parsed.getKeys(true));        // файл снова полный
        assertTrue(result.contains(ConfigSync.MARKER));
    }

    /** Все settings/*.yml, склеенные так же, как это делает PluginSettings. */
    private static String shippedMerged() throws IOException {
        StringBuilder merged = new StringBuilder();
        for (String file : PluginSettings.FILES) merged.append(shipped("settings/" + file)).append('\n');
        return merged.toString();
    }

    @Test
    void everySettingsFileReadsAndIsNotAPlaceholder() throws IOException {
        assertEquals(PluginSettings.FILES.size(), ConfigSync.FILES.size());
        for (String file : PluginSettings.FILES) {
            String name = "settings/" + file;
            String text = shipped(name);
            assertNotNull(ConfigSync.parse(text), name + " должен читаться как YAML");
            assertFalse(text.isBlank(), name + " не должен быть пустым");
            assertTrue(text.contains(":"), name + " должен содержать ключи");
        }
    }

    @Test
    void settingsFilesDoNotOverlap() throws IOException {
        // Один ключ — один файл: иначе значение молча перекрывалось бы соседним.
        Map<String, String> owner = new HashMap<>();
        for (String file : PluginSettings.FILES) {
            YamlConfiguration part = ConfigSync.parse(ShippedSettings.read("settings/" + file));
            assertNotNull(part, "settings/" + file + " должен читаться как YAML");
            for (String key : part.getKeys(true)) {
                if (part.get(key) instanceof ConfigurationSection) continue;
                String previous = owner.put(key, file);
                assertNull(previous, "ключ " + key + " есть и в " + previous + ", и в " + file);
            }
        }
    }

    @Test
    void shippedFolderHasNoMonolithLeftBehind() {
        // Старый общий config.yml в комплекте не остаётся: настройки живут только в settings/.
        assertFalse(ConfigSync.FILES.contains("config.yml"));
        assertTrue(ConfigSync.FILES.stream().allMatch(name -> name.startsWith("settings/")));
    }
}
