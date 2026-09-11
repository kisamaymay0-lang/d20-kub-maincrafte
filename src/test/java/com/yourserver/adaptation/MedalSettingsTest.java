package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Пустые разделы бутербродных медалей отключают выдачу, пока админ не впишет название и заслуги. */
class MedalSettingsTest {
    @TempDir Path directory;

    private static String config(String sandwiches) {
        return """
                messages:
                  public: '{player} получил <green>[{rarity}]</green>!'
                  personal: 'Вы получили новую медаль! Подробнее /profile.'
                  given: '<gray>Медаль «{title}» добавлена игроку {player}.</gray>'
                  taken: '<gray>У игрока {player} забрано медалей: {count}.</gray>'
                  list: '<gold>Медали игрока {player}:</gold>'
                  not-found: '<red>Медаль не найдена. Посмотрите /profile medal list.</red>'
                  reloaded: '<green>Настройки и медали перезагружены.</green>'
                  error: '<red>Изменение медалей не применено: {error}</red>'
                  pending-error: '<red>Медаль пока не удалось записать. Право на неё сохранено; обратитесь к администратору.</red>'
                  no-permission: '<red>Нет прав управлять медалями.</red>'
                types:
                  copper:
                    label: 'Медная медаль'
                    rarity: 'Медную медаль'
                    gradient-end: '#CB8754'
                  silver:
                    label: 'Серебряная медаль'
                    rarity: 'Серебряную медаль'
                    gradient-end: '#71879F'
                  gold:
                    label: 'Золотая медаль'
                    rarity: 'Золотую медаль'
                    gradient-end: '#E6B94B'
                astronomy:
                  title: 'Астрономия!'
                  reasons:
                    - 'Собрано 1 созвездие.'
                """ + (sandwiches == null ? "" : sandwiches);
    }

    private MedalSettings load(String text) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, text);
        return MedalSettings.load(file);
    }

    @Test
    void defaultsDisableBothSandwichMedalsUntilConfigured() {
        MedalSettings settings = MedalSettings.defaults();
        assertFalse(settings.sandwichAllKinds.filled());
        assertFalse(settings.sandwichIceCaviar.filled());
        assertEquals(ProfileMedal.Metal.GOLD, settings.sandwichAllKinds.metal());
        assertEquals(ProfileMedal.Metal.COPPER, settings.sandwichIceCaviar.metal());
    }

    @Test
    void shippedBlankSectionsSilentlyDisableBothSandwichMedals() throws Exception {
        MedalSettings settings = load(config("""
                sandwiches:
                  all-kinds:
                    metal: gold
                    title: ''
                    reasons: []
                  ice-caviar:
                    metal: copper
                    title: ''
                    reasons: []
                """));
        assertFalse(settings.sandwichAllKinds.filled());
        assertFalse(settings.sandwichIceCaviar.filled());
        assertEquals("Астрономия!", settings.astronomyTitle);
        assertEquals(1, settings.astronomyReasons.size());
        assertNotNull(settings.style(ProfileMedal.Metal.GOLD).end());
    }

    @Test
    void configWithoutSandwichSectionStaysValidAndDisablesThem() throws Exception {
        // Старые конфиги серверов без раздела sandwiches не должны ломать загрузку.
        MedalSettings settings = load(config(null));
        assertFalse(settings.sandwichAllKinds.filled());
        assertFalse(settings.sandwichIceCaviar.filled());
        assertEquals("Астрономия!", settings.astronomyTitle);
    }

    @Test
    void filledSandwichSectionsEnableAwardsWithConfiguredMetalTitleAndReasons() throws Exception {
        MedalSettings settings = load(config("""
                sandwiches:
                  all-kinds:
                    metal: gold
                    title: 'Дегустатор'
                    reasons:
                      - 'Съедены все три вида бутербродов'
                  ice-caviar:
                    metal: silver
                    title: 'Ледяной гурман'
                    reasons:
                      - 'Съеден бутерброд с ледяной икрой'
                """));
        assertTrue(settings.sandwichAllKinds.filled());
        assertEquals(ProfileMedal.Metal.GOLD, settings.sandwichAllKinds.metal());
        assertEquals("Дегустатор", settings.sandwichAllKinds.title());
        assertEquals(List.of("Съедены все три вида бутербродов"), settings.sandwichAllKinds.reasons());
        assertTrue(settings.sandwichIceCaviar.filled());
        assertEquals(ProfileMedal.Metal.SILVER, settings.sandwichIceCaviar.metal());
        assertEquals("Ледяной гурман", settings.sandwichIceCaviar.title());
        assertEquals(List.of("Съеден бутерброд с ледяной икрой"), settings.sandwichIceCaviar.reasons());
    }

    @Test
    void partiallyFilledSandwichSectionIsAConfigErrorNotASilentAward() {
        assertThrows(IllegalArgumentException.class, () -> load(config("""
                sandwiches:
                  all-kinds:
                    metal: gold
                    title: 'Только название'
                    reasons: []
                ice-caviar:
                  metal: copper
                  title: ''
                  reasons: []
                """)), "Название без заслуг не должно ни выдавать медаль, ни молча отключаться");
        assertThrows(IllegalArgumentException.class, () -> load(config("""
                sandwiches:
                  all-kinds:
                    metal: gold
                    title: ''
                    reasons:
                      - 'Только заслуга'
                ice-caviar:
                  metal: copper
                  title: ''
                  reasons: []
                """)), "Заслуги без названия — тоже ошибка конфигурации");
    }

    @Test
    void invalidMetalNameInEmptySectionStillRejectsTheWholeConfig() {
        assertThrows(IllegalArgumentException.class, () -> load(config("""
                sandwiches:
                  all-kinds:
                    metal: platinum
                    title: ''
                    reasons: []
                ice-caviar:
                  metal: copper
                  title: ''
                  reasons: []
                """)), "Описавшись в metal, админ должен увидеть ошибку, а не тихо выключенную медаль");
    }
}
