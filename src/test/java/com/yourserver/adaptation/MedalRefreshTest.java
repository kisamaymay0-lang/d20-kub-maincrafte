package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Названия, заслуги и металл медалей берутся из {@code settings/medals.yml} в момент
 * показа, а не запоминаются навсегда в профиле: поправили файл — в игре поменялось.
 */
class MedalRefreshTest {

    @TempDir Path directory;

    private MedalSettings load(String yamlText) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, yamlText);
        return MedalSettings.load(file);
    }

    private static String config(String title, String metal) {
        return """
                napitki:
                  buxlo:
                    when: drunk-max
                    metal: %s
                    title: '%s'
                    reasons:
                    - 'Дойдите до предела.'
                """.formatted(metal, title);
    }

    private static ProfileMedal awarded(String source, String title, long at) {
        return new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, title,
                List.of("Старая заслуга."), at, source);
    }

    @Test
    void названиеИзФайлаПерекрываетЗаписанноеВПрофиле() throws Exception {
        MedalSettings settings = MedalSettings.load(Path.of("src/main/resources/settings/medals.yml"));
        ProfileData data = new ProfileData(UUID.randomUUID(), "Игрок");
        data.award(awarded("drink_medal_buxlo", "Старое название", 123L));

        List<ProfileMedal> updated = settings.refreshAll(data);
        assertEquals(1, updated.size());
        ProfileMedal next = updated.get(0);
        assertEquals("Всему нужно знать меру.", next.title());
        assertEquals(1, next.reasons().size());
        assertTrue(next.reasons().get(0).contains("предела"), next.reasons().get(0));
        assertEquals(123L, next.awardedAt(), "дата выдачи остаётся прежней");
        assertEquals(data.medals().values().iterator().next().id(), next.id(), "id медали тот же");

        assertTrue(data.refresh(next));
        assertEquals("Всему нужно знать меру.", data.medals().values().iterator().next().title());
        assertTrue(settings.refreshAll(data).isEmpty(), "второй раз переписывать нечего");
    }

    @Test
    void правкаТекстаВФайлеВиднаСразу() throws Exception {
        ProfileData data = new ProfileData(UUID.randomUUID(), "Игрок");
        data.award(awarded("drink_medal_buxlo", "Всему нужно знать меру.", 1L));

        MedalSettings changed = load(config("Новое имя", "gold"));
        List<ProfileMedal> updated = changed.refreshAll(data);
        assertEquals(1, updated.size());
        assertEquals("Новое имя", updated.get(0).title());
        assertEquals(ProfileMedal.Metal.GOLD, updated.get(0).metal(), "металл тоже из файла");

        assertTrue(data.refresh(updated.get(0)));
        assertEquals(ProfileMedal.Metal.GOLD, data.medals().values().iterator().next().metal());
    }

    @Test
    void астрономияТожеБерётсяИзНастроек() {
        MedalSettings defaults = MedalSettings.defaults();
        ProfileData data = new ProfileData(UUID.randomUUID(), "Игрок");
        data.award(awarded(ProfileMedal.FIRST_CONSTELLATION, "Медная медаль", 5L));

        ProfileMedal next = defaults.refresh(data.medals().values().iterator().next());
        assertNotNull(next);
        assertEquals("Астрономия!", next.title());
    }

    @Test
    void ручнаяМедальБезИсточникаОстаётсяКакЕсть() {
        MedalSettings settings = MedalSettings.defaults();
        ProfileMedal manual = awarded("", "Выдал админ", 7L);
        assertNull(settings.refresh(manual), "у медали без источника нет текста в файле");
    }
}
