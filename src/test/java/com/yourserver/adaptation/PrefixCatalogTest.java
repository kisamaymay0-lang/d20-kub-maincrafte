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
