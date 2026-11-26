package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Медали за напитки: раздел {@code drinks:} в settings/medals.yml и правила из
 * {@link DrinkMedalRules}. Java не знает конкретных медалей — только пять условий,
 * поэтому здесь проверяется разбор файла и сами правила.
 */
class DrinkMedalsTest {

    @TempDir Path directory;

    private static String config(String drinks) {
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
                  pending-error: '<red>Медаль пока не удалось записать. Право на неё сохранено.</red>'
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
                """ + (drinks == null ? "" : drinks);
    }

    private MedalSettings load(String text) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, text);
        return MedalSettings.load(file);
    }

    @Test
    void поставляемыйФайлЧитаетВсеШестьМедалей() throws Exception {
        MedalSettings settings = MedalSettings.load(Path.of("src/main/resources/settings/medals.yml"));

        assertEquals(List.of("bezuprek", "mead", "daquiri", "pirat", "vino-bezuprekALL", "buxlo"),
                List.copyOf(settings.drinkMedals().keySet()), "порядок медалей — как в файле");
        assertTrue(settings.problems().isEmpty(), () -> "ошибки файла: " + settings.problems());

        MedalSettings.DrinkSpec bezuprek = settings.drinkMedals().get("bezuprek");
        assertEquals("flawless-alcohol", bezuprek.trigger());
        assertEquals(ProfileMedal.Metal.GOLD, bezuprek.metal());
        assertEquals("Без упрёков.", bezuprek.title());
        assertEquals(3, bezuprek.reasons().size());
        assertTrue(bezuprek.filled());

        MedalSettings.DrinkSpec mead = settings.drinkMedals().get("mead");
        assertEquals("drink-made", mead.trigger());
        assertEquals("true_mead", mead.drink(), "напиток берётся из drink, а не из ключа медали");
        assertTrue(mead.alcoholOnly(), "по умолчанию засчитывается только алкогольный вариант");

        assertEquals("drink-made", settings.drinkMedals().get("daquiri").trigger());
        assertEquals("true_daiquiri", settings.drinkMedals().get("daquiri").drink(),
                "опечатка в ключе медали не мешает найти напиток");
        assertEquals("Истинный дайкири.", settings.drinkMedals().get("daquiri").title());
        assertEquals("rum-in-ocean", settings.drinkMedals().get("pirat").trigger());
        assertEquals("all-flawless-wines", settings.drinkMedals().get("vino-bezuprekALL").trigger());
        assertEquals("drunk-max", settings.drinkMedals().get("buxlo").trigger());
    }

    @Test
    void пустыеНазваниеИЗаслугиОтключаютМедаль() throws Exception {
        MedalSettings settings = load(config("""
                drinks:
                  buxlo:
                    when: drunk-max
                    metal: copper
                    title: ''
                    reasons: []
                """));
        assertEquals(1, settings.drinkMedals().size());
        assertFalse(settings.drinkMedals().get("buxlo").filled());
        assertTrue(settings.problems().isEmpty());
    }

    @Test
    void неизвестноеУсловиеНеЛомаетОстальныеМедали() throws Exception {
        MedalSettings settings = load(config("""
                drinks:
                  pereputal:
                    when: выпил-всё
                    title: 'Кривое условие'
                    reasons:
                      - 'Никогда не выдастся.'
                  buxlo:
                    when: DRUNK_MAX
                    title: 'Всему нужно знать меру.'
                    reasons:
                      - 'Дойдите до предела.'
                """));
        assertEquals(1, settings.drinkMedals().size(), "кривая медаль пропускается, остальные остаются");
        assertNotNull(settings.drinkMedals().get("buxlo"));
        assertEquals("drunk-max", settings.drinkMedals().get("buxlo").trigger(),
                "when сравнивается без учёта регистра и подчёркиваний");
        assertEquals(1, settings.problems().size());
        assertTrue(settings.problems().get(0).contains("pereputal"), settings.problems().get(0));
        assertTrue(settings.problems().get(0).contains("drink-made"), "в ошибке перечислены допустимые значения");
    }

    @Test
    void названиеБезЗаслугЭтоОшибкаВСпискеПроблем() throws Exception {
        MedalSettings settings = load(config("""
                drinks:
                  napolovinu:
                    when: drunk-max
                    title: 'Только название'
                    reasons: []
                """));
        assertTrue(settings.drinkMedals().isEmpty());
        assertEquals(1, settings.problems().size());
        assertTrue(settings.problems().get(0).contains("название"), settings.problems().get(0));
    }

    @Test
    void разделNapitkiЧитаетсяКакDrinks() throws Exception {
        MedalSettings settings = load(config("""
                napitki:
                  pirat:
                    when: rum-in-ocean
                    metal: copper
                    title: 'Я настоящий пират!'
                    reasons:
                      - 'Находясь в океане, выпейте пиратский ром.'
                """));
        assertEquals(1, settings.drinkMedals().size());
        assertEquals("rum-in-ocean", settings.drinkMedals().get("pirat").trigger());
    }

    @Test
    void медалиИРецептыНапитковНеДописываются() {
        // Владелец ведёт эти два файла сам: после перезапуска в них не должно появляться
        // строк из комплекта. Остальные файлы настроек дополняются как раньше.
        assertTrue(ConfigSync.NO_TOP_UP.contains("settings/medals.yml"));
        assertTrue(ConfigSync.NO_TOP_UP.contains("settings/recipe-poilo.yml"));
        assertFalse(ConfigSync.NO_TOP_UP.contains("settings/drunk.yml"));
        assertFalse(ConfigSync.NO_TOP_UP.contains("settings/drinks.yml"));
    }

    @Test
    void безWhenМедальСчитаетсяЗаНапитокСЕёКлючом() throws Exception {
        // Минимум, который нужен в файле: блок с названием, заслугами и drink.
        MedalSettings settings = load(config("""
                napitki:
                  true_mead:
                    drink: true_mead
                    title: 'Истинная медовуха.'
                    reasons:
                      - 'Сделайте алкогольную медовуху.'
                """));
        MedalSettings.DrinkSpec spec = settings.drinkMedals().get("true_mead");
        assertEquals("drink-made", spec.trigger(), "без when медаль ждёт изготовления напитка");
        assertEquals("true_mead", spec.drink());
        assertTrue(spec.filled());
        assertTrue(settings.problems().isEmpty());
    }

    @Test
    void опечаткаВIdДлинногоНапиткаПрощается() {
        assertTrue(DrinkMedalRules.sameDrink("daquiri", "daiquiri"), "одна пропущенная буква");
        assertTrue(DrinkMedalRules.sameDrink("mead", "true_mead"), "true_mead — та же медовуха");
        assertTrue(DrinkMedalRules.sameDrink("true_daiquiri", "daiquiri"), "и наоборот");
        assertFalse(DrinkMedalRules.sameDrink("mead", "true_tea"), "приставка не уравнивает чужие напитки");
        assertTrue(DrinkMedalRules.sameDrink("Daiquiri", "daiquiri"));
        assertTrue(DrinkMedalRules.sameDrink("sweet-wine", "sweet_wine"));
        assertFalse(DrinkMedalRules.sameDrink("beer", "wine"));
        assertFalse(DrinkMedalRules.sameDrink("rum", "wine"), "короткие id не сравниваются по опечатке");
    }

    @Test
    void коктейльЗасчитываетсяКакНапитокИзСвоегоСостава() throws Exception {
        assertTrue(DrinkMedalRules.drinkIncludes("mead", "mead"), "сама медовуха");
        assertTrue(DrinkMedalRules.drinkIncludes("true_mead", "true_mead"), "истинная медовуха из настроек");
        assertFalse(DrinkMedalRules.drinkIncludes("mead", "beer"));

        DrinkRecipes previous = DrinkRecipes.current();
        try {
            Path file = directory.resolve("poilo.yml");
            Files.writeString(file, """
                    recipes:
                      mead:
                        name: 'Медовуха'
                        color: '#E6B94B'
                        method: shaker
                        ingredients: [honey_bottle]
                      mead_with_rum:
                        name: 'Медовуха с ромом'
                        color: '#C98B2E'
                        method: shaker
                        ingredients: [mead]
                        alcohol: 6
                    """);
            DrinkRecipes.install(DrinkRecipes.load(file));
            assertTrue(DrinkMedalRules.drinkIncludes("mead", "mead_with_rum"),
                    "алкогольную медовуху делают коктейлем: медовуха плюс ром");
            assertTrue(DrinkMedalRules.isAlcoholic("mead_with_rum"));
            assertFalse(DrinkMedalRules.isAlcoholic(Shaker.RECIPE_MEAD), "в комплекте медовуха безалкогольная");
        } finally {
            DrinkRecipes.install(previous);
        }
    }

    @Test
    void винаИРомУзнаютсяПоИхПрофилям() {
        assertTrue(DrinkMedalRules.isWine("wine"));
        assertTrue(DrinkMedalRules.isWine("sweet_wine"));
        assertTrue(DrinkMedalRules.isWine("bitter_wine"));
        assertFalse(DrinkMedalRules.isWine("pirate_rum"));
        assertFalse(DrinkMedalRules.isWine("beer"), "пиво — не вино");
        assertTrue(DrinkMedalRules.isRum("pirate_rum"));
        assertFalse(DrinkMedalRules.isRum("beer"));
        assertTrue(DrinkMedalRules.isAlcoholic("pirate_rum"), "у рома своя крепость по ступеням");
        assertEquals(4, DrinkMedalRules.wineIds().size(), "ром, пиво и четыре вина в комплекте");
    }

    @Test
    void медальЗаВсеВидыТребуетКаждоеБезупречноеВино() {
        ProfileData profile = new ProfileData(UUID.randomUUID(), "Винодел");
        List<String> wines = DrinkMedalRules.wineIds();
        assertFalse(DrinkMedalRules.allWinesFlawless(profile), "пустой профиль ничего не собрал");
        for (String wine : wines) {
            profile.markClaimed(DrinkMedalRules.flawlessWineMarker(wine));
        }
        assertTrue(DrinkMedalRules.allWinesFlawless(profile));
        profile = new ProfileData(UUID.randomUUID(), "Почти винодел");
        for (int i = 0; i < wines.size() - 1; i++) profile.markClaimed(DrinkMedalRules.flawlessWineMarker(wines.get(i)));
        assertFalse(DrinkMedalRules.allWinesFlawless(profile), "одного вина не хватает — медали нет");
    }
}
