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
    void номерЭтоЦифраИзКартинкиАНеПорядокВФайле() throws Exception {
        // Раньше номер считался по порядку записей, поэтому у префиксов из паков
        // в описании стоял «№0». Теперь номер берётся из имени картинки.
        PrefixCatalog catalog = load("""
                prefixes:
                  legend:
                    name: 'Легенда'
                    color: '#FFB347'
                    file: pref10
                  frost:
                    name: 'Морозный'
                    color: '#8FE3F5'
                    file: pref2
                """);
        assertEquals(10, catalog.get("legend").number(), "pref10 — это №10, даже если запись первая");
        assertEquals(2, catalog.get("frost").number(), "pref2 — это №2");
        assertEquals("legend", catalog.byNumber(10).id());
        assertEquals("frost", catalog.byNumber(2).id());
        assertNull(catalog.byNumber(1), "номера, которого нет в файле, не существует");
        assertEquals("legend", catalog.resolve("10").id(), "выдача по номеру работает как раньше");
    }

    @Test
    void занятыйНомерПолучаетПервыйСвободный() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  first:
                    name: 'Первый'
                    color: '#8FE3F5'
                    file: pref5
                  second:
                    name: 'Второй'
                    color: '#E6B94B'
                    file: other5
                """);
        assertEquals(5, catalog.get("first").number());
        assertNotEquals(5, catalog.get("second").number(), "два префикса не могут иметь один номер");
        assertEquals(2, catalog.get("second").number(), "свободный номер — первый по порядку");
    }

    @Test
    void анимацияЧитаетсяИзФайла() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  avrora:
                    name: 'Аврора'
                    color: '#7FF0D2'
                    file: pref9
                    animation:
                      frames: [pref9, pref11, pref12]
                      interval-hundredths: 8
                  simple:
                    name: 'Простой'
                    color: '#8FE3F5'
                    file: pref1
                """);
        PrefixCatalog.Prefix avrora = catalog.get("avrora");
        assertTrue(avrora.animated());
        assertEquals(List.of("pref9", "pref11", "pref12"), avrora.frames());
        assertEquals(8, avrora.intervalHundredths());
        assertEquals(80L, avrora.frameMillis());
        assertEquals(List.of("pref9", "pref11", "pref12"), avrora.iconFrames());
        assertFalse(catalog.get("simple").animated(), "без animation префикс статичный");
        assertEquals(List.of("pref1"), catalog.get("simple").iconFrames());
        assertEquals(1, catalog.animatedCount());
    }

    @Test
    void короткаяЗаписьАнимацииИГраницыИнтервала() throws Exception {
        PrefixCatalog catalog = load("""
                prefixes:
                  a:
                    name: 'А'
                    file: pref2
                    animation: [pref2, pref3]
                  b:
                    name: 'Б'
                    file: pref4
                    animation:
                      frames: [pref4, pref5]
                      interval-hundredths: 0
                  c:
                    name: 'В'
                    file: pref6
                    animation:
                      frames: [pref6, pref7]
                      interval-hundredths: 99999
                  d:
                    name: 'Г'
                    file: pref8
                    animation:
                      frames: [pref8, pref8, pref8]
                """);
        assertEquals(List.of("pref2", "pref3"), catalog.get("a").frames());
        assertEquals(10, catalog.get("a").intervalHundredths(), "без интервала — 10 сотых");
        assertEquals(1, catalog.get("b").intervalHundredths(), "ноль приводится к 0,01 с");
        assertEquals(50L, catalog.get("b").frameMillis(), "быстрее тика сервера не бывает");
        assertEquals(6000, catalog.get("c").intervalHundredths(), "больше минуты на кадр не нужно");
        assertFalse(catalog.get("d").animated(), "один и тот же кадр — не анимация");
    }

    @Test
    void кадрыИдутПоКругуВоВремени() {
        PrefixCatalog.Prefix animated = new PrefixCatalog.Prefix("avrora", "Аврора",
                net.kyori.adventure.text.format.TextColor.color(0x7FF0D2), "pref9", 9,
                List.of("pref9", "pref10", "pref11"), 10);
        assertEquals(0, ProfileIcons.frameIndex(animated, 0));
        assertEquals(0, ProfileIcons.frameIndex(animated, 99));
        assertEquals(1, ProfileIcons.frameIndex(animated, 100));
        assertEquals(2, ProfileIcons.frameIndex(animated, 250));
        assertEquals(0, ProfileIcons.frameIndex(animated, 300), "после последнего кадра снова первый");
        PrefixCatalog.Prefix simple = new PrefixCatalog.Prefix("s", "С",
                net.kyori.adventure.text.format.TextColor.color(0xFFFFFF), "pref1", 1);
        assertEquals(0, ProfileIcons.frameIndex(simple, 123456), "у статичного кадра всего один");
    }

    @Test
    void интервалКадраЧитаетсяПоЧеловечески() {
        assertEquals("0,08 с", PrefixCatalog.intervalText(8));
        assertEquals("0,1 с", PrefixCatalog.intervalText(10));
        assertEquals("0,5 с", PrefixCatalog.intervalText(50));
        assertEquals("1 с", PrefixCatalog.intervalText(100));
        assertEquals("2,5 с", PrefixCatalog.intervalText(250));
        assertEquals("0,01 с", PrefixCatalog.intervalText(0));
        assertEquals("60 с", PrefixCatalog.intervalText(99999));
    }

    @Test
    void глифСчитаетсяПоНомеруКартинки() {
        // pref1 → E106 … pref10 → E10F, дальше по порядку: pref11 → E110.
        assertNotEquals(ProfileIcons.icon("pref1"), ProfileIcons.icon("pref11"));
        assertNotEquals(ProfileIcons.icon("pref10"), ProfileIcons.icon("pref11"));
        assertEquals(ProfileIcons.icon("pref1"), ProfileIcons.icon("pref1"));
        assertEquals(net.kyori.adventure.text.Component.empty(), ProfileIcons.icon("snowflake"),
                "у картинки без номера глифа нет");
        assertEquals(11, PrefixCatalog.glyphNumber("pref11"));
        assertEquals(0, PrefixCatalog.glyphNumber("not_a_prefix"));
        assertEquals(12, PrefixCatalog.numberFromFile("pref12"));
        assertEquals(3, PrefixCatalog.numberFromFile("kosmetika3"));
        assertEquals(0, PrefixCatalog.numberFromFile("snowflake"));
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
