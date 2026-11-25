package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticCatalogTest {
    @TempDir Path directory;

    private CosmeticCatalog load(String text) throws Exception {
        Path file = directory.resolve("cosmetics.yml");
        Files.writeString(file, text);
        return CosmeticCatalog.load(file);
    }

    @Test
    void defaultKitIsTheThreePackModelsInOrder() {
        CosmeticCatalog catalog = CosmeticCatalog.defaults();
        assertEquals(3, catalog.size());
        List<CosmeticCatalog.Cosmetic> list = catalog.list();
        for (int i = 1; i <= 3; i++) {
            CosmeticCatalog.Cosmetic cosmetic = list.get(i - 1);
            assertEquals("kosmetika" + i, cosmetic.id());
            assertEquals("kosmetika" + i, cosmetic.file(), "Файл модели совпадает с id комплекта");
            assertEquals(i, cosmetic.number());
            assertSame(cosmetic, catalog.byNumber(i));
        }
        assertNull(catalog.byNumber(0));
        assertNull(catalog.byNumber(4));
        assertEquals("kosmetika2", catalog.resolve("2").id(), "Номер из команды находит косметику");
        assertEquals("kosmetika3", catalog.resolve("kosmetika3").id());
        assertNull(catalog.resolve("kosmetika9"));
    }

    @Test
    void parsesNameColorAndModelFileInOrder() throws Exception {
        CosmeticCatalog catalog = load("""
                cosmetics:
                  kosmetika1:
                    name: 'Рога'
                    color: '#8FE3F5'
                    file: kosmetika1
                  second:
                    name: 'Нимб'
                    color: '#E6B94B'
                    file: kosmetika2
                """);
        assertEquals(2, catalog.size());
        List<CosmeticCatalog.Cosmetic> list = catalog.list();
        assertEquals("Рога", list.get(0).name());
        assertEquals(0x8FE3F5, list.get(0).color().value());
        assertEquals("kosmetika2", list.get(1).file());
        assertEquals(2, catalog.get("second").number());
        assertEquals("second", catalog.resolve("Нимб").id(), "Имя тоже находит косметику");
        assertNull(catalog.get("kosmetika2"));
    }

    @Test
    void packMakeupIsMergedIntoCatalogWithoutChangingExistingNumbers() throws Exception {
        Path primary = directory.resolve("cosmetics.yml");
        Files.writeString(primary, """
                cosmetics:
                  base:
                    name: 'Базовая'
                    color: '#8FE3F5'
                    file: kosmetika1
                """);
        Path packs = directory.resolve("cosmeticpacks");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("nabor.yml"), """
                name: 'Набор'
                time-hours: -1
                cosmetics:
                  - kosmetika1
                  - new_hat
                """);

        CosmeticCatalog base = CosmeticCatalog.load(primary);
        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(packs, base, null);
        CosmeticCatalog merged = base.withPacks(catalog);

        // Косметика из настроек не задваивается, а новая из набора получает свободный номер.
        assertEquals(2, merged.size());
        assertEquals(1, merged.get("base").number());
        assertEquals(2, merged.get("nabor_new_hat").number());
        assertEquals("new_hat", merged.get("nabor_new_hat").file());
    }

    @Test
    void additionalFolderIsGoneAndOnlyOneFileIsRead() throws Exception {
        // Дополнительных файлов у косметики больше нет: папка рядом с основным
        // файлом игнорируется, читается только settings/cosmetics.yml.
        Path primary = directory.resolve("cosmetics.yml");
        Files.writeString(primary, """
                cosmetics:
                  base:
                    name: 'Базовая'
                    color: '#8FE3F5'
                    file: kosmetika1
                """);
        Path extra = directory.resolve("cosmetics");
        Files.createDirectories(extra);
        Files.writeString(extra.resolve("02-new.yml"), """
                cosmetics:
                  new_hat:
                    name: 'Новая шапка'
                    color: '#E6B94B'
                    file: new_hat
                """);

        CosmeticCatalog catalog = CosmeticCatalog.load(primary);
        assertEquals(1, catalog.size());
        assertNull(catalog.get("new_hat"));
    }

    @Test
    void fileDefaultsToIdAndMissingFieldsAreConfigErrors() throws Exception {
        CosmeticCatalog catalog = load("""
                cosmetics:
                  third:
                    name: 'Уши'
                    color: '#7BE0A0'
                """);
        assertEquals("third", catalog.get("third").file());
        assertThrows(IllegalArgumentException.class, () -> load("""
                cosmetics:
                  kosmetika1:
                    name: ''
                    color: '#8FE3F5'
                    file: kosmetika1
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                cosmetics:
                  kosmetika1:
                    name: 'Плохой цвет'
                    color: 'no-such-color'
                    file: kosmetika1
                """));
        assertThrows(IllegalArgumentException.class, () -> load("prefixes:\n  pref1:\n    name: 'x'\n"),
                "Без раздела cosmetics файл не принимается");
    }
}
