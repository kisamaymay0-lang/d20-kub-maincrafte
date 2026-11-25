package com.yourserver.adaptation;

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
 * Напитки из {@code settings/recipe-poilo.yml}: рецепты, положительные добавки,
 * методы приготовления и то, что поставляемый файл совпадает с прежним кодом.
 */
final class DrinkRecipesTest {

    @TempDir Path folder;

    private DrinkRecipes load(String text) throws Exception {
        Path file = folder.resolve("recipe-poilo.yml");
        Files.writeString(file, text);
        return DrinkRecipes.load(file);
    }

    @Test
    void поставляемыйФайлСовпадаетСоСтарымиНапитками() throws Exception {
        DrinkRecipes shipped = DrinkRecipes.load(Path.of("src/main/resources/settings/recipe-poilo.yml"));

        assertEquals(6, shipped.size(), "пять напитков шейкера и ром");
        assertEquals(4, shipped.shakerDrinks().size(), "муть — запасной, а не рецепт");
        assertEquals(1, shipped.barrelDrinks().size(), "в бочке только ром");

        assertEquals("Медовуха?", shipped.get(Shaker.RECIPE_MEAD).name());
        assertEquals("Матча-чай", shipped.get(Shaker.RECIPE_MATCHA_TEA).name());
        assertEquals("Дайкири?", shipped.get(Shaker.RECIPE_DAIQUIRI).name());
        assertEquals("Айс-Латте", shipped.get(Shaker.RECIPE_ICED_LATTE).name());
        assertEquals("Муть", shipped.get(Shaker.RECIPE_MURK).name());
        assertEquals("Пиратский ром", shipped.get(Shaker.RECIPE_PIRATE_RUM).name());

        // Описания в файле — те же, что были зашиты в код (включая жёлтую приписку).
        assertEquals(Shaker.DESC_MEAD, shipped.get(Shaker.RECIPE_MEAD).plainDescription());
        assertEquals(Shaker.DESC_MATCHA_TEA, shipped.get(Shaker.RECIPE_MATCHA_TEA).plainDescription());
        assertEquals(Shaker.DESC_DAIQUIRI, shipped.get(Shaker.RECIPE_DAIQUIRI).plainDescription());
        assertEquals(Shaker.DESC_ICED_LATTE, shipped.get(Shaker.RECIPE_ICED_LATTE).plainDescription());
        assertEquals(Shaker.DESC_MURK, shipped.get(Shaker.RECIPE_MURK).plainDescription());
        assertNull(shipped.get(Shaker.RECIPE_PIRATE_RUM).plainDescription(), "у рома своя подсказка");

        // Модели и цвета — прежние.
        assertEquals("medovuha", shipped.get(Shaker.RECIPE_MEAD).model());
        assertEquals("matcha-chai", shipped.get(Shaker.RECIPE_MATCHA_TEA).model());
        assertEquals("daikiri", shipped.get(Shaker.RECIPE_DAIQUIRI).model());
        assertEquals("iced_latte", shipped.get(Shaker.RECIPE_ICED_LATTE).model());
        assertEquals("", shipped.get(Shaker.RECIPE_MURK).model(), "у мути модели нет");
        assertEquals("skull_poilo", shipped.get(Shaker.RECIPE_PIRATE_RUM).model());
        assertEquals("#EBAF28", shipped.get(Shaker.RECIPE_MEAD).color().asHexString());
        assertEquals("#4E9331", shipped.get(Shaker.RECIPE_MURK).color().asHexString());
    }

    @Test
    void поставляемыйФайлСовпадаетСКодомПоУмолчанию() throws Exception {
        DrinkRecipes shipped = DrinkRecipes.load(Path.of("src/main/resources/settings/recipe-poilo.yml"));
        DrinkRecipes defaults = DrinkRecipes.defaults();

        assertEquals(defaults.size(), shipped.size());
        for (DrinkRecipes.Drink drink : defaults.list()) {
            DrinkRecipes.Drink fromFile = shipped.get(drink.id());
            assertNotNull(fromFile, drink.id());
            assertEquals(drink.name(), fromFile.name(), drink.id());
            assertEquals(drink.model(), fromFile.model(), drink.id());
            assertEquals(drink.method(), fromFile.method(), drink.id());
            assertEquals(drink.color().asHexString(), fromFile.color().asHexString(), drink.id());
            assertEquals(drink.ingredients(), fromFile.ingredients(), drink.id());
            assertEquals(drink.additives(), fromFile.additives(), drink.id());
            assertEquals(drink.plainDescription(), fromFile.plainDescription(), drink.id());
        }
    }

    @Test
    void рецептыПроверяютсяПоПорядкуФайла() throws Exception {
        // Первым идёт «болотный сок» — он и победит у набора, который подходит и ему, и медовухе.
        DrinkRecipes recipes = load("""
                recipes:
                  swamp:
                    name: 'Болотный сок'
                    color: '#123456'
                    model: ''
                    method: shaker
                    ingredients: [honeycomb, hot_water_bottle]
                    additives: [sugar]
                  mead:
                    name: 'Медовуха'
                    color: '#EBAF28'
                    model: medovuha
                    method: shaker
                    ingredients: [honeycomb, hot_water_bottle]
                    additives: [sugar]
                """);

        assertEquals("swamp", recipes.match(List.of(Shaker.IngredientKind.HONEYCOMB,
                Shaker.IngredientKind.HOT_WATER_BOTTLE)).id());
        // А без медовых сот рецепт уже не подходит.
        assertNull(recipes.match(List.of(Shaker.IngredientKind.HOT_WATER_BOTTLE,
                Shaker.IngredientKind.SUGAR)));
    }

    @Test
    void положительныеДобавкиИзФайла() throws Exception {
        DrinkRecipes recipes = load("""
                recipes:
                  чай:
                    name: 'Чай'
                    color: '#8FE3F5'
                    method: shaker
                    ingredients: [hot_water_bottle]
                    additives: [honey_bottle, ice]
                """);

        DrinkRecipes.Drink tea = recipes.get("чай");
        assertTrue(tea.positive(Shaker.IngredientKind.HONEY_BOTTLE));
        assertTrue(tea.positive(Shaker.IngredientKind.ICE));
        // Всё, чего нет в списке, — плохая добавка: отдельного списка нет.
        assertFalse(tea.positive(Shaker.IngredientKind.SUGAR));
        assertFalse(tea.positive(Shaker.IngredientKind.GREEN_DYE));
        assertFalse(tea.positive(null));
    }

    @Test
    void ромТолькоВБочкеИНеМожетБытьРецептомШейкера() throws Exception {
        DrinkRecipes recipes = load(Path.of("src/main/resources/settings/recipe-poilo.yml").toString());
        DrinkRecipes.Drink rum = recipes.get(Shaker.RECIPE_PIRATE_RUM);

        assertEquals(DrinkRecipes.Method.BARREL, rum.method());
        assertFalse(rum.shaker());
        assertTrue(rum.ingredients().isEmpty());
        assertTrue(rum.additives().isEmpty());
        // Даже если в шейкер положат всё подряд, ром оттуда не выйдет.
        assertTrue(recipes.shakerDrinks().stream().noneMatch(drink -> drink.id().equals(Shaker.RECIPE_PIRATE_RUM)));
    }

    @Test
    void методМожноПисатьПоРусски() throws Exception {
        DrinkRecipes recipes = load("""
                recipes:
                  настойка:
                    name: 'Настойка'
                    color: '#C2412F'
                    method: бочка
                    ingredients: []
                  лимонад:
                    name: 'Лимонад'
                    color: '#8FE3F5'
                    method: шейкер
                    ingredients: [water_bottle]
                """);

        assertEquals(DrinkRecipes.Method.BARREL, recipes.get("настойка").method());
        assertEquals(DrinkRecipes.Method.SHAKER, recipes.get("лимонад").method());
    }

    @Test
    void мутьОстаётсяЗапаснымНапитком() throws Exception {
        DrinkRecipes recipes = DrinkRecipes.defaults();
        assertEquals(Shaker.NAME_MURK, recipes.murk().name());
        assertTrue(recipes.murk().ingredients().isEmpty());
        // Нет напитка — имя берётся у мути, чтобы подсказка не осталась пустой.
        assertEquals(Shaker.NAME_MURK, recipes.name("нет-такого"));
        assertNull(recipes.get("нет-такого"));
    }

    @Test
    void ошибкиВФайлеВидно() {
        // Неизвестный ингредиент: подсказываем все допустимые имена.
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> load("""
                recipes:
                  чай:
                    name: 'Чай'
                    color: '#8FE3F5'
                    method: shaker
                    ingredients: [ромашка]
                """));
        assertTrue(unknown.getMessage().contains("ромашка"));
        assertTrue(unknown.getMessage().contains("honeycomb"), "в ошибке есть список ингредиентов");

        assertThrows(IllegalArgumentException.class, () -> load("""
                recipes:
                  чай:
                    name: 'Чай'
                    color: 'не-цвет'
                    method: shaker
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                recipes:
                  чай:
                    name: 'Чай'
                    color: '#8FE3F5'
                    method: кипячение
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                drinks:
                  чай:
                    name: 'Чай'
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                recipes: {}
                """));
    }

    @Test
    void имяПоУмолчаниюИЦветПоУмолчанию() throws Exception {
        DrinkRecipes recipes = load("""
                recipes:
                  prosto:
                    ingredients: [water_bottle]
                """);
        DrinkRecipes.Drink drink = recipes.get("prosto");
        assertEquals("prosto", drink.name());
        assertEquals("#E6B94B", drink.color().asHexString());
        assertEquals(DrinkRecipes.Method.SHAKER, drink.method());
        assertNull(drink.plainDescription());
    }

    @Test
    void живойКаталогМеняетсяЦеликом() throws Exception {
        // Шейкер спрашивает текущий каталог; после установки нового видны новые напитки.
        DrinkRecipes previous = DrinkRecipes.current();
        try {
            DrinkRecipes.install(load("""
                    recipes:
                      вода:
                        name: 'Вода'
                        color: '#8FE3F5'
                        method: shaker
                        ingredients: [water_bottle]
                    murk:
                      name: 'Муть'
                      color: '#4E9331'
                      ingredients: []
                    """));
            assertEquals("Вода", Shaker.getDrinkName("вода"));
            assertEquals("вода", Shaker.matchRecipeFromKinds(List.of(Shaker.IngredientKind.WATER_BOTTLE)));
            assertEquals("murk", Shaker.matchRecipeFromKinds(List.of(Shaker.IngredientKind.SUGAR)),
                    "ничего не подошло — муть");
        } finally {
            DrinkRecipes.install(previous);
        }
        assertEquals(Shaker.NAME_MEAD, Shaker.getDrinkName(Shaker.RECIPE_MEAD), "каталог вернулся");
    }

    @Test
    void именаИнгредиентовПоРазному() {
        assertEquals(Shaker.IngredientKind.HOT_WATER_BOTTLE, DrinkRecipes.kindOf("hot_water_bottle"));
        assertEquals(Shaker.IngredientKind.HOT_WATER_BOTTLE, DrinkRecipes.kindOf("Hot-Water-Bottle"));
        assertEquals(Shaker.IngredientKind.ICE, DrinkRecipes.kindOf(" ice "));
        assertNull(DrinkRecipes.kindOf("ромашка"));
        assertNull(DrinkRecipes.kindOf(""));
        assertNull(DrinkRecipes.kindOf(null));
    }
}
