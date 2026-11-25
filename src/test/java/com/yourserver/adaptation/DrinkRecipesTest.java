package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        assertEquals(11, shipped.size(), "пять напитков шейкера, ром, четыре вина и пиво");
        assertEquals(4, shipped.shakerDrinks().size(), "муть — запасной, а не рецепт");
        assertEquals(6, shipped.barrelDrinks().size(), "в бочке ром, четыре вина и пиво");

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
        assertEquals("Вино", shipped.get("wine").name());
        assertEquals("Сладкое вино", shipped.get("sweet_wine").name());
        assertEquals("Белое вино", shipped.get("white_wine").name());
        assertEquals("Горькое вино", shipped.get("bitter_wine").name());
        assertEquals("Пиво", shipped.get("beer").name());
        assertEquals("#C68B2E", shipped.get("beer").color().asHexString());
        assertEquals(DrinkRecipes.Method.BARREL, shipped.get("beer").method(), "пиво — бочковый напиток");
        assertEquals("", shipped.get("wine").model(), "модели у вина пока нет");
        assertEquals(DrinkRecipes.Method.BARREL, shipped.get("wine").method());
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
        DrinkRecipes recipes = DrinkRecipes.load(Path.of("src/main/resources/settings/recipe-poilo.yml"));
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

    @Test
    void рецептыБочкиИзФайла() throws Exception {
        DrinkRecipes shipped = DrinkRecipes.load(Path.of("src/main/resources/settings/recipe-poilo.yml"));

        DrinkRecipes.BarrelRecipe rum = shipped.barrelRecipe(Shaker.RECIPE_PIRATE_RUM);
        assertNotNull(rum, "ром варится в бочке");
        assertEquals(5, rum.total(), "тростник, уголь и три горячей воды");
        assertEquals(3, rum.needed(DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE));

        assertEquals(4, shipped.barrelRecipe("wine").total(), "три помятых ягоды и вода");
        assertEquals(1, shipped.barrelRecipe("sweet_wine").needed(DrinkRecipes.BarrelIngredient.SUGAR));
        assertEquals(1, shipped.barrelRecipe("white_wine").needed(DrinkRecipes.BarrelIngredient.BONE_MEAL));
        assertEquals(3, shipped.barrelRecipe("bitter_wine").needed(DrinkRecipes.BarrelIngredient.HARD_SWEETIE_BERRY));
        assertEquals(5, shipped.barrelRecipe("beer").total(), "три тростника и две воды");
        assertEquals(3, shipped.barrelRecipe("beer").needed(DrinkRecipes.BarrelIngredient.SUGAR_CANE));
        assertEquals(2, shipped.barrelRecipe("beer").needed(DrinkRecipes.BarrelIngredient.WATER_BOTTLE));
        assertNull(shipped.barrelRecipe(Shaker.RECIPE_MEAD), "медовуха не бочковая");
    }

    @Test
    void бочкаЖдётРовноТотНабор() {
        DrinkRecipes.BarrelRecipe wine = new DrinkRecipes.BarrelRecipe(Map.of(
                DrinkRecipes.BarrelIngredient.LOW_SWEETIE_BERRY, 3,
                DrinkRecipes.BarrelIngredient.WATER_BOTTLE, 1));

        java.util.Map<DrinkRecipes.BarrelIngredient, Integer> have =
                new java.util.EnumMap<>(DrinkRecipes.BarrelIngredient.class);
        have.put(DrinkRecipes.BarrelIngredient.LOW_SWEETIE_BERRY, 3);
        have.put(DrinkRecipes.BarrelIngredient.WATER_BOTTLE, 1);
        assertTrue(wine.matches(have), "ровно три ягоды и вода");

        // Лишний сахар — это уже не обычное вино: рецепт ждёт точного набора.
        have.put(DrinkRecipes.BarrelIngredient.SUGAR, 1);
        assertFalse(wine.matches(have), "лишний предмет останавливает варку");

        // Горячая вода годится вместо обычной.
        java.util.Map<DrinkRecipes.BarrelIngredient, Integer> hot =
                new java.util.EnumMap<>(DrinkRecipes.BarrelIngredient.class);
        hot.put(DrinkRecipes.BarrelIngredient.LOW_SWEETIE_BERRY, 3);
        hot.put(DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE, 1);
        assertTrue(wine.matches(hot));

        // А ром наоборот: обычная вода вместо горячей не подойдёт.
        DrinkRecipes.BarrelRecipe rum = new DrinkRecipes.BarrelRecipe(Map.of(
                DrinkRecipes.BarrelIngredient.COAL, 1,
                DrinkRecipes.BarrelIngredient.SUGAR_CANE, 1,
                DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE, 3));
        java.util.Map<DrinkRecipes.BarrelIngredient, Integer> cold =
                new java.util.EnumMap<>(DrinkRecipes.BarrelIngredient.class);
        cold.put(DrinkRecipes.BarrelIngredient.COAL, 1);
        cold.put(DrinkRecipes.BarrelIngredient.SUGAR_CANE, 1);
        cold.put(DrinkRecipes.BarrelIngredient.WATER_BOTTLE, 3);
        assertFalse(rum.matches(cold), "ром требует именно горячую воду");
    }

    @Test
    void новыйБочковойНапитокИзФайлаВиденЦеликом() throws Exception {
        DrinkRecipes recipes = load("""
                recipes:
                  kvass:
                    name: 'Квас'
                    color: '#8B5A2B'
                    model: ''
                    method: barrel
                    ingredients:
                      sugar_cane: 2
                      water_bottle: 1
                """);

        assertEquals(1, recipes.size(), "в файле ровно один напиток");
        assertEquals(1, recipes.barrelDrinks().size());
        assertEquals("2 × сахарный тростник + 1 × бутылочка воды", recipes.recipeText("kvass"));
        assertEquals(2, recipes.maxBarrelCount(DrinkRecipes.BarrelIngredient.SUGAR_CANE),
                "бочка примет ровно столько тростника, сколько просит рецепт");
    }

    @Test
    void обычныеПредметыТожеСчитаютсяИнгредиентами() throws Exception {
        DrinkRecipes recipes = load("""
                recipes:
                  kvass:
                    name: 'Квас'
                    color: '#8B5A2B'
                    method: barrel
                    ingredients:
                      wheat: 3
                      water_bottle: 2
                """);

        String text = recipes.recipeText("kvass");
        assertTrue(text.contains("3 × пшеница"), text);
        assertTrue(text.contains("2 × бутылочка воды"), text);
        assertEquals(3, recipes.maxBarrelCount(DrinkRecipes.BarrelIngredient.WHEAT),
                "пшеницу теперь тоже можно класть в бочку");
    }

    @Test
    void именаИнгредиентовБочкиПоРазному() {
        assertEquals(DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE,
                DrinkRecipes.BarrelIngredient.of("hot_water_bottle"));
        assertEquals(DrinkRecipes.BarrelIngredient.WHEAT, DrinkRecipes.BarrelIngredient.of("wheat"));
        assertEquals(DrinkRecipes.BarrelIngredient.SWEET_BERRIES,
                DrinkRecipes.BarrelIngredient.of("Sweet-Berries"));
        assertEquals(DrinkRecipes.BarrelIngredient.COCOA_BEANS, DrinkRecipes.BarrelIngredient.of("cocoa_beans"));
        assertEquals(DrinkRecipes.BarrelIngredient.HOT_WATER_BOTTLE,
                DrinkRecipes.BarrelIngredient.of("Hot-Water-Bottle"));
        assertEquals(DrinkRecipes.BarrelIngredient.LOW_SWEETIE_BERRY,
                DrinkRecipes.BarrelIngredient.of(" low_sweetie_berry "));
        assertNull(DrinkRecipes.BarrelIngredient.of("ромашка"));
    }
}
