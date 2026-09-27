package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Паки ссылаются на файлы из prefixes.yml — не создают копии префиксов. */
final class PrefixPackCatalogTest {

    private static final String TEST_PACK = """
            name: 'Тестовый пак'
            time-hours: -1
            prefixes:
              - pref1
              - pref9
            """;

    private static PrefixCatalog catalog() throws Exception {
        return PrefixCatalog.load(Path.of("src/main/resources/prefixes.yml"));
    }

    @Test
    void пакЧитаетсяИзСвоегоФайла(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);
        PrefixPackCatalog packs = PrefixPackCatalog.load(folder, catalog());
        PrefixPackCatalog.Pack pack = packs.get("pref-pack1");
        assertNotNull(pack);
        assertEquals("Тестовый пак", pack.name());
        assertTrue(pack.infinite());
        assertEquals(2, pack.size());
    }

    @Test
    void имяЦветИНомерИзОсновногоКонфигаБезДублей(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);
        PrefixCatalog base = catalog();
        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder, base).get("pref-pack1");
        assertNotNull(pack);
        assertEquals(base.get("pref1"), pack.prefixes().get(0));
        assertEquals(base.get("pref9"), pack.prefixes().get(1));
        assertEquals("pref1", pack.prefixes().get(0).id(), "собственного id пака больше нет");
        assertEquals("Морозный", pack.prefixes().get(0).name());
        assertEquals(base.size(), catalog().size(), "пак не добавляет префиксы в общий список");
    }

    @Test
    void старыйФорматЧитаетсяБезИмёнИЦветов(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("old.yml"), """
                name: 'Старый'
                time-hours: 5
                prefixes:
                  - id: old_pref3
                    name: 'Другое имя'
                    file: pref3
                    color: '#FFFFFF'
                """);
        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder, catalog()).get("old");
        assertNotNull(pack);
        assertEquals("pref3", pack.prefixes().get(0).id());
        assertEquals("Звездочёт", pack.prefixes().get(0).name());
        assertFalse(pack.infinite());
        assertEquals(5, pack.timeHours());
    }

    @Test
    void одинФайлНеПовторяетсяВПаке(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("repeat.yml"), """
                prefixes: [pref1, pref1, pref9]
                """);
        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(folder, catalog()).get("repeat");
        assertNotNull(pack);
        assertEquals(2, pack.size());
    }

    @Test
    void неизвестныйФайлДаётОшибкуАНеПустойКейс(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("bad.yml"), """
                prefixes: [pref1, typo]
                """);
        Exception error = assertThrows(IllegalArgumentException.class, () -> PrefixPackCatalog.load(folder, catalog()));
        assertTrue(error.getMessage().contains("typo"));
    }

    @Test
    void пакБезПрефиксовПропускается(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("empty.yml"), "prefixes: []\n");
        Files.writeString(folder.resolve("ok.yml"), TEST_PACK);
        PrefixPackCatalog packs = PrefixPackCatalog.load(folder, catalog());
        assertEquals(1, packs.size());
        assertNull(packs.get("empty"));
        assertNotNull(packs.get("ok"));
    }

    @Test
    void пустойКаталогБезПапкиНеПадает() throws Exception {
        assertEquals(0, PrefixPackCatalog.load(null, catalog()).size());
        assertEquals(0, PrefixPackCatalog.load(Path.of("нет-такой-папки"), catalog()).size());
    }

    @Test
    void пакНаходитсяПоИмени(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("pref-pack1.yml"), TEST_PACK);
        PrefixPackCatalog packs = PrefixPackCatalog.load(folder, catalog());
        assertEquals("pref-pack1", packs.resolve("pref-pack1").id());
        assertEquals("pref-pack1", packs.resolve("Тестовый пак").id());
        assertNull(packs.resolve("нет такого"));
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
        assertEquals(now, PrefixPackCatalog.expiresAt(now, 0));
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
        Path shipped = Path.of("src/main/resources/prefixpacks/pref-pack1.yml");
        assertTrue(Files.exists(shipped));
        PrefixPackCatalog.Pack pack = PrefixPackCatalog.load(shipped.getParent(), catalog()).get("pref-pack1");
        assertNotNull(pack);
        assertEquals("Тестовый пак", pack.name());
        assertTrue(pack.infinite());
        assertEquals(4, pack.size());
    }

    @Test
    void idПриводитсяКБезопасномуВиду() {
        assertEquals("pref-pack1_pref1", PrefixPackCatalog.sanitize("Pref-Pack1 pref1"));
        assertEquals("pref_pack1_pref1", PrefixPackCatalog.sanitize("Pref Pack1 pref1"));
        assertEquals("a_b_c", PrefixPackCatalog.sanitize("a__b  c"));
        assertEquals("", PrefixPackCatalog.sanitize(null));
    }
}
