package com.yourserver.adaptation;

import org.bukkit.Material;
import org.bukkit.inventory.RecipeChoice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Крафт медного нотного блока.
 *
 * <p>Тест читает тот самый список, который {@code registerRecipe()} отдаёт
 * {@code ShapelessRecipe.addIngredient}: если крафт поменяют в одном месте и
 * забудут про другое, здесь это видно. Сервер для этого не нужен — ингредиенты
 * строятся без него, а результат рецепта (предмет с NBT) собирается уже на
 * сервере.
 *
 * <p>В 10.20 кусочек меди в крафте заменили на алмаз.
 */
class CopperBlockRecipeTest {

    private static List<Material> flatten() {
        List<Material> materials = new ArrayList<>();
        for (RecipeChoice choice : CopperBlockListener.recipeIngredients()) {
            materials.addAll(materials(choice));
        }
        return materials;
    }

    private static List<Material> materials(RecipeChoice choice) {
        assertInstanceOf(RecipeChoice.MaterialChoice.class, choice,
                "ингредиент крафта — список материалов");
        return ((RecipeChoice.MaterialChoice) choice).getChoices();
    }

    @Test
    void recipeTakesADiamondAndNoLongerTakesACopperNugget() {
        List<Material> materials = flatten();
        assertTrue(materials.contains(Material.DIAMOND),
                "в крафте должен быть алмаз");
        assertFalse(materials.contains(Material.COPPER_NUGGET),
                "кусочка меди в крафте больше нет");
        assertTrue(materials.contains(Material.NOTE_BLOCK),
                "в крафте должен быть нотный блок");
        assertTrue(materials.contains(Material.REDSTONE),
                "в крафте должен быть редстоун");
    }

    @Test
    void recipeIsShapelessOverFourIngredients() {
        // Четыре слота: нотный блок, редстоун, алмаз и одна решётка на выбор.
        // Пятый слот означал бы, что крафт просит больше предметов, чем заявлено.
        assertEquals(4, CopperBlockListener.recipeIngredients().size(),
                "бесформенный крафт на четыре ингредиента");
        for (RecipeChoice choice : CopperBlockListener.recipeIngredients()) {
            assertFalse(materials(choice).isEmpty(),
                    "пустой список материалов ломает регистрацию рецепта");
        }
        Set<Material> unique = new HashSet<>(flatten());
        assertEquals(flatten().size(), unique.size(),
                "один и тот же предмет не должен требоваться дважды");
    }

    @Test
    void anyCopperGrateFitsTheRecipe() {
        RecipeChoice grates = CopperBlockListener.recipeIngredients().get(3);
        assertEquals(CopperBlockListener.copperGrates(), materials(grates),
                "четвёртый слот — любая медная решётка");
        assertEquals(Set.of(
                        Material.COPPER_GRATE,
                        Material.EXPOSED_COPPER_GRATE,
                        Material.WEATHERED_COPPER_GRATE,
                        Material.OXIDIZED_COPPER_GRATE,
                        Material.WAXED_COPPER_GRATE,
                        Material.WAXED_EXPOSED_COPPER_GRATE,
                        Material.WAXED_WEATHERED_COPPER_GRATE,
                        Material.WAXED_OXIDIZED_COPPER_GRATE),
                new HashSet<>(materials(grates)),
                "годятся все стадии окисления решётки, в том числе вощёные");
        assertTrue(materials(grates).stream().allMatch(m -> m.name().endsWith("COPPER_GRATE")),
                "в четвёртом слоте только медные решётки");
    }
}
