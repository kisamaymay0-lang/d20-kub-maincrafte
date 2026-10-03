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
 * Тесты функционала шейкера:
 * - Вместимость 5 слотов
 * - Идентификаторы моделей f8resurs:sheiker_open и f8resurs:sheiker_close
 * - Определение любого типа льда
 * - Определение бутилированных жидкостей (бутылочка остаётся у игрока)
 * - Требования взбалтывания: 16 взмахов, таймаут 7 тиков
 * - Сопоставление рецептов: Медовуха, Дайкири, Муть
 * - Пустая сериализация и десериализация
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
        // Медовуха: 1 бутылочка меда, 2 сахара, 1 бутылочка воды (всего 4 предмета)
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.HONEY_BOTTLE,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE
        );
        assertEquals(Shaker.RECIPE_MEAD, Shaker.matchRecipeFromKinds(ingredients));

        // Порядок добавления не имеет значения
        List<Shaker.IngredientKind> shuffled = List.of(
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE,
                Shaker.IngredientKind.HONEY_BOTTLE,
                Shaker.IngredientKind.SUGAR
        );
        assertEquals(Shaker.RECIPE_MEAD, Shaker.matchRecipeFromKinds(shuffled));
    }

    @Test
    void рецептДайкириУспешноОпределяется() {
        // Дайкири: 1 сахар, 2 сладких ягоды, 1 бутылочка воды, 1 блок любого льда (всего 5 предметов)
        List<Shaker.IngredientKind> ingredients = List.of(
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.WATER_BOTTLE,
                Shaker.IngredientKind.ICE
        );
        assertEquals(Shaker.RECIPE_DAIQUIRI, Shaker.matchRecipeFromKinds(ingredients));

        // Перемешанный порядок
        List<Shaker.IngredientKind> shuffled = List.of(
                Shaker.IngredientKind.ICE,
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SWEET_BERRIES,
                Shaker.IngredientKind.WATER_BOTTLE
        );
        assertEquals(Shaker.RECIPE_DAIQUIRI, Shaker.matchRecipeFromKinds(shuffled));
    }

    @Test
    void неудачныйРецептДаетМуть() {
        // Неполный состав для медовухи
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.HONEY_BOTTLE,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE
        )));

        // Лишний предмет в медовухе
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.HONEY_BOTTLE,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.WATER_BOTTLE,
                Shaker.IngredientKind.OTHER
        )));

        // Произвольный предмет
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.OTHER
        )));

        // 5 единиц сахара
        assertEquals(Shaker.RECIPE_MURK, Shaker.matchRecipeFromKinds(List.of(
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SUGAR,
                Shaker.IngredientKind.SUGAR,
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
    void запрещеныЛюбыеБлокиКромеЛюбогоЛьда() {
        // Разрешены любые виды льда
        assertTrue(Shaker.isAllowedIngredient(Material.ICE));
        assertTrue(Shaker.isAllowedIngredient(Material.PACKED_ICE));
        assertTrue(Shaker.isAllowedIngredient(Material.BLUE_ICE));
        assertTrue(Shaker.isAllowedIngredient(Material.FROSTED_ICE));

        // Разрешены не-блочные предметы (ингредиенты)
        assertTrue(Shaker.isAllowedIngredient(Material.SUGAR));
        assertTrue(Shaker.isAllowedIngredient(Material.SWEET_BERRIES));
        assertTrue(Shaker.isAllowedIngredient(Material.HONEY_BOTTLE));
        assertTrue(Shaker.isAllowedIngredient(Material.POTION));
        assertTrue(Shaker.isAllowedIngredient(Material.APPLE));

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
}
