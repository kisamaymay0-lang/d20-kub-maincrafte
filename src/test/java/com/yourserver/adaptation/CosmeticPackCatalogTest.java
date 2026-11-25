package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Наборы косметики: файл набора, срок кейса и косметика, взятая из cosmetics.yml. */
final class CosmeticPackCatalogTest {

    private static final String TEST_PACK = """
            name: 'Летний набор'
            time-hours: 24
            cosmetics:
              - kosmetika1
              - file: kosmetika2
                name: 'Своё имя'
              - file: kosmetika9
                color: '#7FF0D2'
            """;

    /** cosmetics.yml, по которому наборы находят имена и цвета. */
    private static final String COSMETICS = """
            cosmetics:
              kosmetika1:
                name: 'Косметика №1'
                color: '#E6B94B'
                file: kosmetika1
              kosmetika2:
                name: 'Косметика №2'
                color: '#8FE3F5'
                file: kosmetika2
              kosmetika3:
                name: 'Косметика №3'
                color: '#C79BF0'
                file: kosmetika3
            """;

    private CosmeticCatalog cosmetics(Path folder) throws Exception {
        Files.writeString(folder.resolve("cosmetics.yml"), COSMETICS);
        return CosmeticCatalog.load(folder.resolve("cosmetics.yml"));
    }

    private Path packFolder(Path folder) throws Exception {
        Path packs = folder.resolve("cosmeticpacks");
        Files.createDirectories(packs);
        return packs;
    }

    @Test
    void наборЧитаетсяИзСвоегоФайла(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("letniy.yml"), TEST_PACK);

        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(packs);
        assertEquals(1, catalog.size());

        CosmeticPackCatalog.Pack pack = catalog.get("letniy");
        assertNotNull(pack);
        assertEquals("Летний набор", pack.name());
        assertEquals(24, pack.timeHours());
        assertEquals(3, pack.size());
        assertEquals(List.of("kosmetika1", "kosmetika2", "letniy_kosmetika9"), pack.itemIds());
    }

    @Test
    void именаИЦветаБерутсяИзНастроек(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("letniy.yml"), TEST_PACK);
        CosmeticCatalog base = cosmetics(folder);

        CosmeticPackCatalog.Pack pack = CosmeticPackCatalog.load(packs, base, null).get("letniy");
        CosmeticCatalog.Cosmetic first = pack.cosmetics().get(0);
        assertEquals("kosmetika1", first.id(), "id — как в настройках");
        assertEquals("Косметика №1", first.name());
        assertEquals(1, first.number(), "номер — из имени модели");

        // Имя переопределено на месте, цвет остался из настроек.
        CosmeticCatalog.Cosmetic second = pack.cosmetics().get(1);
        assertEquals("kosmetika2", second.id());
        assertEquals("Своё имя", second.name());
        assertEquals("#8FE3F5", second.color().asHexString());

        // Модели нет в настройках: набор заводит свою запись из пака и файла.
        CosmeticCatalog.Cosmetic third = pack.cosmetics().get(2);
        assertEquals("letniy_kosmetika9", third.id());
        assertEquals("kosmetika9", third.name());
        assertEquals("#7FF0D2", third.color().asHexString());
    }

    @Test
    void онемецкойМоделиПредупреждаетВЖурнал(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("letniy.yml"), TEST_PACK);
        List<String> warnings = new ArrayList<>();

        CosmeticPackCatalog.load(packs, cosmetics(folder), warnings::add);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("kosmetika9"));
        assertTrue(warnings.get(0).contains("settings/cosmetics.yml"));
    }

    @Test
    void косметикаНабораВходитВОбщийКаталог(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("letniy.yml"), """
                name: 'Летний набор'
                time-hours: -1
                cosmetics:
                  - kosmetika1
                  - kosmetika5
                """);
        CosmeticCatalog base = cosmetics(folder);
        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(packs, base, null);

        CosmeticCatalog merged = base.withPacks(catalog);
        assertEquals(4, merged.size(), "три из настроек и одна новая из набора");
        assertEquals(1, merged.get("kosmetika1").number(), "старые номера не сдвигаются");
        assertEquals(5, merged.get("letniy_kosmetika5").number(), "номер из имени модели");
        assertEquals("kosmetika5", merged.get("letniy_kosmetika5").file());
        // По файлу модели косметику находят и наборы, и меню.
        assertEquals("kosmetika2", merged.byFile("kosmetika2").id());
    }

    @Test
    void срокиКейсовСчитаютсяКакУПаковПрефиксов(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("vechniy.yml"), """
                name: 'Вечный'
                time-hours: -1
                cosmetics: [kosmetika1]
                """);
        Files.writeString(packs.resolve("chas.yml"), """
                name: 'Часовой'
                cosmetics: [kosmetika2]
                """);

        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(packs);
        assertEquals(2, catalog.size());
        assertTrue(catalog.get("vechniy").infinite());
        assertEquals(-1, catalog.get("vechniy").timeHours());
        assertEquals(-1, catalog.get("chas").timeHours(), "без time-hours кейс бессрочный");
        assertEquals(Long.MAX_VALUE, CosmeticPackCatalog.expiresAt(1_000L, -1));
        assertEquals(1_000L + 24 * 3_600_000L, CosmeticPackCatalog.expiresAt(1_000L, 24));
        assertEquals("30 мин", CosmeticPackCatalog.leftText(1_000L + 30 * 60_000L, 1_000L));
    }

    @Test
    void пакНаходитсяПоНазваниюИКороткомуИмени(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        Files.writeString(packs.resolve("letniy.yml"), TEST_PACK);
        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(packs);

        assertEquals("letniy", catalog.resolve("letniy").id());
        assertEquals("letniy", catalog.resolve("Летний набор").id());
        assertEquals("letniy", catalog.resolve("  ЛЕТНИЙ НАБОР ").id());
        assertNull(catalog.resolve("зимний"));
        assertNull(catalog.resolve(null));
    }

    @Test
    void пустойИлиБитыйФайл(@TempDir Path folder) throws Exception {
        Path packs = packFolder(folder);
        // Набор без косметики ничего не значит — его просто нет.
        Files.writeString(packs.resolve("pustoy.yml"), """
                name: 'Пустой'
                cosmetics: []
                """);
        assertEquals(0, CosmeticPackCatalog.load(packs).size());

        // Ошибка в файле — исключение с именем файла: каталог не подменяется наполовину.
        Files.writeString(packs.resolve("broken.yml"), """
                name: 'Кривой'
                cosmetics:
                  - file: kosmetika1
                    color: 'не-цвет'
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CosmeticPackCatalog.load(packs));
        assertTrue(error.getMessage().contains("broken.yml"));

        assertEquals(0, CosmeticPackCatalog.load(null).size());
        assertEquals(0, CosmeticPackCatalog.load(folder.resolve("нет-такой-папки")).size());
    }

    @Test
    void поставляемыйНаборЧитается(@TempDir Path folder) throws Exception {
        Path shippedPacks = Path.of("src/main/resources/content/cosmeticpacks");
        CosmeticCatalog base = CosmeticCatalog.load(Path.of("src/main/resources/settings/cosmetics.yml"));

        CosmeticPackCatalog catalog = CosmeticPackCatalog.load(shippedPacks, base, null);
        assertEquals(1, catalog.size(), "в комплекте один набор-пример");

        CosmeticPackCatalog.Pack pack = catalog.resolve("kosmetika-nabor1");
        assertNotNull(pack);
        assertEquals(3, pack.size(), "в примере весь комплект косметики");
        assertTrue(pack.infinite(), "кейсы примера бессрочные");
        for (int i = 0; i < 3; i++) {
            CosmeticCatalog.Cosmetic cosmetic = pack.cosmetics().get(i);
            assertEquals("kosmetika" + (i + 1), cosmetic.id());
            assertEquals(i + 1, cosmetic.number());
        }
    }
}
