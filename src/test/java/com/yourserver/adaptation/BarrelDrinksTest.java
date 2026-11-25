package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Бочковые напитки из {@code data/napitki/napitki.yml}: сроки, ступени качества,
 * годы и описания. Отдельно проверяется поставляемый файл — в нём лестница рома
 * должна остаться прежней, а у вин стоять прочерк вместо описаний.
 */
final class BarrelDrinksTest {

    @TempDir Path folder;

    private BarrelDrinks load(String text) throws Exception {
        Path file = folder.resolve("napitki.yml");
        Files.writeString(file, text);
        return BarrelDrinks.load(file);
    }

    @Test
    void поставляемыйФайлПовторяетЛестницуРома() throws Exception {
        BarrelDrinks shipped = BarrelDrinks.load(Path.of("src/main/resources/data/napitki/napitki.yml"));
        BarrelDrinks.Profile rum = shipped.of(Shaker.RECIPE_PIRATE_RUM);

        assertEquals(200, rum.brewTicks(), "10 секунд на закладку");
        assertEquals(3, rum.bottles(), "три бутылки из закладки");
        assertEquals(24000L, rum.ticksPerYear(), "1 год = сутки в Minecraft");

        assertEquals(1, rum.tierFor(0));
        assertEquals(1, rum.tierFor(1));
        assertEquals(2, rum.tierFor(3));
        assertEquals(3, rum.tierFor(5));
        assertEquals(4, rum.tierFor(9));
        assertEquals(5, rum.tierFor(10));
        assertEquals(3, rum.tierFor(12));
        assertEquals(2, rum.tierFor(20));

        assertEquals("Молодой сивушный спирт. Обжигает горло.", rum.description(0, 1));
        assertEquals("Вкус еще только формируется...", rum.description(2, 2));
        assertEquals("Добротный пиратский ром, пик для будней на корабле!", rum.description(4, 3));
        assertEquals("Передержали. Ром становится горьким и древесным", rum.description(12, 3));
        assertEquals("Уже не ром, а жидкость со вкусом старой доски.", rum.description(20, 2));
        assertEquals("Теперь ты можешь называть себя настоящим пиратом!", rum.description(10, 5));
    }

    @Test
    void уВинГодыКакУРомаАОписанияПрочерк() throws Exception {
        BarrelDrinks shipped = BarrelDrinks.load(Path.of("src/main/resources/data/napitki/napitki.yml"));

        for (String id : List.of("wine", "sweet_wine", "white_wine", "bitter_wine")) {
            BarrelDrinks.Profile wine = shipped.of(id);
            assertEquals(1, wine.tierFor(0), id);
            assertEquals(5, wine.tierFor(10), id);
            assertEquals(2, wine.tierFor(20), id);
            assertEquals(24000L, wine.ticksPerYear(), id);
            assertEquals("—", wine.description(0, 1), id + ": описания пока заглушка");
            assertEquals("—", wine.description(10, 5), id);
        }
        // Ром при этом не тронут: у него свои описания.
        assertEquals("Молодой сивушный спирт. Обжигает горло.",
                shipped.of(Shaker.RECIPE_PIRATE_RUM).description(0, 1));
    }

    @Test
    void напитокМожетПереписатьСрокиИГоды() throws Exception {
        BarrelDrinks settings = load("""
                default:
                  brew-ticks: 100
                  bottles: 2
                  ticks-per-year: 1200
                  quality:
                    - tier: 1
                      name: 'Плохо'
                      color: '#FF0000'
                      description: 'Совсем не то.'
                    - tier: 2
                      name: 'Хорошо'
                      color: '#00FF00'
                      description: 'Вот это дело.'
                  years:
                    - up-to-years: 2
                      tier: 1
                    - up-to-years: '*'
                      tier: 2
                drinks:
                  beer:
                    brew-ticks: 40
                    bottles: 1
                """);

        BarrelDrinks.Profile beer = settings.of("beer");
        assertEquals(40, beer.brewTicks(), "своё время брожения");
        assertEquals(1, beer.bottles());
        assertEquals(1200L, beer.ticksPerYear(), "остальное — из default");
        assertEquals(1, beer.tierFor(2));
        assertEquals(2, beer.tierFor(3));

        BarrelDrinks.Profile other = settings.of("unknown");
        assertEquals(100, other.brewTicks(), "неизвестный напиток берёт default");
        assertEquals(2, other.tierFor(99), "и его годы тоже");
    }

    @Test
    void штрафЗаПереливания() {
        assertEquals(5, BarrelDrinks.tierAfterTransfers(5, 1));
        assertEquals(4, BarrelDrinks.tierAfterTransfers(5, 2));
        assertEquals(5, BarrelDrinks.tierAfterTransfers(5, 0));
        assertEquals(3, BarrelDrinks.tierAfterTransfers(5, 4));
        assertEquals(2, BarrelDrinks.tierAfterTransfers(5, 6));
        assertEquals(1, BarrelDrinks.tierAfterTransfers(1, 10), "ниже первой ступени не падает");
        assertEquals(2, BarrelDrinks.tierAfterTransfers(4, 10), "шесть переливаний — не выше второй");
    }

    @Test
    void безФайлаОстаётсяПрежнийРом() {
        BarrelDrinks.Profile rum = BarrelDrinks.defaults().of(Shaker.RECIPE_PIRATE_RUM);

        assertEquals(200, rum.brewTicks());
        assertEquals(3, rum.bottles());
        assertEquals(24000L, rum.ticksPerYear());
        assertEquals("Ужасное! ☠", rum.level(1).name());
        assertEquals("Странное...", rum.level(2).name());
        assertEquals("Хорошее ☺", rum.level(3).name());
        assertEquals("Идеальное! ☀", rum.level(4).name());
        assertEquals("БЕЗУПРЕЧНОЕ!!1", rum.level(5).name());
        assertNull(rum.descriptionOverride());
    }

    @Test
    void визменённомФайлеВиденПрочеркВПодсказке() throws Exception {
        BarrelDrinks previous = BarrelDrinks.current();
        try {
            BarrelDrinks.install(BarrelDrinks.load(Path.of("src/main/resources/data/napitki/napitki.yml")));

            List<Component> wine = BambooBarrel.buildDrinkLore("wine", 0, 1);
            PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
            assertEquals(7, wine.size(), "пять строк, опьянение и описание-заглушка");
            assertTrue(plain.serialize(wine.get(1)).contains("Ужасное! ☠"));
            assertEquals("Лет: 0", plain.serialize(wine.get(3)));
            assertEquals("Алькогольное", plain.serialize(wine.get(5)), "вино пьянит, но цифр не показывает");
            assertEquals("—", plain.serialize(wine.get(6)));

            // У рома описание своё — прочерк его не задел.
            List<Component> rum = BambooBarrel.buildDrinkLore(Shaker.RECIPE_PIRATE_RUM, 0, 1);
            assertEquals("Молодой сивушный спирт. Обжигает горло.", plain.serialize(rum.get(6)));
        } finally {
            BarrelDrinks.install(previous);
        }
    }

    @Test
    void описаниеНапиткаИзRecipePoiloДобавляетсяСтрокой() throws Exception {
        BarrelDrinks previous = BarrelDrinks.current();
        DrinkRecipes previousRecipes = DrinkRecipes.current();
        try {
            BarrelDrinks.install(BarrelDrinks.defaults());
            Path file = folder.resolve("recipe-poilo.yml");
            Files.writeString(file, """
                    recipes:
                      pirate_rum:
                        name: 'Ром'
                        color: '#8B3A14'
                        model: skull_poilo
                        description: 'Пахнет палубой и приключениями.'
                        method: barrel
                        ingredients:
                          sugar_cane: 1
                          coal: 1
                          hot_water_bottle: 3
                      murk:
                        name: 'Муть'
                        color: '#4E9331'
                        ingredients: []
                    """);
            DrinkRecipes.install(DrinkRecipes.load(file));

            List<Component> withText = BambooBarrel.buildDrinkLore(Shaker.RECIPE_PIRATE_RUM, 3, 1);
            PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
            assertEquals(8, withText.size(), "описание напитка — отдельная строка в конце");
            assertEquals("Пахнет палубой и приключениями.", plain.serialize(withText.get(7)));
        } finally {
            DrinkRecipes.install(previousRecipes);
            BarrelDrinks.install(previous);
        }
    }

    @Test
    void вижноКакиеКлючиНаписаныСамим() throws Exception {
        BarrelDrinks shipped = BarrelDrinks.load(Path.of("src/main/resources/data/napitki/napitki.yml"));

        assertTrue(shipped.ids().contains("beer"), "пиво есть в файле");
        assertTrue(shipped.ids().contains("wine"));
        assertTrue(shipped.ownKeys("beer").contains("quality"), "у пива свои ступени");
        assertTrue(shipped.ownKeys("beer").contains("years"), "у пива свои годы");
        assertTrue(shipped.ownKeys("beer").contains("drunk"), "у пива своя крепость");
        assertTrue(shipped.ownKeys("wine").contains("drunk"), "вина задают только крепость");
        assertFalse(shipped.ownKeys("wine").contains("quality"),
                "ступени и годы вина наследуют из default — так и задумано");
        assertTrue(shipped.ownKeys("pirate_rum").isEmpty(),
                "ром и есть default: своих ключей у него нет, всё наследуется");
    }

    @Test
    void ошибкиВФайлеВидно() {
        IllegalArgumentException noName = assertThrows(IllegalArgumentException.class, () -> load("""
                default:
                  quality:
                    - tier: 1
                      color: '#FF0000'
                  years:
                    - up-to-years: 1
                      tier: 1
                """));
        assertTrue(noName.getMessage().contains("name"), noName.getMessage());

        IllegalArgumentException noTier = assertThrows(IllegalArgumentException.class, () -> load("""
                default:
                  quality:
                    - tier: 1
                      name: 'Плохо'
                  years:
                    - up-to-years: 1
                      tier: 9
                """));
        assertTrue(noTier.getMessage().contains("ступень 9"), noTier.getMessage());

        IllegalArgumentException badColor = assertThrows(IllegalArgumentException.class, () -> load("""
                default:
                  quality:
                    - tier: 1
                      name: 'Плохо'
                      color: 'не цвет'
                  years:
                    - up-to-years: 1
                      tier: 1
                """));
        assertTrue(badColor.getMessage().contains("цвет"), badColor.getMessage());

        IllegalArgumentException badTicks = assertThrows(IllegalArgumentException.class, () -> load("""
                default:
                  brew-ticks: 0
                  quality:
                    - tier: 1
                      name: 'Плохо'
                  years:
                    - up-to-years: 1
                      tier: 1
                """));
        assertNotNull(badTicks.getMessage());
    }

    @Test
    void силаОпьяненияПоКачествам() throws Exception {
        BarrelDrinks shipped = BarrelDrinks.load(Path.of("src/main/resources/data/napitki/napitki.yml"));

        BarrelDrinks.Profile rum = shipped.of(Shaker.RECIPE_PIRATE_RUM);
        assertEquals(4, rum.drunkFor(1), "ужасный ром");
        assertEquals(5, rum.drunkFor(2));
        assertEquals(6, rum.drunkFor(3));
        assertEquals(7, rum.drunkFor(4));
        assertEquals(8, rum.drunkFor(5), "безупречный ром крепче всего");

        for (String id : List.of("wine", "sweet_wine", "white_wine", "bitter_wine")) {
            BarrelDrinks.Profile wine = shipped.of(id);
            assertEquals(2, wine.drunkFor(1), id);
            assertEquals(3, wine.drunkFor(2), id);
            assertEquals(3, wine.drunkFor(3), id);
            assertEquals(4, wine.drunkFor(4), id);
            assertEquals(5, wine.drunkFor(5), id);
            assertTrue(wine.drunkFor(5) < rum.drunkFor(5), "вино слабее рома того же качества");
        }

        // Пиво — самое слабое: целая бутылка уносит меньше десяти единиц.
        BarrelDrinks.Profile beer = shipped.of("beer");
        assertEquals(1, beer.drunkFor(1));
        assertEquals(3, beer.drunkFor(5), "даже безупречное пиво слабее худшего рома");
        assertEquals(4, beer.bottles(), "пиво разливается по четыре бутылочки");
        assertEquals(15, beer.brewTicks() / 20, "15 секунд брожения");
        assertEquals(5, beer.tierFor(5), "пик пива — на пятом году");
        assertEquals(3, beer.tierFor(6), "перестоявшее пиво выдыхается");
    }

    @Test
    void безФайлаСилаОпьяненияТожеЕсть() {
        BarrelDrinks defaults = BarrelDrinks.defaults();

        assertEquals(4, defaults.of(Shaker.RECIPE_PIRATE_RUM).drunkFor(1));
        assertEquals(8, defaults.of(Shaker.RECIPE_PIRATE_RUM).drunkFor(5));
        assertEquals(2, defaults.of("wine").drunkFor(1));
        assertEquals(5, defaults.of("wine").drunkFor(5));
        // Даже без файла пиво остаётся слабым: 1…3 единицы за глоток.
        assertEquals(1, defaults.of("beer").drunkFor(1));
        assertEquals(3, defaults.of("beer").drunkFor(5));
    }

    @Test
    void качествоIZeroЕдиницНеПьянит() throws Exception {
        BarrelDrinks settings = load("""
                default:
                  quality:
                    - tier: 1
                      name: 'Плохо'
                    - tier: 2
                      name: 'Хорошо'
                  years:
                    - up-to-years: 2
                      tier: 1
                    - up-to-years: '*'
                      tier: 2
                  drunk:
                    1: 3
                drinks:
                  kvass:
                    drunk:
                      2: 0
                """);

        assertEquals(3, settings.of("kvass").drunkFor(1), "чего нет — берём у default");
        assertEquals(0, settings.of("kvass").drunkFor(2), "ноль — напиток не пьянит");
    }
}
