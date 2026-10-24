package com.yourserver.adaptation;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты функционала шейкера и новой системы напитков с добавками:
 * - Вместимость 5 слотов
 * - Идентификаторы моделей f8resurs:sheiker_open, f8resurs:sheiker_close, f8resurs:medovuha, f8resurs:daikiri, f8resurs:iced_latte, f8resurs:matcha-chai
 * - Определение любого типа льда
 * - Определение бутилированных и ведровых жидкостей
 * - Требования взбалтывания: 16 взмахов, таймаут 7 тиков
 * - Сопоставление базовых рецептов и добавок
 * - Положительные и отрицательные добавки
 */
final class ShakerTest {

    @Test
    void вместимостьШейкераПятьСлотов() {
        assertEquals(5, Shaker.MAX_SLOTS, "Шейкер вмещает максимум 5 предметов по 1 штуке");
    }

    @Test
    void моделиШейкераОткрытыйИЗакрытый() {
        assertEquals("sheiker_open", Shaker.MODEL_OPEN);
        assertEquals("sheiker_close", Shaker.MODEL_CLOSE);
        assertEquals("f8resurs:sheiker_open", Shaker.MODEL_OPEN_KEY.asString());
        assertEquals("f8resurs:sheiker_close", Shaker.MODEL_CLOSE_KEY.asString());
    }

    @Test
    void моделиНапитков() {
        assertEquals("f8resurs:medovuha", Shaker.MODEL_MEAD_KEY.asString());
        assertEquals("f8resurs:daikiri", Shaker.MODEL_DAIQUIRI_KEY.asString());
        assertEquals("f8resurs:iced_latte", Shaker.MODEL_ICED_LATTE_KEY.asString());
        assertEquals("f8resurs:matcha-chai", Shaker.MODEL_MATCHA_TEA_KEY.asString());
    }

    @Test
    void параметрыВзбалтыванияШейкера() {
        assertEquals(16, Shaker.REQUIRED_STROKES, "Требуется 16 непрерывных взмахов");
        assertEquals(7, Shaker.STROKE_TIMEOUT_TICKS, "Таймаут взмаха не более 7 тиков для исключения случайного взбивания");
        assertEquals(20.0f, Shaker.MIN_STROKE_PITCH, 0.001f);
    }

    @Test
    void определениеБутилированныхЖидкостей() {
        assertTrue(Shaker.isBottledLiquid(Material.HONEY_BOTTLE));
        assertTrue(Shaker.isBottledLiquid(Material.POTION));
        assertTrue(Shaker.isBottledLiquid(Material.DRAGON_BREATH));

        assertFalse(Shaker.isBottledLiquid(Material.SUGAR));
        assertFalse(Shaker.isBottledLiquid(Material.SWEET_BERRIES));
        assertFalse(Shaker.isBottledLiquid(Material.ICE));
        assertFalse(Shaker.isBottledLiquid(Material.STONE));
        assertFalse(Shaker.isBottledLiquid((Material) null));
    }

    @Test
    void определениеБлоковЛьдаЛюбогоТипа() {
        assertTrue(Shaker.isIce(Material.ICE));
        assertTrue(Shaker.isIce(Material.PACKED_ICE));
        assertTrue(Shaker.isIce(Material.BLUE_ICE));
        assertTrue(Shaker.isIce(Material.FROSTED_ICE));

        assertFalse(Shaker.isIce(Material.STONE));
        assertFalse(Shaker.isIce(Material.WATER));
        assertFalse(Shaker.isIce(Material.GLASS));
        assertFalse(Shaker.isIce((Material) null));
    }

    @Test
    void рецептМедовухиУспешноОпределяется() {
        // Медовуха: соты + бутылочка горячей воды
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.HONEYCOMB,
                Shaker.IngredientKind.HOT_WATER_BOTTLE
        );
        assertEquals(Shaker.RECIPE_MEAD, Shaker.matchRecipeFromKinds(ingredients));

        // С добавкой сахара
        List<Shaker.IngredientKind> withSugar = List.of(
                Shaker.IngredientKind.HONEYCOMB,
                Shaker.IngredientKind.HOT_WATER_BOTTLE,
                Shaker.IngredientKind.SUGAR
        );
        assertEquals(Shaker.RECIPE_MEAD, Shaker.matchRecipeFromKinds(withSugar));
    }

    @Test
    void добавкиМедовухи() {
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_MEAD, Shaker.IngredientKind.SUGAR));
        assertFalse(Shaker.isPositiveAdditive(Shaker.RECIPE_MEAD, Shaker.IngredientKind.ICE));
        assertFalse(Shaker.isPositiveAdditive(Shaker.RECIPE_MEAD, Shaker.IngredientKind.SWEET_BERRIES));
    }

    @Test
    void рецептДайкириУспешноОпределяется() {
        // Дайкири: сладкие ягоды + сахар + обычная вода
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE
        );
        assertEquals(Shaker.RECIPE_DAIQUIRI, Shaker.matchRecipeFromKinds(ingredients));

        // С добавкой льда
        List<Shaker.IngredientKind> withIce = List.of(
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE,
                Shaker.IngredientKind.ICE
        );
        assertEquals(Shaker.RECIPE_DAIQUIRI, Shaker.matchRecipeFromKinds(withIce));
    }

    @Test
    void добавкиДайкири() {
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_DAIQUIRI, Shaker.IngredientKind.ICE));
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_DAIQUIRI, Shaker.IngredientKind.SWEET_BERRIES));
        assertFalse(Shaker.isPositiveAdditive(Shaker.RECIPE_DAIQUIRI, Shaker.IngredientKind.HONEY_BOTTLE));
    }

    @Test
    void рецептАйсЛаттеУспешноОпределяется() {
        // Айс-латте: какао бобы + лед + ведро молока
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.COCOA_BEANS,
                Shaker.IngredientKind.ICE,
                Shaker.IngredientKind.MILK_BUCKET
        );
        assertEquals(Shaker.RECIPE_ICED_LATTE, Shaker.matchRecipeFromKinds(ingredients));

        // С добавкой меда
        List<Shaker.IngredientKind> withHoney = List.of(
                Shaker.IngredientKind.COCOA_BEANS,
                Shaker.IngredientKind.ICE,
                Shaker.IngredientKind.MILK_BUCKET,
                Shaker.IngredientKind.HONEY_BOTTLE
        );
        assertEquals(Shaker.RECIPE_ICED_LATTE, Shaker.matchRecipeFromKinds(withHoney));
    }

    @Test
    void добавкиАйсЛатте() {
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_ICED_LATTE, Shaker.IngredientKind.HONEY_BOTTLE));
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_ICED_LATTE, Shaker.IngredientKind.ICE));
        assertFalse(Shaker.isPositiveAdditive(Shaker.RECIPE_ICED_LATTE, Shaker.IngredientKind.GREEN_DYE));
    }

    @Test
    void рецептМатчаЧайУспешноОпределяется() {
        // Матча-чай: зеленый краситель + горячая вода
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.GREEN_DYE,
                Shaker.IngredientKind.HOT_WATER_BOTTLE
        );
        assertEquals(Shaker.RECIPE_MATCHA_TEA, Shaker.matchRecipeFromKinds(ingredients));

        // С добавками: мед, молоко, лед
        List<Shaker.IngredientKind> withAdditives = List.of(
                Shaker.IngredientKind.GREEN_DYE,
                Shaker.IngredientKind.HOT_WATER_BOTTLE,
                Shaker.IngredientKind.HONEY_BOTTLE,
                Shaker.IngredientKind.MILK_BUCKET,
                Shaker.IngredientKind.ICE
        );
        assertEquals(Shaker.RECIPE_MATCHA_TEA, Shaker.matchRecipeFromKinds(withAdditives));
    }

    @Test
    void добавкиМатчаЧай() {
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_MATCHA_TEA, Shaker.IngredientKind.HONEY_BOTTLE));
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_MATCHA_TEA, Shaker.IngredientKind.MILK_BUCKET));
        assertTrue(Shaker.isPositiveAdditive(Shaker.RECIPE_MATCHA_TEA, Shaker.IngredientKind.ICE));
        assertFalse(Shaker.isPositiveAdditive(Shaker.RECIPE_MATCHA_TEA, Shaker.IngredientKind.SUGAR));
    }

    @Test
    void неудачныйРецептДаетМуть() {
        // Произвольный предмет
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.OTHER
        )));

        // Только сахар
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SUGAR
        )));
    }

    @Test
    void пустойСоставНеСмешивается() {
        assertNull(Shaker.matchRecipeFromKinds(null));
        assertNull(Shaker.matchRecipeFromKinds(List.of()));
        assertFalse(Shaker.canShake(null));
        assertFalse(Shaker.canShake(List.of()));
        assertEquals(-1, Shaker.findLiquidIndex(null));
        assertEquals(-1, Shaker.findLiquidIndex(List.of()));
    }

    @Test
    void сериализацияПустогоСпискаБезопасна() {
        byte[] empty = Shaker.serializeItemList(new ArrayList<>());
        assertNotNull(empty);
        assertEquals(0, empty.length);

        assertTrue(Shaker.deserializeItemList(null).isEmpty());
        assertTrue(Shaker.deserializeItemList(new byte[0]).isEmpty());
    }

    @Test
    void бутылочкаГорячейВодыИдентификаторы() {
        assertEquals("adaptation:hot_water_bottle", Shaker.HOT_WATER_KEY.asString());
    }

    @Test
    void определениеВедровыхЖидкостей() {
        assertTrue(Shaker.isBucketLiquid(Material.MILK_BUCKET));
        assertTrue(Shaker.isBucketLiquid(Material.WATER_BUCKET));
        assertTrue(Shaker.isBucketLiquid(Material.LAVA_BUCKET));

        assertFalse(Shaker.isBucketLiquid(Material.BUCKET));
        assertFalse(Shaker.isBucketLiquid(Material.POTION));
        assertFalse(Shaker.isBucketLiquid(Material.HONEY_BOTTLE));
        assertFalse(Shaker.isBucketLiquid(Material.SUGAR));
        assertFalse(Shaker.isBucketLiquid((Material) null));
    }

    @Test
    void запрещеныЛюбыеБлокиКромеЛюбогоЛьда() {
        // Разрешены любые виды льда
        assertTrue(Shaker.isIce(Material.ICE));
        assertTrue(Shaker.isIce(Material.PACKED_ICE));
        assertTrue(Shaker.isIce(Material.BLUE_ICE));
        assertTrue(Shaker.isIce(Material.FROSTED_ICE));

        // Разрешены не-блочные предметы (ингредиенты)
        assertTrue(Shaker.isAllowedIngredient(Material.SUGAR));
        assertTrue(Shaker.isAllowedIngredient(Material.SWEET_BERRIES));
        assertTrue(Shaker.isAllowedIngredient(Material.HONEY_BOTTLE));
        assertTrue(Shaker.isAllowedIngredient(Material.POTION));
        assertTrue(Shaker.isAllowedIngredient(Material.APPLE));
        assertTrue(Shaker.isAllowedIngredient(Material.COCOA_BEANS));
        assertTrue(Shaker.isAllowedIngredient(Material.MILK_BUCKET));

        // Запрещены блоки (камни, земля, дерево, обсидиан и т.д.)
        assertFalse(Shaker.isAllowedIngredient(Material.STONE));
        assertFalse(Shaker.isAllowedIngredient(Material.DIRT));
        assertFalse(Shaker.isAllowedIngredient(Material.OAK_PLANKS));
        assertFalse(Shaker.isAllowedIngredient(Material.COBBLESTONE));
        assertFalse(Shaker.isAllowedIngredient(Material.OBSIDIAN));

        // Null и воздух запрещены
        assertFalse(Shaker.isAllowedIngredient((Material) null));
        assertFalse(Shaker.isAllowedIngredient(Material.AIR));
    }

    @Test
    void русскиеНазванияИнгредиентов() {
        assertEquals("Медовые соты", Shaker.getRussianMaterialName(Material.HONEYCOMB));
        assertEquals("Бутылочка мёда", Shaker.getRussianMaterialName(Material.HONEY_BOTTLE));
        assertEquals("Сахар", Shaker.getRussianMaterialName(Material.SUGAR));
        assertEquals("Сладкие ягоды", Shaker.getRussianMaterialName(Material.SWEET_BERRIES));
        assertEquals("Лёд", Shaker.getRussianMaterialName(Material.ICE));
        assertEquals("Какао-бобы", Shaker.getRussianMaterialName(Material.COCOA_BEANS));
        assertEquals("Ведро молока", Shaker.getRussianMaterialName(Material.MILK_BUCKET));
        assertEquals("Зелёный краситель", Shaker.getRussianMaterialName(Material.GREEN_DYE));
    }

    @Test
    void лорНапитковБезДобавокПустой() {
        var lore = Shaker.buildDrinkLore(Shaker.RECIPE_MEAD, List.of(), false);
        assertTrue(lore.isEmpty());
        lore = Shaker.buildDrinkLore(Shaker.RECIPE_MEAD, null, true);
        assertTrue(lore.isEmpty());
    }
}
