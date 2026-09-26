package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клешня краба: что она берёт в займы и какие правила отдаёт компоненту {@code minecraft:tool}.
 *
 * Копает сама игра, поэтому числа сверены с вики: камень — дерево 1.15 с, камень-кирка 0.6 с,
 * железо 0.4 с, алмаз 0.3 с, незерит 0.25 с; обсидиан — рука 250 с, дерево 125 с, камень 62.5 с,
 * железо 41.7 с, алмаз 9.4 с, незерит 8.35 с; булыжник незеритом 0.35 с; железная руда каменной
 * киркой 1.15 с, деревянной 7.5 с; рука по камню 7.5 с.
 *
 * Здесь же видно, зачем в правилах есть наборы без дропа: «не тот уровень» — это не только пустой
 * блок, но и штраф к скорости (игра делит на 100 вместо 30), поэтому обсидиан железной киркой
 * копается 41.7 с, а алмазной 9.4 с.
 */
class CrabClawRulesTest {

    private static final double DELTA = 1e-9;

    /** Правила кирки в том виде, в каком их отдаёт игра: сперва «не тот уровень», потом основной набор. */
    private static List<CrabClawRules.Rule> pickaxeRules(int tier, double speed) {
        Set<String> base = Set.of("stone", "copper_ore", "iron_ore", "gold_ore", "diamond_ore",
                "obsidian", "ancient_debris");
        List<CrabClawRules.Rule> rules = new ArrayList<>();
        if (tier < 3) rules.add(new CrabClawRules.Rule(Set.of("diamond_ore", "obsidian", "ancient_debris"), speed, false));
        if (tier < 2) rules.add(new CrabClawRules.Rule(Set.of("gold_ore", "diamond_ore"), speed, false));
        if (tier < 1) rules.add(new CrabClawRules.Rule(Set.of("copper_ore", "iron_ore", "gold_ore"), speed, false));
        rules.add(new CrabClawRules.Rule(base, speed, true));
        return rules;
    }

    @Test
    void familiesComeFromMaterialNames() {
        assertEquals(CrabClawRules.Family.PICKAXE, CrabClawRules.family("DIAMOND_PICKAXE"));
        assertEquals(CrabClawRules.Family.PICKAXE, CrabClawRules.family("WOODEN_PICKAXE"));
        assertEquals(CrabClawRules.Family.AXE, CrabClawRules.family("NETHERITE_AXE"));
        assertEquals(CrabClawRules.Family.SHOVEL, CrabClawRules.family("IRON_SHOVEL"));
        assertEquals(CrabClawRules.Family.HOE, CrabClawRules.family("GOLDEN_HOE"));
        assertEquals(CrabClawRules.Family.SWORD, CrabClawRules.family("STONE_SWORD"));
        assertEquals(CrabClawRules.Family.SHEARS, CrabClawRules.family("SHEARS"));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family("STICK"));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family("PRISMARINE_SHARD"));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family((String) null));
        // Кирка проверяется раньше топора: у «_PICKAXE» и «_AXE» общий хвост.
        assertNotEquals(CrabClawRules.Family.AXE, CrabClawRules.family("DIAMOND_PICKAXE"));
    }

    @Test
    void familiesComeFromAnchorsForUnknownItems() {
        assertEquals(CrabClawRules.Family.PICKAXE, CrabClawRules.family(Set.of("stone", "copper_ore")));
        assertEquals(CrabClawRules.Family.AXE, CrabClawRules.family(Set.of("oak_log")));
        assertEquals(CrabClawRules.Family.SHOVEL, CrabClawRules.family(Set.of("dirt")));
        assertEquals(CrabClawRules.Family.HOE, CrabClawRules.family(Set.of("hay_block")));
        assertEquals(CrabClawRules.Family.SHEARS, CrabClawRules.family(Set.of("cobweb", "white_wool")));
        assertEquals(CrabClawRules.Family.SWORD, CrabClawRules.family(Set.of("cobweb")));
        assertEquals(CrabClawRules.Family.NONE, CrabClawRules.family(Set.of()));
    }

    @Test
    void tiersAndSpeedsMatchVanillaTools() {
        assertEquals(0, CrabClawRules.toolTier("WOODEN_PICKAXE"));
        assertEquals(0, CrabClawRules.toolTier("GOLDEN_SHOVEL"));
        assertEquals(1, CrabClawRules.toolTier("STONE_PICKAXE"));
        assertEquals(1, CrabClawRules.toolTier("COPPER_PICKAXE"));
        assertEquals(2, CrabClawRules.toolTier("IRON_PICKAXE"));
        assertEquals(3, CrabClawRules.toolTier("DIAMOND_PICKAXE"));
        assertEquals(3, CrabClawRules.toolTier("NETHERITE_PICKAXE"));
        assertEquals(0, CrabClawRules.toolTier("SHEARS"));          // ножницы не бывают «не того уровня»
        assertEquals(0, CrabClawRules.toolTier("IRON_SWORD"));
        assertEquals(-1, CrabClawRules.toolTier("STICK"));
        assertEquals(-1, CrabClawRules.toolTier(null));

        assertEquals(2.0, CrabClawRules.tierSpeed("WOODEN_PICKAXE"), DELTA);
        assertEquals(4.0, CrabClawRules.tierSpeed("STONE_PICKAXE"), DELTA);
        assertEquals(5.0, CrabClawRules.tierSpeed("COPPER_PICKAXE"), DELTA);
        assertEquals(6.0, CrabClawRules.tierSpeed("IRON_PICKAXE"), DELTA);
        assertEquals(8.0, CrabClawRules.tierSpeed("DIAMOND_PICKAXE"), DELTA);
        assertEquals(9.0, CrabClawRules.tierSpeed("NETHERITE_PICKAXE"), DELTA);
        assertEquals(12.0, CrabClawRules.tierSpeed("GOLDEN_PICKAXE"), DELTA);
        assertEquals(1.0, CrabClawRules.tierSpeed("STICK"), DELTA);  // не инструмент — как рука
        assertEquals(1.0, CrabClawRules.tierSpeed(null), DELTA);
    }

    @Test
    void efficiencyOnlyHelpsRealTools() {
        assertEquals(19.0, CrabClawRules.withEfficiency(9.0, 3), DELTA);   // незерит + «Эффективность III»
        assertEquals(6.0, CrabClawRules.withEfficiency(4.0, 1), DELTA);
        assertEquals(4.0, CrabClawRules.withEfficiency(4.0, 0), DELTA);
        assertEquals(1.0, CrabClawRules.withEfficiency(1.0, 5), DELTA);    // рука быстрее не копает
    }

    @Test
    void fallbackPickaxeRulesFollowTheTier() {
        // Незерит: один набор — копает всё, что вообще под силу кирке, и всегда с дропом.
        List<CrabClawRules.Spec> netherite = CrabClawRules.fallback(CrabClawRules.Family.PICKAXE, 3, 9.0);
        assertEquals(1, netherite.size());
        assertEquals("mineable/pickaxe", netherite.getFirst().blocks().tag());
        assertEquals(9.0, netherite.getFirst().speed(), DELTA);
        assertTrue(netherite.getFirst().drops());

        // Железо: сперва набор, где дропа нет (алмаз и обсидиан), потом общий.
        List<CrabClawRules.Spec> iron = CrabClawRules.fallback(CrabClawRules.Family.PICKAXE, 2, 6.0);
        assertEquals(List.of("needs_diamond_tool", "mineable/pickaxe"),
                iron.stream().map(spec -> spec.blocks().tag()).toList());
        assertFalse(iron.get(0).drops());

        // Дерево: три запрета подряд, потому что ему не по силам ни алмаз, ни железо, ни камень.
        List<CrabClawRules.Spec> wooden = CrabClawRules.fallback(CrabClawRules.Family.PICKAXE, 0, 2.0);
        assertEquals(List.of("needs_diamond_tool", "needs_iron_tool", "needs_stone_tool", "mineable/pickaxe"),
                wooden.stream().map(spec -> spec.blocks().tag()).toList());
        assertEquals(4, wooden.size());
        assertTrue(wooden.subList(0, 3).stream().noneMatch(CrabClawRules.Spec::drops));
        assertTrue(wooden.get(3).drops());
    }

    @Test
    void fallbackShearsAndSwordKeepTheirSpeed() {
        List<CrabClawRules.Spec> shears = CrabClawRules.fallback(CrabClawRules.Family.SHEARS, 0, 1.0);
        assertEquals(2, shears.size());
        assertTrue(shears.get(0).blocks().names().contains("cobweb"));
        assertEquals(15.0, shears.get(0).speed(), DELTA);          // паутина рвётся вмиг
        assertEquals("wool", shears.get(1).blocks().tag());
        assertEquals(5.0, shears.get(1).speed(), DELTA);

        List<CrabClawRules.Spec> sword = CrabClawRules.fallback(CrabClawRules.Family.SWORD, 0, 1.0);
        assertEquals(List.of("sword_instantly_mines", "sword_efficient"),
                sword.stream().map(spec -> spec.blocks().tag()).toList());
        assertEquals(15.0, sword.get(0).speed(), DELTA);
        assertEquals(1.5, sword.get(1).speed(), DELTA);
    }

    @Test
    void otherFamiliesUseTheirOwnMineableTag() {
        assertEquals("mineable/axe", CrabClawRules.fallback(CrabClawRules.Family.AXE, 0, 2.0).getFirst().blocks().tag());
        assertEquals("mineable/shovel", CrabClawRules.fallback(CrabClawRules.Family.SHOVEL, 0, 2.0).getFirst().blocks().tag());
        assertEquals("mineable/hoe", CrabClawRules.fallback(CrabClawRules.Family.HOE, 0, 2.0).getFirst().blocks().tag());
        assertTrue(CrabClawRules.fallback(CrabClawRules.Family.NONE, 0, 1.0).isEmpty());
        assertTrue(CrabClawRules.fallback(CrabClawRules.Family.SWORD, 0, 1.0).get(0).drops());
    }

    @Test
    void strongerToolsScoreHigherAndWinTheBorrow() {
        int wooden = CrabClawRules.score(pickaxeRules(0, 2.0));
        int stone = CrabClawRules.score(pickaxeRules(1, 4.0));
        int iron = CrabClawRules.score(pickaxeRules(2, 6.0));
        int diamond = CrabClawRules.score(pickaxeRules(3, 8.0));

        assertEquals(0, wooden);            // дерево не берёт ни железо, ни алмаз, ни обсидиан
        assertTrue(stone > wooden);
        assertTrue(iron > stone);
        assertTrue(diamond > iron);
        // Алмаз и незерит одинаково сильны — их разводит скорость.
        assertEquals(diamond, CrabClawRules.score(pickaxeRules(3, 9.0)));
        assertTrue(CrabClawRules.topSpeed(pickaxeRules(3, 9.0)) > CrabClawRules.topSpeed(pickaxeRules(3, 8.0)));
        assertEquals(2.0, CrabClawRules.topSpeed(pickaxeRules(0, 2.0)), DELTA);
    }

    @Test
    void fastFamiliesGoFirstSoSharedBlocksMineFast() {
        // Ножницы рвут листву вмиг, меч по той же листве — в полтора раза быстрее руки,
        // поэтому их правила стоят раньше кирочных: первое подходящее правило и выигрывает.
        assertEquals(List.of(CrabClawRules.Family.SHEARS, CrabClawRules.Family.SWORD, CrabClawRules.Family.HOE,
                        CrabClawRules.Family.SHOVEL, CrabClawRules.Family.AXE, CrabClawRules.Family.PICKAXE),
                CrabClawRules.ORDER);
        assertTrue(CrabClawRules.ORDER.indexOf(CrabClawRules.Family.SHEARS) < CrabClawRules.ORDER.indexOf(CrabClawRules.Family.PICKAXE));
        assertTrue(CrabClawRules.ORDER.indexOf(CrabClawRules.Family.SWORD) < CrabClawRules.ORDER.indexOf(CrabClawRules.Family.AXE));
    }

    @Test
    void wikiDurationsComeOutOfTheRules() {
        // Камень (твёрдость 1.5): дерево 1.15 с, камень 0.6 с, железо 0.4 с, алмаз 0.3 с, незерит 0.25 с.
        assertEquals(1.15, CrabClawRules.seconds(2.0, 1.5, true), DELTA);
        assertEquals(0.6, CrabClawRules.seconds(4.0, 1.5, true), DELTA);
        assertEquals(0.4, CrabClawRules.seconds(6.0, 1.5, true), DELTA);
        assertEquals(0.3, CrabClawRules.seconds(8.0, 1.5, true), DELTA);
        assertEquals(0.25, CrabClawRules.seconds(9.0, 1.5, true), DELTA);
        assertEquals(7.5, CrabClawRules.seconds(1.0, 1.5, false), DELTA);   // рука по камню: штраф ÷100
        assertEquals(0.35, CrabClawRules.seconds(9.0, 2.0, true), DELTA);   // булыжник незеритом
        // Обсидиан (твёрдость 50): рука 250 с, дерево 125 с, камень 62.5 с, железо 41.7 с — всё со штрафом,
        // потому что обсидиан по силам только алмазу; алмаз 9.4 с и незерит 8.35 с — уже как подходящий инструмент.
        assertEquals(250.0, CrabClawRules.seconds(1.0, 50.0, false), DELTA);
        assertEquals(125.0, CrabClawRules.seconds(2.0, 50.0, false), DELTA);
        assertEquals(62.5, CrabClawRules.seconds(4.0, 50.0, false), DELTA);
        assertEquals(41.7, CrabClawRules.seconds(6.0, 50.0, false), DELTA);
        assertEquals(9.4, CrabClawRules.seconds(8.0, 50.0, true), DELTA);
        assertEquals(8.35, CrabClawRules.seconds(9.0, 50.0, true), DELTA);
        // Железная руда (твёрдость 3) каменной киркой 1.15 с, деревянной 7.5 с — и без дропа дерево тоже медленнее.
        assertEquals(1.15, CrabClawRules.seconds(4.0, 3.0, true), DELTA);
        assertEquals(7.5, CrabClawRules.seconds(2.0, 3.0, false), DELTA);
        assertEquals(0.0, CrabClawRules.seconds(2.0, 0.0, true), DELTA);
    }

    @Test
    void wrongTierIsBothSlowAndDropless() {
        // Железная кирка на обсидиане берёт первое правило — «нужен алмаз»: дропа нет и штраф ÷100,
        // поэтому 41.7 с вместо 12.5 с. Алмазная кирка берёт основной набор: дроп есть, 9.4 с.
        List<CrabClawRules.Rule> iron = pickaxeRules(2, 6.0);
        assertFalse(iron.get(0).drops());
        assertTrue(iron.get(1).drops());
        assertEquals(41.7, CrabClawRules.seconds(6.0, 50.0, false), DELTA);
        assertEquals(9.4, CrabClawRules.seconds(8.0, 50.0, true), DELTA);
        // Деревянной кирке запретов три подряд, и все три — без дропа.
        List<CrabClawRules.Rule> wooden = pickaxeRules(0, 2.0);
        assertEquals(3, wooden.stream().filter(rule -> !rule.drops()).count());
        assertTrue(wooden.getLast().drops());
    }

    @Test
    void reachBonusAndWearAreNiceRoundNumbers() {
        assertEquals(3.0, CrabClawRules.REACH_BONUS, DELTA);
        assertEquals(2, CrabClawRules.WEAR_PER_BLOCK);
        assertTrue(CrabClawRules.ANCHORS.containsAll(List.of("stone", "oak_log", "dirt", "hay_block",
                "white_wool", "cobweb", "obsidian", "ancient_debris")));
    }

    @Test
    void mergesEnchantmentsOfEveryToolKeepingTheStrongestLevel() {
        List<CrabClawRules.Enchant> pickaxe = List.of(
                new CrabClawRules.Enchant(CrabClawRules.FORTUNE, 2),
                new CrabClawRules.Enchant(CrabClawRules.UNBREAKING, 1));
        List<CrabClawRules.Enchant> axe = List.of(
                new CrabClawRules.Enchant(CrabClawRules.UNBREAKING, 3),
                new CrabClawRules.Enchant(CrabClawRules.SILK_TOUCH, 1));

        List<CrabClawRules.Enchant> merged = CrabClawRules.merge(List.of(pickaxe, axe, List.of()));
        assertEquals(2, merged.size());
        assertEquals(2, level(merged, CrabClawRules.FORTUNE));
        assertEquals(3, level(merged, CrabClawRules.UNBREAKING), "берём самый высокий уровень");
        assertEquals(0, level(merged, CrabClawRules.SILK_TOUCH),
                "пока главный инструмент с удачей, шелк к нему не примешивается");
    }

    @Test
    void theMainToolDecidesBetweenFortuneAndSilkTouch() {
        List<CrabClawRules.Enchant> silky = List.of(new CrabClawRules.Enchant(CrabClawRules.SILK_TOUCH, 1));
        List<CrabClawRules.Enchant> lucky = List.of(new CrabClawRules.Enchant(CrabClawRules.FORTUNE, 3));

        List<CrabClawRules.Enchant> silkFirst = CrabClawRules.merge(List.of(silky, lucky));
        assertEquals(1, level(silkFirst, CrabClawRules.SILK_TOUCH));
        assertEquals(0, level(silkFirst, CrabClawRules.FORTUNE));

        List<CrabClawRules.Enchant> fortuneFirst = CrabClawRules.merge(List.of(lucky, silky));
        assertEquals(3, level(fortuneFirst, CrabClawRules.FORTUNE));
        assertEquals(0, level(fortuneFirst, CrabClawRules.SILK_TOUCH));
        assertEquals("", CrabClawRules.opposite(CrabClawRules.UNBREAKING));
    }

    @Test
    void pickaxeIsTheMainToolWhenToolsAreEquallyStrong() {
        assertEquals(0, CrabClawRules.rank(CrabClawRules.Family.PICKAXE));
        assertTrue(CrabClawRules.rank(CrabClawRules.Family.PICKAXE)
                < CrabClawRules.rank(CrabClawRules.Family.AXE));
        assertTrue(CrabClawRules.rank(CrabClawRules.Family.HOE)
                < CrabClawRules.rank(CrabClawRules.Family.SHEARS));
    }

    @Test
    void unbreakingSavesDurabilityWithVanillaChances() {
        // «Прочность III» бережёт предмет в трёх случаях из четырёх.
        assertEquals(0, CrabClawRules.wear(2, 3, 0.0));
        assertEquals(0, CrabClawRules.wear(2, 3, 0.74));
        assertEquals(2, CrabClawRules.wear(2, 3, 0.75));
        assertEquals(2, CrabClawRules.wear(2, 3, 0.99));
        assertEquals(0, CrabClawRules.wear(2, 1, 0.49));
        assertEquals(2, CrabClawRules.wear(2, 1, 0.5));
        assertEquals(2, CrabClawRules.wear(2, 0, 0.0), "без «Прочности» износ идёт как идёт");
        assertEquals(0, CrabClawRules.wear(0, 0, 0.9));
        assertEquals(2, CrabClawRules.wear(2, -1, 0.0), "отрицательный уровень — как без «Прочности»");
    }

    @Test
    void mendingTurnsExperienceIntoDurability() {
        assertEquals(8, CrabClawRules.mend(10, 1), "очко опыта — две прочности");
        assertEquals(0, CrabClawRules.mend(10, 5), "лишний опыт просто пропадает");
        assertEquals(0, CrabClawRules.mend(0, 7));
        assertEquals(4, CrabClawRules.mend(4, 0), "без «Починки» ничего не меняется");
        assertTrue(CrabClawRules.LOOT_ENCHANTS.contains(CrabClawRules.FORTUNE));
        assertTrue(CrabClawRules.LOOT_ENCHANTS.contains(CrabClawRules.SILK_TOUCH));
        assertFalse(CrabClawRules.LOOT_ENCHANTS.contains(CrabClawRules.UNBREAKING));
    }

    private static int level(List<CrabClawRules.Enchant> enchants, String id) {
        for (CrabClawRules.Enchant enchant : enchants) if (enchant.id().equals(id)) return enchant.level();
        return 0;
    }

    @Test
    void fallbackBlocksNameTheirTagOrTheirBlocks() {
        CrabClawRules.Blocks tag = new CrabClawRules.Blocks("mineable/pickaxe", List.of());
        assertTrue(tag.names().isEmpty());
        assertEquals("mineable/pickaxe", tag.tag());

        CrabClawRules.Blocks names = new CrabClawRules.Blocks(null, List.of("cobweb", "vine"));
        assertEquals(List.of("cobweb", "vine"), names.names());
        assertNull(names.tag());
        assertNotEquals(tag, names);
    }
}
