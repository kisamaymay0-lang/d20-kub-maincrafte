package com.yourserver.adaptation;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Крафт медного нотного блока.
 *
 * <p>Тест читает те же два списка, из которых {@code registerRecipe()} собирает
 * рецепт: три отдельных предмета и одна медная решётка на выбор. Сами
 * {@code RecipeChoice.MaterialChoice} здесь не строятся — их конструктор
 * спрашивает у каждого материала {@code isAir()}, а это идёт в реестр
 * {@code Registry.BLOCK}, которого без запущенного сервера нет (так и падала
 * первая версия теста). Перечисление {@link Material} при этом инициализируется
 * спокойно.
 *
 * <p>В 10.20 кусочек меди в крафте заменили на алмаз.
 */
class CopperBlockRecipeTest {

    /** Всё, что можно положить в крафт: три отдельных предмета и решётки. */
    private static List<Material> everything() {
        List<Material> materials = new ArrayList<>(CopperBlockListener.recipeSingles());
        materials.addAll(CopperBlockListener.copperGrates());
        return materials;
    }

    @Test
    void recipeTakesADiamondAndNoLongerTakesACopperNugget() {
        List<Material> materials = everything();
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
        // Четыре слота: три отдельных предмета и одна решётка на выбор. Пятый
        // слот означал бы, что крафт просит больше предметов, чем заявлено.
        assertEquals(3, CopperBlockListener.recipeSingles().size(),
                "отдельных предмета три");
        assertEquals(8, CopperBlockListener.copperGrates().size(),
                "решёток на выбор восемь");
        assertFalse(everything().contains(Material.AIR),
                "воздух ингредиентом быть не может");
        assertEquals(everything().size(), new HashSet<>(everything()).size(),
                "один и тот же предмет не должен требоваться дважды");
    }

    @Test
    void craftedStageFollowsTheGrate() {
        // Из какой решётки крафтят, такой блок и получают.
        assertEquals(1, CopperBlockListener.grateStage(Material.COPPER_GRATE));
        assertEquals(2, CopperBlockListener.grateStage(Material.EXPOSED_COPPER_GRATE));
        assertEquals(3, CopperBlockListener.grateStage(Material.WEATHERED_COPPER_GRATE));
        assertEquals(4, CopperBlockListener.grateStage(Material.OXIDIZED_COPPER_GRATE));

        // Вощёная решётка даёт ту же ступень, но блок выходит вощёным.
        assertEquals(1, CopperBlockListener.grateStage(Material.WAXED_COPPER_GRATE));
        assertEquals(2, CopperBlockListener.grateStage(Material.WAXED_EXPOSED_COPPER_GRATE));
        assertEquals(3, CopperBlockListener.grateStage(Material.WAXED_WEATHERED_COPPER_GRATE));
        assertEquals(4, CopperBlockListener.grateStage(Material.WAXED_OXIDIZED_COPPER_GRATE));

        assertFalse(CopperBlockListener.grateWaxed(Material.COPPER_GRATE));
        assertFalse(CopperBlockListener.grateWaxed(Material.OXIDIZED_COPPER_GRATE));
        assertTrue(CopperBlockListener.grateWaxed(Material.WAXED_COPPER_GRATE));
        assertTrue(CopperBlockListener.grateWaxed(Material.WAXED_OXIDIZED_COPPER_GRATE));

        // Посторонние материалы — обычная первая ступень без воска.
        assertEquals(1, CopperBlockListener.grateStage(Material.DIAMOND));
        assertFalse(CopperBlockListener.grateWaxed(Material.DIAMOND));

        // Каждая ступень шейплесс-крафта даёт свой предмет и своё название.
        for (Material grate : CopperBlockListener.copperGrates()) {
            int stage = CopperBlockListener.grateStage(grate);
            boolean waxed = CopperBlockListener.grateWaxed(grate);
            String name = CopperNoteStages.itemName(stage, waxed);
            if (waxed) {
                assertTrue(name.startsWith("Вощёный "), grate.name() + " → " + name);
            } else {
                assertFalse(name.startsWith("Вощёный "), grate.name() + " → " + name);
            }
        }
    }

    @Test
    void anyCopperGrateFitsTheRecipe() {
        assertEquals(Set.of(
                        Material.COPPER_GRATE,
                        Material.EXPOSED_COPPER_GRATE,
                        Material.WEATHERED_COPPER_GRATE,
                        Material.OXIDIZED_COPPER_GRATE,
                        Material.WAXED_COPPER_GRATE,
                        Material.WAXED_EXPOSED_COPPER_GRATE,
                        Material.WAXED_WEATHERED_COPPER_GRATE,
                        Material.WAXED_OXIDIZED_COPPER_GRATE),
                new HashSet<>(CopperBlockListener.copperGrates()),
                "годятся все стадии окисления решётки, в том числе вощёные");
        assertTrue(CopperBlockListener.copperGrates().stream()
                        .allMatch(material -> material.name().endsWith("COPPER_GRATE")),
                "в четвёртом слоте только медные решётки");
        assertEquals(Set.of(), CopperBlockListener.copperGrates().stream()
                        .filter(material -> CopperBlockListener.recipeSingles().contains(material))
                        .collect(java.util.stream.Collectors.toSet()),
                "решётки не повторяют отдельные предметы крафта");
    }
}
