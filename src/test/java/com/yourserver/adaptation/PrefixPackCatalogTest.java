package com.yourserver.adaptation;

import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Паки префиксов: файл пака, срок кейса и свои префиксы внутри. */
final class PrefixPackCatalogTest {

    private static final String TEST_PACK = """
            name: 'Тестовый пак'
            time-hours: -1
            prefixes:
              - name: 'Морозный'
                file: pref1
              - name: 'Аврора'
                file: pref9
                color: '#7FF0D2'
            """;

    @Test
    void пакЧитаетсяИзСвоегоФайла(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);

        PrefixPackCatalog catalog = PrefixPackCatalog.load(folder);
        assertEquals(1, catalog.size());

        PrefixPackCatalog.Pack pack = catalog.get("pref-pack1");
        assertNotNull(pack);
        assertEquals("Тестовый пак", pack.name());
        assertTrue(pack.infinite(), "time-hours: -1 — бессрочный пак");
        assertEquals(2, pack.size());
    }

    @Test
    void префиксыПакаПолучаютСвоиIdИИконки(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);

        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder).get("pref-pack1");
        assertNotNull(pack);
        assertEquals("pref-pack1_pref1", pack.prefixes().get(0).id(), "id собирается из пака и иконки");
        assertEquals("pref1", pack.prefixes().get(0).file());
        assertEquals("Морозный", pack.prefixes().get(0).name());
        assertEquals("pref-pack1_pref9", pack.prefixes().get(1).id());
        assertEquals(TextColor.fromHexString("#7FF0D2"), pack.prefixes().get(1).color());
    }

    @Test
    void свойIdИзФайлаНеПереписывается(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("mine.yml"), """
                name: 'Свой пак'
                time-hours: 5
                prefixes:
                  - id: my-prefix
                    name: 'Свой'
                    file: pref3
                """);

        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder).get("mine");
        assertNotNull(pack);
        assertEquals("my-prefix", pack.prefixes().get(0).id());
        assertFalse(pack.infinite());
        assertEquals(5, pack.timeHours());
    }

    @Test
    void пакБезПрефиксовПропускается(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("empty.yml"), """
                name: 'Пустой'
                time-hours: 1
                prefixes: []
                """);
        Files.writeString(folder.resolve("ok.yml"), TEST_PACK);

        PrefixPackCatalog catalog = PrefixPackCatalog.load(folder);
        assertEquals(1, catalog.size());
        assertNull(catalog.get("empty"));
        assertNotNull(catalog.get("ok"));
    }

    @Test
    void записьБезИмениИлиИконкиПропускается(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("half.yml"), """
                name: 'Половина'
                time-hours: 1
                prefixes:
                  - name: 'Без иконки'
                  - file: pref4
                  - name: 'Полный'
                    file: pref5
                """);

        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder).get("half");
        assertNotNull(pack);
        assertEquals(1, pack.size(), "Из трёх записей годится одна");
        assertEquals("Полный", pack.prefixes().get(0).name());
    }

    @Test
    void пустойКаталогБезПапкиНеПадает() {
        assertEquals(0, PrefixPackCatalog.load(null).size());
        assertEquals(0, PrefixPackCatalog.load(Path.of("нет-такой-папки")).size());
    }

    @Test
    void пакНаходитсяПоИмени(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);
        PrefixPackCatalog catalog = PrefixPackCatalog.load(folder);

        assertEquals("pref-pack1", catalog.resolve("pref-pack1").id());
        assertEquals("pref-pack1", catalog.resolve("Тестовый пак").id());
        assertNull(catalog.resolve("нет такого"));
    }

    @Test
    void срокБессрочногоКейсаНикогдаНеИстекает() {
        long now = 1_700_000_000_000L;
        assertEquals(Long.MAX_VALUE, PrefixPackCatalog.expiresAt(now, -1));
        assertEquals(Long.MAX_VALUE, PrefixPackCatalog.expiresAt(now, -5));
        assertEquals("бессрочный", PrefixPackCatalog.leftText(Long.MAX_VALUE, now));
    }

    @Test
    void срокСчитаетсяВЧасах() {
        long now = 1_700_000_000_000L;
        assertEquals(now + 24 * 3_600_000L, PrefixPackCatalog.expiresAt(now, 24));
        assertEquals(now, PrefixPackCatalog.expiresAt(now, 0), "Ноль часов — сгорает сразу");
    }

    @Test
    void остатокПишетсяПоЧеловечески() {
        long now = 1_700_000_000_000L;
        assertEquals("30 мин", PrefixPackCatalog.leftText(now + 30 * 60_000L, now));
        assertEquals("5 ч", PrefixPackCatalog.leftText(now + 5 * 3_600_000L, now));
        assertEquals("2 д", PrefixPackCatalog.leftText(now + 50 * 3_600_000L, now));
        assertEquals("сгорел", PrefixPackCatalog.leftText(now - 1, now));
        assertEquals("меньше минуты", PrefixPackCatalog.leftText(now + 1_000L, now));
    }

    @Test
    void shippedТестовыйПакРаскладывается() throws Exception {
        // Тот самый файл, что уезжает в jar и копируется игроку при первом запуске.
        Path shipped = Path.of("src/main/resources/prefixpacks/pref-pack1.yml");
        assertTrue(Files.exists(shipped), "Тестовый пак должен лежать в ресурсах плагина");
        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(shipped.getParent()).get("pref-pack1");
        assertNotNull(pack);
        assertEquals("Тестовый пак", pack.name());
        assertTrue(pack.infinite());
        assertFalse(pack.prefixes().isEmpty());
        for (PrefixCatalog.Prefix prefix : pack.prefixes()) {
            assertFalse(prefix.name().isEmpty());
            assertFalse(prefix.file().isEmpty());
        }
    }

    @Test
    void idПриводитсяКБезопасномуВиду() {
        assertEquals("pref-pack1_pref1", PrefixPackCatalog.sanitize("Pref Pack1 pref1"));
        assertEquals("a_b_c", PrefixPackCatalog.sanitize("a__b  c"));
        assertEquals("", PrefixPackCatalog.sanitize(null));
    }
}
