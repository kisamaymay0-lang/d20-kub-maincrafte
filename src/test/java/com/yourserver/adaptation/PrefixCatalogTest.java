package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrefixCatalogTest {
    @TempDir Path directory;

    private PrefixCatalog load(String text) throws Exception {
        Path file = directory.resolve("prefixes.yml");
        Files.writeString(file, text);
        return PrefixCatalog.load(file);
    }

    @Test
    void parsesNameColorAndIconFileInOrder() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  pref1:
                    name: 'Морозный'
                    color: '#8FE3F5'
                    file: pref1
                  pref2:
                    name: 'Зимний мастер'
                    color: '#E6B94B'
                    file: custom_icon
                """);
        assertEquals(2, catalog.size());
        List<PrefixCatalog.Prefix> list = catalog.list();
        assertEquals("pref1", list.get(0).id());
        assertEquals("Морозный", list.get(0).name());
        assertEquals(0x8FE3F5, list.get(0).color().value());
        assertEquals("pref1", list.get(0).file());
        assertEquals("custom_icon", list.get(1).file());
        assertEquals("Зимний мастер", catalog.get("pref2").name());
        assertNull(catalog.get("pref3"));
    }

    @Test
    void префиксНаходитсяПоФайлу() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  pref1:
                    name: 'Морозный'
                    color: '#8FE3F5'
                    file: pref1
                  pref2:
                    name: 'Зимний мастер'
                    color: '#E6B94B'
                    file: custom_icon
                """);
        assertEquals("pref2", catalog.byFile("custom_icon").id());
        assertEquals("pref1", catalog.byFile("pref1").id());
        assertNull(catalog.byFile("нет-такого"));
        assertNull(catalog.byFile(null));
        assertNull(catalog.byFile(""));
    }

    @Test
    void пакиДобавляютсяПослеСвоих(@TempDir Path packFolder) throws Exception {
        PrefixCatalog base = load("""
                prefixes:
                  pref1:
                    name: 'Морозный'
                    color: '#8FE3F5'
                    file: pref1
                """);
        Files.writeString(packFolder.resolve("pref-pack1.yml"), """
                name: 'Тестовый пак'
                prefixes:
                  - pref1
                  - file: my_icon
                    name: 'Свой'
                """);
        PrefixPackCatalog packs = PrefixPackCatalog.load(packFolder, base, null);

        PrefixCatalog all = base.withPacks(packs);
        assertEquals(2, all.size(), "префикс пака, который уже есть в prefixes.yml, не дублируется");
        assertEquals("pref1", all.list().get(0).id());
        assertEquals(1, all.list().get(0).number(), "свои префиксы сохраняют номера");
        assertEquals("pref-pack1_my_icon", all.list().get(1).id(), "префикс пака идёт после своих");
        assertEquals(2, all.list().get(1).number());
        assertEquals("Свой", all.list().get(1).name());
    }

    @Test
    void fileDefaultsToIdAndMissingFieldsAreConfigErrors() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  pref7:
                    name: 'Северный'
                    color: '#7BE0A0'
                """);
        assertEquals("pref7", catalog.get("pref7").file());
        assertThrows(IllegalArgumentException.class, () -> load("""
                prefixes:
                  pref1:
                    name: ''
                    color: '#8FE3F5'
                    file: pref1
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                prefixes:
                  pref1:
                    name: 'Плохой цвет'
                    color: 'no-such-color'
                    file: pref1
                """));
    }
}
